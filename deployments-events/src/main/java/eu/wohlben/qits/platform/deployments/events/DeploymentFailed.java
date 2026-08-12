package eu.wohlben.qits.platform.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The deployment ended badly: nothing was cut over, and whatever was serving before still is. Every
 * terminal outcome that is not {@link DeploymentActive} arrives as this event.
 *
 * <p>Its common fields are {@link DeploymentQueued}'s, argued there.
 *
 * <p><b>{@code status} is a string carrying the deployer's own terminal state</b> — {@code FAILED}
 * or {@code IMAGE_MISSING} — rather than two event types or a boolean. The two are one thing to a
 * consumer ("this did not go live") and different things to a person: an image the registry does not
 * have yet is the expected outcome for an application nothing publishes, and folding it into {@code
 * FAILED} would page somebody for it. A string rather than an enum because an enum on the wire is a
 * shared type, and cross-context references here are plain values.
 *
 * <p><b>{@code detail} is docker's own output or this component's own sentence, and it is
 * nullable.</b> It is a diagnosis for a person, never something to parse — the wording is whatever
 * the failing tool said and is not a contract. A null field is omitted from the canonical payload
 * rather than written as an explicit null.
 *
 * <p>{@code containerName} is absent, unlike on {@link DeploymentActive}: most failures happen
 * before a container exists, and the ones that do not have already removed it by the time this is
 * published. A name that resolves to nothing is worse than no name.
 *
 * <p>{@code occurredAt} is {@code finishedAt}, the value the outcome bookkeeping wrote on the row.
 */
public record DeploymentFailed(
    UUID eventId,
    String deploymentId,
    String applicationName,
    String environmentId,
    String environmentName,
    String commitSha,
    String runId,
    String status,
    String detail,
    Instant finishedAt)
    implements QitsEvent {

  public DeploymentFailed {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public DeploymentFailed(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String commitSha,
      String runId,
      String status,
      String detail,
      Instant finishedAt) {
    this(
        null,
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        commitSha,
        runId,
        status,
        detail,
        finishedAt);
  }

  @Override
  public Instant occurredAt() {
    return finishedAt;
  }
}
