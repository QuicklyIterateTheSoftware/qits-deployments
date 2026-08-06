package eu.wohlben.qits.platformdeployments.api;

import eu.wohlben.qits.platformdeployments.deployments.dto.PdDeploymentDto;
import eu.wohlben.qits.platformdeployments.deployments.dto.PdPinDto;
import eu.wohlben.qits.platformdeployments.environments.dto.PdApplicationDto;
import eu.wohlben.qits.platformdeployments.environments.dto.PdEnvironmentDto;
import eu.wohlben.qits.platformdeployments.environments.dto.PdLinkedServiceDto;
import eu.wohlben.qits.platformdeployments.environments.dto.PdServiceDto;
import eu.wohlben.qits.platformdeployments.environments.entity.PdDeploymentTarget;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type Jackson touches on this API. Several
 * controllers return {@code Response.entity(...)}, which hides the entity types from the build-time
 * analysis — so in the native binary, serialization fails at runtime with a 500 while every JVM
 * test stays green. Measured, not theoretical: qits-serviceregistry's first live {@code PUT
 * /services/{name}} answered 500 on exactly this. Some of these types happen to be reachable today
 * through other paths; they are all listed anyway, because which ones the analysis finds is an
 * implementation detail no test guards.
 */
@RegisterForReflection(
    targets = {
      PdEnvironmentController.CreateEnvironmentRequest.class,
      PdEnvironmentController.UpdateEnvironmentRequest.class,
      PdEnvironmentController.ApplicationSpec.class,
      PdEnvironmentController.EnvironmentResponse.class,
      PdEnvironmentController.ListEnvironmentsResponse.class,
      PdEnvironmentController.ListLinksResponse.class,
      PdServiceController.UpsertServiceRequest.class,
      PdServiceController.ServiceResponse.class,
      PdServiceController.ListServicesResponse.class,
      PdApplicationController.ListApplicationsResponse.class,
      PdDeploymentController.ListDeploymentsResponse.class,
      PdPinController.ListPinsResponse.class,
      PdEventController.BuildSucceededEvent.class,
      PdEnvironmentDto.class,
      PdApplicationDto.class,
      PdServiceDto.class,
      PdLinkedServiceDto.class,
      PdDeploymentDto.class,
      PdPinDto.class,
      PdDeploymentTarget.class
    })
final class ApiWireReflection {

  private ApiWireReflection() {}
}
