package eu.wohlben.qits.platform.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A deployment was scheduled: this commit of this application is going to this place. The first of
 * the four points a deployment announces, and the only one that says nothing about an outcome yet.
 *
 * <p>One event per created row. A green build that addresses three tiers queues three deployments
 * and publishes three of these, all naming the same {@code commitSha} and {@code runId} and each
 * naming its own {@code deploymentId} — which is what a consumer counts and later matches the
 * terminal events against.
 *
 * <p><b>The five fields below are the same five on all four events</b>, so they are argued once
 * here:
 *
 * <ul>
 *   <li>{@code deploymentId} is the identity a consumer follows across the lifecycle. The other
 *       three events carry it and nothing else joins them.
 *   <li>{@code applicationName} is the catalogue's own name for the service — the same string the
 *       repository is called, which is how this platform names an application.
 *   <li>{@code environmentId} and {@code environmentName} are <b>both null on the platform
 *       plane</b>, and that is a statement rather than a gap: a platform service belongs to no tier.
 *       Both are here because the id is what a machine matches on and the name is what a person
 *       reads, and a consumer that had only the id would have to ask this component for the other.
 *   <li>{@code runId} is the qits-ci run whose green build caused this, verbatim and resolved
 *       against nothing — a reader takes it to qits-ci. Null when the announcement door carried
 *       none, which a manual replay may.
 * </ul>
 *
 * <p><b>There is no version and no image digest, and neither is an omission.</b> This component
 * identifies a build by its commit sha end to end: the image ref is derived from the sha, no calver
 * stamp reaches a deployment row, and the digest of what was pulled is never read back. Adding a
 * field the deployer does not hold would put an empty value on the wire for every consumer to learn
 * to ignore.
 *
 * <p>{@code occurredAt} is {@code queuedAt} — the row's own {@code createdAt}, not the moment
 * publish was called. The two differ by however long the queueing transaction took, and the one
 * that belongs in an event log is when the thing happened.
 *
 * <p>{@code eventId} is a component and that is safe: it is generated when absent, final once set,
 * and kept out of the payload by the library rather than by anything spelled here — {@code
 * CanonicalJson} excludes everything {@link QitsEvent} declares. Identity travels in the envelope,
 * which is also why reading a payload back yields a fresh id.
 */
public record DeploymentQueued(
    UUID eventId,
    String deploymentId,
    String applicationName,
    String environmentId,
    String environmentName,
    String commitSha,
    String runId,
    Instant queuedAt)
    implements QitsEvent {

  public DeploymentQueued {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public DeploymentQueued(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String commitSha,
      String runId,
      Instant queuedAt) {
    this(
        null,
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        commitSha,
        runId,
        queuedAt);
  }

  @Override
  public Instant occurredAt() {
    return queuedAt;
  }
}
