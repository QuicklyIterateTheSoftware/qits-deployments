package eu.wohlben.qits.platform.deployments.deployments.dto;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import java.time.Instant;

/**
 * One recorded deployment attempt; {@code applicationName} denormalized for legible listings.
 *
 * <p>{@code applicationId} is DERIVED from {@code (environmentId, applicationName)} — the same
 * definition the applications listing uses on its side — because a deployment row points at no
 * service row and a client joins the two listings on that id.
 *
 * <p>{@code runId} is the qits-ci run that caused this deployment, and it may be null: a sender is
 * free to omit it. A client renders the commit as a link to {@code /ci/runs/<runId>} when it is set
 * and as plain text when it is not; there is no other way to reach the build from here.
 */
public record PdDeploymentDto(
    String id,
    String applicationId,
    String applicationName,
    String commitSha,
    String runId,
    PdDeploymentStatus status,
    String containerName,
    String detail,
    Instant createdAt,
    Instant finishedAt) {}
