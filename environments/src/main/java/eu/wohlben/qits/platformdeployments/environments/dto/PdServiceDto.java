package eu.wohlben.qits.platformdeployments.environments.dto;

import eu.wohlben.qits.platformdeployments.environments.entity.PdDeploymentTarget;
import java.time.Instant;
import java.util.List;

/**
 * One service, flattened with the environments it is linked into.
 *
 * <p>{@code environmentIds} is empty exactly when {@code target} is {@code PLATFORM} — a
 * cross-environment service is linked nowhere in particular and therefore present everywhere — and
 * {@code branch} is the mirror image: only a platform service carries its own.
 *
 * <p>The ids round-trip: what is read here is what {@code PUT
 * /platform-deployments/api/services/{name}} accepts back, so a caller can read a service, change
 * one link and write it whole.
 */
public record PdServiceDto(
    String id,
    String name,
    PdDeploymentTarget target,
    String branch,
    boolean availableOnEnv,
    String healthPath,
    Instant createdAt,
    List<String> environmentIds) {}
