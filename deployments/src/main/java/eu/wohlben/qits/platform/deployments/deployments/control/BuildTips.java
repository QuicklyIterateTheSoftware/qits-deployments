package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.platform.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.platform.deployments.environments.entity.PdEnvironment;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Is this green build still the newest one for its repository and branch?
 *
 * <p><b>Why the question exists at all.</b> The HTTP intake is a live POST: what arrives is what
 * just happened. The bus is not. A durable consumer is caught up from qits-events' log after a
 * disconnect, a restart or a cutover, so it can be handed a build that finished <em>before</em> one
 * it has already deployed — and a handler that simply applied whatever arrived would, one restart
 * later, roll a stale commit over a newer one. The library says so in
 * {@code QitsDurableEventListener}'s javadoc and names this component as the worked example:
 * collapsing to the tip is the consumer's job, because only the consumer knows which of its effects
 * commute.
 *
 * <p>This is not about duplicates. The same event arriving twice is impossible — the library claims
 * each (listener, event id) pair exactly once. This is about <b>different, older</b> events.
 *
 * <h2>What "the tip" is measured against, and why it takes two answers</h2>
 *
 * <p>The floor is the newest build this component already acted on for that {@code (repoId,
 * branch)}, and there are two ways to know it, each covering what the other cannot:
 *
 * <ul>
 *   <li><b>What this process announced</b>, remembered in a map. It is the build's own finish time
 *       against the arriving build's own finish time — the same clock, the same kind of instant —
 *       so it is exact. It is what makes two green builds seconds apart both deploy, in order.
 *   <li><b>The newest deployment row</b> for that application in the tiers listening to that
 *       branch, consulted only when the map has nothing. That is the cross-restart floor: a process
 *       that has just booted remembers nothing, and a catch-up sweep at startup is precisely when a
 *       stale event arrives.
 * </ul>
 *
 * <p>The two are asked in that order rather than combined, and the order is the point. A deployment
 * row is stamped when the row is <em>written</em> — after the spec read, behind whatever the worker
 * was already doing — so it is minutes younger than the build it describes and cannot be compared
 * to a build's finish time without skipping builds that are genuinely newer. It is a floor of last
 * resort, correct when nothing better is known and wrong the moment something is.
 *
 * <p><b>The residual, stated rather than hidden.</b> A restart landing in the seconds between a
 * build finishing and its deployment row being written leaves the row describing the build before
 * it, so the newer build can be read as stale and skipped, with the log line saying so. The cure is
 * the HTTP intake, which is unguarded by design and is what a bootstrap replays through. A ledger of
 * announced tips in this component's own schema would close it exactly; it is not here because it
 * would be a table to answer a question two existing ones nearly answer.
 *
 * <p>The manual door writes nothing here and is deliberately not guarded by it: an operator posting
 * a commit to the intake is choosing that commit.
 */
@ApplicationScoped
public class BuildTips {

  @Inject PdDeploymentRepository deployments;

  @Inject EnvironmentService environments;

  /**
   * The newest build finish time this process has announced, per {@code (repoId, branch)}.
   *
   * <p>In memory on purpose, and bounded by how many branches deploy on this platform. It is a
   * collapse aid rather than a fact — losing it costs a restart the exactness above and falls back
   * to the deployment row, which is the arrangement the strike counter in {@code
   * DeploymentObserver} is written the same way for.
   */
  private final Map<String, Instant> announced = new HashMap<>();

  /**
   * Whether any tier listens to this branch at all — the question that decides whether a bus event
   * is worth claiming a row for.
   *
   * <p>Most green builds on the platform are on branches no environment listens to, and an event
   * this answers {@code false} for is stored nowhere. It is asked before the claim, so it runs
   * outside the library's transaction and takes one of its own.
   */
  public boolean anyTierListensTo(String branch) {
    return !QuarkusTransaction.requiringNew().call(() -> environments.onBranch(branch)).isEmpty();
  }

  /**
   * Claim this build as the tip for its repository and branch: true if it is the newest one seen,
   * false if something newer was already announced or deployed.
   *
   * <p>Synchronized because the two delivery channels are two threads — the stream's socket worker
   * and the catch-up sweeper — and the read of the floor and the write of the new one have to be
   * one step, or a burst of catch-up rows can each pass a floor none of them then raises.
   *
   * @param finishedAt when the build finished, which is the event's own {@code occurredAt}
   */
  public synchronized boolean claim(String repoId, String branch, Instant finishedAt) {
    if (finishedAt == null) {
      // An event with no time cannot be ordered against anything. Deploying it is the safe half of
      // the trade — the guard exists to stop a stale build, not to stop an odd one.
      return true;
    }
    String key = repoId + "\n" + branch;
    Instant floor = announced.get(key);
    if (floor == null) {
      floor = newestDeployedAt(repoId, branch);
    }
    if (floor != null && !finishedAt.isAfter(floor)) {
      return false;
    }
    announced.put(key, finishedAt);
    return true;
  }

  /**
   * When this application was last handed a deployment in a tier that listens to this branch, or
   * null if it never was.
   *
   * <p><b>In a transaction of its own.</b> The caller is inside the library's claim transaction,
   * which has the {@code eventstream} datasource enlisted; joining it would put a second
   * non-XA resource in one transaction, which Narayana refuses. Suspending it is also the honest
   * shape: this is a read about the world, not part of the claim.
   */
  private Instant newestDeployedAt(String repoId, String branch) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<PdEnvironment> tiers = environments.onBranch(branch);
              Set<String> ids = new LinkedHashSet<>();
              boolean platform = false;
              for (PdEnvironment tier : tiers) {
                ids.add(tier.id);
                platform |= tier.platform;
              }
              // The platform plane is included when the platform tier listens to the branch,
              // because that is exactly when a build rolls it — and its rows carry no environment
              // id, so no tier filter can reach them.
              Optional<PdDeployment> newest = deployments.newestInPlaces(repoId, ids, platform);
              return newest.map(deployment -> deployment.createdAt).orElse(null);
            });
  }
}
