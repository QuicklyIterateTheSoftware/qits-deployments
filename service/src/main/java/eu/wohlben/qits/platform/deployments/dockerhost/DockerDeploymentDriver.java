package eu.wohlben.qits.platform.deployments.dockerhost;

import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.HealthGate;
import eu.wohlben.qits.platform.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.platform.deployments.orchestration.Orchestrated;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The docker implementation of {@link DeploymentDriver}: the hand-rolled replace cutover, whole,
 * behind {@link #apply} and {@link #awaitConverged}.
 *
 * <p><b>Every line of it used to live in {@code DeployService}</b>, and moving it here is the
 * change that made a second orchestrator possible. The reason is one sentence: the predecessor
 * search, the alias union, the stop-before-start, the join loop, the reconciliation and the
 * rollback are not "how a deployment works" — they are how <i>docker</i> is made to behave like an
 * orchestrator. Swarm has all six built in, so a state machine written around them could only be
 * told to skip them. Now the state machine says {@code apply} and asks whether it converged, and
 * both drivers answer in their own vocabulary.
 *
 * <p><b>A self-update is REFUSED here, and that is the honest answer rather than a gap.</b> This
 * path used to launch a detached referee container to arbitrate its own succession — start the
 * successor, stop this instance, await the gate, remove whichever side lost. The referee is gone:
 * swarm supplies one that lives in the daemon, and keeping a second, hand-rolled arbiter alive for
 * a path that is about to be deleted would be maintaining the hardest code in this component for
 * one release. So a deployment that would replace the running instance changes nothing and says
 * what to do about it, and this component updates itself under swarm.
 *
 * <p><b>What that costs is one piece of state, and it is worth naming.</b> The cutover spans two
 * calls — the predecessors are stopped in {@link #apply} and are either removed or restarted in
 * {@link #awaitConverged} — and a stopped container holds no alias any more, so the second call
 * cannot find them again by asking docker. So it remembers, keyed by the container name it was
 * asked about. The worker is single-threaded and one deployment is in flight at a time; the map is
 * keyed anyway, so a lost verdict leaks one entry rather than confusing the next deployment, and a
 * repeat of the same name overwrites it.
 *
 * <p><b>The cutover invariant, unchanged by the move:</b> the previous container is only
 * <i>stopped</i> during the gate and is removed only after the new one passed it; a failed
 * deployment — docker refused, a join was refused, the health gate expired — removes the fresh
 * container and restarts what was stopped, so the previous deployment stays {@code ACTIVE} and
 * serving. Stop-before-start is what makes stateful applications deployable at all here: one binder
 * per published host port, one process per single-writer store. That is also why {@code
 * update_order} is read and ignored on this path — docker's cutover is stop-first by construction,
 * and {@code start-first} is a promise only swarm can keep.
 *
 * <p>The predecessor is whatever holds the application's alias on any of the networks the fresh
 * container is about to be on — including containers this component did not start (a bootstrap's
 * seeded originals, or the retired qits-cd's) and containers still living on the legacy network
 * alone, which is how the platform migrates onto per-application networks without ever running two
 * copies.
 */
@ApplicationScoped
@Orchestrated(Orchestrated.Kind.DOCKER)
public class DockerDeploymentDriver implements DeploymentDriver {

  private static final Logger LOG = Logger.getLogger(DockerDeploymentDriver.class);

  @Inject DockerHost docker;

  /** What each in-flight cutover stopped, by the container name the successor was given. */
  private final Map<String, List<String>> stopped = new ConcurrentHashMap<>();

  /** Docker names one container per deployment: a replace is two containers that must not collide. */
  @Override
  public String nameOf(ServiceSpec spec) {
    return spec.deploymentName();
  }

  @Override
  public ApplyResult apply(ServiceSpec spec) {
    String container = spec.deploymentName();
    String primary = spec.primaryNetwork();
    List<String> joins = spec.networks().subList(1, spec.networks().size());

    // The replace cutover: whatever currently answers to the application's alias is STOPPED — not
    // removed — before the fresh container starts. Keeping the stopped containers around is what
    // preserves the rollback: a failed gate restarts them. The search covers every network the
    // fresh container will be on, so it also absorbs predecessors this component did not start and
    // predecessors still living on the legacy network alone — holding the alias is what makes
    // something the predecessor, not a row.
    List<DockerHost.Holder> predecessors =
        predecessorsOf(docker.aliasHolders(spec.networks(), searchAliases(spec)), spec);
    DockerHost.Holder self = selfAmong(predecessors);

    if (self != null) {
      // A self-update, and this path cannot do one: the process that would stop the predecessor IS
      // the predecessor, and there is nothing left afterwards to await the successor's gate or put
      // the loser back. It used to launch a detached referee container for exactly that; the
      // referee is gone, because swarm supplies one that lives in the daemon.
      //
      // Refused BEFORE anything is stopped or started, so the running instance keeps serving and
      // the row records why.
      LOG.warnf(
          "Refusing to deploy %s: it would replace this very instance (%s)",
          spec.applicationName(), self.name());
      return new ApplyResult(
          ApplyOutcome.REFUSED,
          "this deployment replaces the instance performing it, and the docker path has no third"
              + " party to finish that. Deploy it under swarm ("
              + ORCHESTRATOR_KEY
              + "=swarm), where the manager arbitrates the succession. "
              + self.name()
              + " keeps serving.");
    }

    DockerHost.StartSpec start = startSpec(spec, primary);
    for (DockerHost.Holder predecessor : predecessors) {
      docker.stop(predecessor.name());
    }
    DockerHost.StartResult started = docker.start(start);
    if (!started.started()) {
      docker.remove(container); // in case docker created it and then failed
      rollback(predecessors);
      return new ApplyResult(ApplyOutcome.REFUSED, started.detail());
    }
    // Docker takes one network at `run`; everything else is a join, and the set is recomputed from
    // docker on every deployment rather than remembered — which makes this the self-heal too: a
    // membership lost to a manual `network disconnect` or to a network that did not exist last
    // time is simply back on the replacement.
    //
    // A membership the deployment asked for and did not get is a FAILED deployment, not a warning.
    // The health gate cannot catch it — it curls localhost inside the container, which answers
    // perfectly well from a network nobody else is on — so an unreachable container would go ACTIVE
    // and the predecessor would be removed under it. This is the same rollback a failed gate takes.
    String unjoined = join(container, spec.wireAlias(), joins);
    if (unjoined != null) {
      docker.remove(container);
      rollback(predecessors);
      return new ApplyResult(ApplyOutcome.REFUSED, unjoined);
    }
    reconcile(spec, primary);
    stopped.put(container, predecessors.stream().map(DockerHost.Holder::name).toList());
    return new ApplyResult(ApplyOutcome.APPLIED, null);
  }

  /**
   * The health gate, plus the half of the cutover that depends on its verdict.
   *
   * <p>The gate itself is {@link HealthGate}'s, polled through {@link DockerHost#awaitHealthy}: a
   * container that is restarting or not yet healthy is PENDING until the deadline, and only the
   * deadline or a container docker cannot find at all ends it. What is here is the consequence — a
   * failed gate removes the fresh container and restarts what the cutover stopped, so the previous
   * deployment goes back to serving before this returns.
   *
   * <p>The predecessors it {@link Convergence#retired() retired} are handed back rather than
   * removed here, because the caller removes them only once the rows say the successor is live.
   */
  @Override
  public Convergence awaitConverged(String name, Duration timeout) {
    List<String> predecessors = stopped.remove(name);
    HealthGate.Result health = docker.awaitHealthy(name, timeout);
    if (!health.healthy()) {
      docker.remove(name);
      for (String predecessor : predecessors == null ? List.<String>of() : predecessors) {
        docker.restart(predecessor);
      }
      return Convergence.failed(health.detail());
    }
    return Convergence.converged(predecessors == null ? List.of() : predecessors);
  }

  @Override
  public void reap(List<String> names) {
    for (String name : names) {
      docker.remove(name);
    }
  }

  /** What the container is running, straight off {@code .Config.Image} — see the seam. */
  @Override
  public Optional<RunningImage> runningImage(String name) {
    String image = docker.runningImage(name);
    // No UpdateStatus to quote: docker has no orchestrator keeping one, so the image is the whole
    // of the evidence and the sweep says "superseded" in its own words.
    return image.isBlank() ? Optional.empty() : Optional.of(new RunningImage(image, null));
  }

  @Override
  public boolean ensureNetwork(Network spec) {
    return docker.ensureNetwork(spec);
  }

  @Override
  public void removeNetwork(String network) {
    docker.removeNetwork(network);
  }

  @Override
  public List<Network> networks() {
    return docker.networks();
  }

  @Override
  public void detachPlatformPlane(List<String> networks) {
    for (DockerHost.Endpoint platform : docker.platformContainers()) {
      for (String network : networks) {
        docker.disconnect(network, platform.id());
      }
    }
  }

  @Override
  public int removeEnvironmentContainers(String environmentId) {
    return docker.removeEnvironmentContainers(environmentId);
  }

  @Override
  public PullResult pull(String imageRef) {
    return docker.pull(imageRef);
  }

  @Override
  public HealthGate.Poll observe(String name) {
    return docker.observe(name);
  }

  /**
   * What the predecessor search asks about — the wire alias, plus the bare application name while
   * anything started before the tier qualifier existed is still running.
   *
   * <p>Without the second the first deployment of every application would run a second copy beside
   * the one serving: those containers hold the bare name and nothing else, and the cutover finds a
   * predecessor by the alias alone. It costs nothing to keep asking — a holder of the bare name
   * that belongs to another tier is filtered out by its environment label like any other, and an
   * unlabelled one is adoptable, which is the whole of how this platform migrates.
   */
  static List<String> searchAliases(ServiceSpec spec) {
    return spec.wireAlias().equals(spec.applicationName())
        ? List.of(spec.wireAlias())
        : List.of(spec.wireAlias(), spec.applicationName());
  }

  /**
   * Which of the containers answering to this alias this deployment may replace.
   *
   * <p>The alias search is a union that includes the legacy network, and the legacy network is
   * shared by every tier — so it also returns another environment's copy of the same application,
   * holding the same alias, perfectly healthy. Stopping that one would be a deployment of one tier
   * silently taking a container out of another, which is what the environment label prevents:
   *
   * <ul>
   *   <li>a holder labelled with <b>this</b> environment is this deployment's own predecessor;
   *   <li>a holder labelled with <b>another</b> environment belongs to that tier and is left alone;
   *   <li>a holder with <b>no</b> label is unclaimed — a compose original, a container the retired
   *       qits-cd started, or a platform service — and stays adoptable, because that is the whole
   *       of how this platform migrates onto per-application networks.
   * </ul>
   *
   * <p>A platform deployment keeps only the unlabelled ones, which by the same rule means platform
   * containers and unclaimed originals: a container that carries an environment id belongs to a
   * tier, and no tier's container is the platform plane's predecessor.
   */
  static List<DockerHost.Holder> predecessorsOf(
      List<DockerHost.Holder> holders, ServiceSpec spec) {
    List<DockerHost.Holder> mine = new ArrayList<>();
    for (DockerHost.Holder holder : holders) {
      if (holder.environmentId() == null || holder.environmentId().equals(spec.environmentId())) {
        mine.add(holder);
      } else {
        LOG.debugf(
            "%s holds the alias %s for environment %s — not this deployment's predecessor",
            holder.name(), spec.applicationName(), holder.environmentId());
      }
    }
    return List.copyOf(mine);
  }

  /**
   * The one predecessor this process must never stop: itself. Finding it is what makes a
   * self-update refusable rather than a deployment that kills the deployer half way through.
   */
  private DockerHost.Holder selfAmong(List<DockerHost.Holder> predecessors) {
    String self = docker.selfContainerId();
    if (self.isBlank()) {
      return null;
    }
    return predecessors.stream()
        .filter(p -> p.id().startsWith(self) || self.startsWith(p.id()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Put the fresh container on every network it needs beyond its primary one.
   *
   * @return null when it is on all of them, or the failure to record on the deployment row — these
   *     joins are what makes the container addressable, so a refused one is not a warning
   */
  private String join(String container, String alias, List<String> networks) {
    for (String network : networks) {
      DockerHost.ConnectResult joined = docker.connect(network, container, alias);
      if (!joined.joined()) {
        return "could not join "
            + container
            + " to '"
            + network
            + "'\n"
            + (joined.detail() == null ? "" : joined.detail());
      }
    }
    return null;
  }

  /**
   * Put the environment's public nodes and every platform container on this application's network,
   * both found by their container labels — docker is the membership bookkeeping, so this asks the
   * runtime rather than a table.
   *
   * <p>It runs on <b>every</b> deployment, not only on the one that made the network, for the same
   * reason the container's own joins are recomputed: the network outlives the deployment that
   * created it. A deployment that made the network and then failed to start leaves it behind with
   * nobody on it, and the application would stay unreachable from the gateway and from every
   * platform service until some hub happened to redeploy. Joining is idempotent — docker refuses an
   * already-joined container and changes nothing — so recomputing it is the self-heal.
   *
   * <p>Each of them is joined under <b>its own</b> wire alias, not this application's. A hub is one
   * of this environment's containers, so it takes this environment's qualifier; a platform service
   * is on no tier and keeps its bare name. The label carries the application name alone, which is
   * why the qualifier is put back here rather than read.
   */
  private void reconcile(ServiceSpec spec, String primaryNetwork) {
    if (spec.platform()) {
      return;
    }
    for (DockerHost.Endpoint hub : docker.hubContainers(spec.environmentId())) {
      docker.connect(
          primaryNetwork, hub.id(), PdNetworks.alias(spec.environmentName(), hub.applicationName()));
    }
    for (DockerHost.Endpoint platform : docker.platformContainers()) {
      docker.connect(primaryNetwork, platform.id(), PdNetworks.alias(null, platform.applicationName()));
    }
  }

  /** A failed cutover restarts every container it stopped — the previous deployment serves again. */
  private void rollback(List<DockerHost.Holder> predecessors) {
    for (DockerHost.Holder predecessor : predecessors) {
      docker.restart(predecessor.name());
    }
  }

  private static DockerHost.StartSpec startSpec(ServiceSpec spec, String primaryNetwork) {
    return new DockerHost.StartSpec(
        spec.environmentId(),
        spec.environmentName(),
        spec.applicationId(),
        spec.applicationName(),
        spec.deploymentId(),
        spec.commitSha(),
        primaryNetwork,
        spec.imageRef(),
        spec.deploymentName(),
        spec.healthPath(),
        spec.healthCmd(),
        spec.target(),
        spec.availableOnEnv(),
        spec.resources());
  }
}
