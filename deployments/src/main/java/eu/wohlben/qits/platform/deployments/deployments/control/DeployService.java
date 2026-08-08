package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.platform.deployments.environments.control.ApplicationKeys;
import eu.wohlben.qits.platform.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.platform.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.platform.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.platform.deployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.platform.deployments.environments.control.ServiceCatalog.LinkedService;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import eu.wohlben.qits.platform.deployments.environments.entity.PdEnvironment;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The deployment orchestrator: a build-succeeded event → the repository's deployment spec at that
 * commit → the environments that spec addresses → one recorded deployment each, driven pull → run →
 * join → health gate → cutover on a single-threaded daemon worker (the intake returns immediately;
 * deployments across all environments are serialized — parallelism is an explicit follow-up, and
 * serial is what makes "the previous ACTIVE deployment" an uncontended read).
 *
 * <p><b>Registration is derived, and it is a local write.</b> Nothing declares an application over
 * the API. A green build carries this component to {@code .config/qits/deployments.yml} in the
 * repository at that sha, and the service row is created or brought up to date from it: an {@code
 * environment} target is linked into every environment whose branch matches, a {@code platform}
 * target keeps no links at all and deploys once for the whole platform. Both planes answer the same
 * branch question — does an environment listen to this ref — so {@code environment/<name>} is the
 * only deploy ref the platform has. A repository with no such file gets the defaults and behaves
 * exactly as it did before the file existed.
 *
 * <p><b>This is what the merge bought.</b> Registration and resolution used to be HTTP calls onto
 * qits-serviceregistry: a port, a {@code java.net.http} implementation, a stub server in the suite,
 * a bearer to mint, and a whole recorded-FAILED posture for the case where the peer was down —
 * because a deployer that cannot ask where a build belongs must never guess a topology. All of it
 * is gone. Registration writes rows in the same transaction as everything else here, resolution is
 * a repository query with an index behind it, and there is no outage to have a posture about. The
 * one remote call left is the spec read, and its posture stays exactly as it was.
 *
 * <p>Each DB transition sits in its own {@link QuarkusTransaction#requiringNew()} bracket so the
 * slow docker work never holds a transaction, and everything the docker calls need is copied into a
 * plain {@link Plan} first — the worker thread has no request context and no open session.
 *
 * <p><b>The cutover invariant:</b> the previous container is only <i>stopped</i> during the gate and
 * is removed only after the new one passed it; a failed deployment — image missing, docker refused,
 * health gate expired — removes the fresh container and restarts what was stopped, so the previous
 * deployment stays {@code ACTIVE} and serving. Stop-before-start (rather than an overlapping
 * cutover) is what makes stateful applications deployable at all: one process per H2 file, one
 * binder per published host port. The pull still happens before the stop, so replacing the OCI
 * registry's own application does not depend on it being up mid-cutover. The predecessor is
 * whatever holds the application's alias on any of the networks the fresh container is about to be
 * on — including containers this component did not start (a bootstrap's seeded originals, or the
 * retired qits-cd's) and containers still living on the legacy network alone, which is how the
 * platform migrates onto per-application networks without ever running two copies. The one
 * predecessor never stopped in-process is this process's own container: deploying this component
 * takes the handoff path — start the successor, launch the detached referee that stops this
 * instance and arbitrates the gate, and let the surviving instance record the outcome (the
 * successor's sweep adopts the row; a rolled-back predecessor's sweep fails it).
 */
@ApplicationScoped
public class DeployService implements BuildAnnouncements {

  private static final Logger LOG = Logger.getLogger(DeployService.class);

  /** Every platform repository carries it, and no path segment does. */
  private static final String NAME_PREFIX = "qits-";

  @Inject PdDeploymentRepository deployments;
  @Inject DeploymentDriver driver;
  @Inject SpecSource specs;
  @Inject ServiceCatalog catalog;
  @Inject EnvironmentService environments;

  @ConfigProperty(name = "qits.artifacts.registry-host")
  String registryHost;

  @ConfigProperty(name = "qits.artifacts.image-repository")
  String imageRepository;

  /**
   * The last resort at deploy time, for a service row that carries no path. Registration writes
   * {@link #conventionHealthPath} now, so this only reaches rows nothing has registered since.
   */
  @ConfigProperty(name = "qits.platform.deployments.default-health-path")
  String defaultHealthPath;

  @ConfigProperty(name = "qits.platform.deployments.health-timeout-seconds")
  long healthTimeoutSeconds;

  /**
   * The network every fresh container additionally joins while the platform still holds direct
   * cross-application URLs. Emptying it is the enforcement flip: from then on an application can
   * only be reached through the gateway route or a hub join, and a URL nobody migrated fails loudly
   * instead of resolving on a flat network.
   *
   * <p>{@code Optional} because SmallRye reads an empty value as ABSENT, not as an empty string —
   * so the flip's own spelling ({@code QITS_PLATFORM_DEPLOYMENTS_LEGACY_NETWORK=}) would fail this
   * bean's injection if the field were a plain String.
   */
  @ConfigProperty(name = "qits.platform.deployments.legacy-network")
  Optional<String> legacyNetwork;

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "pd-deploy-worker");
            t.setDaemon(true);
            return t;
          });

  @PreDestroy
  void shutdown() {
    worker.shutdownNow();
  }

  /**
   * A deployment left {@code QUEUED} or {@code STARTING} by a crash can never make progress — the
   * worker queue does not survive the JVM — so it would show as forever-deploying. Fail those once
   * at startup, with one exception: a {@code STARTING} row whose container is <b>this very
   * process</b> is a self-update handoff that succeeded — the predecessor recorded the row, the
   * referee retired it, and this instance is the successor booting for the first time. That row is
   * ADOPTED (ACTIVE, prior ACTIVE rows decommissioned): the instance that survived the handoff
   * records its outcome. The containers are deliberately NOT reaped: a deployed application
   * outlives its deployer, and whatever was {@code ACTIVE} before the restart is still serving.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    try {
      sweepInFlight();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not sweep interrupted deployments at startup");
    }
  }

  /** Package-private so the suite drives the sweep without a real StartupEvent. */
  void sweepInFlight() {
    String self = driver.selfContainerId();
    int swept =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<PdDeployment> orphans =
                      new ArrayList<>(deployments.listByStatus(PdDeploymentStatus.QUEUED));
                  orphans.addAll(deployments.listByStatus(PdDeploymentStatus.STARTING));
                  int failed = 0;
                  for (PdDeployment orphan : orphans) {
                    if (orphan.status == PdDeploymentStatus.STARTING
                        && orphan.containerName != null
                        && !self.isBlank()) {
                      String id = driver.containerId(orphan.containerName);
                      if (!id.isBlank() && (id.startsWith(self) || self.startsWith(id))) {
                        // The prior actives of the same (application, tier). The pair is matched
                        // with an explicit null test for the platform plane — `environment_id = ?`
                        // would silently match nothing, and this instance would come back having
                        // failed its own deployment.
                        for (PdDeployment previous :
                            deployments.listActiveByApplication(
                                orphan.applicationName, orphan.environmentId)) {
                          previous.status = PdDeploymentStatus.DECOMMISSIONED;
                          previous.finishedAt = Instant.now();
                        }
                        orphan.status = PdDeploymentStatus.ACTIVE;
                        orphan.detail =
                            "[adopted at startup: this instance is the successor of a self-update"
                                + " handoff]";
                        orphan.finishedAt = Instant.now();
                        LOG.infof(
                            "Adopted deployment %s: this instance (%s) is its container",
                            orphan.id, orphan.containerName);
                        continue;
                      }
                    }
                    orphan.status = PdDeploymentStatus.FAILED;
                    orphan.detail = "[interrupted by a qits-platform-deployments restart]";
                    orphan.finishedAt = Instant.now();
                    failed++;
                  }
                  return failed;
                });
    if (swept > 0) {
      LOG.infof("Marked %d deployment(s) left in flight by a previous shutdown as FAILED", swept);
    }
  }

  /**
   * The async entry every announcement door calls ({@link BuildAnnouncements}). It validates, hands
   * the event to the worker and returns — the sender is fire-and-forget and has nothing to do with
   * the answer.
   *
   * <p><b>The whole event runs on the worker, registration included</b>, and that placement is the
   * concurrency contract rather than a detail. Derived registration is a read-then-write — "what
   * does the catalogue hold for this service, and what should it hold now" — and two green builds
   * of one repository arriving together would each read the old state and each write a link set
   * computed from it. The worker is single-threaded, so putting the read and the write on it is
   * what makes the pair atomic against every other event — the same reason the cutover lives there,
   * applied to the rows instead of the containers. ({@code ServiceCatalog.upsert} is synchronized
   * as the belt for every other caller.)
   *
   * <p>{@code runId} is optional and is recorded on every row this queues, verbatim: it is the only
   * pointer from a deployment back to the build that caused it, and it is resolved against nothing
   * — a reader takes it to qits-ci. The triple that actually drives the deployment is (repoId,
   * branch, commitSha).
   */
  @Override
  public void announce(String runId, String repoId, String branch, String commitSha) {
    DeploymentIdentifiers.requireRunId(runId);
    DeploymentIdentifiers.requireRepoId(repoId);
    PdIdentifiers.requireBranch(branch);
    DeploymentIdentifiers.requireSha(commitSha);
    worker.submit(
        () -> {
          try {
            deploy(runId, repoId, branch, commitSha);
          } catch (RuntimeException e) {
            LOG.errorf(
                e, "The build-succeeded event for %s@%s could not be handled", repoId, commitSha);
          }
        });
  }

  /**
   * One place this build deploys to: one application in one tier, or the platform plane. Resolved
   * before anything is queued and carried by value from there on — the docker work must not need a
   * second query to know where it is going.
   *
   * <p>{@code healthCmd} is the spec's {@code health_cmd} and is <b>the only field here no row
   * holds</b>. It needs none: it is read fresh from the repository before every deployment, and
   * the one path that resolves targets from the catalogue instead ({@link #alreadyRegistered})
   * records a failure and deploys nothing. Null is the HTTP probe over {@code healthPath}.
   */
  record Target(
      String applicationName,
      String environmentId,
      String environmentName,
      String bundleNetwork,
      PdDeploymentTarget target,
      boolean availableOnEnv,
      String healthPath,
      String healthCmd) {}

  /**
   * One build-succeeded event, start to finish, on the worker thread: read what the repository
   * declares, bring the catalogue up to date with it, and deploy each place it addresses.
   *
   * <p>The spec read comes first because it decides <b>which places exist</b> — there is nothing to
   * queue until it has answered. A read that fails (the git host is down, the file does not parse)
   * does not guess: the places this repository is already registered in each get a recorded {@code
   * FAILED} deployment naming the cause, and a repository with nothing registered gets nothing,
   * exactly as an unknown repository always has.
   */
  private void deploy(String runId, String repoId, String branch, String commitSha) {
    DeploymentSpec spec = null;
    String failure = null;
    try {
      spec = specs.read(repoId, commitSha);
    } catch (RuntimeException e) {
      failure = "[deployment spec unreadable: " + e.getMessage() + "]";
      LOG.warnf(
          "Could not read the deployment spec of %s@%s: %s", repoId, commitSha, e.getMessage());
    }

    List<Target> targets;
    if (spec == null) {
      targets = alreadyRegistered(repoId, branch);
    } else {
      try {
        targets = register(runId, repoId, branch, commitSha, spec);
      } catch (RuntimeException e) {
        // Registration is a local transaction, so this is a bug rather than an outage — and a bug
        // here is exactly the shape that once cost an hour of silence: a fire-and-forget sender,
        // no row, no signal. It is recorded where an operator looks, in the tiers this repository
        // is already registered in.
        LOG.errorf(e, "Registration of %s@%s failed", repoId, branch);
        failure = "[registration failed: " + e.getMessage() + "]";
        targets = alreadyRegistered(repoId, branch);
      }
    }

    List<String> queued = queue(runId, commitSha, targets);
    if (failure != null) {
      for (String deploymentId : queued) {
        finish(deploymentId, PdDeploymentStatus.FAILED, failure);
      }
      return;
    }
    for (int i = 0; i < queued.size(); i++) {
      String deploymentId = queued.get(i);
      try {
        execute(deploymentId, targets.get(i), commitSha);
      } catch (RuntimeException e) {
        LOG.errorf(e, "Deployment %s failed unexpectedly", deploymentId);
        finish(deploymentId, PdDeploymentStatus.FAILED, "[unexpected: " + e + "]");
      }
    }
  }

  /**
   * Bring the catalogue up to date with what the repository declares, and answer where to deploy.
   * The whole of derived registration.
   */
  private List<Target> register(
      String runId, String repoId, String branch, String commitSha, DeploymentSpec spec) {
    if (!isDeployableName(repoId)) {
      // The application name is the image path segment and the network alias, so it has to be a
      // dns label. A repository whose id is not one cannot be deployed by convention at all, and
      // the intake is fire-and-forget — saying so in the log beats a 400 nobody reads.
      LOG.warnf("Repository %s cannot be an application name, so nothing was registered", repoId);
      return List.of();
    }
    Optional<LinkedService> known = catalog.find(repoId);
    return spec.target() == PdDeploymentTarget.PLATFORM
        ? registerPlatform(repoId, branch, spec, known)
        : registerInEnvironments(runId, repoId, branch, commitSha, spec, known);
  }

  /**
   * The environment half. A repository that is <b>already a platform service</b> is refused here
   * rather than registered: the two planes are not symmetric, and going back is not a conversion.
   *
   * <p>Coming the other way, environment links become the platform plane because there is exactly
   * one destination to move the history to. Going back has as many destinations as there are
   * environments tracking the branch, no answer to which of them inherits the deployment history,
   * and a running container on {@code qits-platform} that the environment deployment would find
   * through the legacy network and remove — leaving a row that says {@code ACTIVE} about a
   * container that no longer exists. So this refuses, loudly and on the record.
   *
   * <p>The link set written is the <b>union</b> of what the catalogue already holds and the
   * environments this branch addresses. A green build on {@code environment/dev} says nothing about
   * whether the service also belongs in preprod, and the upsert replaces the whole set — so sending
   * only this branch's environments would silently unlink every other tier.
   */
  private List<Target> registerInEnvironments(
      String runId,
      String repoId,
      String branch,
      String commitSha,
      DeploymentSpec spec,
      Optional<LinkedService> known) {
    if (known.filter(s -> s.service().deploymentTarget == PdDeploymentTarget.PLATFORM).isPresent()) {
      LOG.errorf(
          "%s is registered as a platform service and its deployments.yml now asks for"
              + " deployment_target: environment. Going back is not a conversion and was refused —"
              + " remediate deliberately (retire the platform service, then push again).",
          repoId);
      recordRejection(
          repoId,
          runId,
          commitSha,
          "[refused: "
              + repoId
              + " is a platform service and this commit asks for deployment_target: environment."
              + " An environment application converts into a platform service, never the reverse —"
              + " there is no one environment to inherit the history and the running platform"
              + " container would be removed by the first environment deployment. Retire the"
              + " platform service deliberately, then push again.]");
      return List.of();
    }

    List<PdEnvironment> matching = environments.onBranch(branch);
    if (matching.isEmpty()) {
      // No tier listens to this branch: the normal case for every green build on a branch without
      // an environment. Nothing to link into, so nothing is written.
      return List.of();
    }

    Set<String> links =
        new LinkedHashSet<>(known.map(LinkedService::environmentIds).orElse(List.of()));
    for (PdEnvironment environment : matching) {
      links.add(environment.id);
    }
    String healthPath = resolveHealthPath(repoId, spec, known);
    catalog.upsert(
        new ServiceCatalog.Upsert(
            repoId,
            PdDeploymentTarget.ENVIRONMENT,
            null, // an environment application takes its branch from its tier
            spec.availableOnEnv(),
            healthPath,
            List.copyOf(links)));

    List<Target> targets = new ArrayList<>();
    for (PdEnvironment environment : matching) {
      if (known.isEmpty() || !known.get().environmentIds().contains(environment.id)) {
        LOG.infof("Registered %s in environment %s", repoId, environment.name);
      }
      targets.add(
          new Target(
              repoId,
              environment.id,
              environment.name,
              environment.network,
              PdDeploymentTarget.ENVIRONMENT,
              spec.availableOnEnv(),
              healthPath,
              spec.healthCmd()));
    }
    return List.copyOf(targets);
  }

  /**
   * The platform half, including the conversion a service goes through when it becomes
   * cross-environment: a repository that was an environment application until this commit had links
   * in every environment it was in, and those links go rather than sit beside the platform row. Its
   * deployment history is <b>moved onto the platform plane</b> — the active rows decommissioned,
   * since the application they described is about to be replaced from a different plane — by
   * clearing their environment rather than deleting them. Moving rather than deleting is what keeps
   * an in-flight self-update row alive across the component's own conversion; the containers those
   * rows started are absorbed by the next cutover, which finds them on the legacy network exactly
   * as it finds any other predecessor.
   *
   * <p>There is no "this name already belongs to another repository" check, and there is nothing to
   * check: the catalogue holds one identity for a service and derived registration has always named
   * an application after its repository, so the name IS the repository.
   *
   * <p><b>The branch decides whether this event is for this plane at all, and it is the same
   * question the environment arm asks.</b> A platform service deploys when an environment listens
   * to the built branch — there is one set of deploy refs on the platform, {@code
   * environment/<name>}, and a plane of its own with a second convention was one ref more than the
   * model needed. A build on a branch no environment tracks registers nothing and deploys nothing,
   * which is what keeps a push to the integration trunk from shipping the platform.
   *
   * <p>What it deploys is still platform-shaped — one instance, no environment id, no links — so
   * with several environments any one of their branches would roll the single platform instance.
   * That is acceptable while one environment exists and has to be revisited before the second one
   * is created; the plan gates environment #2 on it anyway.
   */
  private List<Target> registerPlatform(
      String repoId, String branch, DeploymentSpec spec, Optional<LinkedService> known) {
    if (environments.onBranch(branch).isEmpty()) {
      return List.of();
    }
    if (known.isEmpty()) {
      LOG.infof("Registered %s as a platform service", repoId);
    }
    String healthPath = resolveHealthPath(repoId, spec, known);
    catalog.upsert(
        new ServiceCatalog.Upsert(
            repoId,
            PdDeploymentTarget.PLATFORM,
            null, // the deploy refs are the environments' now — see PdService.branch
            false,
            healthPath,
            List.of()));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              List<PdDeployment> scoped = deployments.listEnvironmentScoped(repoId);
              for (PdDeployment deployment : scoped) {
                if (deployment.status == PdDeploymentStatus.ACTIVE) {
                  deployment.status = PdDeploymentStatus.DECOMMISSIONED;
                  deployment.finishedAt = Instant.now();
                }
                deployment.environmentId = null;
              }
              if (!scoped.isEmpty()) {
                LOG.infof(
                    "Converted %s from an environment application to a platform service", repoId);
              }
            });

    return List.of(
        new Target(
            repoId,
            null,
            null,
            null,
            PdDeploymentTarget.PLATFORM,
            false,
            healthPath,
            spec.healthCmd()));
  }

  /**
   * Where a service's health path comes from, in this order: the repository's own {@code
   * health_path}, then the value the catalogue already holds, then the convention derived from the
   * name. The convention is <b>written to the row</b> like every other derived fact, so a fresh
   * database gets working health gates with nothing to fill in by hand.
   *
   * <p>The stored value sits between the two on purpose. An operator who set a path is fixing
   * something the convention got wrong, and a later green build must not undo the fix; a repository
   * that states its own path is the more specific statement and does.
   *
   * <p>What this replaces: registration once had no source for the path at all, so every row was
   * written null, every deployment fell back to {@code
   * qits.platform.deployments.default-health-path} ({@code /q/health/ready}), and every service
   * mounted under its own prefix — all of them but the gateway — failed a health gate against a URL
   * that 404s while the container was fine.
   */
  private static String resolveHealthPath(
      String applicationName, DeploymentSpec spec, Optional<LinkedService> known) {
    if (spec.healthPath() != null) {
      return spec.healthPath();
    }
    return known
        .map(linked -> linked.service().healthPath)
        .filter(path -> path != null && !path.isBlank())
        .orElseGet(() -> conventionHealthPath(applicationName));
  }

  /**
   * The platform's path convention: a service serves everything under its own name without the
   * {@code qits-} prefix, so qits-observability answers on {@code /observability/q/health/ready}
   * and this component on {@code /platform-deployments/q/health/ready}. A name that does not carry
   * the prefix keeps the whole name.
   */
  static String conventionHealthPath(String applicationName) {
    String segment =
        applicationName.startsWith(NAME_PREFIX)
            ? applicationName.substring(NAME_PREFIX.length())
            : applicationName;
    // A repository called exactly `qits-` would leave nothing to mount under; keep its whole name
    // rather than compose a path with an empty segment in it.
    return "/" + (segment.isBlank() ? applicationName : segment) + "/q/health/ready";
  }

  /**
   * A refused registration, written down where the operator will look for it: one {@code FAILED}
   * deployment on the platform plane. A log line alone would say the same thing to nobody — the
   * intake is fire-and-forget, so the row is the only surface a refusal can surface on.
   */
  private void recordRejection(
      String applicationName, String runId, String commitSha, String detail) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment rejected = new PdDeployment();
              rejected.id = UUID.randomUUID().toString();
              rejected.applicationName = applicationName;
              rejected.environmentId = null;
              rejected.commitSha = commitSha;
              rejected.runId = runId;
              rejected.status = PdDeploymentStatus.FAILED;
              rejected.detail = detail;
              rejected.createdAt = Instant.now();
              rejected.finishedAt = Instant.now();
              deployments.persist(rejected);
            });
  }

  /**
   * What a failed spec read falls back to: where this (repository, branch) is already registered,
   * read off the catalogue. It answers where to record the failure — never where to deploy.
   *
   * <p>Which is why every target here carries a null {@code healthCmd} and needs nothing better:
   * no container starts off one of them.
   */
  private List<Target> alreadyRegistered(String repoId, String branch) {
    Optional<LinkedService> known = catalog.find(repoId);
    if (known.isEmpty()) {
      return List.of();
    }
    LinkedService linked = known.get();
    if (linked.service().deploymentTarget == PdDeploymentTarget.PLATFORM) {
      // The same branch question the deploying path asks, so a spec read that failed records the
      // failure exactly where a successful one would have deployed.
      return environments.onBranch(branch).isEmpty()
          ? List.of()
          : List.of(
              new Target(
                  repoId,
                  null,
                  null,
                  null,
                  PdDeploymentTarget.PLATFORM,
                  false,
                  linked.service().healthPath,
                  null));
    }
    List<Target> targets = new ArrayList<>();
    for (PdEnvironment environment : environments.onBranch(branch)) {
      if (linked.environmentIds().contains(environment.id)) {
        targets.add(
            new Target(
                repoId,
                environment.id,
                environment.name,
                environment.network,
                PdDeploymentTarget.ENVIRONMENT,
                linked.service().availableOnEnv,
                linked.service().healthPath,
                null));
      }
    }
    return List.copyOf(targets);
  }

  private List<String> queue(String runId, String commitSha, List<Target> targets) {
    if (targets.isEmpty()) {
      return List.of();
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<String> ids = new ArrayList<>();
              for (Target target : targets) {
                PdDeployment deployment = new PdDeployment();
                deployment.id = UUID.randomUUID().toString();
                deployment.applicationName = target.applicationName();
                deployment.environmentId = target.environmentId();
                deployment.commitSha = commitSha;
                deployment.runId = runId;
                deployment.status = PdDeploymentStatus.QUEUED;
                deployment.createdAt = Instant.now();
                deployments.persist(deployment);
                ids.add(deployment.id);
              }
              return ids;
            });
  }

  /** Everything a deployment needs off the worker thread — plain values, never entities. */
  private record Plan(
      String deploymentId, Target target, String sha, String healthPath, String healthCmd) {

    String applicationName() {
      return target.applicationName();
    }

    String environmentId() {
      return target.environmentId();
    }

    String environmentName() {
      return target.environmentName();
    }

    String bundleNetwork() {
      return target.bundleNetwork();
    }

    boolean availableOnEnv() {
      return target.availableOnEnv();
    }

    boolean platform() {
      return target.target() == PdDeploymentTarget.PLATFORM;
    }

    /** The one network {@code docker run} can take; every other membership is a join. */
    String primaryNetwork() {
      return platform()
          ? PdNetworks.PLATFORM
          : PdNetworks.application(environmentName(), applicationName());
    }

    /**
     * The address peers dial this container by, on every network it is on: {@code
     * <environment>-<application>} for a tier's copy, the bare application name for a platform
     * service. The run's {@code --network-alias}, every later join's {@code --alias} and the
     * predecessor search all take this one value.
     */
    String wireAlias() {
      return PdNetworks.alias(environmentName(), applicationName());
    }

    /**
     * What the predecessor search asks about — the wire alias, plus the bare application name while
     * anything started before the tier qualifier existed is still running.
     *
     * <p>Without the second the first deployment of every application would run a second copy
     * beside the one serving: today's containers hold the bare name and nothing else, and the
     * cutover finds a predecessor by the alias alone. It costs nothing to keep asking — a holder of
     * the bare name that belongs to another tier is filtered out by its environment label like any
     * other, and an unlabelled one is adoptable, which is the whole of how this platform migrates.
     */
    List<String> searchAliases() {
      String qualified = wireAlias();
      return qualified.equals(applicationName())
          ? List.of(qualified)
          : List.of(qualified, applicationName());
    }
  }

  /** The synchronous deployment — package-private so tests drive it without the worker. */
  void execute(String deploymentId, Target target, String commitSha) {
    Plan plan =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  PdDeployment deployment = deployments.findById(deploymentId);
                  if (deployment == null || deployment.status != PdDeploymentStatus.QUEUED) {
                    return null; // torn down or swept while queued — nothing to do
                  }
                  deployment.status = PdDeploymentStatus.STARTING;
                  return new Plan(
                      deploymentId,
                      target,
                      commitSha,
                      target.healthPath() != null ? target.healthPath() : defaultHealthPath,
                      // No default to fall back on, and none to want: an image that named no
                      // command is one the HTTP probe describes.
                      target.healthCmd());
                });
    if (plan == null) {
      return;
    }

    String imageRef =
        ImageRefs.imageRef(registryHost, imageRepository, plan.applicationName(), plan.sha());

    // The registry having no image for a green build is an expected outcome (nothing may publish
    // this application yet) and gets its own state rather than a generic failure.
    DeploymentDriver.PullResult pulled = driver.pull(imageRef);
    switch (pulled.outcome()) {
      case IMAGE_MISSING -> {
        finish(
            deploymentId,
            PdDeploymentStatus.IMAGE_MISSING,
            "no image " + imageRef + "\n" + safe(pulled.detail()));
        return;
      }
      case ERROR -> {
        finish(deploymentId, PdDeploymentStatus.FAILED, safe(pulled.detail()));
        return;
      }
      case OK -> {
        /* fall through */
      }
    }

    // Named after the deployment, not the sha: re-deploying the same commit must never collide
    // with the container it is about to replace.
    String containerName =
        ContainerNames.of(plan.environmentName(), plan.applicationName(), deploymentId);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment deployment = deployments.findById(deploymentId);
              if (deployment != null) {
                deployment.containerName = containerName;
              }
            });

    // Networks are re-ensured on every deployment rather than trusted from creation time — an
    // environment created while docker was down must heal, not stay broken.
    String primaryNetwork = plan.primaryNetwork();
    driver.ensureNetwork(primaryNetworkSpec(plan));
    if (!plan.platform() && plan.availableOnEnv()) {
      driver.ensureNetwork(
          new DeploymentDriver.Network(
              plan.bundleNetwork(),
              plan.environmentId(),
              DeploymentDriver.NetworkKind.BUNDLE,
              null));
    }
    List<String> joins = desiredJoins(plan, primaryNetwork);

    // The replace cutover: whatever currently answers to the application's alias is STOPPED — not
    // removed — before the fresh container starts. Stopping first is what makes stateful
    // applications deployable at all (one process per H2 file, one binder per published host
    // port); keeping the stopped containers around is what preserves the rollback: a failed gate
    // restarts them. The search covers every network the fresh container will be on, so it also
    // absorbs predecessors this component did not start (the bootstrap's seeded originals, the
    // retired qits-cd's) and predecessors still living on the legacy network alone — holding the
    // alias is what makes something the predecessor, not a row here.
    List<String> searchNetworks = new ArrayList<>();
    searchNetworks.add(primaryNetwork);
    searchNetworks.addAll(joins);
    List<DeploymentDriver.Holder> predecessors =
        predecessorsOf(
            driver.aliasHolders(List.copyOf(searchNetworks), plan.searchAliases()), plan);
    String self = driver.selfContainerId();
    DeploymentDriver.Holder selfHolder =
        self.isBlank()
            ? null
            : predecessors.stream()
                .filter(p -> p.id().startsWith(self) || self.startsWith(p.id()))
                .findFirst()
                .orElse(null);
    if (selfHolder != null) {
      // The self-update handoff. This process cannot stop itself and then finish the cutover, so
      // the roles split three ways: this instance starts the successor (which retries on the H2
      // lock under its restart policy) and launches a detached referee; the referee stops this
      // container — freeing the lock — awaits the successor's health gate, and removes whichever
      // side lost; the successor's startup sweep adopts the row it finds itself named on. The row
      // is left STARTING on purpose: adoption marks it ACTIVE, and after a referee rollback this
      // instance's own sweep marks it FAILED — each outcome recorded by the instance that survived
      // it.
      DeploymentDriver.StartResult successor =
          driver.start(startSpec(plan, primaryNetwork, imageRef, containerName));
      if (!successor.started()) {
        driver.remove(containerName);
        finish(deploymentId, PdDeploymentStatus.FAILED, safe(successor.detail()));
        return;
      }
      String unjoined = join(containerName, plan.wireAlias(), joins);
      if (unjoined != null) {
        // No handoff: the referee would promote a successor no caller can address, and it would do
        // it by removing the instance that still works. Nothing was stopped yet, so dropping the
        // successor puts everything back.
        driver.remove(containerName);
        finish(deploymentId, PdDeploymentStatus.FAILED, unjoined);
        return;
      }
      reconcile(plan, primaryNetwork);
      driver.handoff(
          new DeploymentDriver.HandoffSpec(
              imageRef, selfHolder.id(), containerName, healthTimeoutSeconds));
      LOG.infof(
          "Self-update handoff initiated: %s succeeds this instance (%s); the referee arbitrates",
          containerName, selfHolder.name());
      return;
    }
    for (DeploymentDriver.Holder predecessor : predecessors) {
      driver.stop(predecessor.name());
    }

    DeploymentDriver.StartResult started =
        driver.start(startSpec(plan, primaryNetwork, imageRef, containerName));
    if (!started.started()) {
      driver.remove(containerName); // in case docker created it and then failed
      rollback(predecessors);
      finish(deploymentId, PdDeploymentStatus.FAILED, safe(started.detail()));
      return;
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
    String unjoined = join(containerName, plan.wireAlias(), joins);
    if (unjoined != null) {
      driver.remove(containerName);
      rollback(predecessors);
      finish(deploymentId, PdDeploymentStatus.FAILED, unjoined);
      return;
    }
    reconcile(plan, primaryNetwork);

    DeploymentDriver.HealthResult health =
        driver.awaitHealthy(containerName, Duration.ofSeconds(healthTimeoutSeconds));
    if (!health.healthy()) {
      // The fresh container failed the gate: remove IT and restart what the cutover stopped —
      // the previous deployment goes back to serving.
      driver.remove(containerName);
      rollback(predecessors);
      finish(deploymentId, PdDeploymentStatus.FAILED, safe(health.detail()));
      return;
    }

    // Cutover: the new deployment is the application's ACTIVE one, whatever was ACTIVE before is
    // decommissioned — rows first, then the stopped containers (rows' and alias-holders' alike;
    // a set, since the healthy path sees most containers from both angles).
    List<String> oldContainers =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<String> old = new ArrayList<>();
                  for (PdDeployment previous :
                      deployments.listActiveByApplication(
                          plan.applicationName(), plan.environmentId())) {
                    previous.status = PdDeploymentStatus.DECOMMISSIONED;
                    previous.finishedAt = Instant.now();
                    if (previous.containerName != null) {
                      old.add(previous.containerName);
                    }
                  }
                  PdDeployment deployment = deployments.findById(deploymentId);
                  deployment.status = PdDeploymentStatus.ACTIVE;
                  deployment.finishedAt = Instant.now();
                  return old;
                });
    Set<String> toRemove = new LinkedHashSet<>(oldContainers);
    for (DeploymentDriver.Holder predecessor : predecessors) {
      toRemove.add(predecessor.name());
    }
    toRemove.remove(containerName);
    for (String oldContainer : toRemove) {
      driver.remove(oldContainer);
    }
    LOG.infof(
        "Deployed %s@%s into %s (%s)",
        plan.applicationName(),
        plan.sha(),
        plan.platform() ? "the platform" : plan.environmentName(),
        containerName);
  }

  private DeploymentDriver.Network primaryNetworkSpec(Plan plan) {
    return plan.platform()
        ? new DeploymentDriver.Network(
            PdNetworks.PLATFORM, null, DeploymentDriver.NetworkKind.PLATFORM, null)
        : new DeploymentDriver.Network(
            plan.primaryNetwork(),
            plan.environmentId(),
            DeploymentDriver.NetworkKind.APPLICATION,
            plan.applicationName());
  }

  private DeploymentDriver.StartSpec startSpec(
      Plan plan, String primaryNetwork, String imageRef, String containerName) {
    return new DeploymentDriver.StartSpec(
        plan.environmentId(),
        plan.environmentName(),
        ApplicationKeys.of(plan.environmentId(), plan.applicationName()),
        plan.applicationName(),
        plan.deploymentId(),
        plan.sha(),
        primaryNetwork,
        imageRef,
        containerName,
        plan.healthPath(),
        plan.healthCmd(),
        plan.target().target(),
        plan.availableOnEnv());
  }

  /**
   * Every network the fresh container joins after it started, primary excluded.
   *
   * <ul>
   *   <li>the legacy network, while {@code qits.platform.deployments.legacy-network} names one —
   *       the transition membership that keeps today's direct cross-application URLs resolving;
   *   <li>a public node ({@code availableOnEnv}) additionally joins its environment's bundle and
   *       <b>every</b> per-application network of that environment: that is the hub, and it is how
   *       an application reaches the gateway and how the gateway proxies every application;
   *   <li>a platform service joins every per-application network of every environment — being
   *       locally reachable everywhere is what makes it platform-plane rather than a shared service
   *       that needs a route.
   * </ul>
   */
  private List<String> desiredJoins(Plan plan, String primaryNetwork) {
    Set<String> joins = new LinkedHashSet<>();
    legacyNetwork.map(String::strip).filter(n -> !n.isEmpty()).ifPresent(joins::add);
    if (plan.platform()) {
      for (DeploymentDriver.Network network : driver.networks()) {
        if (network.kind() == DeploymentDriver.NetworkKind.APPLICATION) {
          joins.add(network.name());
        }
      }
    } else if (plan.availableOnEnv()) {
      joins.add(plan.bundleNetwork());
      for (DeploymentDriver.Network network : driver.networks()) {
        if (network.kind() == DeploymentDriver.NetworkKind.APPLICATION
            && plan.environmentId().equals(network.environmentId())) {
          joins.add(network.name());
        }
      }
    }
    joins.remove(primaryNetwork);
    return List.copyOf(joins);
  }

  /**
   * Put the fresh container on every network it needs beyond its primary one.
   *
   * @return null when it is on all of them, or the failure to record on the deployment row — these
   *     joins are what makes the container addressable, so a refused one is not a warning
   */
  private String join(String containerName, String alias, List<String> networks) {
    for (String network : networks) {
      DeploymentDriver.ConnectResult joined = driver.connect(network, containerName, alias);
      if (!joined.joined()) {
        return "could not join " + containerName + " to '" + network + "'\n" + safe(joined.detail());
      }
    }
    return null;
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
  private static List<DeploymentDriver.Holder> predecessorsOf(
      List<DeploymentDriver.Holder> holders, Plan plan) {
    List<DeploymentDriver.Holder> mine = new ArrayList<>();
    for (DeploymentDriver.Holder holder : holders) {
      if (holder.environmentId() == null || holder.environmentId().equals(plan.environmentId())) {
        mine.add(holder);
      } else {
        LOG.debugf(
            "%s holds the alias %s for environment %s — not this deployment's predecessor",
            holder.name(), plan.applicationName(), holder.environmentId());
      }
    }
    return List.copyOf(mine);
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
  private void reconcile(Plan plan, String primaryNetwork) {
    if (plan.platform()) {
      return;
    }
    for (DeploymentDriver.Endpoint hub : driver.hubContainers(plan.environmentId())) {
      driver.connect(
          primaryNetwork,
          hub.id(),
          PdNetworks.alias(plan.environmentName(), hub.applicationName()));
    }
    for (DeploymentDriver.Endpoint platform : driver.platformContainers()) {
      driver.connect(primaryNetwork, platform.id(), PdNetworks.alias(null, platform.applicationName()));
    }
  }

  /** A failed cutover restarts every container it stopped — the previous deployment serves again. */
  private void rollback(List<DeploymentDriver.Holder> predecessors) {
    for (DeploymentDriver.Holder predecessor : predecessors) {
      driver.restart(predecessor.name());
    }
  }

  private void finish(String deploymentId, PdDeploymentStatus status, String detail) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment deployment = deployments.findById(deploymentId);
              if (deployment == null) {
                return; // environment torn down mid-deploy
              }
              deployment.status = status;
              deployment.detail = detail;
              deployment.finishedAt = Instant.now();
            });
    if (status != PdDeploymentStatus.ACTIVE) {
      LOG.warnf("Deployment %s ended %s: %s", deploymentId, status, firstLine(detail));
    }
  }

  /** An environment's deployments across all its applications, newest-first. */
  public List<PdDeployment> deploymentsFor(String environmentId) {
    return deployments.listByEnvironmentNewestFirst(environmentId);
  }

  /**
   * The platform plane's deployments across all its applications, newest-first — the same question
   * as {@link #deploymentsFor}, asked of the plane that has no environment id to ask with.
   */
  public List<PdDeployment> platformDeployments() {
    return deployments.listPlatformNewestFirst();
  }

  /** Drop this environment's recorded deployments — the first step of a teardown. */
  public void forgetEnvironment(String environmentId) {
    QuarkusTransaction.requiringNew()
        .run(() -> deployments.delete("environmentId = ?1", environmentId));
  }

  private static boolean isDeployableName(String repoId) {
    try {
      PdIdentifiers.requireName(repoId, "application name");
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String safe(String detail) {
    return detail == null ? "" : detail;
  }

  private static String firstLine(String output) {
    if (output == null || output.isBlank()) {
      return "(no detail)";
    }
    String trimmed = output.strip();
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? trimmed : trimmed.substring(0, newline);
  }

  /**
   * Test hook: waits for the work queued at this moment to drain. Public because the whole of an
   * event runs on the worker — registration included — so a suite that asserts "nothing was
   * registered" has to be able to wait for the worker rather than for a row that never appears.
   */
  public void awaitIdle() throws Exception {
    worker.submit(() -> {}).get();
  }
}
