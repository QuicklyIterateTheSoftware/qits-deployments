package eu.wohlben.qits.platform.deployments.deployments.control;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * The health gate's semantics, apart from the docker calls that feed it: poll a container's state
 * until it is healthy, until the deadline, or until the container is gone.
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
 * <p><b>The follow-up this makes optional rather than urgent</b>: {@code docker create} → {@code
 * network connect} for every join → {@code docker start} would put the container on all its
 * networks before its first boot, so the race would not happen at all. It is a restructure across
 * the driver seam — the argv stops being a {@code run}, {@code StartSpec} grows the join set, and
 * the self-update handoff and the cutover's call-order assertions all move with it — and a patient
 * gate makes the race self-heal without any of that. Recorded, not done.
 */
public final class HealthGate {

  private HealthGate() {}

  /**
   * One observation of the container: docker's {@code <status>/<health>} string, or the reason it
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
  public static DeploymentDriver.HealthResult await(
      Duration timeout, Duration poll, Supplier<Poll> polls, Supplier<String> logs) {
    long deadline = System.nanoTime() + timeout.toNanos();
    String last = "(never inspected)";
    while (true) {
      Poll observed = polls.get();
      if (observed.gone() != null) {
        return new DeploymentDriver.HealthResult(
            false, "container vanished: " + observed.gone());
      }
      last = observed.state();
      if (healthy(observed)) {
        return new DeploymentDriver.HealthResult(true, null);
      }
      if (System.nanoTime() >= deadline) {
        break;
      }
      try {
        Thread.sleep(poll.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new DeploymentDriver.HealthResult(
            false, "interrupted while waiting on the health gate");
      }
    }
    // The verdict names the state it gave up on, because "restarting" and "unhealthy" are two very
    // different bugs to go looking for: the first is a container dying and being restarted, the
    // second is one that is up and answering its probe with a failure.
    return new DeploymentDriver.HealthResult(
        false,
        "container still " + last + " after " + timeout.toSeconds() + "s\n" + logs.get());
  }
}
