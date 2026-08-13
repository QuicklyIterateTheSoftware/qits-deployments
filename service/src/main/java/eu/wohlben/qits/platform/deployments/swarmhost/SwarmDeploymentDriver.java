package eu.wohlben.qits.platform.deployments.swarmhost;

import eu.wohlben.qits.platform.deployments.deployments.control.DeployedIdentity;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentIdentifiers;
import eu.wohlben.qits.platform.deployments.deployments.control.HealthGate;
import eu.wohlben.qits.platform.deployments.deployments.control.PdProcess;
import eu.wohlben.qits.platform.deployments.deployments.control.ServiceExtras;
import eu.wohlben.qits.platform.deployments.dockerhost.DockerHost;
import eu.wohlben.qits.platform.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.platform.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.platform.deployments.orchestration.Orchestrated;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The swarm implementation of {@link DeploymentDriver}: a deployed application is a <b>service</b>,
 * and a replace is {@code docker service update --image} on it.
 *
 * <p><b>Almost everything the docker driver does by hand is a flag here.</b> {@code
 * --update-order start-first} is the overlap, {@code --update-monitor} is the gate window, {@code
 * --update-failure-action rollback} is the rollback, and a task does not enter DNS until its
 * healthcheck passes — measured on this host: while a task was {@code Starting}, {@code getent
 * hosts} answered nothing for its name while the VIP already existed. So there is no predecessor to
 * find, nothing to stop, nothing to restart and nobody to referee: this class issues one command
 * and then reads a verdict.
 *
 * <p><b>The name is the address, and that is the one thing to keep in mind reading this.</b> {@code
 * container_name} does not exist in swarm — a task container is {@code
 * <service>.<slot>.<taskid>} — so the service NAME is what peers resolve, which makes it the wire
 * alias and makes a replace an update of the same service rather than a second container beside the
 * first. Every question this component asks afterwards ({@code awaitConverged}, {@code observe},
 * the environment teardown) is a service query, never a container name match.
 *
 * <p><b>The topology collapses to two overlays, and it is not a simplification for its own
 * sake.</b> {@code service update --network-add} recreates the task, so the docker path's
 * hub-and-spoke — one network per application, joined after the fact by every hub and every
 * platform service — would turn a single deployment into a restart storm across the platform. So a
 * service declares its whole membership at create time: {@code
 * qits.platform.deployments.swarm.flat-network} (attachable, so plain {@code docker run} containers
 * — CI steps, workspaces, agents — keep working on it) plus {@code qits-platform} for a platform
 * service. The per-application networks the caller asks for are dropped, deliberately and out loud.
 *
 * <p><b>What a service keeps across an update</b> is its mounts, its networks and its published
 * ports: {@link #buildUpdateArgv} changes the image, the identity labels, the environment and the
 * update policy, and nothing else. Changing the SHAPE of a service — a new volume, another port —
 * is therefore a {@code service rm} and a redeploy, which is the honest reading of it: a change of
 * shape is not a deployment.
 *
 * <p><b>Two verbs are borrowed from the docker seam rather than reimplemented</b>, and neither is
 * swarm-shaped: {@code docker pull} classifies a missing image (swarm pulls on its own, but a task
 * that never starts is a much worse way to learn that nothing published this build), and {@code
 * docker network ls} is the same command whatever created the networks.
 *
 * <p><b>What a deployment adds beyond its image</b> — mounts, published ports, groups, environment
 * — is {@link ServiceExtras}, stated in deployment config and rendered here in swarm's own words.
 * Nothing translates a {@code docker run} argv any more: config states the intent, and the one
 * intent swarm cannot express — a publish bound to an ip — is refused rather than widened.
 *
 * <p><b>The one piece of a self-update swarm does not do for us is the row.</b> The instance that
 * issues the update on its own service dies before the outcome exists, so the deployment stays
 * {@code STARTING} until an instance boots that can settle it — from {@link #runningImage}, the
 * image the service is running, which is the only reading that tells a completed succession from a
 * rolled-back one.
 */
@ApplicationScoped
@Orchestrated(Orchestrated.Kind.SWARM)
public class SwarmDeploymentDriver implements DeploymentDriver {

  private static final Logger LOG = Logger.getLogger(SwarmDeploymentDriver.class);

  private static final Duration APPLY_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(30);

  /** How often {@link #awaitConverged} asks swarm where the update got to. */
  private static final Duration CONVERGE_POLL = Duration.ofSeconds(1);

  /**
   * A network is removable roughly a second after the services on it go, not immediately —
   * measured. So a teardown retries rather than reporting a failure that is only a moment early.
   */
  /**
   * How long a reaped seed twin's task may take to stop before the successor is created anyway.
   * Ten seconds covers a postgres shutdown; the give-up arm exists so a wedged task cannot hold
   * every deployment hostage, and it says what it risks.
   */
  private static final int TWIN_DRAIN_ATTEMPTS = 10;

  private static final Duration TWIN_DRAIN_WAIT = Duration.ofSeconds(1);

  private static final int NETWORK_REMOVE_ATTEMPTS = 5;

  private static final Duration NETWORK_REMOVE_WAIT = Duration.ofSeconds(1);

  /** Lines of service log kept as a failed convergence's diagnosis. */
  private static final String LOG_TAIL_LINES = "200";

  /**
   * What swarm calls the update it is in the middle of, and what it says about it. A service that
   * has never been updated has no {@code UpdateStatus} at all, which is why the format prints an
   * empty state rather than failing — see {@link #awaitConverged}.
   */
  static final String UPDATE_STATUS_FORMAT =
      "{{if .UpdateStatus}}{{.UpdateStatus.State}}|{{.UpdateStatus.Message}}{{else}}|{{end}}";

  /**
   * The startup sweep's evidence: what the service runs, then the same update status as wording.
   * One format because both fields sit on the object one {@code service inspect} returns.
   */
  static final String RUNNING_IMAGE_FORMAT =
      "{{.Spec.TaskTemplate.ContainerSpec.Image}}|" + UPDATE_STATUS_FORMAT;

  /** The label swarm itself puts on a task container, naming the service it belongs to. */
  private static final String SWARM_SERVICE_LABEL = "com.docker.swarm.service.name";

  /**
   * The seed stack's namespace. The bootstrap deploys the seed as {@code docker stack deploy …
   * qits}, and a stack prefixes every service it creates — so the seed twin of {@code dev-qits-ci}
   * is {@code qits_dev-qits-ci}. Two things follow, and both live in {@link #apply}: the twin IS
   * this process when the deployer still runs as the seed (a self-update targets the stack-named
   * service), and for every other application the twin must be REMOVED at cutover — it holds the
   * wire alias and any host-mode ports, so a successor beside it schedules never (the port) or
   * serves half the traffic (the alias round-robins).
   */
  static final String SEED_STACK_PREFIX = "qits_";

  /** Task states that mean the task is not coming up. Everything else is patience. */
  private static final Set<String> TERMINAL_TASK_STATES =
      Set.of("failed", "rejected", "shutdown", "orphaned", "complete", "remove");

  @ConfigProperty(name = "qits.platform.deployments.container-runtime")
  String runtime;

  @ConfigProperty(name = "qits.platform.deployments.health-interval-seconds")
  long healthIntervalSeconds;

  @ConfigProperty(name = "qits.platform.deployments.health-retries")
  int healthRetries;

  @ConfigProperty(name = "qits.platform.deployments.health-start-period-seconds")
  long healthStartPeriodSeconds;

  @ConfigProperty(name = "qits.platform.deployments.swarm.update-monitor-seconds")
  long updateMonitorSeconds;

  @ConfigProperty(name = "qits.platform.deployments.swarm.flat-network")
  String flatNetwork;

  @ConfigProperty(name = "qits.platform.deployments.output-max-chars")
  int outputMaxChars;

  /** Looked up per key rather than {@code @ConfigProperty}: the key carries the application name. */
  @Inject Config config;

  /** The two verbs that are the same command under swarm — see the class javadoc. */
  @Inject DockerHost docker;

  /**
   * One docker CLI call. A seam so the suite can script the conversation: the argv IS the contract
   * with swarm, and asserting it — and the verdicts read back out of it — needs no daemon.
   */
  @FunctionalInterface
  interface Cli {
    PdProcess.Result run(List<String> argv, Duration timeout);
  }

  private volatile Cli cli;

  /**
   * Package-private, and a method rather than a field write, because an injected reference is a CDI
   * client proxy: a field set on the proxy would never reach the bean.
   */
  void scriptCli(Cli scripted) {
    this.cli = scripted;
  }

  private PdProcess.Result run(List<String> argv, Duration timeout) {
    Cli scripted = cli;
    return scripted != null
        ? scripted.run(argv, timeout)
        : PdProcess.run(null, argv, timeout, outputMaxChars);
  }

  /** A swarm service's name IS its address, so the wire alias is the name. */
  @Override
  public String nameOf(ServiceSpec spec) {
    return spec.wireAlias();
  }

  @Override
  public ApplyResult apply(ServiceSpec spec) {
    String name = spec.wireAlias();
    List<String> networks = collapse(spec);
    for (String network : networks) {
      ensureNetwork(
          new Network(
              network,
              null,
              PdNetworks.PLATFORM.equals(network) ? NetworkKind.PLATFORM : NetworkKind.BUNDLE,
              null));
    }

    // Asked BEFORE the update, because after it this process may not exist to ask anything: the
    // manager stops this task the moment the new one is healthy. `own` is the service label on
    // this very container: when the deployer still runs as the SEED STACK's service, that label
    // is the stack-prefixed name, and the self-update must target that service — creating a
    // bare-named sibling instead would leave two deployers on one registry.
    String own = ownServiceName();
    boolean self = own.equals(name) || own.equals(SEED_STACK_PREFIX + name);
    String target = self ? own : name;
    boolean exists = serviceExists(target);
    List<String> argv;
    try {
      argv = exists ? buildUpdateArgv(spec, target) : buildCreateArgv(spec, target, networks);
    } catch (ServiceExtras.Refused e) {
      // Deployment config said something swarm cannot express. Nothing was applied — the argv is
      // built before the command runs — so this deployment changed nothing.
      LOG.warnf("Refusing to deploy %s: %s", name, e.getMessage());
      return new ApplyResult(ApplyOutcome.REFUSED, e.getMessage());
    }
    if (!self) {
      // The seed twin dies at cutover, and it dies FIRST. It holds the wire alias (DNS would
      // round-robin between seed and successor — measured: a step's ci-daemon registered with the
      // instance that had not launched it and exited 6) and any host-mode ports (the successor's
      // task then sits Pending on "port already in use" forever). Removed after the argv is built,
      // so a REFUSED deployment changes nothing; if the create still fails, the task the twin ran
      // is what the last boot's stack file restores.
      reapSeedTwin(name);
    }
    PdProcess.Result result = run(argv, APPLY_TIMEOUT);
    if (result.exitCode() != 0 || result.timedOut()) {
      LOG.warnf("Could not %s service %s: %s", exists ? "update" : "create", name, result.output());
      return new ApplyResult(ApplyOutcome.REFUSED, result.output());
    }
    if (self) {
      // The self-update, and this is the arbiter the docker path never had: the manager lives in
      // the daemon rather than in a container this process owns, so it can stop this task, start the
      // successor and revert the spec if the successor never goes healthy. Nothing here waits for
      // that — this process is what is being replaced.
      LOG.infof(
          "Self-update issued on service %s: the swarm manager finishes it, and the row stays"
              + " STARTING until the instance that survives records it",
          name);
      return new ApplyResult(
          ApplyOutcome.HANDED_OFF, "the swarm manager arbitrates this service's own succession");
    }
    return new ApplyResult(ApplyOutcome.APPLIED, null);
  }

  /**
   * Swarm's own verdict on the update, polled from {@code .UpdateStatus.State}.
   *
   * <ul>
   *   <li>{@code completed} — the successor is running and healthy; DNS points at it.
   *   <li>{@code rollback_completed} — the successor never went healthy, swarm reverted the spec,
   *       and under {@code start-first} the predecessor never stopped serving. A failed deployment
   *       with nothing lost.
   *   <li>{@code paused} / {@code rollback_paused} — swarm stopped trying and is waiting for a
   *       person. A failure, with its message as the diagnosis.
   *   <li>{@code updating} / {@code rollback_started} — keep waiting.
   * </ul>
   *
   * <p><b>A freshly created service has no {@code UpdateStatus} at all</b>, and that is the one
   * case this cannot read off a single field: the first deployment of an application is a {@code
   * service create}, and swarm records an update status only from the first {@code update} onward.
   * So an empty state falls through to the task itself — a task is {@code Running} only once its
   * healthcheck has passed, which is the same statement the field would have made.
   */
  @Override
  public Convergence awaitConverged(String name, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    String last = "(never inspected)";
    while (true) {
      PdProcess.Result inspected =
          run(
              List.of(runtime, "service", "inspect", "--format", UPDATE_STATUS_FORMAT, name),
              INSPECT_TIMEOUT);
      if (inspected.exitCode() != 0) {
        // Not a state at all: swarm has no such service. There is nothing to keep waiting for.
        return Convergence.failed("no service " + name + ": " + safe(inspected.output()));
      }
      String[] parts = safe(inspected.output()).strip().split("\\|", 2);
      String state = parts[0].strip().toLowerCase(Locale.ROOT);
      String message = parts.length > 1 ? parts[1].strip() : "";
      last = state.isEmpty() ? "created" : state;
      switch (state) {
        case "completed" -> {
          return Convergence.converged(List.of());
        }
        case "rollback_completed" -> {
          return Convergence.rolledBack(
              "swarm rolled "
                  + name
                  + " back to its predecessor: "
                  + (message.isBlank() ? "the successor never went healthy" : message));
        }
        case "paused", "rollback_paused" -> {
          return Convergence.failed(
              "swarm paused the update of " + name + ": " + message + "\n" + tasks(name));
        }
        case "" -> {
          // A service nothing has updated yet — the first deployment of this application.
          Convergence fresh = freshCreateVerdict(name);
          if (fresh != null) {
            return fresh;
          }
        }
        default -> {
          /* updating, rollback_started: keep waiting */
        }
      }
      if (System.nanoTime() >= deadline) {
        break;
      }
      try {
        Thread.sleep(CONVERGE_POLL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Convergence.failed("interrupted while waiting for " + name + " to converge");
      }
    }
    return Convergence.failed(
        "service "
            + name
            + " was still "
            + last
            + " after "
            + timeout.toSeconds()
            + "s\n"
            + tasks(name)
            + "\n"
            + logs(name));
  }

  /**
   * The first deployment's verdict, read off the tasks: {@code Running} is healthy (swarm holds a
   * task in {@code Starting} until its healthcheck passes), a generation whose tasks have all ended
   * is a failure, and anything else is still pending.
   */
  private Convergence freshCreateVerdict(String name) {
    List<String> states = runningGenerationStates(name);
    if (states.isEmpty()) {
      return null;
    }
    if (states.stream().anyMatch("running"::equals)) {
      return Convergence.converged(List.of());
    }
    if (states.stream().allMatch(TERMINAL_TASK_STATES::contains)) {
      return Convergence.failed(
          "no task of " + name + " came up: " + String.join(", ", states) + "\n" + tasks(name));
    }
    return null;
  }

  /**
   * One observation of the service, in the {@code <status>/<health>} spelling the gate and the
   * observer both read.
   *
   * <p>The mapping is swarm's task state, and it carries the health in it rather than beside it: a
   * task is {@code Starting} until its healthcheck passes and {@code Running} afterwards, so
   * {@code running/healthy} and {@code starting/unhealthy} are exact rather than approximate. A
   * service the daemon does not have is {@code gone}, which is the same structural fact a missing
   * container is on the docker path.
   */
  @Override
  public HealthGate.Poll observe(String name) {
    PdProcess.Result listed =
        run(
            List.of(
                runtime,
                "service",
                "ps",
                name,
                "--filter",
                "desired-state=running",
                "--no-trunc",
                "--format",
                "{{.CurrentState}}"),
            INSPECT_TIMEOUT);
    if (listed.exitCode() != 0) {
      return HealthGate.Poll.gone(listed.output());
    }
    List<String> states = taskStates(listed.output());
    if (states.stream().anyMatch("running"::equals)) {
      return HealthGate.Poll.of("running/healthy");
    }
    if (states.stream().anyMatch(state -> !TERMINAL_TASK_STATES.contains(state))) {
      return HealthGate.Poll.of("starting/unhealthy");
    }
    return HealthGate.Poll.of("exited/unhealthy");
  }

  /**
   * Nothing to reap: a replace is in place, so the predecessor and the successor are one service.
   *
   * <p>Removing what the caller names here would remove the deployment that just went live, which
   * is why this is a stated no-op rather than a delegation to {@code service rm}.
   */
  @Override
  public void reap(List<String> names) {
    if (!names.isEmpty()) {
      LOG.debugf("Nothing to reap for %s: a swarm replace is an update of the same service", names);
    }
  }

  /**
   * What the service runs now, and swarm's own account of the update that put it there — one
   * inspect, because the two fields sit on one object.
   *
   * <p><b>The image is the verdict and {@code UpdateStatus} is only the wording</b>, which is the
   * whole reason the sweep asks this rather than reading the status alone: that field holds the
   * most recent update, so a later deployment overwrites what it said about the one a row is about.
   * The image a service is running cannot be out of date in that way.
   */
  @Override
  public Optional<RunningImage> runningImage(String name) {
    PdProcess.Result inspected =
        run(
            List.of(runtime, "service", "inspect", "--format", RUNNING_IMAGE_FORMAT, name),
            INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      return Optional.empty(); // swarm has no such service
    }
    String[] parts = safe(inspected.output()).strip().split("\\|", 3);
    String image = parts[0].strip();
    if (image.isEmpty()) {
      return Optional.empty();
    }
    String state = parts.length > 1 ? parts[1].strip() : "";
    String message = parts.length > 2 ? parts[2].strip() : "";
    return Optional.of(
        new RunningImage(image, state.isEmpty() ? null : (state + ": " + message).strip()));
  }

  /**
   * The service this task belongs to — the label swarm puts on every task container
   * ({@value #SWARM_SERVICE_LABEL}), read via this container's own id. Empty outside a container,
   * which is every local run.
   *
   * <p>Asked by {@link #apply} alone, and it answers one question: may this process wait for the
   * verdict, or is it what is being replaced. The NAME matters as much as the yes: a deployer
   * still running as the seed stack's service must update that stack-named service in place.
   * Whether the succession then WORKED is a different question, asked of the image by the next
   * instance to boot ({@link #runningImage}) — "am I this service" is true of the successor and of
   * a predecessor swarm rolled back to, alike.
   */
  private String ownServiceName() {
    String hostname = selfContainerId();
    if (hostname.isBlank()) {
      return "";
    }
    PdProcess.Result inspected =
        run(
            List.of(
                runtime,
                "inspect",
                "--format",
                "{{index .Config.Labels \"" + SWARM_SERVICE_LABEL + "\"}}",
                hostname),
            INSPECT_TIMEOUT);
    if (inspected.exitCode() != 0) {
      return "";
    }
    String service = safe(inspected.output()).strip();
    return service.isBlank() || "<no value>".equals(service) ? "" : service;
  }

  /**
   * Remove the seed stack's service for this application, when one is still there — and WAIT for
   * its task containers to be gone before returning.
   * <p>
   * The wait is not about ports: a successor whose host port is briefly still held sits
   * {@code Pending} and schedules by itself. It is about VOLUMES. {@code service rm} returns
   * while the task is still shutting down, and a successor created in that window starts beside
   * it — for a stateless service an overlap of seconds is nothing, for postgres on its data
   * volume it is two writers on one cluster. Measured twice: the un-reaped twin corrupted the WAL
   * over hours, and the first reap-then-create did the same in its seconds of overlap — both
   * boots ended in "could not locate a valid checkpoint record" at the next cold start.
   */
  private void reapSeedTwin(String name) {
    String twin = SEED_STACK_PREFIX + name;
    if (!serviceExists(twin)) {
      return;
    }
    PdProcess.Result removed = run(List.of(runtime, "service", "rm", twin), CLEANUP_TIMEOUT);
    if (removed.exitCode() != 0) {
      LOG.warnf(
          "Could not remove the seed service %s — the successor may wait on its ports: %s",
          twin, removed.output());
      return;
    }
    for (int attempt = 0; attempt < TWIN_DRAIN_ATTEMPTS; attempt++) {
      PdProcess.Result tasks =
          run(
              List.of(
                  runtime,
                  "ps",
                  "--quiet",
                  "--filter",
                  "label=" + SWARM_SERVICE_LABEL + "=" + twin),
              INSPECT_TIMEOUT);
      if (tasks.exitCode() == 0 && safe(tasks.output()).strip().isEmpty()) {
        LOG.infof("Removed the seed service %s: %s takes the alias and the ports", twin, name);
        return;
      }
      try {
        Thread.sleep(TWIN_DRAIN_WAIT.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    LOG.warnf(
        "The seed service %s is removed but its task is still stopping — the successor may start"
            + " beside it",
        twin);
  }

  /**
   * Where this task container's own id is read from. A field rather than a constant so the suite
   * can point it at a file of its own — a test that depended on the build host having an
   * {@code /etc/hostname} would be asserting something about the host.
   */
  Path hostnameFile = Path.of("/etc/hostname");

  /** This task container's own id. Blank outside a container, which is every local run. */
  private String selfContainerId() {
    try {
      return Files.readString(hostnameFile).strip();
    } catch (Exception e) {
      return "";
    }
  }

  /**
   * Create the overlay when it is missing — and <b>only</b> the two the collapse keeps.
   *
   * <p>A per-application or per-environment network would be a network no service is ever on: the
   * membership is declared at create time and a later join costs a task restart, so building the
   * hub-and-spoke topology under swarm would be paying that price on every deployment. The caller
   * still asks for them (it is the same state machine on both paths), and the answer here is a
   * debug line rather than an overlay nothing uses.
   */
  @Override
  public boolean ensureNetwork(Network spec) {
    if (!collapsed(spec.name())) {
      LOG.debugf(
          "Not creating '%s': under swarm the topology is %s plus %s, declared at service create",
          spec.name(), flatNetwork, PdNetworks.PLATFORM);
      return false;
    }
    if (run(List.of(runtime, "network", "inspect", spec.name()), CLEANUP_TIMEOUT).exitCode() == 0) {
      // Already there, labels and all — including one the bootstrap made. Adopting rather than
      // insisting on labelling is the docker path's stance and holds for the same reason.
      return false;
    }
    PdProcess.Result created = run(buildNetworkCreateArgv(spec), CLEANUP_TIMEOUT);
    if (created.exitCode() != 0) {
      LOG.warnf("Could not ensure overlay '%s': %s", spec.name(), created.output());
      return false;
    }
    LOG.infof("Created attachable overlay %s (%s)", spec.name(), spec.kind());
    return true;
  }

  /**
   * Package-private for the argv test. {@code --attachable} is the load-bearing flag: it is what
   * lets plain {@code docker run} containers — CI steps, workspace containers, project agents —
   * live on the same network as the services, which is the whole reason the platform can move one
   * component at a time.
   */
  List<String> buildNetworkCreateArgv(Network spec) {
    List<String> argv =
        new ArrayList<>(List.of(runtime, "network", "create", "-d", "overlay", "--attachable"));
    argv.add("--label");
    argv.add(NETWORK_LABEL + "=" + spec.kind().name().toLowerCase(Locale.ROOT));
    if (spec.environmentId() != null) {
      argv.add("--label");
      argv.add(ENVIRONMENT_LABEL + "=" + spec.environmentId());
    }
    if (spec.applicationName() != null) {
      argv.add("--label");
      argv.add(APP_NAME_LABEL + "=" + spec.applicationName());
    }
    argv.add(spec.name());
    return List.copyOf(argv);
  }

  /**
   * Remove the network, retrying for a few seconds.
   *
   * <p>Measured: a network is removable about a second after the services on it are gone, not
   * immediately — the tasks' endpoints outlive the {@code service rm} that ordered them away. A
   * single attempt would report a failure that is only early.
   */
  @Override
  public void removeNetwork(String network) {
    for (int attempt = 1; attempt <= NETWORK_REMOVE_ATTEMPTS; attempt++) {
      PdProcess.Result removed =
          run(List.of(runtime, "network", "rm", network), CLEANUP_TIMEOUT);
      if (removed.exitCode() == 0) {
        return;
      }
      String output = safe(removed.output()).toLowerCase(Locale.ROOT);
      if (output.contains("not found") || output.contains("no such network")) {
        return; // somebody already did
      }
      if (attempt == NETWORK_REMOVE_ATTEMPTS) {
        LOG.debugf("Could not remove network '%s': %s", network, removed.output());
        return;
      }
      try {
        Thread.sleep(NETWORK_REMOVE_WAIT.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** {@code docker network ls} is the same command whatever created the networks — see the class javadoc. */
  @Override
  public List<Network> networks() {
    return docker.networks();
  }

  /**
   * Nothing to detach. A service's networks are declared when it is created and a teardown does not
   * reshape one; what makes the networks removable is the services going, which the reap before
   * this already ordered, and {@link #removeNetwork}'s retry loop waits for.
   */
  @Override
  public void detachPlatformPlane(List<String> networks) {
    LOG.debugf("Nothing to detach from %s: a service's networks are declared, not joined", networks);
  }

  /** The environment's services, by the label every one of them carries. */
  @Override
  public int removeEnvironmentContainers(String environmentId) {
    PdProcess.Result listed =
        run(
            List.of(
                runtime,
                "service",
                "ls",
                "-q",
                "--filter",
                "label=" + ENVIRONMENT_LABEL + "=" + environmentId),
            CLEANUP_TIMEOUT);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list services of environment %s: %s", environmentId, listed.output());
      return 0;
    }
    List<String> ids = lines(listed.output());
    if (ids.isEmpty()) {
      return 0;
    }
    List<String> argv = new ArrayList<>(List.of(runtime, "service", "rm"));
    argv.addAll(ids);
    run(argv, CLEANUP_TIMEOUT);
    return ids.size();
  }

  /** Classifying a missing image is ours on both paths — see the class javadoc. */
  @Override
  public PullResult pull(String imageRef) {
    return docker.pull(imageRef);
  }

  // --- the argv ------------------------------------------------------------------------------

  /**
   * Package-private for the argv test: the whole service, declared at once.
   *
   * <p>{@code --detach} because this component reads the verdict itself ({@link #awaitConverged});
   * without it the CLI blocks on convergence and the deployment's timeout would be the CLI's.
   * {@code --no-resolve-image} because the seed's {@code qits/*} tags exist only on this host and no
   * registry can resolve them to a digest.
   */
  List<String> buildCreateArgv(ServiceSpec spec, String name, List<String> networks) {
    List<String> argv =
        new ArrayList<>(
            List.of(
                runtime,
                "service",
                "create",
                "--detach",
                "--name",
                name,
                "--replicas",
                "1",
                "--no-resolve-image"));
    // The FULL membership, here and nowhere else: every later --network-add recreates the task.
    for (String network : networks) {
      argv.add("--network");
      argv.add(network);
    }
    // A deployed application outlives the daemon's restart, which is what `unless-stopped` says on
    // the docker path.
    argv.add("--restart-condition");
    argv.add("any");
    for (String label : labels(spec)) {
      argv.add("--label");
      argv.add(label);
      // The task container carries the same labels, so `docker ps --filter label=` still reads the
      // platform the way a person and the environment teardown both expect.
      argv.add("--container-label");
      argv.add(label);
    }
    healthFlags(argv, spec, "--health-cmd");
    updateFlags(argv, spec, "--update-order");
    for (String variable : environment(spec)) {
      argv.add("--env");
      argv.add(variable);
    }
    extras(argv, ServiceExtras.of(config, spec.applicationName()));
    argv.add(spec.imageRef());
    return List.copyOf(argv);
  }

  /**
   * Package-private for the argv test: the replace, which is this and nothing more.
   *
   * <p><b>Mounts, networks and published ports are deliberately absent.</b> A service update keeps
   * every part of the spec it is not asked to change, so re-stating them would at best be noise and
   * at worst would append a second copy of a mount. What changes on a deployment is the image, the
   * identity this deployment stamps on the service, and the policy the update itself runs under.
   *
   * <p><b>The environment is the exception, and it is re-stated in full</b> — this component's own
   * variables and the deployment config's alike. A variable is a value rather than a shape: config
   * naming a new address is a change the next deployment is supposed to carry, and {@code
   * --env-add} of an existing key replaces it.
   */
  List<String> buildUpdateArgv(ServiceSpec spec, String name) {
    List<String> argv =
        new ArrayList<>(
            List.of(
                runtime,
                "service",
                "update",
                "--detach",
                "--no-resolve-image",
                "--image",
                spec.imageRef()));
    for (String label : labels(spec)) {
      argv.add("--label-add");
      argv.add(label);
      argv.add("--container-label-add");
      argv.add(label);
    }
    healthFlags(argv, spec, "--health-cmd");
    updateFlags(argv, spec, "--update-order");
    for (String variable : environment(spec)) {
      argv.add("--env-add");
      argv.add(variable);
    }
    for (String variable : ServiceExtras.of(config, spec.applicationName()).env()) {
      // After this component's own, for the precedence rule the docker path has: the last
      // assignment of a key wins, so what config says outranks what this component defaults.
      argv.add("--env-add");
      argv.add(variable);
    }
    argv.add(name);
    return List.copyOf(argv);
  }

  /**
   * The gate, enforced by docker inside the container exactly as on the docker path — either the
   * repository's own command, passed through as ONE argv element, or the curl template over an
   * allowlist-validated path.
   *
   * <p>The three timings are the docker path's own keys, and the reason they are shared is that
   * they describe the same probe. What is <i>not</i> shared is the deadline: under swarm the window
   * is these plus {@code --update-monitor}, and both want measuring per application rather than
   * deriving from one platform-wide number.
   */
  private void healthFlags(List<String> argv, ServiceSpec spec, String cmdFlag) {
    // Re-validated here, at the last line before the argv, because this is the value that lands
    // inside a shell string the CONTAINER runs. Which value that is depends on the gate: a
    // repository that named a health_cmd replaced the path-shaped probe, so the path is neither
    // used nor checked.
    String command;
    if (spec.healthCmd() != null) {
      command = DeploymentIdentifiers.requireHealthCmd(spec.healthCmd());
    } else {
      command =
          "curl -fsS http://localhost:8080"
              + PdIdentifiers.requireHealthPath(spec.healthPath())
              + " || exit 1";
    }
    argv.add(cmdFlag);
    argv.add(command);
    argv.add("--health-interval");
    argv.add(healthIntervalSeconds + "s");
    argv.add("--health-retries");
    argv.add(String.valueOf(healthRetries));
    argv.add("--health-start-period");
    argv.add(healthStartPeriodSeconds + "s");
  }

  /**
   * The cutover, as three flags.
   *
   * <p>{@code --update-failure-action rollback} is not configurable and is not meant to be: a
   * successor that never goes healthy must leave the platform running whatever it replaced, which
   * is the invariant the docker path spends a stop and a restart on.
   */
  private void updateFlags(List<String> argv, ServiceSpec spec, String orderFlag) {
    argv.add(orderFlag);
    argv.add(spec.updateOrder().spelling());
    argv.add("--update-monitor");
    argv.add(updateMonitorSeconds + "s");
    argv.add("--update-failure-action");
    argv.add("rollback");
  }

  /**
   * The bookkeeping labels — the same six the docker path writes, because everything that reads
   * them (the environment teardown, the reconciliation's lookups, a person on the host) reads them
   * by name and does not care what created them.
   */
  private static List<String> labels(ServiceSpec spec) {
    List<String> labels = new ArrayList<>();
    // A platform service gets NO environment label, and the absence is the feature: an environment
    // teardown reaps every service carrying its id, and a platform-plane service must never go down
    // with a tier it merely serves.
    if (spec.environmentId() != null) {
      labels.add(ENVIRONMENT_LABEL + "=" + spec.environmentId());
    }
    labels.add(APPLICATION_LABEL + "=" + spec.applicationId());
    labels.add(DEPLOYMENT_LABEL + "=" + spec.deploymentId());
    labels.add(TARGET_LABEL + "=" + spec.target().name().toLowerCase(Locale.ROOT));
    labels.add(AVAILABLE_ON_ENV_LABEL + "=" + spec.availableOnEnv());
    labels.add(APP_NAME_LABEL + "=" + spec.applicationName());
    return List.copyOf(labels);
  }

  /**
   * Who and where this service is, plus whatever was provisioned for it. Deliberately minimal —
   * application config (datasources, peers) is the image's and the environment's own story.
   */
  private static List<String> environment(ServiceSpec spec) {
    List<String> variables = new ArrayList<>();
    // QITS_ENVIRONMENT is written for environment applications ONLY: a platform service serves
    // every environment, and telling it that it lives in one would be a statement that is untrue.
    if (spec.environmentName() != null) {
      variables.add("QITS_ENVIRONMENT=" + spec.environmentName());
    }
    variables.add("QITS_APPLICATION=" + spec.applicationName());
    String identity =
        DeployedIdentity.resourceAttributes(
            spec.commitSha(), spec.environmentName(), spec.wireAlias());
    variables.add(DeployedIdentity.OTEL_VARIABLE + "=" + identity);
    variables.add(DeployedIdentity.QUARKUS_OTEL_VARIABLE + "=" + identity);
    // What ResourceProvisioning made exist a moment ago, as the generic contract. The name is
    // re-validated HERE, at the last line before the argv, exactly like the health path: it is
    // repository-authored input being spliced into an environment-variable key.
    for (ResourceBinding binding : spec.resources()) {
      String key =
          PdIdentifiers.requireResourceName(binding.name())
              .toUpperCase(Locale.ROOT)
              .replace('-', '_');
      variables.add("QITS_RESOURCE_" + key + "_URL=" + safe(binding.url()));
      variables.add("QITS_RESOURCE_" + key + "_USERNAME=" + safe(binding.username()));
      variables.add("QITS_RESOURCE_" + key + "_PASSWORD=" + safe(binding.password()));
    }
    return List.copyOf(variables);
  }

  /**
   * {@link ServiceExtras} in {@code service create}'s vocabulary. Only this application's own keys
   * are read, which is the security property the docker path states the same way: one
   * application's socket bind cannot ride along on a sibling's deployment.
   */
  private void extras(List<String> argv, ServiceExtras extras) {
    for (ServiceExtras.Mount mount : extras.mounts()) {
      // Swarm names the kind rather than inferring it from a leading slash, which is what config
      // states — so this is a spelling, not a decision.
      argv.add("--mount");
      argv.add(
          "type="
              + mount.kind().name().toLowerCase(Locale.ROOT)
              + ",source="
              + mount.source()
              + ",target="
              + mount.target()
              + (mount.readOnly() ? ",readonly" : ""));
    }
    for (ServiceExtras.Publish publish : extras.publishes()) {
      // mode=host rather than the ingress default: it is per node, like a plain `docker run`, and
      // this platform is one node.
      //
      // AN IP IS A REFUSAL, NOT A WARNING. Swarm's publish syntax has no ip field in either mode
      // — measured: a host-mode publish listens on 0.0.0.0 — so a spec that asks for loopback
      // cannot be honoured, and honouring it approximately would put an endpoint that was
      // deliberately unreachable on every interface of the host.
      if (!publish.bindsAllInterfaces()) {
        throw new ServiceExtras.Refused(
            "swarm cannot publish "
                + publish.published()
                + " on "
                + publish.ip()
                + ": a service publish has no ip field, so this port would be on every interface");
      }
      argv.add("--publish");
      argv.add(
          "published="
              + publish.published()
              + ",target="
              + publish.target()
              + (publish.protocol() == null ? "" : ",protocol=" + publish.protocol())
              + ",mode=host");
    }
    for (String group : extras.groups()) {
      argv.add("--group");
      argv.add(group);
    }
    for (String variable : extras.env()) {
      argv.add("--env");
      argv.add(variable);
    }
  }

  // --- reading swarm back ----------------------------------------------------------------------

  /** The two overlays a service may be on, in declaration order. See the class javadoc. */
  private List<String> collapse(ServiceSpec spec) {
    Set<String> networks = new LinkedHashSet<>();
    if (flatNetwork != null && !flatNetwork.isBlank()) {
      networks.add(flatNetwork.strip());
    }
    if (spec.platform()) {
      networks.add(PdNetworks.PLATFORM);
    }
    List<String> dropped =
        spec.networks().stream().filter(network -> !networks.contains(network)).toList();
    if (!dropped.isEmpty()) {
      LOG.debugf(
          "%s is declared on %s; %s are not made under swarm — a join after create restarts the"
              + " task, so the topology is flat",
          spec.applicationName(), networks, dropped);
    }
    return List.copyOf(networks);
  }

  private boolean collapsed(String network) {
    return (flatNetwork != null && flatNetwork.strip().equals(network))
        || PdNetworks.PLATFORM.equals(network);
  }

  private boolean serviceExists(String name) {
    return run(
                List.of(runtime, "service", "inspect", "--format", "{{.ID}}", name),
                INSPECT_TIMEOUT)
            .exitCode()
        == 0;
  }

  /** The state word of every task of the current generation, lowercased. */
  private List<String> runningGenerationStates(String name) {
    PdProcess.Result listed =
        run(
            List.of(
                runtime,
                "service",
                "ps",
                name,
                "--filter",
                "desired-state=running",
                "--no-trunc",
                "--format",
                "{{.CurrentState}}"),
            INSPECT_TIMEOUT);
    return listed.exitCode() == 0 ? taskStates(listed.output()) : List.of();
  }

  /**
   * Package-private for the parsing test: {@code docker service ps} prints a phrase ({@code Running
   * 3 minutes ago}, {@code Starting less than a second ago}), and the first word is the state.
   */
  static List<String> taskStates(String output) {
    List<String> states = new ArrayList<>();
    for (String line : lines(output)) {
      String[] words = line.split("\\s+");
      if (words.length > 0 && !words[0].isBlank()) {
        states.add(words[0].toLowerCase(Locale.ROOT));
      }
    }
    return List.copyOf(states);
  }

  /** The tasks as a person would read them — a failed convergence's first diagnosis. */
  private String tasks(String name) {
    PdProcess.Result listed =
        run(List.of(runtime, "service", "ps", name, "--no-trunc"), INSPECT_TIMEOUT);
    return safe(listed.output());
  }

  /** A bounded tail of the service's own output — the second half of the diagnosis. */
  private String logs(String name) {
    PdProcess.Result result =
        run(
            List.of(runtime, "service", "logs", "--tail", LOG_TAIL_LINES, name), CLEANUP_TIMEOUT);
    return safe(result.output());
  }

  private static List<String> lines(String output) {
    return Arrays.stream((output == null ? "" : output).split("\\R"))
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .toList();
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
