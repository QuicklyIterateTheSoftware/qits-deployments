package eu.wohlben.qits.platform.deployments.dockerhost;

import static eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver.APPLICATION_LABEL;
import static eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver.AVAILABLE_ON_ENV_LABEL;
import static eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver.DEPLOYMENT_LABEL;
import static eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver.ENVIRONMENT_LABEL;
import static eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver.NETWORK_LABEL;
import static eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver.TARGET_LABEL;

import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver.Network;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver.NetworkKind;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver.PullOutcome;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver.PullResult;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver.ResourceBinding;
import eu.wohlben.qits.platform.deployments.deployments.control.DeployedIdentity;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentIdentifiers;
import eu.wohlben.qits.platform.deployments.deployments.control.HealthGate;
import eu.wohlben.qits.platform.deployments.deployments.control.PdProcess;
import eu.wohlben.qits.platform.deployments.deployments.control.ServiceExtras;
import eu.wohlben.qits.platform.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.platform.deployments.environments.control.PdNetworks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The docker CLI itself: shells it via {@link PdProcess} ({@code ProcessBuilder}, never a shell —
 * nothing is ever re-split). This component's whole docker vocabulary is here: {@code pull}, {@code
 * run}, {@code inspect}, {@code logs}, {@code rm}, {@code ps}, {@code network}
 * create/inspect/ls/connect/disconnect/rm. {@code exec} is not in it and must not enter it — what a
 * deployed container runs is its image's own entrypoint, and this component's relationship with it
 * ends at lifecycle.
 *
 * <p><b>It performs, it does not decide.</b> Which container to stop, whether a refused join fails
 * a deployment and what a self-update does are {@link DockerDeploymentDriver}'s, one layer up;
 * this class was that whole file until there was a second orchestrator to keep docker's model out
 * of. The argv assembly and the output parsing stayed here, which is also where their tests are.
 *
 * <p><b>Labels are the membership bookkeeping.</b> Which containers sit on which networks is never
 * stored in cd's database; it is written as docker labels at {@code run}/{@code network create} and
 * read back with {@code --filter label=}. One record of the truth, and it is the runtime's.
 *
 * <p><b>The health gate runs inside the container, on purpose.</b> cd never joins an environment's
 * network, so it cannot probe the fresh container itself; instead the {@code docker run} carries a
 * {@code --health-cmd} curl'ing localhost, and {@link #awaitHealthy} polls {@code docker inspect}
 * for docker's own verdict. The image contract that buys: the image carries {@code curl} and the
 * application listens on 8080 (both platform conventions). An image without curl fails the gate —
 * visibly, with the health log in the deployment's detail.
 *
 * <p>An image that meets neither convention — a plain postgres, say — names its own probe with
 * {@code health_cmd} in its spec, and that string becomes the {@code --health-cmd} verbatim. The
 * gate itself is unchanged: it is still docker's verdict, still taken inside the container.
 *
 * <p><b>Containers run detached with {@code --restart unless-stopped} and are removed
 * explicitly.</b> A deployed application must survive a docker daemon restart and a restart of
 * this component both; every removal is a decision recorded on a deployment row (a decommission,
 * a failed cutover, a teardown), never a side effect.
 */
@ApplicationScoped
public class DockerCli implements DockerHost {

  private static final Logger LOG = Logger.getLogger(DockerCli.class);

  private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(10);

  /** How often {@link #awaitHealthy} asks docker for its verdict. */
  private static final Duration HEALTH_POLL = Duration.ofMillis(500);

  /** Lines of container log kept as a failed gate's diagnosis. */
  private static final String LOG_TAIL_LINES = "200";

  /**
   * The name a container carries its application by. Re-exported here because a reconciliation
   * joins a <i>running</i> container to a network and has to give it the right alias there — the
   * application id it already carried names the row, not the address peers use.
   */
  static final String APP_NAME_LABEL = DeploymentDriver.APP_NAME_LABEL;

  /**
   * What docker says when the registry answered "no such image". Matched case-insensitively over
   * the pull's combined output to tell {@code IMAGE_MISSING} from a docker that is down — brittle
   * by nature (docker's wording is not an API), so the match errs toward {@code ERROR}: an
   * unrecognized failure is a failed deployment, never a false "nothing published an image".
   */
  private static final List<String> IMAGE_MISSING_MARKERS =
      List.of(
          "manifest unknown",
          "not found",
          "name unknown",
          "repository does not exist",
          "pull access denied");

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

  @ConfigProperty(name = "qits.platform.deployments.output-max-chars")
  int outputMaxChars;

  /**
   * What one application needs beyond its image, in deployment config: {@code
   * qits.platform.deployments.extras.<application-name>.*}, rendered here in {@code docker run}'s
   * own spelling. Deployment config is the ONLY source — never the API, never the intake — which is
   * what keeps the trust domain the one that already holds the docker socket. Package-private for
   * the argv tests.
   */
  static final String EXTRAS_PREFIX = DeploymentDriver.EXTRAS_PREFIX;

  /** Looked up per key rather than {@code @ConfigProperty}: the key carries the application name. */
  @Inject Config config;

  @Override
  public boolean ensureNetwork(Network spec) {
    if (PdProcess.run(
                null,
                List.of(runtime, "network", "inspect", spec.name()),
                CLEANUP_TIMEOUT,
                8192)
            .exitCode()
        == 0) {
      // Already there, labels and all — including a network made outside cd. Adopting the
      // platform's own qits-net rather than insisting on labelling it is deliberate: the labels
      // are how cd FINDS the networks it made, not a claim of ownership over every network.
      return false;
    }
    PdProcess.Result create =
        PdProcess.run(null, buildNetworkCreateArgv(spec), CLEANUP_TIMEOUT, 8192);
    if (create.exitCode() != 0) {
      LOG.warnf("Could not ensure network '%s': %s", spec.name(), create.output());
      return false;
    }
    LOG.debugf("Created network %s (%s)", spec.name(), spec.kind());
    return true;
  }

  /** Package-private for the argv test: the labels are how every later lookup finds this network. */
  List<String> buildNetworkCreateArgv(Network spec) {
    List<String> argv = new ArrayList<>(List.of(runtime, "network", "create"));
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

  @Override
  public List<Network> networks() {
    PdProcess.Result listed =
        PdProcess.run(
            null,
            List.of(
                runtime,
                "network",
                "ls",
                "--filter",
                "label=" + NETWORK_LABEL,
                "--format",
                "{{.Name}}|{{.Labels}}"),
            CLEANUP_TIMEOUT,
            outputMaxChars);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list cd's networks: %s", listed.output());
      return List.of();
    }
    return parseNetworks(listed.output());
  }

  /** Package-private for the parsing test: one {@code name|k=v,k=v} line per network. */
  static List<Network> parseNetworks(String output) {
    List<Network> networks = new ArrayList<>();
    for (String line : (output == null ? "" : output).split("\\R")) {
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

  @Override
  public ConnectResult connect(String network, String container, String alias) {
    List<String> argv = new ArrayList<>(List.of(runtime, "network", "connect"));
    if (alias != null && !alias.isBlank()) {
      argv.add("--alias");
      argv.add(alias);
    }
    argv.add(network);
    argv.add(container);
    PdProcess.Result result = PdProcess.run(null, argv, CLEANUP_TIMEOUT, 8192);
    if (result.exitCode() == 0 && !result.timedOut()) {
      return new ConnectResult(true, null);
    }
    String output = result.output() == null ? "" : result.output();
    if (alreadyJoined(output)) {
      // The normal outcome of the self-heal, and of re-reconciling a network somebody is already
      // on: docker changed nothing and the container is where the caller wanted it.
      LOG.debugf("%s was already on '%s'", container, network);
      return new ConnectResult(true, null);
    }
    LOG.warnf("Could not join %s to '%s': %s", container, network, output);
    return new ConnectResult(false, output);
  }

  /**
   * Whether docker's refusal was "it is already there". Measured against the platform's own daemon
   * (29.5.3): {@code endpoint with name <container> already exists in network <network>}. The
   * second wording is the one older daemons answer with. Brittle by nature, so it errs the safe
   * way: an unrecognised refusal is a failed join, never a false "already fine". Package-private
   * for the test that pins both wordings.
   */
  static boolean alreadyJoined(String output) {
    String lowered = output.toLowerCase(Locale.ROOT);
    return lowered.contains("already exists in network")
        || lowered.contains("is already connected to network");
  }

  @Override
  public void disconnect(String network, String container) {
    PdProcess.Result result =
        PdProcess.run(
            null,
            List.of(runtime, "network", "disconnect", network, container),
            CLEANUP_TIMEOUT,
            8192);
    if (result.exitCode() != 0) {
      LOG.debugf("Could not remove %s from '%s': %s", container, network, result.output());
    }
  }

  @Override
  public List<Endpoint> hubContainers(String environmentId) {
    return endpoints(
        List.of(
            "label=" + AVAILABLE_ON_ENV_LABEL + "=true",
            "label=" + ENVIRONMENT_LABEL + "=" + environmentId));
  }

  @Override
  public List<Endpoint> platformContainers() {
    return endpoints(List.of("label=" + TARGET_LABEL + "=platform"));
  }

  /** Running containers matching every filter, each with the alias it must keep on a new network. */
  private List<Endpoint> endpoints(List<String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtime, "ps", "-q"));
    for (String filter : filters) {
      argv.add("--filter");
      argv.add(filter);
    }
    PdProcess.Result listed = PdProcess.run(null, argv, CLEANUP_TIMEOUT, 8192);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list containers %s: %s", filters, listed.output());
      return List.of();
    }
    List<String> ids = idLines(listed.output());
    if (ids.isEmpty()) {
      return List.of();
    }
    List<String> inspect =
        new ArrayList<>(
            List.of(
                runtime,
                "inspect",
                "--format",
                "{{.Id}}|{{index .Config.Labels \"" + APP_NAME_LABEL + "\"}}"));
    inspect.addAll(ids);
    PdProcess.Result inspected = PdProcess.run(null, inspect, CLEANUP_TIMEOUT, outputMaxChars);
    if (inspected.exitCode() != 0) {
      LOG.debugf("Could not inspect containers %s: %s", filters, inspected.output());
      return List.of();
    }
    return parseEndpoints(inspected.output());
  }

  /** Package-private for the parsing test: one {@code id|app-name} line per container. */
  static List<Endpoint> parseEndpoints(String output) {
    List<Endpoint> endpoints = new ArrayList<>();
    for (String line : (output == null ? "" : output).split("\\R")) {
      String[] parts = line.trim().split("\\|", 2);
      if (parts.length < 2 || parts[0].isEmpty() || parts[1].isBlank()) {
        continue;
      }
      endpoints.add(new Endpoint(parts[0], parts[1].trim()));
    }
    return List.copyOf(endpoints);
  }

  @Override
  public void removeNetwork(String network) {
    PdProcess.Result result =
        PdProcess.run(null, List.of(runtime, "network", "rm", network), CLEANUP_TIMEOUT, 8192);
    if (result.exitCode() != 0) {
      LOG.debugf("Could not remove network '%s': %s", network, result.output());
    }
  }

  @Override
  public PullResult pull(String imageRef) {
    PdProcess.Result result =
        PdProcess.run(
            null,
            List.of(runtime, "pull", imageRef),
            Duration.ofSeconds(pullTimeoutSeconds),
            outputMaxChars);
    if (result.exitCode() == 0 && !result.timedOut()) {
      return new PullResult(PullOutcome.OK, null);
    }
    String output = result.output() == null ? "" : result.output();
    String lowered = output.toLowerCase(Locale.ROOT);
    boolean missing = IMAGE_MISSING_MARKERS.stream().anyMatch(lowered::contains);
    return new PullResult(missing ? PullOutcome.IMAGE_MISSING : PullOutcome.ERROR, output);
  }

  @Override
  public List<Holder> aliasHolders(List<String> networks, List<String> aliases) {
    // The UNION over every network the fresh container is about to be on, legacy one included.
    // A `docker ps --filter network=a --filter network=b` is an OR over networks, so one call
    // answers the whole question — and a container on two of them appears once.
    List<String> argv = new ArrayList<>(List.of(runtime, "ps", "-q"));
    for (String network : networks) {
      argv.add("--filter");
      argv.add("network=" + network);
    }
    PdProcess.Result listed = PdProcess.run(null, argv, CLEANUP_TIMEOUT, 8192);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list containers on %s: %s", networks, listed.output());
      return List.of();
    }
    List<String> ids = idLines(listed.output());
    if (ids.isEmpty()) {
      return List.of();
    }
    // One inspect for all of them: id|name|every alias the container holds anywhere|its
    // environment. A container's own name always resolves on a user-defined network, so it counts
    // as an alias here — that is what lets a replace cutover absorb a predecessor the bootstrap
    // started outside cd. The aliases are read across all networks rather than one: the containers
    // were already filtered to the networks that matter, and a predecessor that holds the alias on
    // the legacy network alone is exactly the one this has to find.
    //
    // The environment is read by RANGING over the labels rather than with `index`, which is not a
    // style choice: measured on docker 29.5.3, an `index` of an empty label map that FOLLOWS the
    // alias ranges above prints `<no value>` — which would read back as an environment id no
    // environment has. The range form answers empty, which is what an unlabelled container means.
    List<String> inspect =
        new ArrayList<>(
            List.of(
                runtime,
                "inspect",
                "--format",
                "{{.Id}}|{{.Name}}|{{range $net, $conf := .NetworkSettings.Networks}}"
                    + "{{range $conf.Aliases}}{{.}} {{end}}{{end}}|"
                    + "{{range $key, $value := .Config.Labels}}{{if eq $key \""
                    + ENVIRONMENT_LABEL
                    + "\"}}{{$value}}{{end}}{{end}}"));
    inspect.addAll(ids);
    PdProcess.Result inspected = PdProcess.run(null, inspect, CLEANUP_TIMEOUT, outputMaxChars);
    if (inspected.exitCode() != 0) {
      LOG.debugf("Could not inspect containers on %s: %s", networks, inspected.output());
      return List.of();
    }
    return parseHolders(inspected.output(), aliases);
  }

  private static List<String> idLines(String output) {
    return Arrays.stream((output == null ? "" : output).split("\\R"))
        .map(String::trim)
        .filter(id -> !id.isEmpty())
        .toList();
  }

  /**
   * Package-private for the parsing test: one `id|/name|alias alias ...|env` line per container,
   * kept when it answers to any of the aliases asked about.
   *
   * <p><b>A container's own name counts as an alias</b>, because it resolves on a user-defined
   * network exactly as an alias does — that is what lets a cutover absorb a bootstrap-seeded
   * original called {@code qits-gateway}. It is matched against the same alias set, so an original
   * named after the bare application is still found now that this component's own containers hold
   * the tier-qualified spelling. The names this component assigns
   * ({@code qits-pd-<env>-<app>-<id8>}) match no alias by construction, which is deliberate: a
   * predecessor is found by what peers dial, never by what a person reads.
   */
  static List<Holder> parseHolders(String inspectOutput, List<String> aliases) {
    List<Holder> holders = new ArrayList<>();
    for (String line : (inspectOutput == null ? "" : inspectOutput).split("\\R")) {
      String[] parts = line.trim().split("\\|", 4);
      if (parts.length < 2) {
        continue;
      }
      String name = parts[1].startsWith("/") ? parts[1].substring(1) : parts[1];
      List<String> held =
          parts.length >= 3 ? Arrays.asList(parts[2].trim().split("\\s+")) : List.of();
      if (aliases.stream().anyMatch(alias -> name.equals(alias) || held.contains(alias))) {
        holders.add(new Holder(parts[0], name, environmentOf(parts)));
      }
    }
    return List.copyOf(holders);
  }

  /**
   * The environment field of an inspect line, or null when the container carries none. {@code <no
   * value>} is treated as none as well: the format avoids producing it, and a belt here is cheaper
   * than an environment id no environment has reaching a predecessor decision.
   */
  private static String environmentOf(String[] parts) {
    if (parts.length < 4) {
      return null;
    }
    String value = parts[3].trim();
    return value.isEmpty() || "<no value>".equals(value) ? null : value;
  }

  @Override
  public void stop(String containerName) {
    PdProcess.Result result =
        PdProcess.run(null, List.of(runtime, "stop", containerName), RUN_TIMEOUT, 8192);
    if (result.exitCode() != 0) {
      LOG.warnf("Could not stop container %s: %s", containerName, result.output());
    }
  }

  @Override
  public void restart(String containerName) {
    PdProcess.Result result =
        PdProcess.run(null, List.of(runtime, "start", containerName), RUN_TIMEOUT, 8192);
    if (result.exitCode() != 0) {
      LOG.warnf("Could not restart container %s: %s", containerName, result.output());
    }
  }

  @Override
  public String selfContainerId() {
    try {
      return java.nio.file.Files.readString(java.nio.file.Path.of("/etc/hostname")).strip();
    } catch (Exception e) {
      return "";
    }
  }

  @Override
  public String runningImage(String containerName) {
    PdProcess.Result result =
        PdProcess.run(null, buildRunningImageArgv(containerName), INSPECT_TIMEOUT, 8192);
    if (result.exitCode() != 0 || result.output() == null) {
      return ""; // no such container: docker cannot inspect it at all
    }
    return result.output().strip();
  }

  /**
   * Package-private for the argv test. {@code .Config.Image} is the reference the container was
   * <b>run</b> with, tag and all — {@code .Image} would be the resolved image id, which carries no
   * sha the deployment row could be compared against.
   */
  List<String> buildRunningImageArgv(String containerName) {
    return List.of(runtime, "inspect", "--format", "{{.Config.Image}}", containerName);
  }

  @Override
  public StartResult start(StartSpec spec) {
    List<String> argv;
    try {
      argv = buildArgv(spec);
    } catch (ServiceExtras.Refused e) {
      // Config said something that cannot be rendered. Nothing runs, and the caller puts the
      // predecessor back — a typed key family makes garbage in it a bug, not a vocabulary.
      LOG.warnf("Refusing to start %s: %s", spec.containerName(), e.getMessage());
      return new StartResult(false, e.getMessage());
    }
    PdProcess.Result result = PdProcess.run(null, argv, RUN_TIMEOUT, outputMaxChars);
    if (result.exitCode() != 0 || result.timedOut()) {
      LOG.warnf("Could not start container %s: %s", spec.containerName(), result.output());
      return new StartResult(false, result.output());
    }
    LOG.debugf("Started container %s (%s)", spec.containerName(), spec.imageRef());
    return new StartResult(true, null);
  }

  /**
   * The gate is {@link HealthGate}'s — this half is only the docker calls it polls through. The
   * semantics live in the domain module because the suite's fake gate has to be the same gate: a
   * container that is restarting or not yet healthy is PENDING until the deadline, and only the
   * deadline or a container docker cannot find at all ends it.
   */
  @Override
  public HealthGate.Result awaitHealthy(String containerName, Duration timeout) {
    return HealthGate.await(
        timeout, HEALTH_POLL, () -> observe(containerName), () -> logs(containerName));
  }

  /**
   * One {@code docker inspect} of the container's state, as the gate reads it — and as the periodic
   * observation reads it too. The gate polls this in a loop; the observer asks it once per pass. One
   * docker call, one meaning of "healthy", one meaning of "gone".
   */
  @Override
  public HealthGate.Poll observe(String containerName) {
    PdProcess.Result inspected =
        PdProcess.run(
            null,
            List.of(
                runtime,
                "inspect",
                "--format",
                // Status is `running`/`restarting`/`exited`/`dead`; Health.Status only exists
                // because every run here carries a --health-cmd.
                "{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
                containerName),
            INSPECT_TIMEOUT,
            8192);
    if (inspected.exitCode() != 0) {
      // Not a state at all: docker has no such container. Every other answer is something to keep
      // waiting on — a container under `--restart unless-stopped` that died on its first boot is
      // seconds away from a second one, and that second boot is the deployment working.
      return HealthGate.Poll.gone(inspected.output());
    }
    return HealthGate.Poll.of(inspected.output() == null ? "" : inspected.output().strip());
  }

  @Override
  public void remove(String containerName) {
    PdProcess.Result result =
        PdProcess.run(null, List.of(runtime, "rm", "-f", containerName), CLEANUP_TIMEOUT, 8192);
    if (result.exitCode() != 0) {
      LOG.debugf("Could not remove container %s: %s", containerName, result.output());
    }
  }

  @Override
  public int removeEnvironmentContainers(String environmentId) {
    PdProcess.Result listed =
        PdProcess.run(
            null,
            List.of(
                runtime, "ps", "-aq", "--filter", "label=" + ENVIRONMENT_LABEL + "=" + environmentId),
            CLEANUP_TIMEOUT,
            8192);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list containers of environment %s: %s", environmentId, listed.output());
      return 0;
    }
    List<String> ids = idLines(listed.output());
    if (ids.isEmpty()) {
      return 0;
    }
    List<String> argv = new ArrayList<>(List.of(runtime, "rm", "-f"));
    argv.addAll(ids);
    PdProcess.run(null, argv, CLEANUP_TIMEOUT, 8192);
    return ids.size();
  }

  /** A bounded tail of the container's own output — the diagnosis of a failed health gate. */
  private String logs(String containerName) {
    PdProcess.Result result =
        PdProcess.run(
            null,
            List.of(runtime, "logs", "--tail", LOG_TAIL_LINES, containerName),
            CLEANUP_TIMEOUT,
            outputMaxChars);
    return result.output() == null ? "" : result.output();
  }

  /** Package-private for argv assembly tests. */
  List<String> buildArgv(StartSpec spec) {
    // Everything reaching this argv was validated at the boundary; the health gate's value is
    // re-checked here because it is what lands inside a shell string the CONTAINER runs, and this
    // is the last line before it. Which value that is depends on the gate: a repository that named
    // a health_cmd replaced the path-shaped probe, so the path is neither used nor checked.
    if (spec.healthCmd() != null) {
      DeploymentIdentifiers.requireHealthCmd(spec.healthCmd());
    } else {
      PdIdentifiers.requireHealthPath(spec.healthPath());
    }
    List<String> argv = new ArrayList<>();
    argv.add(runtime);
    argv.add("run");
    argv.add("-d");
    argv.add("--name");
    argv.add(spec.containerName());
    // Docker takes ONE network at run time — the application's own for an environment application,
    // qits-platform for a platform service. Every further membership is a `network connect --alias`
    // after the start (DeployService.desiredJoins), carrying this same alias, so the address
    // resolves on every network the container is on and not just the first.
    //
    // The alias is what peers resolve, and it stays stable across deployments while container names
    // do not. It is derived in PdNetworks so the run, the joins and the predecessor search cannot
    // disagree: <environment>-<application> for a tier's copy, the bare name for a platform
    // service.
    argv.add("--network");
    argv.add(spec.network());
    argv.add("--network-alias");
    argv.add(PdNetworks.alias(spec.environmentName(), spec.applicationName()));
    // A deployed application outlives its deployer and a daemon restart both. `unless-stopped`
    // rather than `always`: a decommissioned container is stopped before removal and must not race
    // its own restart.
    argv.add("--restart");
    argv.add("unless-stopped");
    // A platform service gets NO environment label, and the absence is the feature: an
    // environment teardown reaps every container carrying its id, and a platform-plane container
    // must never go down with a tier it merely serves.
    if (spec.environmentId() != null) {
      argv.add("--label");
      argv.add(ENVIRONMENT_LABEL + "=" + spec.environmentId());
    }
    argv.add("--label");
    argv.add(APPLICATION_LABEL + "=" + spec.applicationId());
    argv.add("--label");
    argv.add(DEPLOYMENT_LABEL + "=" + spec.deploymentId());
    // What a reconciliation looks this container up by when a network it belongs on is created
    // later: the plane it is on, whether it is a public node, and the alias it must keep.
    argv.add("--label");
    argv.add(TARGET_LABEL + "=" + spec.target().name().toLowerCase(Locale.ROOT));
    argv.add("--label");
    argv.add(AVAILABLE_ON_ENV_LABEL + "=" + spec.availableOnEnv());
    argv.add("--label");
    argv.add(APP_NAME_LABEL + "=" + spec.applicationName());
    // The health gate, enforced by docker inside the container (see the class javadoc). Either the
    // repository's own command, passed through as ONE argv element — docker runs it with
    // `/bin/sh -c`, so its spaces are the shell's and not this component's to split — or the curl
    // template over an allowlist-validated path, which is every service with an HTTP surface.
    argv.add("--health-cmd");
    argv.add(
        spec.healthCmd() != null
            ? spec.healthCmd()
            : "curl -fsS http://localhost:8080" + spec.healthPath() + " || exit 1");
    argv.add("--health-interval");
    argv.add(healthIntervalSeconds + "s");
    argv.add("--health-retries");
    argv.add(String.valueOf(healthRetries));
    argv.add("--health-start-period");
    argv.add(healthStartPeriodSeconds + "s");
    // Who and where this container is, for its own logs/telemetry. Deliberately minimal —
    // application config (datasources, peers) is the image's and the environment's own story.
    // QITS_ENVIRONMENT is written for environment applications ONLY: a platform service serves
    // every environment, and telling it that it lives in one would be a statement that is untrue.
    if (spec.environmentName() != null) {
      env(argv, "QITS_ENVIRONMENT", spec.environmentName());
    }
    env(argv, "QITS_APPLICATION", spec.applicationName());
    // The same identity again, in the vocabulary OpenTelemetry reads (see DeployedIdentity).
    String resourceAttributes =
        DeployedIdentity.resourceAttributes(
            spec.commitSha(), spec.environmentName(), spec.containerName());
    env(argv, DeployedIdentity.OTEL_VARIABLE, resourceAttributes);
    env(argv, DeployedIdentity.QUARKUS_OTEL_VARIABLE, resourceAttributes);
    // What ResourceProvisioning made exist a moment ago, as the generic contract:
    // QITS_RESOURCE_<NAME>_URL / _USERNAME / _PASSWORD. The application maps these three in its own
    // shipped defaults, so this component names no framework and no datasource key — which is what
    // lets one code path deploy a Quarkus service, a plain image and whatever comes next.
    //
    // The name is re-validated HERE, at the last line before the argv, exactly like the health
    // path: it is repository-authored input, it is being spliced into an environment-variable key,
    // and this is the belt that turns a loosened boundary check into a failed deployment rather
    // than a forged second variable. The VALUES are this component's own — generated, or read back
    // from its registry — and nothing arriving over HTTP contributes one.
    for (ResourceBinding binding : spec.resources()) {
      String key =
          PdIdentifiers.requireResourceName(binding.name())
              .toUpperCase(Locale.ROOT)
              .replace('-', '_');
      env(argv, "QITS_RESOURCE_" + key + "_URL", binding.url());
      env(argv, "QITS_RESOURCE_" + key + "_USERNAME", binding.username());
      env(argv, "QITS_RESOURCE_" + key + "_PASSWORD", binding.password());
    }
    // What this application needs beyond its image, in docker's spelling. The application name was
    // already dns-label-validated at the boundary, so the assembled key cannot escape the family,
    // and only this application's own keys are read.
    //
    // THE ENVIRONMENT GOES LAST, AND THAT IS THE PRECEDENCE RULE: docker keeps the LAST assignment
    // of a repeated env key (measured: `docker run -e FOO=first -e FOO=second` leaves one FOO, and
    // it is `second`). So every variable cd sets above is a DEFAULT the deployment can override by
    // naming the same key, and cd never overwrites what an operator wrote.
    extras(argv, ServiceExtras.of(config, spec.applicationName()));
    argv.add(spec.imageRef());
    return List.copyOf(argv);
  }

  /**
   * {@link ServiceExtras} in {@code docker run}'s vocabulary — the whole of what this driver does
   * with it.
   *
   * <p>Each element becomes one flag and one value, so a value with a space in it survives: the
   * free-form family this replaced was whitespace split, and could not carry one at all.
   */
  private static void extras(List<String> argv, ServiceExtras extras) {
    for (ServiceExtras.Mount mount : extras.mounts()) {
      // docker run takes a volume name and a host path in the same flag and tells them apart by
      // the leading slash. The kind is stated in config; here it is only checked against that.
      argv.add("-v");
      argv.add(mount.source() + ":" + mount.target() + (mount.readOnly() ? ":ro" : ""));
    }
    for (ServiceExtras.Publish publish : extras.publishes()) {
      argv.add("-p");
      argv.add(
          (publish.ip() == null ? "" : publish.ip() + ":")
              + publish.published()
              + ":"
              + publish.target()
              + (publish.protocol() == null ? "" : "/" + publish.protocol()));
    }
    for (String group : extras.groups()) {
      argv.add("--group-add");
      argv.add(group);
    }
    for (String variable : extras.env()) {
      argv.add("--env");
      argv.add(variable);
    }
  }

  private static void env(List<String> argv, String key, String value) {
    argv.add("--env");
    argv.add(key + "=" + (value == null ? "" : value));
  }
}
