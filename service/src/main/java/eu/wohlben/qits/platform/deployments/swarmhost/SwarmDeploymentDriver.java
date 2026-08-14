package eu.wohlben.qits.platform.deployments.swarmhost;

import eu.wohlben.qits.platform.deployments.deployments.control.DeployedIdentity;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentIdentifiers;
import eu.wohlben.qits.platform.deployments.deployments.control.HealthGate;
import eu.wohlben.qits.platform.deployments.deployments.control.PdProcess;
import eu.wohlben.qits.platform.deployments.deployments.control.ServiceExtras;
import eu.wohlben.qits.platform.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.platform.deployments.environments.control.PdNetworks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The swarm implementation of {@link DeploymentDriver}: a deployed application is a <b>service</b>,
 * and a replace is {@code docker service update --image} on it.
 *
 * <p><b>The whole cutover is flags.</b> {@code --update-order start-first} is the overlap, {@code
 * --update-monitor} is the gate window, {@code --update-failure-action rollback} is the rollback,
 * and a task does not enter DNS until its healthcheck passes — measured on this host: while a task
 * was {@code Starting}, {@code getent hosts} answered nothing for its name while the VIP already
 * existed. So there is no predecessor to find, nothing to stop, nothing to restart and nobody to
 * referee: this class issues one command and then reads a verdict.
 *
 * <p><b>The name is the address, and that is the one thing to keep in mind reading this.</b> {@code
 * container_name} does not exist in swarm — a task container is {@code
 * <service>.<slot>.<taskid>} — so the service NAME is what peers resolve, which makes it the wire
 * alias and makes a replace an update of the same service rather than a second container beside the
 * first. Every question this component asks afterwards ({@code awaitConverged}, {@code observe},
 * the environment teardown) is a service query, never a container name match.
 *
 * <p><b>The topology collapses to two overlays, and it is not a simplification for its own
 * sake.</b> {@code service update --network-add} recreates the task, so a hub-and-spoke topology —
 * one network per application, joined after the fact by every hub and every platform service —
 * would turn a single deployment into a restart storm across the platform. So a service declares
 * its whole membership at create time: {@code
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
 * <p><b>Two verbs here are not swarm-shaped at all</b>, and they are kept for what they answer:
 * {@code docker pull} classifies a missing image (swarm pulls on its own, but a task that never
 * starts is a much worse way to learn that nothing published this build), and {@code docker network
 * ls} reads the membership bookkeeping back whatever created the networks.
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
   * What swarm calls the update it is in the middle of, what it says about it, and <b>when it
   * started</b>. A service that has never been updated has no {@code UpdateStatus} at all, which is
   * why the format prints an empty state rather than failing — see {@link #awaitConverged}.
   *
   * <p><b>{@code StartedAt} sits in the middle on purpose.</b> The message is free text from the
   * daemon and is the one field that could contain a {@code |}, so it is read as the remainder of
   * the line; a timestamp behind it would be whatever the message left over.
   */
  static final String UPDATE_STATUS_FORMAT =
      "{{if .UpdateStatus}}{{.UpdateStatus.State}}|{{.UpdateStatus.StartedAt}}"
          + "|{{.UpdateStatus.Message}}{{else}}||{{end}}";

  /**
   * The startup sweep's evidence: what the service runs, then the same update status as wording.
   * One format because both fields sit on the object one {@code service inspect} returns.
   */
  static final String RUNNING_IMAGE_FORMAT =
      "{{.Spec.TaskTemplate.ContainerSpec.Image}}|" + UPDATE_STATUS_FORMAT;

  /**
   * How far an update's {@code StartedAt} may sit <i>before</i> the moment this process issued it
   * and still be believed to be that update.
   *
   * <p>Five seconds, and the asymmetry is the argument. In real time the daemon stamps {@code
   * StartedAt} <b>after</b> the CLI returned, so the only thing that can make it look earlier is
   * the two clocks disagreeing — an NTP-disciplined host is inside milliseconds and a daemon
   * reached over the network is inside a second or two. What this tolerance has to stay well under
   * is the distance to the update it must reject: the <i>previous</i> cutover of the same service,
   * which is another deployment and therefore minutes to months old. Five seconds is far above the
   * first number and far below the second, so no plausible skew makes a fresh status look stale and
   * no stale status can pass as fresh.
   */
  private static final Duration ISSUE_SKEW = Duration.ofSeconds(5);

  /**
   * How long an issued update is remembered when nobody ever came back for the verdict. Only a
   * deployment that never reached {@link #awaitConverged} can leave one behind (a crash between the
   * two calls), so this is a leak stop rather than a working value — an hour is far beyond any
   * health timeout, and pruning on write is what keeps the map bounded without a sweeper.
   */
  private static final Duration ISSUE_RETENTION = Duration.ofHours(1);

  /**
   * When this process issued the update swarm is now running, per service name — written by {@link
   * #apply}, read and cleared by {@link #awaitConverged}, which is the same "one orchestrator, one
   * seam" carry the retired docker driver used for its in-flight cutover state: both calls land on
   * this one {@code @ApplicationScoped} bean.
   *
   * <p>Concurrent because a bean is shared, not because the callers race: deployments run one at a
   * time on {@code pd-deploy-worker}.
   */
  private final Map<String, Instant> issuedUpdates = new ConcurrentHashMap<>();

  /**
   * The clock the issue instant is read from. A field so the suite can pin it — a test that
   * compared a scripted {@code StartedAt} against the wall clock would be timing the build host.
   */
  Clock clock = Clock.systemUTC();

  /**
   * Go's {@code time.Time.String()}, which is what {@code docker service inspect --format} prints
   * for {@code .UpdateStatus.StartedAt} — measured on docker 29.7.2: {@code 2026-08-13
   * 10:21:12.655795838 +0000 UTC}. <b>The JSON body of the same inspect says RFC3339 instead</b>
   * ({@code 2026-08-13T10:21:12.655795838Z}), so the parser takes both and this is only the first
   * of the two. The trailing zone name — and Go's monotonic {@code m=+...} suffix, which a value
   * decoded from the API never carries — are matched and dropped.
   */
  private static final Pattern GO_TIMESTAMP =
      Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?) ([+-]\\d{2}:?\\d{2})(?:\\s.*)?$");

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

  /**
   * What docker says when the registry answered "no such image". Matched case-insensitively over
   * the pull's combined output — brittle by nature (docker's wording is not an API), so the match
   * errs toward {@code ERROR}: an unrecognized failure is a failed deployment, never a false
   * "nothing published an image".
   */
  private static final List<String> IMAGE_MISSING_MARKERS =
      List.of("manifest unknown", "not found", "name unknown", "repository does not exist");

  /**
   * What docker says when the registry <b>refused</b> the pull. Matched the same way and read
   * <b>before</b> {@link #IMAGE_MISSING_MARKERS}, which is the whole point of the order: docker's
   * own refusal reads {@code pull access denied for <image>, repository does not exist or may
   * require 'docker login'}, so it carries a missing-image marker inside it and a first-match-wins
   * list would keep calling a refusal a missing image.
   *
   * <p>Which is what it did. The platform's registry sits behind the edge proxy, and the day reads
   * there stop being anonymous every deployment would have been recorded as "nothing published this
   * build" — sending an operator to a pipeline that had published perfectly well, while the thing
   * to fix is the credential the daemon reads.
   *
   * <p>The narrowness rule is unchanged: anything neither list recognises is {@code ERROR}.
   */
  private static final List<String> AUTH_REFUSED_MARKERS =
      List.of(
          "pull access denied",
          "docker login",
          "authorization failed",
          "no basic auth credentials",
          "unauthorized",
          "authentication required");

  @ConfigProperty(name = "qits.platform.deployments.container-runtime")
  String runtime;

  @ConfigProperty(name = "qits.platform.deployments.pull-timeout-seconds")
  long pullTimeoutSeconds;

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

  /**
   * Whether a service create and a service update carry {@code --with-registry-auth}. False
   * shipped, and then every argv here is what it was byte for byte.
   *
   * <p>See {@link #registryAuthFlag} for what the flag does and why the key exists.
   */
  @ConfigProperty(name = "qits.platform.deployments.registry-auth")
  boolean registryAuth;

  @ConfigProperty(name = "qits.platform.deployments.output-max-chars")
  int outputMaxChars;

  /** Looked up per key rather than {@code @ConfigProperty}: the key carries the application name. */
  @Inject Config config;

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
    if (exists && !self) {
      // WHICH update the verdict is about, recorded the only place that knows. See
      // `awaitConverged`: a service that has been cut over before answers with the PREVIOUS
      // update's terminal state until the daemon has replaced it, and one poll of that is a
      // deployment declared live 43 milliseconds after it was issued.
      rememberIssued(target);
    } else if (!exists) {
      // A create has no update to wait for, and a service that was removed and made again must not
      // inherit the removed one's issue instant — its empty status would then never settle.
      issuedUpdates.remove(target);
    }
    if (self) {
      // The self-update, and the arbiter is what makes it possible at all: the manager lives in
      // the daemon rather than in a container this process owns, so it can stop this task, start the
      // successor and revert the spec if the successor never goes healthy. Nothing here waits for
      // that — this process is what is being replaced.
      LOG.infof(
          "Self-update issued on service %s: the swarm manager finishes it, and the row stays"
              + " STARTING until the instance that survives records it",
          target);
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
   *
   * <p><b>The field says nothing about WHICH update it describes, and reading it as though it did
   * was a live defect.</b> {@code UpdateStatus} holds the most recent update of the service, and
   * {@code service update --detach} returns before the daemon has replaced it — so the first poll
   * after an update reads either the PREVIOUS cutover's terminal state or, in the window where
   * swarm has cleared it, nothing at all. Both were read as an answer: qits-docs was recorded
   * {@code ACTIVE} 43ms after its update was issued, off a {@code completed} left by an earlier
   * deployment, and its predecessor was decommissioned while swarm was still rolling the successor
   * back. The empty arm was as wrong for the same reason — under {@code start-first} the
   * predecessor's task is still {@code Running}, so the task fallback answered "converged" about
   * the deployment being replaced.
   *
   * <p>So an update this process ISSUED is matched by {@code StartedAt}: a status stamped before
   * the issue instant (less {@link #ISSUE_SKEW}) belongs to the update before this one and is
   * <b>pending</b>, an absent or unreadable stamp is <b>pending</b>, and the task fallback is
   * reached only where it is still true — a service this process has just created. Nothing here
   * waits forever: the caller's deadline ends it either way, and the timeout detail names what was
   * last seen, so an update whose status never arrives fails as an update rather than passing as
   * one.
   */
  @Override
  public Convergence awaitConverged(String name, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    // Null when nothing issued an update for this service — the first deployment of an
    // application, or an instance that did not perform the update it is asking about.
    Instant issued = issuedUpdates.get(name);
    String last = "(never inspected)";
    try {
      while (true) {
        PdProcess.Result inspected =
            run(
                List.of(runtime, "service", "inspect", "--format", UPDATE_STATUS_FORMAT, name),
                INSPECT_TIMEOUT);
        if (inspected.exitCode() != 0) {
          // Not a state at all: swarm has no such service. There is nothing to keep waiting for.
          return Convergence.failed("no service " + name + ": " + safe(inspected.output()));
        }
        String[] parts = safe(inspected.output()).strip().split("\\|", 3);
        String state = parts[0].strip().toLowerCase(Locale.ROOT);
        String startedAt = parts.length > 1 ? parts[1].strip() : "";
        String message = parts.length > 2 ? parts[2].strip() : "";
        String stale = issued == null ? null : notThisUpdate(state, startedAt, issued);
        if (stale != null) {
          // Somebody else's update, or not this one yet. Keep waiting, and remember the wording so
          // the timeout can say what it kept seeing.
          last = stale;
        } else {
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
    } finally {
      // The verdict is reached, however it went: this update is nobody's in-flight state now.
      issuedUpdates.remove(name);
    }
  }

  /**
   * Why this {@code UpdateStatus} is not the verdict of the update that was issued at {@code
   * issued} — or null when it is, and may be read.
   *
   * <p>Every arm here is a reason to keep waiting rather than to fail: the caller's deadline is
   * what ends the wait, and it carries the returned wording into the failure detail.
   */
  private static String notThisUpdate(String state, String startedAt, Instant issued) {
    if (state.isEmpty()) {
      // Swarm clears the field while it takes the update in. The task fallback is NOT reachable
      // here: under start-first the predecessor is still Running, so it would answer "converged"
      // about the deployment being replaced.
      return "not started yet (no update status)";
    }
    Instant stamped = parseStartedAt(startedAt);
    if (stamped == null) {
      // Never a crash and never a verdict: a stamp this cannot read is a stamp it cannot match, and
      // the raw text is what a person needs to see in the timeout.
      return state + " with an unreadable StartedAt '" + startedAt + "'";
    }
    if (stamped.isBefore(issued.minus(ISSUE_SKEW))) {
      return state + " from the earlier update started " + startedAt;
    }
    return null;
  }

  /**
   * Docker's two spellings of the same instant — Go's {@code time.Time.String()} from {@code
   * --format} and RFC3339 from the JSON body — or null for anything else, {@code <nil>} and {@code
   * <no value>} included.
   *
   * <p>Package-private for the parsing test.
   */
  static Instant parseStartedAt(String value) {
    String raw = safe(value).strip();
    if (raw.isEmpty()) {
      return null;
    }
    Matcher go = GO_TIMESTAMP.matcher(raw);
    if (go.matches()) {
      String offset = go.group(3);
      if (offset.length() == 5) {
        offset = offset.substring(0, 3) + ":" + offset.substring(3); // +0000 -> +00:00
      }
      raw = go.group(1) + "T" + go.group(2) + offset;
    }
    try {
      return OffsetDateTime.parse(raw).toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Record that this process just issued an update of {@code service}, pruning whatever an earlier
   * deployment left behind — see {@link #ISSUE_RETENTION}.
   */
  private void rememberIssued(String service) {
    Instant now = clock.instant();
    issuedUpdates.entrySet().removeIf(entry -> entry.getValue().isBefore(now.minus(ISSUE_RETENTION)));
    issuedUpdates.put(service, now);
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
   * service the daemon does not have is {@code gone}, which is a structural fact rather than a
   * wording match.
   */
  @Override
  public HealthGate.Poll observe(String name) {
    PdProcess.Result listed = observeTasks(name);
    if (listed.exitCode() != 0) {
      // The application may live under the seed stack's name — the deployer's own self-update
      // keeps its stack-named service. Asking only the bare alias flipped a healthy self-updated
      // deployer to FAILED: two observation passes read "no such service" while
      // qits_dev-qits-deployments served. The same fallback runningImage already has.
      listed = observeTasks(SEED_STACK_PREFIX + name);
    }
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

  private PdProcess.Result observeTasks(String service) {
    return run(
        List.of(
            runtime,
            "service",
            "ps",
            service,
            "--filter",
            "desired-state=running",
            "--no-trunc",
            "--format",
            "{{.CurrentState}}"),
        INSPECT_TIMEOUT);
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
      // The application may live under the seed stack's name: the deployer's own self-update
      // targets the stack-named service it runs as, so the successor's startup sweep must read
      // its evidence from the same place — asking only the bare alias settled the self-update's
      // row as "interrupted" while the service ran the row's exact image.
      inspected =
          run(
              List.of(
                  runtime,
                  "service",
                  "inspect",
                  "--format",
                  RUNNING_IMAGE_FORMAT,
                  SEED_STACK_PREFIX + name),
              INSPECT_TIMEOUT);
    }
    if (inspected.exitCode() != 0) {
      return Optional.empty(); // swarm has no such service under either name
    }
    // image | state | startedAt | message — the sweep wants the first two words and the last; WHEN
    // the update started is `awaitConverged`'s business, and reading past it here is what keeps the
    // message whole.
    String[] parts = safe(inspected.output()).strip().split("\\|", 4);
    String image = parts[0].strip();
    if (image.isEmpty()) {
      return Optional.empty();
    }
    String state = parts.length > 1 ? parts[1].strip() : "";
    String message = parts.length > 3 ? parts[3].strip() : "";
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
   * still asks for them (the state machine computes a membership without knowing who runs it),
   * and the answer here is a
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
      // Already there, labels and all — including one the bootstrap made. The labels are how this
      // component FINDS the networks it made, not a claim of ownership over every network.
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

  /**
   * Every network this component labelled, read back from the daemon.
   *
   * <p>{@code network ls} is the same command whatever created the networks — a bridge a retired
   * docker path made and an overlay this class made answer it alike — so the labels stay the one
   * record of the membership.
   */
  @Override
  public List<Network> networks() {
    PdProcess.Result listed =
        run(
            List.of(
                runtime,
                "network",
                "ls",
                "--filter",
                "label=" + NETWORK_LABEL,
                "--format",
                "{{.Name}}|{{.Labels}}"),
            CLEANUP_TIMEOUT);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list this component's networks: %s", listed.output());
      return List.of();
    }
    return parseNetworks(listed.output());
  }

  /** Package-private for the parsing test: one {@code name|k=v,k=v} line per network. */
  static List<Network> parseNetworks(String output) {
    List<Network> networks = new ArrayList<>();
    for (String line : safe(output).split("\\R")) {
      String[] parts = line.trim().split("\\|", 2);
      if (parts.length < 2 || parts[0].isEmpty()) {
        continue;
      }
      String environmentId = null;
      String applicationName = null;
      NetworkKind kind = null;
      for (String label : parts[1].split(",")) {
        int equals = label.indexOf('=');
        if (equals < 0) {
          continue;
        }
        String key = label.substring(0, equals).trim();
        String value = label.substring(equals + 1).trim();
        switch (key) {
          case ENVIRONMENT_LABEL -> environmentId = value;
          case APP_NAME_LABEL -> applicationName = value;
          case NETWORK_LABEL -> kind = kind(value);
          default -> {
            /* someone else's label */
          }
        }
      }
      if (kind != null) {
        networks.add(new Network(parts[0], environmentId, kind, applicationName));
      }
    }
    return List.copyOf(networks);
  }

  private static NetworkKind kind(String value) {
    for (NetworkKind candidate : NetworkKind.values()) {
      if (candidate.name().equalsIgnoreCase(value)) {
        return candidate;
      }
    }
    return null;
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

  /**
   * Classifying a missing image is ours, not swarm's — see the class javadoc.
   *
   * <p>The wording match is deliberately narrow: anything it does not recognise is {@code ERROR},
   * so a daemon that is down never reads as "nothing published this build".
   *
   * <p><b>A refusal is asked about first</b>, because docker's refusal wording contains a
   * missing-image marker — see {@link #AUTH_REFUSED_MARKERS}.
   */
  @Override
  public PullResult pull(String imageRef) {
    PdProcess.Result result =
        run(List.of(runtime, "pull", imageRef), Duration.ofSeconds(pullTimeoutSeconds));
    if (result.exitCode() == 0 && !result.timedOut()) {
      return new PullResult(PullOutcome.OK, null);
    }
    String output = safe(result.output());
    String lowered = output.toLowerCase(Locale.ROOT);
    if (AUTH_REFUSED_MARKERS.stream().anyMatch(lowered::contains)) {
      return new PullResult(PullOutcome.AUTH_REFUSED, output);
    }
    boolean missing = IMAGE_MISSING_MARKERS.stream().anyMatch(lowered::contains);
    return new PullResult(missing ? PullOutcome.IMAGE_MISSING : PullOutcome.ERROR, output);
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
    // Read once: the same refusal would otherwise be reached twice, and the aliases belong to the
    // membership while everything else belongs to the flags below it.
    ServiceExtras extras = ServiceExtras.of(config, spec.applicationName());
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
    registryAuthFlag(argv);
    // The FULL membership, here and nowhere else: every later --network-add recreates the task.
    networkFlags(argv, networks, extras.aliases());
    // A deployed application outlives the daemon's restart.
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
    extras(argv, extras, spec.publishMode());
    argv.add(spec.imageRef());
    return List.copyOf(argv);
  }

  /**
   * The membership, and the aliases that ride the shared network's attachment.
   *
   * <p><b>The short form is what a service with no aliases gets, byte for byte.</b> {@code
   * --network <net>} and {@code --network name=<net>} mean the same thing to swarm, but only one of
   * them is what every service on this platform was created with, and a shape change with no
   * intent behind it is a diff a reader has to rule out.
   *
   * <p><b>Only the flat network carries them.</b> An alias is an address, and the address has to be
   * on the network the platform's names are resolved on — the one every service joins. {@code
   * qits-platform} is the plane's own network and keeps the short form.
   *
   * <p><b>Aliases with no flat network to hold them are a REFUSAL</b>, the publish-with-an-ip
   * stance: a name that was asked for and quietly not registered is a peer resolving nothing, and
   * that failure surfaces as somebody else's outage hours later.
   */
  private void networkFlags(List<String> argv, List<String> networks, List<String> aliases) {
    String shared = flatNetwork == null ? "" : flatNetwork.strip();
    boolean carried = false;
    for (String network : networks) {
      argv.add("--network");
      if (aliases.isEmpty() || !network.equals(shared)) {
        argv.add(network);
        continue;
      }
      StringBuilder attachment = new StringBuilder("name=").append(network);
      for (String alias : aliases) {
        attachment.append(",alias=").append(alias);
      }
      argv.add(attachment.toString());
      carried = true;
    }
    if (!aliases.isEmpty() && !carried) {
      throw new ServiceExtras.Refused(
          "aliases "
              + aliases
              + " are declared, and this service joins no shared network to hold them: an alias is"
              + " an address on qits.platform.deployments.swarm.flat-network");
    }
  }

  /**
   * Package-private for the argv test: the replace, which is this and nothing more.
   *
   * <p><b>Mounts, networks and published ports are deliberately absent.</b> A service update keeps
   * every part of the spec it is not asked to change, so re-stating them would at best be noise and
   * at worst would append a second copy of a mount. What changes on a deployment is the image, the
   * identity this deployment stamps on the service, and the policy the update itself runs under.
   *
   * <p><b>The publish MODE rides with the ports, so changing it is not a deployment.</b> A
   * repository that starts saying {@code publish_mode: ingress} is describing a different shape of
   * service, and an existing service keeps the mode it was created with until it is removed and
   * created again — the {@code service rm} and redeploy every shape change here takes.
   *
   * <p><b>The network ALIASES ride with the networks, so changing them is not a deployment
   * either.</b> An attachment is restated as a whole or not at all: swarm has no add-an-alias, and
   * {@code --network-add} of a network the service is already on is an error. So a service that is
   * gaining or losing an alias takes {@code service update --network-rm <net> --network-add
   * name=<net>,alias=…} by hand — which recreates the task — or the {@code service rm} and redeploy.
   * A deployment after that keeps whatever the service holds, which is why an alias declared in
   * config reaches a LIVE service only on its next create.
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
    registryAuthFlag(argv);
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
      // After this component's own, which is the precedence rule: the last assignment of a key
      // wins, so what config says outranks what this component defaults.
      argv.add("--env-add");
      argv.add(variable);
    }
    argv.add(name);
    return List.copyOf(argv);
  }

  /**
   * The gate, enforced by docker inside the container — either the repository's own command,
   * passed through as ONE argv element, or the curl template over an allowlist-validated path.
   *
   * <p>The three timings are the {@code qits.platform.deployments.health-*} keys, and they describe
   * the probe alone. The deadline is not among them: the window is these plus {@code
   * --update-monitor}, and both want measuring per application rather than deriving from one
   * platform-wide number.
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
   * {@code --with-registry-auth}, on a create and on an update alike, when {@code
   * qits.platform.deployments.registry-auth} says so. Unset — the shipped state — this writes
   * nothing and both argvs are what they were byte for byte.
   *
   * <p><b>What the flag does.</b> It serialises the credential the CLI holds for the registry into
   * the service spec, so the swarm agent authenticates the task's own pull. Without it only the
   * warm-up {@code docker pull} above is authenticated — that one runs as this process, with this
   * process's {@code DOCKER_CONFIG} — and the node-side pull the task then performs carries nothing
   * and is refused. The image being present in the local image store is not a substitute: swarm
   * re-pulls per node, and this platform being one node is a coincidence rather than a contract.
   *
   * <p><b>Why it is a key rather than always on.</b> The platform's registry reads are anonymous
   * today, so the flag has nothing to serialise and would only put an empty auth block on every
   * service spec. The key is what lets the deployer ship ahead of the flip and be turned on with
   * the deployment that mounts a {@code config.json} — one restart rather than a release.
   *
   * <p><b>It does not conflict with {@code --no-resolve-image}.</b> The two answer different
   * questions: no-resolve tells the CLI not to ask the registry to turn the tag into a digest (the
   * manager keeps the tag as written, which is what the seed's registry-less {@code qits/*} tags
   * need), and this one hands the agents a credential for the pull they perform later. Nothing
   * about carrying auth makes the manager resolve a digest again.
   */
  private void registryAuthFlag(List<String> argv) {
    if (registryAuth) {
      argv.add("--with-registry-auth");
    }
  }

  /**
   * The cutover, as three flags.
   *
   * <p>{@code --update-failure-action rollback} is not configurable and is not meant to be: a
   * successor that never goes healthy must leave the platform running whatever it replaced.
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
   * The bookkeeping labels — six, and everything that reads them (the environment teardown, a
   * person on the host) reads them by name and does not care what created them, which is what lets
   * a container the bootstrap seeded and a service this class made be found the same way.
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
   * are read, and that is the security property: one application's socket bind cannot ride along
   * on a sibling's deployment.
   */
  private void extras(List<String> argv, ServiceExtras extras, PublishMode publishMode) {
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
      // The mode is the repository's `publish_mode`, and it defaults to host: the task binds the
      // port per node, like a plain `docker run`, which is what every publishing service does
      // today. `ingress` gives the port to the routing mesh instead, so a replacement can start
      // while the predecessor still holds the door open.
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
              + ",mode="
              + publishMode.spelling());
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
