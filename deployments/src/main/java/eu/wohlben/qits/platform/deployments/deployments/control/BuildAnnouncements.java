package eu.wohlben.qits.platform.deployments.deployments.control;

import java.util.UUID;

/**
 * How a green build reaches the deploy orchestration. One method, implemented by {@link
 * DeployService}, called by whatever door the announcement came through.
 *
 * <p><b>Wave 3 has landed, and there are two doors now.</b> Neither wins, because they are not in
 * competition: both funnel into {@link #announce} and everything downstream of it — the spec read,
 * derived registration, the queue, the health-gated cutover — cannot tell them apart and is
 * unchanged. This interface is unchanged too, which is what it existed ahead of the consumer for.
 *
 * <ul>
 *   <li>{@code POST /platform-deployments/api/events/build-succeeded} — the direct HTTP intake
 *       ({@code api/PdEventController}). It stays, and it stays the <b>manual and bootstrap</b>
 *       door: a bootstrap replays a lost event through it by hand, and it is the door that still
 *       works before qits-events exists. It is fire-and-forget and nobody retries it.
 *   <li>The bus ({@code bus/PdBuildSuccessfulSubscriber}) — a durable consumer of qits-ci's
 *       {@code BuildSuccessful}, which is the door a deployment should follow from: the publisher
 *       retries it, the log replays it after a cutover, and the eventstream library hands it over
 *       exactly once per event whichever channel delivered it.
 * </ul>
 *
 * <p><b>Idempotency is unchanged by the second door and was never this seam's job.</b> Two
 * announcements of one commit are two deployments of one commit, exactly as two POSTs always were:
 * the container is named after the deployment rather than the sha, the predecessor search finds the
 * first one and cuts it over. What the bus adds is a guarantee the POST never had — an event is not
 * lost when nobody was listening — and one obligation the POST never had, which is ordering. A
 * replayed event can be <em>older</em> than one already deployed, so the subscriber collapses to
 * the tip ({@link BuildTips}) before it calls this. That check belongs to the door, not here: the
 * manual door is an operator choosing a commit, and guarding it would be refusing the choice.
 *
 * <p>Whichever door delivers it, the triple that drives the deployment is {@code (repository,
 * branch, commitSha)} and the run id is a pointer nothing resolves.
 *
 * <p><b>The repository arrives as a {@link RepositoryRef} rather than as one string</b>, because
 * the platform has two coordinate systems now: an opaque storage UUID, and the public {@code
 * (projectId, repoName)} pair. The application name — the image tag, the wire alias, the container
 * name, the GC pin key — is resolved from the pair exactly ONCE, here at the intake, and is carried
 * by value from there on. Nothing below this seam passes a repository id where a name is meant.
 *
 * <p><b>The one thing that has changed here, and why it had to.</b> The signature carries a fifth
 * value now, {@code causationId} — the event this announcement is the effect of, recorded on every
 * row it produces ({@code PdDeployment.causationId}). It is a parameter rather than something the
 * far side reads off an ambient scope, because everything downstream runs on {@code
 * pd-deploy-worker} and an executor hop is exactly where {@code CausationScope} — a plain
 * ThreadLocal — dies. Each door knows the answer on its own thread and states it: the subscriber
 * passes the frame's id, the HTTP intake passes the scope the causation filter restored from the
 * caller's header. A plain {@code UUID} keeps the domain modules holding no bus type but the
 * persistence trio.
 */
public interface BuildAnnouncements {

  /**
   * One green pipeline for one commit. Returns as soon as the event is accepted: the deployment
   * runs on this component's own worker, and the announcer — a fire-and-forget POST or a bus
   * subscriber — has nothing to do with the outcome. A bus subscriber in particular must return
   * from its handler rather than wait: it is holding the claim transaction open while it does.
   *
   * @param runId the qits-ci run that produced the image, optional and resolved against nothing
   * @param repository which repository went green, in both coordinate systems — the storage id
   *     always, the public {@code (projectId, repoName)} pair when the event carried one. Its
   *     {@link RepositoryRef#applicationName()} is what this deployment is named after.
   * @param causationId the event this announcement is the effect of, recorded on every row it
   *     produces. Null is a rootless announcement — a bootstrap's hand-made POST — and never a
   *     reason to refuse one: causation is advisory and a deployment must not fail over a column
   *     only the trace graph reads.
   * @throws eu.wohlben.qits.platform.deployments.environments.error.BadRequestException if any of
   *     the identifiers could escape an argv or overrun its column
   */
  void announce(
      String runId, RepositoryRef repository, String branch, String commitSha, UUID causationId);
}
