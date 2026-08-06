package eu.wohlben.qits.platformdeployments.api;

import eu.wohlben.qits.platformdeployments.deployments.control.DeployService;
import eu.wohlben.qits.platformdeployments.deployments.control.EnvironmentOperations;
import eu.wohlben.qits.platformdeployments.deployments.dto.PdDeploymentDto;
import eu.wohlben.qits.platformdeployments.deployments.mapper.DeploymentMapper;
import eu.wohlben.qits.platformdeployments.environments.error.BadRequestException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The deployment read surface. The deployment is the entity and the environment is a required
 * <b>filter</b> ({@code ?environmentId=}), the ci-runs shape: an unscoped listing would return
 * every deployment on the instance, and a missing environment must say so (404) rather than answer
 * with an empty list.
 *
 * <p><b>Platform deployments are not reachable here</b>, and that is the known cost of the filter:
 * they belong to no tier, so there is no environmentId to ask with. A reader after them takes
 * {@code GET /applications} for the row and the container naming for the rest. It is a real gap —
 * the bootstrap watched platform-plane liveness through docker for exactly this reason — and
 * closing it is a follow-up, not a silent widening of this route.
 */
@Path("/deployments")
@Produces(MediaType.APPLICATION_JSON)
public class PdDeploymentController {

  @Inject DeployService deployService;
  @Inject EnvironmentOperations environments;
  @Inject DeploymentMapper mapper;

  public record ListDeploymentsResponse(List<PdDeploymentDto> deployments) {}

  @GET
  @Operation(summary = "An environment's recorded deployments, newest-first")
  @APIResponse(responseCode = "200", description = "The deployments")
  @APIResponse(responseCode = "400", description = "environmentId was not given")
  @APIResponse(responseCode = "404", description = "No such environment")
  public ListDeploymentsResponse list(@QueryParam("environmentId") String environmentId) {
    if (environmentId == null || environmentId.isBlank()) {
      throw new BadRequestException("environmentId is required");
    }
    environments.require(environmentId);
    return new ListDeploymentsResponse(
        deployService.deploymentsFor(environmentId).stream().map(mapper::toDto).toList());
  }
}
