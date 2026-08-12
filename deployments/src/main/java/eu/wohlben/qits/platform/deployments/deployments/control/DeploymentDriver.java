package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import java.time.Duration;
import java.util.List;

/**
 * The seam between this component's orchestration and whatever runs the containers — the {@code
 * CiStepRunner} arrangement: this module owns the interface and the state machine that calls it,
 * {@code service/} owns the implementations, and the suites install a scripted fake so a clone's
 * {@code mvn verify} needs no docker.
 *
 * <p><b>There are two implementations now, chosen by {@code
 * qits.platform.deployments.orchestrator} ({@code docker} — the default — or {@code swarm}).</b>
 * That is what reshaped this interface. It used to be docker's own vocabulary written out: {@code
 * start} one container, {@code stop} and {@code restart} the predecessors, {@code connect} each
 * network after the fact, {@code handoff} to a referee, and a caller in {@link DeployService} that
 * sequenced all of it. Every one of those verbs is a statement about how <i>docker</i> replaces a
 * container, and swarm replaces one by updating a service in place — so keeping them here would
 * have made one orchestrator's model look like the contract, and left the other implementing verbs
 * it has no use for.
 *
 * <p><b>So the seam is now two verbs and the rest is bookkeeping</b>: {@link #apply(ServiceSpec)}
 * makes the described service exist at the described image, and {@link #awaitConverged} says
 * whether it took. How that happens — stop-then-start with a hand-rolled rollback, or {@code
 * service update --update-order start-first --update-failure-action rollback} — is the
 * implementation's business, and the whole of the difference between the two lives there.
 *
 * <p>Everything crossing this seam is ids, names and references — never entities. The driver knows
 * nothing about environments or deployments; it applies a spec, watches it converge, and makes and
 * removes networks.
 *
 * <p><b>Docker is the membership bookkeeping, on both paths.</b> Which service sits on which
 * network is never stored in this component's database — it is read back from the labels below.
 * One record of the truth, and it is the runtime's, so a row cannot describe a topology the
 * runtime does not have.
 *
 * <p><b>The labels are {@code qits.platform.deployments.*}, and every earlier spelling is a
 * legacy.</b> {@code qits.cd.*} came from the retired ancestor and {@code qits.pd.*} from this
 * component before the namespace was written out in full; containers and networks carrying either
 * are treated exactly like unlabelled ones — adoptable predecessors, never protected — because that
 * is what makes each cutover a deployment rather than a flag day. Nothing here reads a legacy
 * label, and nothing should start to: the absence of a {@code qits.platform.deployments.*} label is
 * already the whole statement.
 */
public interface DeploymentDriver {

  /** The config key that picks the implementation: {@code docker} (default) or {@code swarm}. */
  String ORCHESTRATOR_KEY = "qits.platform.deployments.orchestrator";

  /**
   * The prefix of the per-application family that says what one application needs beyond its image
   * — mounts, published ports, extra groups, extra environment. {@link ServiceExtras} is the
   * grammar and the reader.
   *
   * <p><b>Deployment config is the ONLY source</b>, never the API and never the intake, which is
   * what keeps the trust domain the one that already holds the docker socket. It is spelled here,
   * on the seam, because both drivers read it and each renders it in its orchestrator's vocabulary
   * — and a second spelling of the key would let the two disagree about which application's
   * arguments they are.
   */
  String EXTRAS_PREFIX = "qits.platform.deployments.extras.";

  /** The environment a service belongs to. Absent on platform services: they belong to no tier. */
  String ENVIRONMENT_LABEL = "qits.platform.deployments.environment";

  String APPLICATION_LABEL = "qits.platform.deployments.application";
  String DEPLOYMENT_LABEL = "qits.platform.deployments.deployment";

  /** {@code environment} or {@code platform} — what a reconciliation looks a container up by. */
  String TARGET_LABEL = "qits.platform.deployments.target";

  /** {@code true} on an environment's public nodes — the other half of the reconciliation lookup. */
  String AVAILABLE_ON_ENV_LABEL = "qits.platform.deployments.available-on-env";

  /** On networks: {@code bundle}, {@code application} or {@code platform}. */
  String NETWORK_LABEL = "qits.platform.deployments.network";

  /** On containers and on per-application networks: whose it is. */
  String APP_NAME_LABEL = "qits.platform.deployments.app-name";

  /** What a network this component made is for. */
  enum NetworkKind {
    /** An environment's public nodes ({@code availableOnEnv}). */
    BUNDLE,
    /** One application of one environment — its own containers and its joined hub. */
    APPLICATION,
    /** Where platform services run. Belongs to no environment. */
    PLATFORM
  }

  /** A network this component made, as its labels describe it. {@code environmentId} is null on PLATFORM. */
  record Network(String name, String environmentId, NetworkKind kind, String applicationName) {}

  /**
   * Best-effort ensure the network exists, labelled — warn, never fail, when the runtime is absent.
   *
   * <p>Returns whether this call <b>created</b> it. The reconciliation deliberately does not hang
   * off that answer — a network outlives the deployment that made it, so who belongs on it is
   * recomputed every time rather than joined once. An already-existing network keeps whatever
   * labels it has: adopting an unlabelled network made outside this component (the platform's own
   * {@code qits-net}, or one a retired qits-cd labelled) stays supported, deliberately.
   *
   * <p>The driver decides the network's <b>driver</b>: a local bridge on the docker path, an
   * attachable overlay under swarm, where a bridge cannot carry a service at all.
   */
  boolean ensureNetwork(Network spec);

  /** Best-effort remove the named network (it may still hold endpoints; the runtime refuses). */
  void removeNetwork(String network);

  /** Every network this component labelled — the membership bookkeeping, read back from the runtime. */
  List<Network> networks();

  /**
   * Release whatever the <b>platform plane</b> holds on these networks, so a teardown can remove
   * them.
   *
   * <p>It is one call rather than the {@code platformContainers} + {@code disconnect} pair it
   * replaces, because the pair was docker's answer and not the question. The question is "these
   * networks are about to go; the platform plane is on them and does not belong to the tier that
   * owns them". On the docker path that is a {@code network disconnect} per platform container; a
   * swarm service declares its networks when it is created and a teardown does not reshape one, so
   * there the honest answer is to do nothing and let the removal's own retry loop wait for the
   * tasks to go.
   */
  void detachPlatformPlane(List<String> networks);

  /** Remove everything labelled as belonging to the environment. Returns how many there were. */
  int removeEnvironmentContainers(String environmentId);

  /**
   * Pull the reference so a missing image is its own recorded outcome.
   *
   * <p><b>It survives the move to swarm even though swarm pulls on its own</b>: the pull is not how
   * the image gets to the host, it is how "nothing published this application yet" is told apart
   * from "the deployment failed". A service create would report the same condition as a task that
   * never starts, minutes later, with the registry's words buried in a task error.
   */
  PullResult pull(String imageRef);

  /**
   * What this orchestrator calls the thing {@code spec} describes — and therefore what the
   * deployment row records, and what {@link #awaitConverged}, {@link #observe} and {@link #reap}
   * are asked about afterwards.
   *
   * <p><b>The two answers differ, and the difference is the migration in one line.</b> Docker names
   * a fresh container per deployment ({@code qits-pd-<env>-<app>-<id8>}) because a replace is two
   * containers that must not collide, and the address peers dial is a {@code --network-alias} on
   * top. A swarm service's name <b>is</b> the address — {@code container_name} does not exist there
   * — so the name is the wire alias and a replace is an update of that one service.
   *
   * <p>It is asked <b>before</b> {@link #apply}, because the row has to name the thing before
   * anything starts: a crash between the two leaves a {@code STARTING} row the startup sweep can
   * still identify.
   */
  String nameOf(ServiceSpec spec);

  /**
   * Make the described service exist, at the described image, on the described networks — creating
   * it or updating it in place, and idempotent either way.
   *
   * <p><b>This is where each orchestrator's replace lives.</b> The docker implementation runs the
   * whole hand-rolled cutover behind this one call (find whoever holds the alias, stop it, run the
   * fresh container, join it to every other network, reconcile the hubs); the swarm one is a
   * single {@code service create}-or-{@code update} carrying the full network list, because
   * declaring membership at create time is the only way to have it without a task restart.
   *
   * <p>Returning is not success: {@link #awaitConverged} is where the outcome is. What returning
   * <i>does</i> settle is whether this deployment is still this process's to finish — see {@link
   * ApplyOutcome#HANDED_OFF}.
   */
  ApplyResult apply(ServiceSpec spec);

  /**
   * Park until the applied service is serving, is back on its predecessor, or the deadline passes.
   *
   * <p>The verdict is the orchestrator's own: docker's health gate polled by this component
   * ({@link HealthGate}, with the rollback of a failed gate performed by the driver that stopped
   * the predecessor), or swarm's {@code UpdateStatus} — {@code completed}, {@code
   * rollback_completed}, {@code paused} — where the rollback already happened without anybody
   * asking.
   *
   * <p>A failed convergence leaves the world as it was: whatever was serving before is serving
   * again by the time this returns. The caller's remaining job is the row.
   */
  Convergence awaitConverged(String name, Duration timeout);

  /**
   * <b>One</b> observation of the named service — docker's {@code <status>/<health>} string, or the
   * statement that the runtime has no such thing. The same reading {@link #awaitConverged} polls
   * through on the docker path, and deliberately the same type: {@link DeploymentObserver} settles
   * a row on {@link HealthGate#healthy}, so "healthy" means to an observation exactly what it means
   * to a gate, and "gone" is a structural fact rather than a wording match.
   *
   * <p>It exists because a deployment's status used to be written once and never read back against
   * the world. Asking by the <b>name the row itself carries</b> is the point: only the service a
   * row named may settle that row, and a healthy service belonging to somebody else must not
   * resurrect it.
   */
  HealthGate.Poll observe(String name);

  /**
   * Remove what settled deployments left behind — the containers of the rows a cutover just
   * decommissioned, plus whatever {@link Convergence#retired()} named.
   *
   * <p>Called <b>after</b> the rows say so, never before, which is why it is a call of its own
   * rather than the tail of {@link #awaitConverged}: a bookkeeping bracket that has to retry for
   * thirty seconds must not have removed the predecessor first.
   *
   * <p>Under swarm this has nothing to do, and the reason is worth stating rather than
   * discovering: a replace is in place, so the predecessor and the successor are one service and
   * removing "the old one" would remove the deployment that just went live.
   */
  void reap(List<String> names);

  /**
   * Whether the named service is <b>this very process</b> — what tells a self-update handoff that
   * succeeded apart from a deployment a restart interrupted.
   *
   * <p>The startup sweep is its only caller. It replaces the {@code selfContainerId} / {@code
   * containerId} pair, which was two docker questions asked to answer one: whose container am I,
   * and is that the container this row names.
   */
  boolean isSelf(String name);

  /**
   * Everything one deployed service is described by. Plain values, resolved before anything
   * runtime-side happens.
   *
   * <p>{@code commitSha} is carried beside {@code imageRef} rather than parsed back out of it: it
   * is the deployment's own identity — the sha the row was created with and the image was addressed
   * by — and it becomes the service's {@code service.version} resource attribute.
   *
   * <p>{@code networks} is the <b>full membership</b>, primary first, and declaring it whole is
   * what lets an orchestrator that cannot join afterwards do the job at all: every swarm {@code
   * --network-add} recreates the task, so a hub-and-spoke model built out of joins would turn one
   * deployment into a restart storm. The docker path still starts on {@code networks.get(0)} and
   * joins the rest, because {@code docker run} takes exactly one.
   *
   * <p>{@code deploymentName} and {@code wireAlias} are both here because they are two different
   * facts and only one orchestrator uses each as the name — see {@link #nameOf}. The alias is the
   * address peers dial and is derived in one place ({@code PdNetworks}) so the run, the joins and
   * the predecessor search cannot disagree.
   *
   * <p>{@code environmentId} and {@code environmentName} are null on a platform service, which is
   * what leaves it without an environment label — an environment teardown reaps by that label, and
   * it must never take a platform-plane service with it.
   *
   * <p>{@code healthCmd} is the repository's own readiness probe and, when present, <b>replaces</b>
   * the health path rather than adding to it: an image with no HTTP surface has no path to fetch.
   * Null is every service that has one.
   *
   * <p>{@code updateOrder} is the repository's, and it is the one field the docker path reads and
   * ignores: docker's cutover is stop-first by construction. See {@link UpdateOrder}.
   *
   * <p>{@code resources} is what {@code ResourceProvisioning} made exist a moment ago, one entry
   * per resource the repository declared. Empty for every application that stores nothing, which is
   * most of them.
   *
   * <p><b>What is deliberately NOT here: mounts, ports and extra env.</b> Those are {@link
   * ServiceExtras}, read by the driver from deployment config, which is the trust domain that
   * already holds the socket. Routing them through this record would put a value that reaches an
   * argv on a path that starts at an HTTP intake.
   */
  record ServiceSpec(
      String environmentId,
      String environmentName,
      String applicationId,
      String applicationName,
      String deploymentId,
      String commitSha,
      String deploymentName,
      String wireAlias,
      List<String> networks,
      String imageRef,
      String healthPath,
      String healthCmd,
      PdDeploymentTarget target,
      boolean availableOnEnv,
      UpdateOrder updateOrder,
      List<ResourceBinding> resources) {

    /** Null lists and empty ones are the same statement: this application declared none. */
    public ServiceSpec {
      networks = networks == null ? List.of() : List.copyOf(networks);
      resources = resources == null ? List.of() : List.copyOf(resources);
      updateOrder = updateOrder == null ? UpdateOrder.START_FIRST : updateOrder;
    }

    /** The one network {@code docker run} can take, and the first one a service declares. */
    public String primaryNetwork() {
      return networks.isEmpty() ? null : networks.get(0);
    }

    /** Whether this is the platform plane — one instance, no tier, the bare wire alias. */
    public boolean platform() {
      return target == PdDeploymentTarget.PLATFORM;
    }
  }

  /**
   * How a replacement overlaps its predecessor — {@code update_order} in the repository's spec.
   *
   * <p><b>{@code start-first} is what makes a rollback lossless</b>: the predecessor keeps serving
   * while the successor is starting, and an orchestrator that fails the successor reverts to a
   * container that never stopped. Measured on this platform's daemon: under {@code start-first} an
   * unhealthy successor sat in {@code Starting} for 33 seconds while the old task stayed {@code
   * Running}, and the update ended {@code rollback_completed} with the spec reverted.
   *
   * <p><b>{@code stop-first} is for anything that cannot be two processes at once</b> — one binder
   * per published host port, one writer per store, one holder of a config volume. It still rolls
   * back; it just has a gap in service, which is what those applications have today anyway. This
   * component's own deployment is one of them.
   */
  enum UpdateOrder {
    START_FIRST,
    STOP_FIRST;

    /** The spelling in {@code .config/qits/deployments.yml}: {@code start-first}/{@code stop-first}. */
    public String spelling() {
      return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
  }

  /**
   * One provisioned resource, as the service is told about it: {@code
   * QITS_RESOURCE_<NAME>_URL/_USERNAME/_PASSWORD}, with {@code name} uppercased and its dashes
   * underscored.
   *
   * <p><b>The contract is generic on purpose.</b> An application maps these three variables in its
   * own shipped configuration defaults — this component names no framework and no datasource key,
   * so a Quarkus service, a plain image and whatever comes next are all deployed by the same code.
   *
   * <p>The password here is a value this component generated and holds in its own registry. Nothing
   * arriving over HTTP contributes it, and nothing writes it to a log.
   */
  record ResourceBinding(String name, String url, String username, String password) {}

  enum PullOutcome {
    OK,
    /** The registry answered and has no such image — the deployment's {@code IMAGE_MISSING}. */
    IMAGE_MISSING,
    /** The runtime failed some other way (daemon absent, registry unreachable, ...). */
    ERROR
  }

  record PullResult(PullOutcome outcome, String detail) {}

  /** What {@link #apply} did, and therefore what the caller does next. */
  enum ApplyOutcome {
    /** The spec is in the runtime's hands; ask {@link #awaitConverged} what became of it. */
    APPLIED,
    /**
     * This deployment replaces <b>this very process</b>, and the outcome will be recorded by
     * whichever instance is alive to record it — the row stays {@code STARTING} on purpose.
     *
     * <p>Neither instance can arbitrate its own succession: the old is about to stop and the new
     * cannot boot until it has. Docker answers that with a detached referee container; swarm
     * answers it with the manager, which lives in the daemon rather than in a container this
     * process owns. Either way the caller returns without a verdict, and the next boot's sweep
     * settles the row.
     */
    HANDED_OFF,
    /** Nothing runs that did not run before, and {@code detail} says why. */
    REFUSED
  }

  record ApplyResult(ApplyOutcome outcome, String detail) {}

  /** How an applied deployment ended. */
  enum ConvergenceOutcome {
    /** It is serving. */
    CONVERGED,
    /** The predecessor is serving again — the orchestrator put it back. */
    ROLLED_BACK,
    /** Neither converged nor cleanly reverted; {@code detail} is the diagnosis. */
    FAILED
  }

  /**
   * The verdict, and what it left for the caller to clean up.
   *
   * <p>{@code retired} is what the driver stopped and is done with — the alias holders a docker
   * cutover replaced. It comes back as data rather than being removed inside the driver so the
   * order the component has always had survives: <b>rows first, containers after</b>. Empty on a
   * failure (nothing was retired — it is serving again) and empty under swarm (a replace is in
   * place).
   */
  record Convergence(ConvergenceOutcome outcome, String detail, List<String> retired) {

    public Convergence {
      retired = retired == null ? List.of() : List.copyOf(retired);
    }

    public static Convergence converged(List<String> retired) {
      return new Convergence(ConvergenceOutcome.CONVERGED, null, retired);
    }

    public static Convergence failed(String detail) {
      return new Convergence(ConvergenceOutcome.FAILED, detail, List.of());
    }

    public static Convergence rolledBack(String detail) {
      return new Convergence(ConvergenceOutcome.ROLLED_BACK, detail, List.of());
    }

    public boolean converged() {
      return outcome == ConvergenceOutcome.CONVERGED;
    }
  }
}
