package eu.wohlben.qits.platformdeployments.deployments.control;

/**
 * How a green build reaches the deploy orchestration. One method, implemented by {@link
 * DeployService}, called by whatever door the announcement came through.
 *
 * <p>Today there is exactly one door: {@code POST /platform-deployments/api/events/build-succeeded},
 * the direct HTTP intake qits-ci's notifier has always posted to. It stays, and it stays the
 * transitional and the manual door — the bootstrap replays lost events through it by hand, and a
 * fire-and-forget POST is what qits-ci sends.
 *
 * <p><b>The target model is the bus, and it is wave 3 — deliberately not this repo's dependency
 * yet.</b> qits-ci already publishes {@code BuildSuccessful} and {@code SoftwareRelease} onto
 * qits-events, and a deployment should follow from those rather than from a point-to-point call
 * that no one retries and no one can replay. What lands then is one class in {@code service/} — a
 * bus subscriber that decodes an event and calls {@link #announce} — plus the eventstream
 * dependency it needs. Nothing in this interface changes: the seam is the reason it exists ahead of
 * the consumer.
 *
 * <p>What is NOT here, on purpose:
 *
 * <ul>
 *   <li>no eventstream dependency, and no client for one. Adding it before the subscriber exists
 *       would put a peer on the boot path for a capability nothing uses.
 *   <li>no stub, no fake and no test double for a bus. The suites drive the HTTP intake, which is
 *       the door that ships; a double for a subscriber nobody wrote would assert a shape wave 3 is
 *       still free to choose.
 * </ul>
 *
 * <p>Whichever door delivers it, the triple that drives the deployment is {@code (repoId, branch,
 * commitSha)} and the run id is a pointer nothing resolves — so a bus event carrying the same four
 * values needs no new contract here.
 */
public interface BuildAnnouncements {

  /**
   * One green pipeline for one commit. Returns as soon as the event is accepted: the deployment
   * runs on this component's own worker, and the announcer — a fire-and-forget POST today, a bus
   * subscriber tomorrow — has nothing to do with the outcome.
   *
   * @param runId the qits-ci run that produced the image, optional and resolved against nothing
   * @throws eu.wohlben.qits.platformdeployments.environments.error.BadRequestException if any of
   *     the identifiers could escape an argv or overrun its column
   */
  void announce(String runId, String repoId, String branch, String commitSha);
}
