package eu.wohlben.qits.platform.deployments.deployments.control;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * The health gate's semantics, apart from the runtime calls that feed it: read a service's state
 * and say whether it is healthy — and, for a caller that has to wait for one itself, poll until it
 * is healthy, until the deadline, or until it is gone.
 *
 * <p><b>{@link #await} has no caller left</b>, and knowing why saves the next reader a search: it
 * was the docker path's cutover, which polled a fresh container itself. Swarm reaches its own
 * verdict ({@code UpdateStatus}), so the seam asks {@code awaitConverged} instead. What the
 * component still reads is {@link Poll} and {@link #healthy} — {@link DeploymentObserver} settles a
 * row on exactly that reading. The loop is kept because the patience below is a decision worth not
 * re-deriving, not because something needs it today.
 *
 * <p><b>Everything short of healthy is PENDING, and that is the whole of this class.</b> The gate
 * used to end the moment docker reported anything other than {@code running/…}: a {@code
 * restarting} container failed the deployment instantly, and so did a {@code running/unhealthy}
 * one. That was right while every application carried its store in-process and a container that
 * had already died was never coming back. It stopped being right when the platform moved onto
 * PostgreSQL. A container starts on its primary network only — {@code docker run} takes exactly one
 * — and the joins that make the postgres alias resolve happen after the start, so a PG-backed
 * application runs Flyway before its database has an address, dies with an acquisition timeout, and
 * is restarted seconds later by its {@code unless-stopped} policy into a world where the networks
 * are joined. The second boot succeeds. The old gate never saw it: it read {@code
 * restarting/unhealthy} once and failed a deployment that was about to work, which is what
 * qits-platform-idp's first PostgreSQL deployment did 18 seconds in.
 *
 * <p>So the only two verdicts that end the gate early are <b>healthy</b> and <b>gone</b>. Gone is a
 * container docker cannot inspect at all — removed underneath the deployment — and there is nothing
 * to wait for. Everything else is the deadline's to decide, and the deadline is the caller's
 * ({@code qits.platform.deployments.health-timeout-seconds}); this change does not extend it by a
 * second, it only stops spending it early.
 *
 * <p><b>The race itself is gone with the path.</b> A swarm service declares its whole membership
 * when it is created, so a first boot never runs before its peers are addressable. The patience
 * survives it: the same states are read one at a time by the observer, and a container that is
 * restarting or answering its probe with a failure is still not a dead deployment.
 */
public final class HealthGate {

  private HealthGate() {}

  /**
   * One observation of the service: docker's {@code <status>/<health>} string, or the reason it
   * could not be inspected at all.
   *
   * <p>The two are deliberately separate fields rather than a sentinel state string: "gone" is the
   * one answer that ends the gate early besides healthy, and reading it off a state that docker
   * itself never prints would be a wording match where a structural fact is available.
   */
  public record Poll(String state, String gone) {

    public static Poll of(String state) {
      return new Poll(state == null ? "" : state, null);
    }

    public static Poll gone(String detail) {
      return new Poll(null, detail == null ? "" : detail);
    }
  }

  /**
   * What a completed gate says: healthy, or why it gave up.
   *
   * <p>It lives here rather than on a driver interface because the gate is the domain's, and an
   * orchestrator that gates for itself never builds one. It was {@code
   * DeploymentDriver.HealthResult} while the seam carried docker's own vocabulary.
   */
  public record Result(boolean healthy, String detail) {}

  /**
   * The gate's one early success verdict, on its own so nothing has to restate it.
   *
   * <p>{@link DeploymentObserver} settles a row on exactly this reading — a {@code FAILED} row is
   * only recovered when the container would have passed the gate — and "healthy" spelled twice is
   * two things to keep in agreement. A container docker cannot inspect is never healthy, whatever
   * its last known state was.
   */
  public static boolean healthy(Poll observed) {
    return observed != null
        && observed.gone() == null
        && observed.state() != null
        && observed.state().endsWith("/healthy");
  }

  /**
   * Park until the container is healthy or the timeout expires.
   *
   * @param timeout the gate's whole budget — the caller's config, untouched by this class
   * @param poll how long to wait between observations
   * @param polls one observation of the container, called at least once
   * @param logs the container's log tail, read <b>only</b> when the gate is about to fail — it is
   *     the diagnosis, and fetching it on every poll would spend the deadline on docker calls
   */
  public static Result await(
      Duration timeout, Duration poll, Supplier<Poll> polls, Supplier<String> logs) {
    long deadline = System.nanoTime() + timeout.toNanos();
    String last = "(never inspected)";
    while (true) {
      Poll observed = polls.get();
      if (observed.gone() != null) {
        return new Result(false, "container vanished: " + observed.gone());
      }
      last = observed.state();
      if (healthy(observed)) {
        return new Result(true, null);
      }
      if (System.nanoTime() >= deadline) {
        break;
      }
      try {
        Thread.sleep(poll.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Result(false, "interrupted while waiting on the health gate");
      }
    }
    // The verdict names the state it gave up on, because "restarting" and "unhealthy" are two very
    // different bugs to go looking for: the first is a container dying and being restarted, the
    // second is one that is up and answering its probe with a failure.
    return new Result(
        false,
        "container still " + last + " after " + timeout.toSeconds() + "s\n" + logs.get());
  }
}
