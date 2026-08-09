package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The second half of the eaa34fbc story. A deployment's status is written once, by the deployment
 * that earned it, and until this class existed it was never read back against the world: row
 * eaa34fbc ended {@code FAILED: [unexpected: JDBCConnectionException …]} because the post-gate
 * bookkeeping ran on connections its own cutover of qits-oci-postgresql had just killed, while the
 * container it started stayed {@code Up (healthy)} for hours holding the application's alias.
 * {@link DbRetry} fixed the CAUSE; a row that ended {@code FAILED} still stays {@code FAILED}
 * forever, and the mirror image — an {@code ACTIVE} row whose container died an hour after the gate
 * passed — was never noticed at all.
 *
 * <p>So this is a periodic observation, not a second deploy path. <b>It writes rows only.</b> It
 * reaps no container, starts no container and touches no network — the startup sweep's "deliberately
 * reaps no containers" stance, which applies here more strongly than there: the sweep at least runs
 * once, at a moment nothing else is happening, while this runs beside a live platform forever.
 *
 * <p><b>It settles the LATEST row per (application, tier) and nothing else.</b> Latest by {@code
 * seq}, the documented ordering — {@code createdAt} is not unique and the id is a random UUID.
 * History stays history: an older {@code FAILED} row describes an attempt that really did fail, and
 * a healthy container today says nothing about it. {@code QUEUED} and {@code STARTING} belong to the
 * worker's own state machine (the intake queued them, the sweep fails them after a crash) and
 * {@code DECOMMISSIONED} is a decision another deployment made; none of the three is observable from
 * a container's state.
 *
 * <p><b>Two transitions, and each is deliberately narrow.</b>
 *
 * <ul>
 *   <li>{@code FAILED} → {@code ACTIVE} when the container <b>the row itself names</b> exists, runs
 *       and is healthy by {@link HealthGate#healthy} — the gate's own verdict, so a recovery cannot
 *       mean something a health gate would have refused. Only the row's own container counts: a
 *       healthy container of some other deployment must never resurrect a foreign row, which is why
 *       this asks docker about {@code containerName} rather than about the alias.
 *   <li>{@code ACTIVE} → {@code FAILED} when the container is <b>absent or terminally exited</b> on
 *       {@value #STRIKES_TO_DEMOTE} consecutive passes. Patience is inherited from the health gate
 *       rather than reinvented: <b>restarting is not dead</b> and <b>running-but-unhealthy is not
 *       dead</b> — that is the postgres-alias boot race the gate already tolerates, and a container
 *       coming back from it must not be declared failed on the way. The second pass is the belt for
 *       a docker hiccup: one {@code inspect} that could not answer must never flip a deployment that
 *       is serving.
 * </ul>
 *
 * <p><b>A recovery also retires the predecessors it never got to retire.</b> The bookkeeping that
 * died in eaa34fbc was one bracket doing two things — decommission the prior {@code ACTIVE} rows of
 * this (application, tier) and mark this one {@code ACTIVE} — so a row recovered here may well have
 * an older row still claiming to serve. Leaving it would break the invariant {@code
 * PdDeploymentRepository#listActiveByApplication} is written around (at most one ACTIVE per place),
 * and the rollback pins read off it. Their containers are NOT removed, exactly as the sweep does not
 * remove them: whatever still holds the alias is absorbed as a predecessor by the next deployment.
 *
 * <p>The pass runs <b>on the deploy worker</b> ({@code pd-deploy-worker}), enqueued by {@link
 * DeployService}, which is the whole reason it can read these rows at all without racing a cutover —
 * see that class for the collapse rule. Its own shape follows the worker's: read the candidates in
 * one transaction, copy them out as plain values, ask docker outside any transaction, then write each
 * settled row in its own {@link DbRetry}-wrapped bracket. Retried for the same reason the cutover
 * bookkeeping is: this is bookkeeping that runs <i>after</i> a container is running, and one day it
 * will run during a postgres self-cutover.
 */
@ApplicationScoped
public class DeploymentObserver {

  private static final Logger LOG = Logger.getLogger(DeploymentObserver.class);

  /**
   * How many consecutive passes must agree that an {@code ACTIVE} row's container is gone before the
   * row is demoted. Two, because one docker call that could not answer — a daemon reloading, an
   * {@code inspect} that timed out — must not take a serving deployment's row with it.
   */
  static final int STRIKES_TO_DEMOTE = 2;

  /** Docker container statuses that are not coming back. Everything else is patience. */
  private static final Set<String> TERMINAL_STATUSES = Set.of("exited", "dead");

  @Inject PdDeploymentRepository deployments;
  @Inject DeploymentDriver driver;

  /**
   * Consecutive dead observations per deployment id. In memory on purpose: it is a debounce, not a
   * fact about the world, and a restart that loses it simply spends two more passes agreeing. Keyed
   * by deployment id and pruned to the candidates of the latest pass, so it cannot grow with the
   * history. A concurrent map rather than a plain one because the pass and a shutdown are different
   * threads, not because two passes ever overlap — they cannot, the worker is single-threaded.
   */
  private final Map<String, Integer> strikes = new ConcurrentHashMap<>();

  /** One row worth observing, as plain values — the {@code Plan} stance: never an entity. */
  private record Candidate(
      String deploymentId,
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName,
      String detail) {}

  /**
   * One observation pass. Package-private so the suite drives it without the tick, exactly as {@link
   * DeployService#sweepInFlight()} is driven without a real StartupEvent.
   */
  void observeOnce() {
    List<Candidate> candidates =
        DbRetry.call(
            "The observation pass's candidate read",
            () -> QuarkusTransaction.requiringNew().call(this::candidates));
    Set<String> seen = new HashSet<>();
    for (Candidate candidate : candidates) {
      seen.add(candidate.deploymentId());
      // Outside every transaction: a docker call is a child process, and no bracket of this
      // component's own may span one.
      HealthGate.Poll observed = driver.observe(candidate.containerName());
      if (candidate.status() == PdDeploymentStatus.FAILED) {
        if (HealthGate.healthy(observed)) {
          recover(candidate, observed);
        }
        continue;
      }
      if (dead(observed)) {
        int strike = strikes.merge(candidate.deploymentId(), 1, Integer::sum);
        if (strike >= STRIKES_TO_DEMOTE) {
          demote(candidate, observed);
          strikes.remove(candidate.deploymentId());
        } else {
          LOG.debugf(
              "%s looks %s, and one pass is not a verdict — waiting for a second",
              candidate.containerName(), describe(observed));
        }
      } else {
        // Restarting, unhealthy, paused, created: all of them are the health gate's PENDING, and a
        // container that answered at all clears whatever the last pass thought.
        strikes.remove(candidate.deploymentId());
      }
    }
    strikes.keySet().retainAll(seen);
  }

  /**
   * The latest row of each (application, tier) that a container's state can say anything about: an
   * {@code ACTIVE} one that should still be serving, or a {@code FAILED} one that named a container.
   *
   * <p>The whole history is read and reduced here rather than asked of SQL, which is the same trade
   * {@code RollbackPins} makes: one ordered scan of a table with one row per deployment ever, and
   * the (application, tier) pair — whose tier half is null on the platform plane — is grouped in
   * Java where a null is an ordinary value rather than something {@code =} silently drops.
   */
  private List<Candidate> candidates() {
    List<Candidate> candidates = new ArrayList<>();
    Set<String> latestSeen = new HashSet<>();
    for (PdDeployment row : deployments.listAllNewestFirst()) {
      // Environment ids are UUIDs, so the empty string cannot collide with one.
      if (!latestSeen.add(
          row.applicationName + " " + (row.environmentId == null ? "" : row.environmentId))) {
        continue; // not the latest for its place — history, and history stays history
      }
      if (row.containerName == null || row.containerName.isBlank()) {
        continue; // nothing to observe: this row never got as far as a `docker run`
      }
      if (row.status == PdDeploymentStatus.ACTIVE || row.status == PdDeploymentStatus.FAILED) {
        candidates.add(
            new Candidate(
                row.id, row.applicationName, row.environmentId, row.status, row.containerName,
                row.detail));
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * A {@code FAILED} row whose container is in fact serving. The original failure text is
   * <b>appended, never erased</b> — it is the diagnosis of what went wrong at deploy time, and
   * eaa34fbc is exactly the row where that text is the whole reason anybody found the bug.
   */
  private void recover(Candidate candidate, HealthGate.Poll observed) {
    Instant at = Instant.now();
    List<String> retired =
        DbRetry.call(
            "The observed recovery of deployment " + candidate.deploymentId(),
            () ->
                QuarkusTransaction.requiringNew()
                    .call(
                        () -> {
                          PdDeployment row = deployments.findById(candidate.deploymentId());
                          if (row == null || row.status != PdDeploymentStatus.FAILED) {
                            return List.<String>of(); // deleted, or already settled by a deployment
                          }
                          List<String> stale = new ArrayList<>();
                          for (PdDeployment previous :
                              deployments.listActiveByApplication(
                                  candidate.applicationName(), candidate.environmentId())) {
                            if (previous.id.equals(row.id)) {
                              continue;
                            }
                            previous.status = PdDeploymentStatus.DECOMMISSIONED;
                            previous.finishedAt = at;
                            stale.add(previous.id);
                          }
                          row.status = PdDeploymentStatus.ACTIVE;
                          row.detail = recoveryDetail(candidate, observed, at);
                          row.finishedAt = at;
                          return List.copyOf(stale);
                        }));
    LOG.infof(
        "Recovered deployment %s by observation: %s is %s, so the row that said FAILED was wrong%s",
        candidate.deploymentId(),
        candidate.containerName(),
        describe(observed),
        retired.isEmpty() ? "" : " (retired " + retired.size() + " row(s) it never decommissioned)");
  }

  /** An {@code ACTIVE} row whose container two passes agree is gone. */
  private void demote(Candidate candidate, HealthGate.Poll observed) {
    Instant at = Instant.now();
    DbRetry.run(
        "The observed failure of deployment " + candidate.deploymentId(),
        () ->
            QuarkusTransaction.requiringNew()
                .run(
                    () -> {
                      PdDeployment row = deployments.findById(candidate.deploymentId());
                      if (row == null || row.status != PdDeploymentStatus.ACTIVE) {
                        return; // deleted, or replaced by a deployment while this pass ran
                      }
                      row.status = PdDeploymentStatus.FAILED;
                      row.detail = failureDetail(candidate, observed, at);
                      row.finishedAt = at;
                    }));
    LOG.warnf(
        "Deployment %s was ACTIVE, but %s is %s on %d consecutive observations — recorded FAILED."
            + " No container was touched: whatever still holds the alias is the next deployment's"
            + " predecessor.",
        candidate.deploymentId(), candidate.containerName(), describe(observed), STRIKES_TO_DEMOTE);
  }

  /** The recovery stamp, with the original failure text kept under it. */
  private static String recoveryDetail(Candidate candidate, HealthGate.Poll observed, Instant at) {
    String stamp =
        "[recovered by observation at "
            + at
            + ": container "
            + candidate.containerName()
            + " is "
            + describe(observed)
            + ", so this deployment is serving]";
    return candidate.detail() == null || candidate.detail().isBlank()
        ? stamp
        : stamp + "\n" + candidate.detail();
  }

  private static String failureDetail(Candidate candidate, HealthGate.Poll observed, Instant at) {
    return "[failed by observation at "
        + at
        + ": container "
        + candidate.containerName()
        + " is "
        + describe(observed)
        + " on "
        + STRIKES_TO_DEMOTE
        + " consecutive observations]";
  }

  /**
   * Whether the container is not coming back. Absent is one answer (docker cannot inspect it at all
   * — the gate's own "gone"), and a terminal status is the other. Everything docker still has a
   * state for and that is not terminal is PENDING, which is the health gate's rule and the reason a
   * restart loop recovering from the postgres-alias race is left alone.
   */
  private static boolean dead(HealthGate.Poll observed) {
    if (observed == null) {
      return false; // a driver that answered nothing has said nothing
    }
    if (observed.gone() != null) {
      return true;
    }
    return TERMINAL_STATUSES.contains(status(observed.state()));
  }

  /** Docker prints {@code <status>/<health>}; the status is what says whether it is running. */
  private static String status(String state) {
    if (state == null) {
      return "";
    }
    int slash = state.indexOf('/');
    return (slash < 0 ? state : state.substring(0, slash)).strip().toLowerCase(Locale.ROOT);
  }

  /** How an observation reads on a row and in a log line. */
  private static String describe(HealthGate.Poll observed) {
    if (observed == null) {
      return "unobservable";
    }
    if (observed.gone() != null) {
      return observed.gone().isBlank() ? "gone" : "gone (" + firstLine(observed.gone()) + ")";
    }
    return observed.state() == null || observed.state().isBlank() ? "stateless" : observed.state();
  }

  private static String firstLine(String output) {
    String trimmed = output.strip();
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? trimmed : trimmed.substring(0, newline);
  }
}
