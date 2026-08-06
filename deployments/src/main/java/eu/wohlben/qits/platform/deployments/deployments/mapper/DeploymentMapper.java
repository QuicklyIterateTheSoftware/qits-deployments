package eu.wohlben.qits.platform.deployments.deployments.mapper;

import eu.wohlben.qits.platform.deployments.deployments.dto.PdDeploymentDto;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.environments.control.ApplicationKeys;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The deployment wire shape. Hand-written rather than a MapStruct interface for one reason: {@code
 * applicationId} is not a column. It is derived from the row's {@code (environmentId,
 * applicationName)} pair through {@link ApplicationKeys}, which is the same definition the
 * applications listing derives its own id from — that shared derivation is what lets a client keep
 * joining the two listings when neither side has a row to take an id from.
 */
@ApplicationScoped
public class DeploymentMapper {

  public PdDeploymentDto toDto(PdDeployment deployment) {
    return new PdDeploymentDto(
        deployment.id,
        ApplicationKeys.of(deployment.environmentId, deployment.applicationName),
        deployment.applicationName,
        deployment.commitSha,
        deployment.runId,
        deployment.status,
        deployment.containerName,
        deployment.detail,
        deployment.createdAt,
        deployment.finishedAt);
  }
}
