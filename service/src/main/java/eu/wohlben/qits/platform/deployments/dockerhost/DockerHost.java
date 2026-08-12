package eu.wohlben.qits.platform.deployments.dockerhost;

import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.HealthGate;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import java.time.Duration;
import java.util.List;

/**
 * The docker CLI, as a seam — {@code run}, {@code stop}, {@code start}, {@code rm}, {@code ps},
 * {@code inspect}, {@code logs}, {@code pull} and {@code network} create/inspect/ls/connect/
 * disconnect/rm. {@code exec} is not in it and must not enter it: what a deployed container runs is
 * its image's own entrypoint, and this component's relationship with it ends at lifecycle.
 *
 * <p><b>This used to be the {@link DeploymentDriver} interface itself</b>, and the split is what
 * the second orchestrator cost. Two things were tangled in one type: the vocabulary a docker host
 * answers to, and the contract the deployment state machine needs. A swarm driver can honour the
 * second and has no use at all for the first — it neither stops a predecessor nor joins a network
 * after the fact — so the first moved down here, into the adapter package where docker's model is
 * allowed to be the model.
 *
 * <p><b>It is also what keeps the suite docker-free.</b> {@link DockerDeploymentDriver} is the
 * whole replace choreography and is worth testing; it is testable because this interface is where
 * the child processes are, and the suite installs a scripted fake of it. So the {@code @QuarkusTest}
 * flow tests drive the real cutover — the stop, the run, the joins, the reconcile, the rollback,
 * the referee — against a fake host, which is the same arrangement that was there before, one layer
 * further down.
 *
 * <p>The labels and the {@code Network} shape stay on {@link DeploymentDriver}: both orchestrators
 * label what they make with the same keys, and a network is a network.
 */
public interface DockerHost {

  /** See {@link DeploymentDriver#ensureNetwork}. Creates a local bridge when it is missing. */
  boolean ensureNetwork(DeploymentDriver.Network spec);

  /** Best-effort remove the named docker network (it may still hold containers; docker refuses). */
  void removeNetwork(String network);

  /** Every network this component labelled — the membership bookkeeping, read back from docker. */
  List<DeploymentDriver.Network> networks();

  /**
   * Join the container to the network under the alias.
   *
   * <p><b>Already joined counts as joined.</b> Docker answers an existing endpoint with a non-zero
   * exit and a message naming it, and telling that apart from a refusal is this seam's job — the
   * wording is docker's, so it is matched where docker's other wordings are matched. Everything
   * else is a real failure and is reported as one: a membership the caller asked for and did not
   * get leaves a container nobody can address, which no health gate can see.
   */
  ConnectResult connect(String network, String container, String alias);

  /** Whether the container is on the network now, and what docker said when it is not. */
  record ConnectResult(boolean joined, String detail) {}

  /** Leave the network. Not being on it is not an error. */
  void disconnect(String network, String container);

  /** The running containers of an environment's public nodes — one half of a reconciliation. */
  List<Endpoint> hubContainers(String environmentId);

  /**
   * Every running platform-plane container — the other half; platform services are everywhere by
   * design.
   */
  List<Endpoint> platformContainers();

  /**
   * A container a reconciliation joins to a new network, and the application it is. Joining without
   * an alias would put the container on the network under nothing but its own deployment-suffixed
   * container name — reachable by an address no peer has ever been told.
   *
   * <p>{@code applicationName} is the {@value DeploymentDriver#APP_NAME_LABEL} label, which is the
   * bare name; the wire alias is derived from it and the container's plane by the caller, which is
   * the half of the pair that knows the environment.
   */
  record Endpoint(String id, String applicationName) {}

  /** {@code docker pull} the reference so a missing image is its own recorded outcome. */
  DeploymentDriver.PullResult pull(String imageRef);

  /**
   * The containers currently answering to <b>any</b> of these aliases anywhere in the given
   * networks — the predecessors a replace cutover stops, whoever started them: a prior deployment,
   * an original this platform's bootstrap seeded outside any deployer, or one the retired qits-cd
   * started.
   *
   * <p>The networks are the <b>union</b> of everything the fresh container is about to be on,
   * legacy network included. That breadth is what finds a predecessor still living on the old
   * topology: a container started before per-application networks existed holds its alias on {@code
   * qits-net} and nowhere else, and a search of the new networks alone would start a second copy
   * beside it.
   *
   * <p><b>The aliases are a set for the same kind of reason.</b> An environment container's wire
   * alias carries its tier now ({@code prod-qits-gateway}), and every container started before that
   * holds the bare application name instead — so a search for the new spelling alone would run a
   * second copy beside the one that is serving. The caller sends both while that is true.
   *
   * <p>The breadth is also why each holder reports the environment it belongs to: the legacy
   * network is shared by every tier, so the union sees another environment's copy of the same
   * application under the bare alias. Deciding which of them is a predecessor is the caller's, and
   * {@link Holder#environmentId()} is what it decides on.
   */
  List<Holder> aliasHolders(List<String> networks, List<String> aliases);

  /**
   * One container holding an application's alias: the full docker id, the container name, and the
   * environment it was started for.
   *
   * <p>{@code environmentId} is the container's {@value DeploymentDriver#ENVIRONMENT_LABEL} label
   * and is <b>null for three very different containers</b>: a platform service, which belongs to no
   * tier by design; a compose original from before anything labelled containers; and one the
   * retired qits-cd started, whose {@code qits.cd.environment} label this component does not read.
   * All three are adoptable by whoever is deploying, which is why they share the null: a
   * predecessor nobody has claimed is claimed by the deployment that finds it.
   */
  record Holder(String id, String name, String environmentId) {}

  /** Stop the container, leaving it restartable — the first half of the replace cutover. */
  void stop(String containerName);

  /** Start a container {@link #stop} left behind — the rollback of a failed gate. */
  void restart(String containerName);

  /**
   * This process's own container id ({@code /etc/hostname} in a container), blank when unknown —
   * what routes a deployment of this component onto the handoff path: it must never stop the
   * instance performing the deployment.
   */
  String selfContainerId();

  /** The full docker id of the named container, blank when it does not exist. */
  String containerId(String containerName);

  /**
   * Launch the detached self-update referee: stop the old container (freeing the published port
   * and the socket the successor is retrying on), await the successor's health gate, then remove
   * the old container — or, on a missed gate, remove the successor and restart the old. Detached
   * because neither instance can referee its own succession: the old is about to be stopped and the
   * new cannot boot until it is.
   */
  void handoff(HandoffSpec spec);

  /** Everything the referee needs: who retires, who succeeds, and how long the gate may take. */
  record HandoffSpec(
      String imageRef, String oldContainerId, String newContainerName, long timeoutSeconds) {}

  /** Start the container, detached, on its primary network. The image's entrypoint runs. */
  StartResult start(StartSpec spec);

  record StartResult(boolean started, String detail) {}

  /**
   * Park until the container's own health gate answers: healthy, unhealthy/dead (with the
   * container's log tail as the diagnosis), or the deadline.
   */
  HealthGate.Result awaitHealthy(String containerName, Duration timeout);

  /** <b>One</b> observation of the named container — see {@link DeploymentDriver#observe}. */
  HealthGate.Poll observe(String containerName);

  /** Remove the container, running or not. Every decommission and every failed cutover ends here. */
  void remove(String containerName);

  /** Remove every container labelled as belonging to the environment. Returns how many there were. */
  int removeEnvironmentContainers(String environmentId);

  /**
   * Everything one container is started with.
   *
   * <p>{@code commitSha} is carried beside {@code imageRef} rather than parsed back out of it: it
   * is the deployment's own identity — the sha the row was created with and the image was addressed
   * by — and it becomes the container's {@code service.version} resource attribute.
   *
   * <p>{@code network} is the <b>primary</b> one, the only one {@code docker run} can take: the
   * application's own network for an environment application, {@code qits-platform} for a platform
   * service. Every further membership is a join after the start, because docker allows exactly one
   * network at run time.
   *
   * <p>{@code environmentId} and {@code environmentName} are null on a platform service, which is
   * what leaves it without an environment label — an environment teardown reaps by that label, and
   * it must never take a platform-plane container with it.
   *
   * <p>{@code healthCmd} is the repository's own readiness probe and, when present, <b>replaces</b>
   * the health gate rather than adding to it: {@code healthPath} is then unused, because an image
   * with no HTTP surface has no path to fetch. Null is every service that has one.
   *
   * <p>{@code resources} is what {@code ResourceProvisioning} made exist a moment ago, one entry
   * per resource the repository declared. Empty for every application that stores nothing, which is
   * most of them.
   */
  record StartSpec(
      String environmentId,
      String environmentName,
      String applicationId,
      String applicationName,
      String deploymentId,
      String commitSha,
      String network,
      String imageRef,
      String containerName,
      String healthPath,
      String healthCmd,
      PdDeploymentTarget target,
      boolean availableOnEnv,
      List<DeploymentDriver.ResourceBinding> resources) {

    /** A null list and an empty one are the same statement: this application declared none. */
    public StartSpec {
      resources = resources == null ? List.of() : List.copyOf(resources);
    }
  }
}
