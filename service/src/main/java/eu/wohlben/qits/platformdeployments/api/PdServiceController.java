package eu.wohlben.qits.platformdeployments.api;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.platformdeployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.platformdeployments.environments.dto.PdServiceDto;
import eu.wohlben.qits.platformdeployments.environments.entity.PdDeploymentTarget;
import eu.wohlben.qits.platformdeployments.environments.mapper.EnvironmentMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The service catalogue: one {@code PUT} that states a service whole, and the flat read.
 *
 * <p><b>Why an upsert and not a create/update pair.</b> What is written is derived — a green build
 * reads a repository's {@code .config/qits/deployments.yml} at that sha and states the shape it
 * found there. A caller holding the whole file has nothing partial to say, so the name in the path
 * is the key and the body is the entire row. That also makes the write idempotent, which matters
 * because a green build fans out over every environment tracking a branch.
 *
 * <p><b>The usual writer is in-process.</b> Derived registration calls {@code ServiceCatalog}
 * directly — it is a local transaction now, not an HTTP round trip onto another service. This
 * surface stays because it is the operator's door onto the same rules: the remediation the refused
 * plane flip points at is a {@code DELETE} here, and reading the catalogue is what the web client
 * and a person do.
 *
 * <p><b>Both writes call {@link MachineAuth#require()}; the read does not.</b> The read is driven
 * by people through the client and by anything reconciling, neither of which would survive a guard
 * the day the gate flips on.
 */
@Path("/services")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PdServiceController {

  @Inject MachineAuth machineAuth;
  @Inject ServiceCatalog catalog;
  @Inject EnvironmentMapper mapper;

  /**
   * The whole of a service.
   *
   * <p>{@code environmentIds} <b>replaces</b> the link set — it is not a delta, and an empty or
   * absent list on an environment service unlinks it everywhere. On a {@code PLATFORM} service it
   * must be absent or empty: a platform service is present in every environment by having no links,
   * so naming some would say the opposite of what it means, and the answer is a 400 rather than a
   * silent drop.
   *
   * <p>{@code branch} is a platform service's own deploy branch. Beside {@code ENVIRONMENT} it is
   * accepted and ignored — an environment service takes each environment's branch — which is the
   * same tolerance the spec parser gives the equivalent key.
   */
  public record UpsertServiceRequest(
      PdDeploymentTarget deploymentTarget,
      String branch,
      boolean availableOnEnv,
      String healthPath,
      List<String> environmentIds) {}

  public record ServiceResponse(PdServiceDto service) {}

  public record ListServicesResponse(List<PdServiceDto> services) {}

  /**
   * Register or update one service, whole. 201 the first time a name is seen, 200 afterwards.
   *
   * <p>A flip between planes is asymmetric: {@code ENVIRONMENT} → {@code PLATFORM} converts and
   * drops the links, which is the one-time migration a service goes through when it becomes
   * cross-environment; {@code PLATFORM} → {@code ENVIRONMENT} is refused with the remediation in
   * the message, because a platform service has no set of environments to go back to and a guessed
   * one would start a second copy beside the running instance.
   */
  @PUT
  @Path("/{name}")
  @Operation(summary = "Register or update one service, replacing its environment links")
  @APIResponse(responseCode = "200", description = "The updated service")
  @APIResponse(responseCode = "201", description = "The newly registered service")
  @APIResponse(
      responseCode = "400",
      description = "Validation failed, or a platform service was given environment links")
  @APIResponse(responseCode = "404", description = "An environmentId names no environment")
  @APIResponse(
      responseCode = "409",
      description = "A platform service cannot become an environment service")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  public Response upsert(@PathParam("name") String name, UpsertServiceRequest request) {
    machineAuth.require();
    UpsertServiceRequest body =
        request == null ? new UpsertServiceRequest(null, null, false, null, null) : request;
    ServiceCatalog.UpsertResult result =
        catalog.upsert(
            new ServiceCatalog.Upsert(
                name,
                body.deploymentTarget(),
                body.branch(),
                body.availableOnEnv(),
                body.healthPath(),
                body.environmentIds()));
    PdServiceDto dto = mapper.toDto(result.service().service(), result.service().environmentIds());
    return Response.status(result.created() ? Response.Status.CREATED : Response.Status.OK)
        .entity(new ServiceResponse(dto))
        .build();
  }

  /**
   * Every service, oldest first, each flattened with the environments it is linked into.
   *
   * <p>Flat because a platform service belongs to no environment: reading the catalogue through the
   * environments would leave qits-idp and this component out of it, which are the two a reader most
   * wants to find.
   */
  @GET
  @Operation(summary = "Every service, with the environments each is linked into")
  @APIResponse(responseCode = "200", description = "The services")
  public ListServicesResponse list() {
    return new ListServicesResponse(
        catalog.list().stream()
            .map(linked -> mapper.toDto(linked.service(), linked.environmentIds()))
            .toList());
  }

  /**
   * Remove a service and its links. This is the deliberate act the refused platform → environment
   * flip points at: remove the row, then let the next green build register the service afresh in
   * the shape its repository now declares.
   *
   * <p>It takes no deployment history with it, by design — the rows name a service by string with
   * no FK, so what ran stays readable and the rollback pins keep answering.
   */
  @DELETE
  @Path("/{name}")
  @Operation(summary = "Remove a service and its links")
  @APIResponse(responseCode = "204", description = "Removed")
  @APIResponse(responseCode = "404", description = "No such service")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  public Response delete(@PathParam("name") String name) {
    machineAuth.require();
    catalog.delete(name);
    return Response.noContent().build();
  }
}
