package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.db.DbRetry;
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
import eu.wohlben.qits.platform.deployments.events.DeploymentActive;
import eu.wohlben.qits.platform.deployments.events.DeploymentFailed;
import eu.wohlben.qits.platform.deployments.events.DeploymentQueued;
import eu.wohlben.qits.platform.deployments.events.DeploymentStarted;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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
 * <p><b>The worker carries one thing that is not an event</b>: the periodic observation pass ({@link
 * DeploymentObserver}), enqueued by a ticker here and running in queue order like everything else.
 * That is the whole reason it may touch deployment rows at all — see {@link #enqueueObservation()}.
 *
 * <p>Each DB transition sits in its own {@link QuarkusTransaction#requiringNew()} bracket so the
 * slow docker work never holds a transaction, and everything the docker calls need is copied into a
 * plain {@link Plan} first — the worker thread has no request context and no open session.
 *
 * <p><b>Three of those brackets are wrapped in {@link DbRetry}</b> — the platform's, from
 * qits-db-core — because this component deploys the postgres its own registry lives in and a cutover
 * of qits-oci-postgresql kills the connections of the very deployment performing it: the catalogue
 * read an event opens with, the post-gate cutover bookkeeping, and {@link #finish}. Connection-class
 * failures only, and {@link #CUTOVER_BUDGET} only — the rest of the brackets are deliberately left
 * alone, and {@link #queue} says why.
 *
 * <p><b>Which of the two spellings each one takes is decided by who owns the transaction.</b> The
 * catalogue read is a {@link DbRetry#call} around a read that brackets itself; the two writes are
 * {@link DbRetry#inNewTx}, which <i>is</i> the {@code requiringNew} — the retry owning the boundary
 * is what lets it retry only attempts that certainly did not commit, and leaves the one undecidable
 * round trip (the commit acknowledgement) reported rather than repeated.
 *
 * <p><b>The cutover is the DRIVER's, and that is the shape this class settled into.</b> What is
 * left here is one path with no branches in it: resolve the target, provision what the repository
 * declared, pull so a missing image is its own outcome, {@link DeploymentDriver#apply} the spec,
 * {@link DeploymentDriver#awaitConverged wait for the verdict}, record it. A predecessor search, a
 * stop-before-start and a hand-rolled rollback used to sit in the middle of that, and each was a
 * statement about how one orchestrator replaces a container rather than about deploying. Nothing
 * about the sequence above is orchestrator-shaped, which is the test that it is in the right place.
 *
 * <p><b>What stayed is the bookkeeping</b>: the row per place, the four announcements, the cutover
 * bracket that decommissions the prior {@code ACTIVE} rows of an (application, tier) and marks this
 * one live, and the reap that follows it — rows first, services after, so a bracket that has to
 * retry for thirty seconds has not already removed the predecessor.
 *
 * <p><b>One outcome is neither success nor failure and is worth knowing about here</b>: a
 * deployment that replaces THIS process comes back {@link DeploymentDriver.ApplyOutcome#HANDED_OFF
 * HANDED_OFF}, and the row is deliberately left {@code STARTING}. Neither instance can arbitrate
 * its own succession, so the outcome is recorded by whichever survives: the next boot's {@link
 * #sweepInFlight() sweep} settles the row from the image the service is running.
 */
@ApplicationScoped
public class DeployService implements BuildAnnouncements {

  private static final Logger LOG = Logger.getLogger(DeployService.class);

  /** Every platform repository carries it, and no path segment does. */
  private static final String NAME_PREFIX = "qits-";

  /** What an in-flight row says when nothing is running under its name — see {@link #sweepInFlight()}. */
  private static final String INTERRUPTED = "[interrupted by a qits-platform-deployments restart]";

  /**
   * How long a self-inflicted blip may last before it is a failure worth recording — longer than
   * {@link DbRetry#DEFAULT_DEADLINE}, and stated at every call site here rather than taken from the
   * library, because this worker's outage is one it caused itself: it deploys the postgres its own
   * registry lives in, and the container has to come all the way back before the bracket can
   * succeed. Safe to sleep for, because the worker is single-threaded — nothing is queued behind a
   * lock and the next event simply waits. Do not lift it onto a request thread.
   *
   * <p>Package-private because {@link DeploymentObserver} runs on the same worker and wraps its
   * brackets for the same reason, and one budget spelled twice would drift.
   */
  static final Duration CUTOVER_BUDGET = Duration.ofSeconds(30);

  @Inject PdDeploymentRepository deployments;
  @Inject DeploymentDriver driver;
  @Inject SpecSource specs;
  @Inject ServiceCatalog catalog;
  @Inject EnvironmentService environments;
  @Inject ResourceProvisioning resourceProvisioning;
  @Inject DeploymentObserver observer;

  /**
   * Where this component's own events leave it ({@link DeployAnnouncer}). An {@code Instance}
   * because zero implementations is a supported configuration: a build without the bus deploys
   * exactly as before and says nothing to anybody.
   */
  @Inject Instance<DeployAnnouncer> announcers;

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

  /** How often the observation pass is enqueued; {@code 0} switches the observer off entirely. */
  @ConfigProperty(name = "qits.platform.deployments.observe-interval-seconds")
  long observeIntervalSeconds;

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

  /**
   * At most one observation pass is pending behind the deploy queue at a time. A tick that fires
   * while one is already waiting collapses into it: an observation is a statement about NOW, so
   * stacking ten of them behind a long deploy queue would only re-answer a question the first one is
   * about to answer.
   */
  private final AtomicBoolean observationPending = new AtomicBoolean();

  /**
   * The observation tick — a bare daemon thread, the worker's own shape, rather than the
   * quarkus-scheduler extension. It has one job (submit a runnable every n seconds), it must not run
   * the pass itself, and a scheduler extension would add a managed thread pool and a second
   * concurrency model to a component whose whole ordering story is "one worker, in queue order".
   */
  private volatile Thread observerTicker;

  @PreDestroy
  void shutdown() {
    Thread ticker = observerTicker;
    if (ticker != null) {
      ticker.interrupt();
    }
    worker.shutdownNow();
  }

  /**
   * A deployment left {@code QUEUED} or {@code STARTING} by a crash can never make progress — the
   * worker queue does not survive the JVM — so it would show as forever-deploying. Settle those
   * once at startup, from what the runtime says: see {@link #sweepInFlight()}. The
   * containers are deliberately NOT reaped: a deployed application outlives its deployer, and
   * whatever was {@code ACTIVE} before the restart is still serving.
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
    startObserving();
  }

  /**
   * Start the periodic tick that keeps deployment rows honest ({@link DeploymentObserver}).
   *
   * <p>Nothing here in test mode, for the same reason the sweep is skipped: {@code onStart} returns
   * before this on a {@code LaunchMode.TEST} boot, so no {@code @QuarkusTest} has a ticker running
   * behind it and the suite's observation tests drive {@link #enqueueObservation()} themselves. That
   * is also why the interval keeps its shipped default in the suite — a test-resource override would
   * be re-declaring an app-level setting to disable something that never starts.
   */
  private void startObserving() {
    if (observeIntervalSeconds <= 0) {
      LOG.info(
          "Deployment observation is off (qits.platform.deployments.observe-interval-seconds=0):"
              + " a row's status will be whatever the deployment that wrote it said");
      return;
    }
    Thread ticker =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(Duration.ofSeconds(observeIntervalSeconds).toMillis());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                enqueueObservation();
              }
            },
            "pd-observation-ticker");
    ticker.setDaemon(true);
    observerTicker = ticker;
    ticker.start();
    LOG.infof("Observing deployment rows every %ds on the deploy worker", observeIntervalSeconds);
  }

  /**
   * Queue one observation pass <b>on the deploy worker</b>. Package-private so the suite drives a
   * pass without waiting on the tick.
   *
   * <p>The placement is the concurrency contract, not a convenience. Serial execution is what makes
   * "the previous ACTIVE deployment" an uncontended read during a cutover, and an observer thread
   * reading and writing those same rows while a cutover runs would take that invariant away — it
   * would see the half-written state between a cutover's own brackets and could demote a row a
   * deployment is in the middle of promoting. On the worker there is no middle: the pass runs between
   * events, never inside one.
   */
  void enqueueObservation() {
    if (!observationPending.compareAndSet(false, true)) {
      LOG.debugf("An observation pass is already queued; this tick collapses into it");
      return;
    }
    worker.submit(
        () -> {
          // Cleared as the pass BEGINS, not when it ends: a tick arriving during a long pass may
          // queue the next one, so the queue holds at most one pending pass plus the running one.
          observationPending.set(false);
          try {
            observer.observeOnce();
          } catch (RuntimeException e) {
            LOG.warnf(e, "The deployment observation pass failed; the next tick tries again");
          }
        });
  }

  /**
   * Settle every row a previous process left in flight, from what the runtime is running now.
   * Package-private so the suite drives the sweep without a real StartupEvent.
   *
   * <p><b>This is the self-update bookkeeping, and it is the only part of it left.</b> An instance
   * deploying itself hands the succession to the orchestrator and dies with the row {@code
   * STARTING}; whichever instance boots next has to say what became of it. The question is asked of
   * the deployment rather than of the deployer — {@link DeploymentDriver#runningImage} on the name
   * the row itself carries — which is why it is one arm for both orchestrators and for every
   * application, not a special case for this one.
   *
   * <table>
   *   <caption>What settles a row, and on what evidence</caption>
   *   <tr><th>row</th><th>the runtime says</th><th>verdict</th></tr>
   *   <tr><td>{@code QUEUED}, or {@code STARTING} with no name</td><td>nothing was asked</td>
   *       <td>{@code FAILED}: nothing ever ran, and the queue did not survive</td></tr>
   *   <tr><td>{@code STARTING}</td><td>no such service</td>
   *       <td>{@code FAILED}: it was interrupted, or the orchestrator took it away</td></tr>
   *   <tr><td>{@code STARTING}</td><td>an image carrying this row's sha</td>
   *       <td>{@code ACTIVE}, prior actives of the place decommissioned</td></tr>
   *   <tr><td>{@code STARTING}</td><td>an image carrying another sha</td>
   *       <td>{@code SUPERSEDED}: a rollback, or a later deployment, took the place</td></tr>
   * </table>
   *
   * <p><b>The last two rows used to be one word, and telling them apart is the point.</b> An
   * interrupted row with no evidence leaves nobody known to be serving, which is {@code FAILED};
   * a row whose place a newer sha is already serving is {@code SUPERSEDED}, and nothing is owed
   * about it. The sweep is the only writer of that word.
   *
   * <p><b>An adopted row is not a claim that the gate passed</b>, and it does not need to be: on
   * what carries the row's image is what is serving under the row's name, and a
   * container that is in fact dying is demoted by {@link DeploymentObserver} on the next two
   * passes. The prior actives are matched with an explicit null test for the platform plane
   * ({@code environment_id = ?} would silently match nothing, and a self-updated instance would
   * come back having failed its own deployment).
   *
   * <p>The shape is the observation pass's: read the rows in one transaction, ask the runtime
   * outside every one of them — a driver call is a child process, and no bracket of this
   * component's own may span one — then write each row in a bracket of its own.
   */
  void sweepInFlight() {
    List<InFlight> rows = QuarkusTransaction.requiringNew().call(this::inFlight);
    int adopted = 0;
    int settled = 0;
    for (InFlight row : rows) {
      Verdict verdict = verdictOn(row);
      QuarkusTransaction.requiringNew().run(() -> record(row, verdict));
      if (verdict.adopt()) {
        adopted++;
        LOG.infof(
            "Adopted deployment %s at startup: %s is running its image",
            row.deploymentId(), row.containerName());
      } else {
        settled++;
      }
    }
    if (settled > 0) {
      LOG.infof(
          "Settled %d deployment(s) left in flight by a previous shutdown — SUPERSEDED where a"
              + " newer sha is serving the place, FAILED where nothing is",
          settled);
    }
    if (adopted > 0) {
      LOG.infof("Adopted %d deployment(s) the previous instance could not record", adopted);
    }
  }

  /** One in-flight row as plain values — nothing carries an entity across a driver call. */
  private record InFlight(
      String deploymentId,
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName,
      String commitSha) {}

  /**
   * What settles this row: the status to write, and the detail that argues it. It carries the word
   * rather than a boolean because the sweep has three answers now — adopt, superseded, failed — and
   * a boolean could only spell two of them.
   */
  private record Verdict(PdDeploymentStatus status, String detail) {

    /** Adoption is the one verdict with bookkeeping of its own: the prior actives of the place. */
    boolean adopt() {
      return status == PdDeploymentStatus.ACTIVE;
    }
  }

  private List<InFlight> inFlight() {
    List<PdDeployment> rows = new ArrayList<>(deployments.listByStatus(PdDeploymentStatus.QUEUED));
    rows.addAll(deployments.listByStatus(PdDeploymentStatus.STARTING));
    return rows.stream()
        .map(
            row ->
                new InFlight(
                    row.id,
                    row.applicationName,
                    row.environmentId,
                    row.status,
                    row.containerName,
                    row.commitSha))
        .toList();
  }

  /** The decision table above, asked of the runtime. */
  private Verdict verdictOn(InFlight row) {
    if (row.status() != PdDeploymentStatus.STARTING || row.containerName() == null) {
      return new Verdict(PdDeploymentStatus.FAILED, INTERRUPTED);
    }
    DeploymentDriver.RunningImage running = driver.runningImage(row.containerName()).orElse(null);
    if (running == null) {
      return new Verdict(PdDeploymentStatus.FAILED, INTERRUPTED);
    }
    if (ImageRefs.carries(running.imageRef(), row.commitSha())) {
      return new Verdict(
          PdDeploymentStatus.ACTIVE,
          "[adopted at startup: " + row.containerName() + " is running this deployment]");
    }
    return new Verdict(
        PdDeploymentStatus.SUPERSEDED,
        "[superseded: "
            + row.containerName()
            + " runs "
            + running.imageRef()
            + ", which is another deployment"
            + (running.detail() == null || running.detail().isBlank()
                ? ""
                : "; " + firstLine(running.detail()))
            + "]");
  }

  private void record(InFlight row, Verdict verdict) {
    PdDeployment deployment = deployments.findById(row.deploymentId());
    if (deployment == null) {
      return; // the environment was torn down between the read and this write
    }
    if (verdict.adopt()) {
      for (PdDeployment previous :
          deployments.listActiveByApplication(row.applicationName(), row.environmentId())) {
        previous.status = PdDeploymentStatus.DECOMMISSIONED;
        previous.finishedAt = Instant.now();
      }
    }
    deployment.status = verdict.status();
    deployment.detail = verdict.detail();
    deployment.finishedAt = Instant.now();
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
   *
   * <p><b>{@code causationId} is carried by value from here on, and that is the whole reason it is
   * a parameter.</b> {@code CausationScope} is a ThreadLocal: the door's scope — the frame's for the
   * bus, the request filter's restored one for the HTTP intake — stands on THIS thread and is gone
   * the instant the lambda below runs somewhere else. So the cause travels the way {@code runId}
   * always has, as data, and the rows this event writes set it explicitly rather than hoping a
   * listener finds a scope that is not there. It is validated by nothing: an announcement is never
   * refused over a trace column.
   */
  @Override
  public void announce(
      String runId, String repoId, String branch, String commitSha, UUID causationId) {
    DeploymentIdentifiers.requireRunId(runId);
    DeploymentIdentifiers.requireRepoId(repoId);
    PdIdentifiers.requireBranch(branch);
    DeploymentIdentifiers.requireSha(commitSha);
    worker.submit(
        () -> {
          try {
            deploy(runId, repoId, branch, commitSha, causationId);
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
   * <p>{@code healthCmd}, {@code resources}, {@code updateOrder} and {@code publishMode} are the
   * spec's, and are <b>the only fields here no row holds</b>. They need none: the spec is read
   * fresh from the repository before every deployment, and the one path that resolves targets from
   * the catalogue instead ({@link #alreadyRegistered}) records a failure and deploys nothing. Null
   * is the HTTP probe over {@code healthPath}; an empty resource list is every application that
   * stores nothing; the default order is {@code start-first} and the default publish mode is
   * {@code host}.
   */
  record Target(
      String applicationName,
      String environmentId,
      String environmentName,
      String bundleNetwork,
      PdDeploymentTarget target,
      boolean availableOnEnv,
      String healthPath,
      String healthCmd,
      List<ResourceProvisioning.Resolved> resources,
      DeploymentDriver.UpdateOrder updateOrder,
      DeploymentDriver.PublishMode publishMode) {}

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
  private void deploy(
      String runId, String repoId, String branch, String commitSha, UUID causationId) {
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
        targets = register(runId, repoId, branch, commitSha, spec, causationId);
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

    List<String> queued = queue(runId, commitSha, targets, causationId);
    if (failure != null) {
      for (int i = 0; i < queued.size(); i++) {
        finish(queued.get(i), targets.get(i), PdDeploymentStatus.FAILED, failure);
      }
      return;
    }
    for (int i = 0; i < queued.size(); i++) {
      String deploymentId = queued.get(i);
      try {
        execute(deploymentId, targets.get(i), commitSha);
      } catch (RuntimeException e) {
        LOG.errorf(e, "Deployment %s failed unexpectedly", deploymentId);
        finish(deploymentId, targets.get(i), PdDeploymentStatus.FAILED, "[unexpected: " + e + "]");
      }
    }
  }

  /**
   * The catalogue read every event starts with — and the first database access a build-succeeded
   * event makes, which is why it is retried.
   *
   * <p>An event arriving while this component's own postgres is cutting over used to die here and
   * be swallowed: the intake is fire-and-forget, the worker logs the exception, and the build that
   * caused it is simply never deployed. There is no row to look at afterwards, because a row is
   * what the read was on the way to creating.
   */
  private Optional<LinkedService> findService(String repoId) {
    return DbRetry.call(
        "The catalogue lookup of " + repoId, () -> catalog.find(repoId), CUTOVER_BUDGET);
  }

  /** The tiers listening to a branch — the topology half of the same read. See {@link #findService}. */
  private List<PdEnvironment> tiersOnBranch(String branch) {
    return DbRetry.call(
        "The tier lookup for " + branch, () -> environments.onBranch(branch), CUTOVER_BUDGET);
  }

  /**
   * Bring the catalogue up to date with what the repository declares, and answer where to deploy.
   * The whole of derived registration.
   */
  private List<Target> register(
      String runId,
      String repoId,
      String branch,
      String commitSha,
      DeploymentSpec spec,
      UUID causationId) {
    if (!isDeployableName(repoId)) {
      // The application name is the image path segment and the network alias, so it has to be a
      // dns label. A repository whose id is not one cannot be deployed by convention at all, and
      // the intake is fire-and-forget — saying so in the log beats a 400 nobody reads.
      LOG.warnf("Repository %s cannot be an application name, so nothing was registered", repoId);
      return List.of();
    }
    Optional<LinkedService> known = findService(repoId);
    return spec.target() == PdDeploymentTarget.PLATFORM
        ? registerPlatform(repoId, branch, spec, known, causationId)
        : registerInEnvironments(runId, repoId, branch, commitSha, spec, known, causationId);
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
      Optional<LinkedService> known,
      UUID causationId) {
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
              + " platform service deliberately, then push again.]",
          causationId);
      return List.of();
    }

    List<PdEnvironment> matching = tiersOnBranch(branch);
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
    // The spec's databases, with the convention filled in where the file left it out. Resolved
    // ONCE, here, because this is the first place that knows the application's name — and before
    // any Target is built, so a collision is one refusal rather than one per tier.
    List<ResourceProvisioning.Resolved> resources =
        ResourceProvisioning.resolve(repoId, spec.resources());
    catalog.upsert(
        new ServiceCatalog.Upsert(
            repoId,
            PdDeploymentTarget.ENVIRONMENT,
            null, // an environment application takes its branch from its tier
            spec.availableOnEnv(),
            healthPath,
            List.copyOf(links)),
        causationId);

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
              spec.healthCmd(),
              resources,
              spec.updateOrder(),
              spec.publishMode()));
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
   * <p><b>The branch decides whether this event is for this plane at all, and it is nearly the same
   * question the environment arm asks.</b> There is one set of deploy refs on the platform, {@code
   * environment/<name>}, and a plane of its own with a second convention was one ref more than the
   * model needed. A build on a branch no environment tracks registers nothing and deploys nothing,
   * which is what keeps a push to the integration trunk from shipping the platform.
   *
   * <p>Where the two arms differ is <b>which</b> environment counts. The environment arm fans out
   * over every tier listening to the branch; this one deploys only when the <b>platform
   * environment</b> is among them. What it deploys is one instance with no environment id and no
   * links, so "every tier's branch rolls it" was never a fan-out — it was several tiers taking turns
   * overwriting one container. {@code PdEnvironment.platform} is what settles which tier owns that
   * turn, and it is why a second environment is now an ordinary thing to create.
   */
  private List<Target> registerPlatform(
      String repoId,
      String branch,
      DeploymentSpec spec,
      Optional<LinkedService> known,
      UUID causationId) {
    if (tiersOnBranch(branch).stream().noneMatch(environment -> environment.platform)) {
      return List.of();
    }
    if (known.isEmpty()) {
      LOG.infof("Registered %s as a platform service", repoId);
    }
    String healthPath = resolveHealthPath(repoId, spec, known);
    List<ResourceProvisioning.Resolved> resources =
        ResourceProvisioning.resolve(repoId, spec.resources());
    catalog.upsert(
        new ServiceCatalog.Upsert(
            repoId,
            PdDeploymentTarget.PLATFORM,
            null, // the deploy refs are the environments' now — see PdService.branch
            false,
            healthPath,
            List.of()),
        causationId);

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
            spec.healthCmd(),
            resources,
            spec.updateOrder(),
            spec.publishMode()));
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
      String applicationName, String runId, String commitSha, String detail, UUID causationId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment rejected = new PdDeployment();
              rejected.id = UUID.randomUUID().toString();
              // See queue(): the stamp finds no scope on this thread, so the cause is set here.
              rejected.causationId = causationId;
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
    Optional<LinkedService> known = findService(repoId);
    if (known.isEmpty()) {
      return List.of();
    }
    LinkedService linked = known.get();
    if (linked.service().deploymentTarget == PdDeploymentTarget.PLATFORM) {
      // The same branch question the deploying path asks, so a spec read that failed records the
      // failure exactly where a successful one would have deployed.
      return tiersOnBranch(branch).isEmpty()
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
                  null,
                  List.of(),
                  null,
                  null));
    }
    List<Target> targets = new ArrayList<>();
    for (PdEnvironment environment : tiersOnBranch(branch)) {
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
                null,
                List.of(),
                null,
                null));
      }
    }
    return List.copyOf(targets);
  }

  /**
   * Write one {@code QUEUED} row per place this build deploys to.
   *
   * <p><b>Deliberately not retried</b>, and it is the same answer for {@link #recordRejection}, the
   * {@code STARTING} transition and the platform conversion. They INSERT or move rows, so a commit
   * whose outcome the connection died before reporting would be duplicated by a second attempt; and
   * they all run before anything docker-side has happened, so losing one drops the event with
   * nothing half-done and a resent event replays it. The retried brackets are the ones that come
   * AFTER a container is already running, where dropping the work leaves a live container with no
   * row that admits it.
   *
   * <p>Each created row is announced as {@code DeploymentQueued} <b>after the transaction
   * commits</b>, so a consumer that reads the deployment back finds it. One event per row: a build
   * addressing three tiers queues three deployments and says so three times.
   */
  private List<String> queue(
      String runId, String commitSha, List<Target> targets, UUID causationId) {
    if (targets.isEmpty()) {
      return List.of();
    }
    List<Queued> rows =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Queued> queued = new ArrayList<>();
                  for (Target target : targets) {
                    queued.add(persistQueued(runId, commitSha, target, causationId));
                  }
                  return queued;
                });
    for (Queued row : rows) {
      announceQueued(row, runId, commitSha, causationId);
    }
    return rows.stream().map(Queued::deploymentId).toList();
  }

  /** A queued row, and the facts its announcement needs, carried out of the transaction. */
  private record Queued(String deploymentId, Target target, Instant queuedAt) {}

  /** One {@code QUEUED} row. Inside {@link #queue}'s transaction, never on its own. */
  private Queued persistQueued(String runId, String commitSha, Target target, UUID causationId) {
    PdDeployment deployment = new PdDeployment();
    deployment.id = UUID.randomUUID().toString();
    // The generic causation column, set EXPLICITLY. This runs on pd-deploy-worker, behind the
    // queue hop the intake made: CausationScope does not follow work, so CausationStamp would read
    // null here and record a decision nobody made. An author-set value is what the stamp yields to.
    // Every row of one event shares it — one build going green is one cause, however many tiers it
    // fans out over.
    deployment.causationId = causationId;
    deployment.applicationName = target.applicationName();
    deployment.environmentId = target.environmentId();
    deployment.commitSha = commitSha;
    deployment.runId = runId;
    deployment.status = PdDeploymentStatus.QUEUED;
    deployment.createdAt = Instant.now();
    deployments.persist(deployment);
    return new Queued(deployment.id, target, deployment.createdAt);
  }

  /**
   * Everything a deployment needs off the worker thread — plain values, never entities.
   *
   * <p><b>The last three are for the announcements and nothing else.</b> They are read off the row
   * inside the {@code STARTING} transaction because that is the one place a deployment has the row
   * open; re-reading it at each of the three later announcement points would be three queries for
   * values that cannot change. {@code startedAt} is a clock reading rather than a column — there is
   * no {@code started_at} — taken at the transition rather than at publish, which is the property an
   * event log actually needs.
   */
  private record Plan(
      String deploymentId,
      Target target,
      String sha,
      String healthPath,
      String healthCmd,
      String runId,
      UUID cause,
      Instant startedAt) {

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

    List<ResourceProvisioning.Resolved> resources() {
      return target.resources();
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

    DeploymentDriver.UpdateOrder updateOrder() {
      return target.updateOrder();
    }

    DeploymentDriver.PublishMode publishMode() {
      return target.publishMode();
    }

    /**
     * The address peers dial this by, on every network it is on: {@code
     * <environment>-<application>} for a tier's copy, the bare application name for a platform
     * service. It is derived in one place because everything that has to agree on it takes it from
     * here — docker's {@code --network-alias} and every join after it, swarm's service NAME, and
     * the predecessor search underneath both.
     */
    String wireAlias() {
      return PdNetworks.alias(environmentName(), applicationName());
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
                      target.healthCmd(),
                      deployment.runId,
                      deployment.causationId,
                      Instant.now());
                });
    if (plan == null) {
      return;
    }
    announceStarted(plan);

    // PROVISIONING GOES HERE, between the row's STARTING transition and the pull, and the placement
    // is three decisions at once:
    //   * AFTER the transition, because the row is what a failure is recorded on — a resource that
    //     cannot be made to exist has to be readable as a failed deployment, not as a log line
    //     under a fire-and-forget intake;
    //   * BEFORE the pull, because nothing docker-side has happened yet: there is no fresh
    //     container to remove and no predecessor stopped to restart, so the failure path is a
    //     single `finish` and the previous deployment is untouched;
    //   * on plain values, because Plan is plain values — no transaction and no open session spans
    //     the DDL call to another server.
    List<DeploymentDriver.ResourceBinding> bindings;
    try {
      bindings =
          resourceProvisioning.ensureAll(
              plan.applicationName(), plan.environmentName(), plan.resources());
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not provision the resources of %s: %s", plan.applicationName(), e.getMessage());
      finish(
          deploymentId,
          target,
          PdDeploymentStatus.FAILED,
          "[resource provisioning failed: " + e.getMessage() + "]");
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
            target,
            PdDeploymentStatus.IMAGE_MISSING,
            "no image " + imageRef + "\n" + safe(pulled.detail()));
        return;
      }
      case AUTH_REFUSED -> {
        // A refusal is a FAILED deployment rather than IMAGE_MISSING, and the detail is why the
        // two are told apart at all: what has to be fixed is the credential the daemon reads, not
        // the pipeline that publishes the image.
        finish(
            deploymentId,
            target,
            PdDeploymentStatus.FAILED,
            "the registry refused the pull of "
                + imageRef
                + " — check the deployer's registry credential\n"
                + safe(pulled.detail()));
        return;
      }
      case ERROR -> {
        finish(deploymentId, target, PdDeploymentStatus.FAILED, safe(pulled.detail()));
        return;
      }
      case OK -> {
        /* fall through */
      }
    }

    // Named after the deployment, not the sha: re-deploying the same commit must never collide
    // with what it is about to replace. Whether that name is what the runtime uses is the driver's
    // answer, below: docker names one container per deployment, a swarm service's name IS its
    // address and a replace updates it in place.
    String deploymentName =
        ContainerNames.of(plan.environmentName(), plan.applicationName(), deploymentId);

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
    // The FULL membership, primary first, handed over in one piece. It is a list rather than a
    // primary plus a join set because an orchestrator that cannot join after the fact has to
    // declare all of it at create time — every swarm `--network-add` recreates the task — and the
    // docker driver still starts on the first and joins the rest.
    List<String> networks = new ArrayList<>();
    networks.add(primaryNetwork);
    networks.addAll(desiredJoins(plan, primaryNetwork));

    DeploymentDriver.ServiceSpec spec =
        serviceSpec(plan, List.copyOf(networks), imageRef, deploymentName, bindings);
    // What the runtime will call it, and therefore what the row records: asked BEFORE anything is
    // applied, so a crash in the middle leaves a STARTING row the startup sweep can still identify.
    String name = driver.nameOf(spec);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment deployment = deployments.findById(deploymentId);
              if (deployment != null) {
                deployment.containerName = name;
              }
            });

    // The replace, whole, in one call. What it costs — a predecessor stopped and restartable, a
    // hand-rolled rollback — is the docker driver's business; under swarm it is one `service
    // update` and the orchestrator's own update policy.
    DeploymentDriver.ApplyResult applied = driver.apply(spec);
    switch (applied.outcome()) {
      case REFUSED -> {
        finish(deploymentId, target, PdDeploymentStatus.FAILED, safe(applied.detail()));
        return;
      }
      case HANDED_OFF -> {
        // Deploying this component itself. The row stays STARTING on purpose: whichever instance
        // survives the succession records the outcome — the successor's sweep adopts it, a
        // rolled-back predecessor's sweep fails it.
        LOG.infof(
            "Self-update handed over: %s succeeds this instance, and the row stays STARTING until"
                + " whoever survives records it (%s)",
            name, safe(applied.detail()));
        return;
      }
      case APPLIED -> {
        /* fall through to the verdict */
      }
    }

    DeploymentDriver.Convergence converged =
        driver.awaitConverged(name, Duration.ofSeconds(healthTimeoutSeconds));
    if (!converged.converged()) {
      // Nothing runs that did not run before: the orchestrator reverted the spec itself, and the
      // predecessor is serving again by the time this returns.
      //
      // ROLLED_BACK is that verdict kept rather than flattened. The orchestrator already answered
      // the question a reader has ("is anything serving this place?") and FAILED threw the answer
      // away — a rolled-back deployment and a refused apply asked for very different reactions and
      // read identically on the row. A convergence that failed WITHOUT a rollback keeps FAILED,
      // because there nothing is known to serve.
      finish(
          deploymentId,
          target,
          converged.outcome() == DeploymentDriver.ConvergenceOutcome.ROLLED_BACK
              ? PdDeploymentStatus.ROLLED_BACK
              : PdDeploymentStatus.FAILED,
          safe(converged.detail()));
      return;
    }

    // Cutover: the new deployment is the application's ACTIVE one, whatever was ACTIVE before is
    // decommissioned — rows first, then whatever is left to reap (the rows' own names and the
    // predecessors the driver retired alike; a set, since the healthy path usually sees the same
    // ones from both angles).
    //
    // THE RETRY IS NOT DECORATION HERE. This component deploys the platform's postgres, and a
    // cutover of qits-oci-postgresql kills every connection this process holds — in the middle of
    // the deployment that just performed it. The container was healthy; only the bookkeeping died,
    // and it recorded the whole deployment FAILED for it. Re-running the bracket is safe because it
    // re-reads its entities and writes them the same values.
    //
    // `inNewTx` rather than `call` around a `requiringNew`: the retry owns the transaction, so an
    // attempt that fails is one it knows never committed. The two spelled separately would retry a
    // lost commit acknowledgement as well, which is the one round trip nothing can place.
    Cutover cutover =
        DbRetry.inNewTx(
            "The cutover bookkeeping of " + deploymentId,
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
              // Flushed here rather than left to the commit, and that is what makes the retry
              // above worth having: an ORM flushes at commit by default, which would put these
              // statements on the far side of the one round trip nothing can place. Flushed, a
              // lost connection is a body failure — certainly not committed, so certainly safe to
              // run again.
              deployments.flush();
              // The timestamp comes back out rather than being taken again afterwards: the event
              // says when the cutover happened, and a retry that ran the body twice must announce
              // the value that is on the row.
              return new Cutover(old, deployment.finishedAt);
            },
            CUTOVER_BUDGET);
    Set<String> toRemove = new LinkedHashSet<>(cutover.oldContainers());
    toRemove.addAll(converged.retired());
    // Never the thing that just went live. Under swarm that removes the whole set by itself: a
    // replace is in place, so the predecessor row names the same service this deployment applied.
    toRemove.remove(name);
    driver.reap(List.copyOf(toRemove));
    LOG.infof(
        "Deployed %s@%s into %s (%s)",
        plan.applicationName(),
        plan.sha(),
        plan.platform() ? "the platform" : plan.environmentName(),
        name);
    // Last, so an unreachable qits-events delays nothing this deployment still has to do. The
    // timestamp is the row's, so announcing late does not make the event late.
    announceActive(plan, name, cutover.finishedAt());
  }

  /** What the cutover bracket carries out: the containers to reap, and when it happened. */
  private record Cutover(List<String> oldContainers, Instant finishedAt) {}

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

  /**
   * Everything the driver needs, as plain values — the {@link Plan} stance carried one step
   * further, since a driver may run the spec on a thread and in a process this one does not own.
   */
  private DeploymentDriver.ServiceSpec serviceSpec(
      Plan plan,
      List<String> networks,
      String imageRef,
      String deploymentName,
      List<DeploymentDriver.ResourceBinding> bindings) {
    return new DeploymentDriver.ServiceSpec(
        plan.environmentId(),
        plan.environmentName(),
        ApplicationKeys.of(plan.environmentId(), plan.applicationName()),
        plan.applicationName(),
        plan.deploymentId(),
        plan.sha(),
        deploymentName,
        plan.wireAlias(),
        networks,
        imageRef,
        plan.healthPath(),
        plan.healthCmd(),
        plan.target().target(),
        plan.availableOnEnv(),
        plan.updateOrder(),
        plan.publishMode(),
        bindings);
  }

  /**
   * Every network this deployment belongs on beyond its primary one — a membership the driver
   * declares at create time or joins after the start, whichever its orchestrator can do.
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
   * Record the outcome. Retried on a connection-class failure for the same reason the cutover
   * bookkeeping is: this is the last write of a deployment that may have just replaced the database
   * it writes to, and an outcome nobody could record is a row that says {@code STARTING} forever.
   *
   * <p><b>The one funnel every recorded failure goes through, which is why the announcement lives
   * here</b> rather than at the nine call sites that reach it. It announces only when the written
   * status is not {@code ACTIVE} — the same test the WARN already made, and nothing calls this with
   * {@code ACTIVE} today; the happy path announces from the cutover, which knows things this does
   * not.
   *
   * <p><b>The word travels.</b> {@code DeploymentFailed} already carries the status as a string, so
   * a {@code ROLLED_BACK} outcome announces that word and consumers that only care "this did not go
   * live" are unaffected — which is why refining the vocabulary needed no fifth event.
   *
   * <p><b>No row, no event.</b> A deployment whose environment was torn down mid-deploy has nothing
   * to record and nothing to announce: the bracket returns null and this returns without publishing.
   *
   * <p>{@code target} is the caller's, and it is the only thing here the row cannot supply — {@code
   * pd_deployment} holds the environment's ID and never its NAME. Every call site already has one.
   */
  private void finish(
      String deploymentId, Target target, PdDeploymentStatus status, String detail) {
    Finished finished =
        DbRetry.inNewTx(
            "Recording deployment " + deploymentId + " as " + status,
            () -> {
              PdDeployment deployment = deployments.findById(deploymentId);
              if (deployment == null) {
                return null; // environment torn down mid-deploy
              }
              deployment.status = status;
              deployment.detail = detail;
              deployment.finishedAt = Instant.now();
              // statement phase, so a lost connection is retriable — see the cutover bracket
              deployments.flush();
              return new Finished(
                  deployment.commitSha,
                  deployment.runId,
                  deployment.causationId,
                  deployment.finishedAt);
            },
            CUTOVER_BUDGET);
    if (status != PdDeploymentStatus.ACTIVE) {
      LOG.warnf("Deployment %s ended %s: %s", deploymentId, status, firstLine(detail));
      if (finished != null) {
        announceFailed(deploymentId, target, status, detail, finished);
      }
    }
  }

  /** What the outcome bracket carries out — the row's own values, for the announcement. */
  private record Finished(String commitSha, String runId, UUID cause, Instant finishedAt) {}

  /**
   * The four announcements, each called after the transaction that made its statement true.
   *
   * <p><b>They are wrapped, every one of them, and that is the rule rather than caution.</b> An
   * announcement is a statement about a deployment, never part of it: a bus that is unreachable, a
   * serializer that refuses a value, an implementation with a bug must all cost the log line below
   * and nothing else. The port already says an implementation must not throw; this is what makes a
   * broken one survivable.
   *
   * <p>Every announcer is offered the event — {@code Instance} is a set, usually of one and validly
   * of none — and one that throws does not stop the next.
   */
  private void announceQueued(Queued row, String runId, String commitSha, UUID cause) {
    announce(
        row.deploymentId(),
        announcer ->
            announcer.onQueued(
                new DeploymentQueued(
                    row.deploymentId(),
                    row.target().applicationName(),
                    row.target().environmentId(),
                    row.target().environmentName(),
                    commitSha,
                    runId,
                    row.queuedAt()),
                cause));
  }

  private void announceStarted(Plan plan) {
    announce(
        plan.deploymentId(),
        announcer ->
            announcer.onStarted(
                new DeploymentStarted(
                    plan.deploymentId(),
                    plan.applicationName(),
                    plan.environmentId(),
                    plan.environmentName(),
                    plan.sha(),
                    plan.runId(),
                    plan.startedAt()),
                plan.cause()));
  }

  private void announceActive(Plan plan, String containerName, Instant finishedAt) {
    announce(
        plan.deploymentId(),
        announcer ->
            announcer.onActive(
                new DeploymentActive(
                    plan.deploymentId(),
                    plan.applicationName(),
                    plan.environmentId(),
                    plan.environmentName(),
                    plan.sha(),
                    plan.runId(),
                    containerName,
                    finishedAt),
                plan.cause()));
  }

  private void announceFailed(
      String deploymentId,
      Target target,
      PdDeploymentStatus status,
      String detail,
      Finished finished) {
    announce(
        deploymentId,
        announcer ->
            announcer.onFailed(
                new DeploymentFailed(
                    deploymentId,
                    target.applicationName(),
                    target.environmentId(),
                    target.environmentName(),
                    finished.commitSha(),
                    finished.runId(),
                    status.name(),
                    detail,
                    finished.finishedAt()),
                finished.cause()));
  }

  /** See {@link #announceQueued} for why every one of these is wrapped. */
  private void announce(String deploymentId, Consumer<DeployAnnouncer> announcement) {
    for (DeployAnnouncer announcer : announcers) {
      try {
        announcement.accept(announcer);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing deployment %s failed", deploymentId);
      }
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
