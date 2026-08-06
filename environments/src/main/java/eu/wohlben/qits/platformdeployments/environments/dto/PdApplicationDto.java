package eu.wohlben.qits.platformdeployments.environments.dto;

import eu.wohlben.qits.platformdeployments.environments.entity.PdDeploymentTarget;
import java.time.Instant;

/**
 * One deployable application, flattened into one tier — the shape the web client reads.
 *
 * <p>{@code environmentId} and {@code environmentName} are null exactly when {@code target} is
 * {@code PLATFORM} — a cross-environment application belongs to no tier — and {@code branch} is the
 * mirror image: only a platform service carries its own, because an environment service takes its
 * environment's.
 *
 * <p>{@code id} is DERIVED from {@code (environmentId, name)} ({@code ApplicationKeys}) rather than
 * being the service row's id, because a service has one row across every tier while this listing
 * has one entry per tier — and the client joins it against a deployment's {@code applicationId},
 * which is derived from the same pair on the other side.
 *
 * <p>{@code repoId} repeats {@code name}. There is one identity for a service, and derived
 * registration has always named an application after its repository; the field stays so the
 * client's existing column keeps resolving.
 */
public record PdApplicationDto(
    String id,
    String repoId,
    String name,
    String environmentId,
    String environmentName,
    PdDeploymentTarget target,
    boolean availableOnEnv,
    String branch,
    String healthPath,
    Instant createdAt) {}
