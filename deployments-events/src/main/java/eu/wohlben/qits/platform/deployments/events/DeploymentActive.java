package eu.wohlben.qits.platform.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The deployment succeeded: this container passed the health gate, the cutover was recorded, and
 * this commit of this application is what serves now. <b>The one event on this bus that says a
 * change is live.</b>
 *
 * <p>Its common fields are {@link DeploymentQueued}'s, argued there.
 *
 * <p><b>{@code containerName} is the fact a consumer cannot derive.</b> The name is built from the
 * deployment id rather than the sha — re-deploying one commit must never collide with the container
 * it replaces — so it is knowable only from here. It is what a person greps the host for.
 *
 * <p><b>The wire alias is deliberately absent.</b> That is the address peers dial, and it is derived
 * from the environment and application names a consumer already has; putting it on the wire would
 * be a second spelling of a convention that has exactly one home in this component.
 *
 * <p>{@code occurredAt} is {@code finishedAt}, the value the cutover bookkeeping wrote on the row.
 * The predecessor it decommissioned announces nothing: a deployment being replaced is this event
 * seen from the other side, and a consumer that wants "what is live" reads the newest event per
 * (application, tier).
 */
public record DeploymentActive(
    UUID eventId,
    String deploymentId,
    String applicationName,
    String environmentId,
    String environmentName,
    String commitSha,
    String runId,
    String containerName,
    Instant finishedAt)
    implements QitsEvent {

  public DeploymentActive {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public DeploymentActive(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String commitSha,
      String runId,
      String containerName,
      Instant finishedAt) {
    this(
        null,
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        commitSha,
        runId,
        containerName,
        finishedAt);
  }

  @Override
  public Instant occurredAt() {
    return finishedAt;
  }
}
