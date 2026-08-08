package eu.wohlben.qits.platform.deployments.environments.dto;

import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import java.time.Instant;
import java.util.List;

/**
 * One service, flattened with the environments it is linked into.
 *
 * <p>{@code environmentIds} is empty exactly when {@code target} is {@code PLATFORM} — a
 * cross-environment service is linked nowhere in particular and therefore present everywhere.
 *
 * <p>{@code branch} is <b>vestigial</b> and reads null on everything derived registration writes:
 * both planes deploy off {@code environment/<name>} now, so a service has no deploy ref of its own
 * to report. See {@code PdService.branch}.
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
