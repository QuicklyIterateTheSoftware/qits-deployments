package eu.wohlben.qits.platformdeployments.environments.mapper;

import eu.wohlben.qits.platformdeployments.environments.control.ApplicationKeys;
import eu.wohlben.qits.platformdeployments.environments.control.ServiceCatalog.ApplicationView;
import eu.wohlben.qits.platformdeployments.environments.dto.PdApplicationDto;
import eu.wohlben.qits.platformdeployments.environments.dto.PdEnvironmentDto;
import eu.wohlben.qits.platformdeployments.environments.dto.PdLinkedServiceDto;
import eu.wohlben.qits.platformdeployments.environments.dto.PdServiceDto;
import eu.wohlben.qits.platformdeployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.platformdeployments.environments.entity.PdService;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The topology's wire shapes. Hand-written rather than a MapStruct interface because none of these
 * is a field-for-field copy: two of them derive an id the entity does not carry, and one flattens a
 * service into a tier it has no column for.
 */
@ApplicationScoped
public class EnvironmentMapper {

  /** Applications are attached explicitly by the boundary; listings leave them null. */
  public PdEnvironmentDto toDto(PdEnvironment environment) {
    return toDto(environment, null);
  }

  public PdEnvironmentDto toDto(PdEnvironment environment, List<ApplicationView> applications) {
    return new PdEnvironmentDto(
        environment.id,
        environment.name,
        environment.branch,
        environment.network,
        environment.createdAt,
        applications == null ? null : applications.stream().map(this::toDto).toList());
  }

  /**
   * The environment is flattened rather than nested: a platform service has none, and a listing
   * that mixes both wants one shape.
   */
  public PdApplicationDto toDto(ApplicationView view) {
    PdService service = view.service();
    return new PdApplicationDto(
        ApplicationKeys.of(view.environmentId(), service.name),
        service.name,
        service.name,
        view.environmentId(),
        view.environmentName(),
        service.deploymentTarget,
        service.availableOnEnv,
        service.branch,
        service.healthPath,
        service.createdAt);
  }

  /** The full catalogue shape: a service and the environments it is linked into. */
  public PdServiceDto toDto(PdService service, List<String> environmentIds) {
    return new PdServiceDto(
        service.id,
        service.name,
        service.deploymentTarget,
        service.branch,
        service.availableOnEnv,
        service.healthPath,
        service.createdAt,
        environmentIds);
  }

  /** The pull query's shape — presence and what to reconcile with, nothing else. */
  public PdLinkedServiceDto toLinkDto(PdService service) {
    return new PdLinkedServiceDto(
        service.id,
        service.name,
        service.deploymentTarget,
        service.availableOnEnv,
        service.healthPath);
  }
}
