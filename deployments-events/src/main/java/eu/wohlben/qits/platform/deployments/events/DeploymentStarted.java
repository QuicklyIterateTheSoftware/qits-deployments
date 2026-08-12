package eu.wohlben.qits.platform.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Execution began: the deploy worker took this queued deployment and moved it to {@code STARTING}.
 * Everything slow — the pull, the cutover, the health gate — happens after this and before the
 * terminal event.
 *
 * <p>Its fields are {@link DeploymentQueued}'s, argued there. What it adds is a second point in
 * time, which is what makes the queue wait measurable: deployments are serialized on one worker, so
 * the gap between a queue event and its start event is how long this one waited behind the others.
 *
 * <p><b>{@code startedAt} is read off a clock rather than off the row, and it is the one timestamp
 * here that is.</b> The deployment row carries {@code created_at} and {@code finished_at} and no
 * column for the {@code STARTING} transition, so there is nothing to read back. It is taken inside
 * the transaction that writes the transition, so it is still when the thing happened and not when
 * the announcement was made — the property that matters — and a column would only be a second copy
 * of it. If one is ever added, this field reads it and nothing else changes.
 *
 * <p>There is no container name yet: the container is named after the deployment and created some
 * way further on, past the pull. {@link DeploymentActive} is where it appears.
 */
public record DeploymentStarted(
    UUID eventId,
    String deploymentId,
    String applicationName,
    String environmentId,
    String environmentName,
    String commitSha,
    String runId,
    Instant startedAt)
    implements QitsEvent {

  public DeploymentStarted {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public DeploymentStarted(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String commitSha,
      String runId,
      Instant startedAt) {
    this(
        null,
        deploymentId,
        applicationName,
        environmentId,
        environmentName,
        commitSha,
        runId,
        startedAt);
  }

  @Override
  public Instant occurredAt() {
    return startedAt;
  }
}
