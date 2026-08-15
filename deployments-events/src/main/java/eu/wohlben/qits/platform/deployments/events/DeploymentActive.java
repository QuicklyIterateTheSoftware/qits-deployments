package eu.wohlben.qits.platform.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
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
 * <p><b>{@code endpoints} is the complete, immutable routing snapshot.</b> It includes the resolved
 * wire alias rather than asking consumers to reimplement a deployer convention. An empty list is a
 * service that declares no public routes; consumers replace, never merge, the prior snapshot.
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
    Instant finishedAt,
    List<DeploymentEndpoint> endpoints)
    implements QitsEvent {

  public DeploymentActive {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
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
        finishedAt,
        List.of());
  }

  /** The constructor a publisher uses when this deployment exposes public endpoints. */
  public DeploymentActive(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String commitSha,
      String runId,
      String containerName,
      Instant finishedAt,
      List<DeploymentEndpoint> endpoints) {
    this(
        null,
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        commitSha,
        runId,
        containerName,
        finishedAt,
        endpoints);
  }

  @Override
  public Instant occurredAt() {
    return finishedAt;
  }
}
