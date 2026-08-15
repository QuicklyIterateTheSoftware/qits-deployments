package eu.wohlben.qits.platform.deployments.api;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.platform.deployments.deployments.control.EnvironmentOperations;
import eu.wohlben.qits.platform.deployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.platform.deployments.environments.dto.PdEnvironmentDto;
import eu.wohlben.qits.platform.deployments.environments.dto.PdLinkedServiceDto;
import eu.wohlben.qits.platform.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.platform.deployments.environments.mapper.EnvironmentMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

/**
 * The environment surface: creating a tier, renaming or retargeting it, tearing it down, the reads,
 * and the link query a reconciliation pulls.
 *
 * <p>A tier is created <b>deliberately</b>; what it holds is not. Service rows are derived from each
 * repository's {@code deployments.yml} on every green build, so this surface has no write for them
 * and gained none — {@link PdServiceController} is where the derived writes land.
 *
 * <p><b>The create and the delete have docker side effects, and that is why this component owns
 * them.</b> Creating a tier makes its bundle network; tearing one down reaps its containers and
 * removes its networks before the rows go. That composition lives in {@code EnvironmentOperations};
 * splitting it across two services is exactly what the merge undid.
 *
 * <p><b>Every write calls {@link MachineAuth#require()}; no read does.</b> The writer here is a
 * machine — the bootstrap and the deploy path — so a bearer is a credential its caller can hold,
 * and the reads are the opposite: a person drives them through qits-gateway's session and the web
 * client polls them, so a guard there would close the surface for both the day the gate flips on.
 * The guard is gated off by {@code qits.auth.machine.required} until qits-platform-idp grants this
 * audience.
 */
@Path("/environments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PdEnvironmentController {

  private static final Logger LOG = Logger.getLogger(PdEnvironmentController.class);

  @Inject MachineAuth machineAuth;
  @Inject EnvironmentOperations environments;
  @Inject ServiceCatalog catalog;
  @Inject EnvironmentMapper mapper;
  @Inject PdReadPatience reads;

  /**
   * One tracked application.
   *
   * @deprecated ignored — see {@link CreateEnvironmentRequest#applications()}.
   */
  @Deprecated
  public record ApplicationSpec(@NotBlank String repoId, @NotBlank String name, String healthPath) {}

  /**
   * The creation payload. {@code branch} and {@code network} are conventions when omitted: {@code
   * environment/<name>} and {@code qits-env-<name>}.
   *
   * <p>{@code applications} is <b>deprecated and ignored</b>, with a WARN so a sender finds out.
   * Service rows are derived from each repository's own {@code .config/qits/deployments.yml}, so
   * naming them here only pre-created what the next green build creates anyway — and the catalogue
   * holds one identity for a service (its name), so a {@code (repoId, name)} pair that disagrees
   * has nowhere to land. The field is still accepted so an older sender's payload keeps
   * deserializing; send nothing.
   *
   * <p>{@code platform} makes this the <b>platform environment</b> — the one tier whose branch
   * deploys the platform plane. Omitted is false, and the bootstrap is the sender that sets it: it
   * creates the standing environment that {@code --platform-env} names. Creating a tier without it
   * on a database that has no platform environment yet is legal and logs a warning, because a
   * platform build would then deploy nowhere and say nothing.
   */
  public record CreateEnvironmentRequest(
      @NotBlank String name,
      String branch,
      String network,
      Boolean platform,
      @Deprecated List<@Valid ApplicationSpec> applications) {}

  /**
   * The rename/retarget/designate payload — every field optional, an omitted one is left alone.
   * This is how an environment moves onto the {@code environment/<name>} branch convention, and how
   * the platform designation moves between tiers.
   *
   * <p>{@code platform: true} moves the designation to this environment. {@code false} is refused
   * (409): the platform plane always has a tier, so the designation is moved, never dropped.
   */
  public record UpdateEnvironmentRequest(String name, String branch, Boolean platform) {}

  public record EnvironmentResponse(PdEnvironmentDto environment) {}

  public record ListEnvironmentsResponse(List<PdEnvironmentDto> environments) {}

  public record ListLinksResponse(List<PdLinkedServiceDto> services) {}

  @POST
  @Operation(summary = "Create an environment: a name, a branch to listen to, a bundle network")
  @APIResponse(responseCode = "201", description = "Created; green builds on the branch now deploy")
  @APIResponse(responseCode = "400", description = "A name, branch or network failed validation")
  @APIResponse(responseCode = "409", description = "An environment of that name already exists")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  @jakarta.annotation.security.RolesAllowed("qits-platform:system")
  public Response create(@Valid CreateEnvironmentRequest request) {
    machineAuth.require();
    if (request.applications() != null && !request.applications().isEmpty()) {
      LOG.warnf(
          "Ignoring %d declared application(s) on the creation of environment %s — applications are"
              + " derived from each repository's deployments.yml on its next green build",
          request.applications().size(), request.name());
    }
    PdEnvironment environment =
        environments.create(
            request.name(),
            request.branch(),
            request.network(),
            Boolean.TRUE.equals(request.platform()));
    return Response.status(Response.Status.CREATED).entity(toResponse(environment)).build();
  }

  /**
   * Rename an environment or point it at another branch. <b>No docker side effects</b> — a rename
   * that tore containers down would be a delete in disguise, and delete is the one thing never to
   * reach for on a live environment. The bundle network is not renamed either: dev's is {@code
   * qits-net} by design. The next deployment of each application moves it onto the networks the new
   * name derives; what runs now keeps running.
   *
   * <p>Moving the platform designation has none either, for the same reason plus one of its own: a
   * platform service has no environment id and keeps its bare wire alias, so the plane's containers
   * are not a tier's to move. The flag decides which branch may roll them next.
   */
  @PATCH
  @Path("/{environmentId}")
  @Operation(summary = "Rename an environment, point it at another branch, or designate it the platform environment")
  @APIResponse(responseCode = "200", description = "The updated environment")
  @APIResponse(responseCode = "400", description = "A name or branch failed validation")
  @APIResponse(responseCode = "404", description = "No such environment")
  @APIResponse(
      responseCode = "409",
      description = "Another environment already has that name, or the platform designation was cleared rather than moved")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  @jakarta.annotation.security.RolesAllowed("qits-platform:system")
  public EnvironmentResponse update(
      @PathParam("environmentId") String environmentId, UpdateEnvironmentRequest request) {
    machineAuth.require();
    PdEnvironment environment =
        environments.update(
            environmentId,
            request == null ? null : request.name(),
            request == null ? null : request.branch(),
            request == null ? null : request.platform());
    return toResponse(environment);
  }

  /**
   * All environments, newest-first, without their applications (fetch one for the full shape).
   *
   * <p>Held through a short database outage rather than answering 500 — see {@link PdReadPatience}.
   * Every read on this surface is; no write is.
   */
  @GET
  @Operation(summary = "List environments")
  // The 200 is spelled out because declaring ANY response suppresses the generated one, and this
  // operation had only the generated one — leaving it off would drop the schema from the document.
  @APIResponse(responseCode = "200", description = "The environments")
  @jakarta.annotation.security.RolesAllowed("qits-platform:admin")
  public ListEnvironmentsResponse list() {
    return new ListEnvironmentsResponse(
        reads.call("The environment listing", environments::list).stream()
            .map(mapper::toDto)
            .toList());
  }

  @GET
  @Path("/{environmentId}")
  @Operation(summary = "One environment with the applications it tracks")
  @APIResponse(responseCode = "200", description = "The environment")
  @APIResponse(responseCode = "404", description = "No such environment")
  @jakarta.annotation.security.RolesAllowed("qits-platform:admin")
  public EnvironmentResponse get(@PathParam("environmentId") String environmentId) {
    // Both reads inside one bracket — the tier row and the applications it holds are one answer,
    // and a cutover between them would fail the half that ran second.
    return reads.call(
        "The read of environment " + environmentId,
        () -> toResponse(environments.require(environmentId)));
  }

  /**
   * <b>The pull query.</b> Every service present in this environment: the ones linked into it, then
   * every platform service — which is what a new environment picks up without anyone linking
   * anything.
   *
   * <p>It is what a reconciliation compares against the docker labels on the host — the shared
   * runtime truth — before connecting what is missing. It differs from the environment aggregate
   * above deliberately: that one is the tier's own services, this one composes the platform plane
   * in, and a reader that took the aggregate for the answer would silently miss qits-platform-idp.
   */
  @GET
  @Path("/{environmentId}/links")
  @Operation(summary = "Every service present in this environment: its links, plus every platform service")
  @APIResponse(responseCode = "200", description = "The services present in this environment")
  @APIResponse(responseCode = "404", description = "No such environment")
  @jakarta.annotation.security.RolesAllowed("qits-platform:admin")
  public ListLinksResponse links(@PathParam("environmentId") String environmentId) {
    return new ListLinksResponse(
        reads
            .call(
                "The link query of environment " + environmentId,
                () -> catalog.linksOf(environmentId))
            .stream()
            .map(mapper::toLinkDto)
            .toList());
  }

  /**
   * Tear the environment down: its recorded deployments, its containers, its networks, and last the
   * tier itself. 204 — after this the branch deploys nowhere.
   *
   * <p>The platform environment is refused: the platform plane would be left deployable from no
   * branch at all. Designate another environment first.
   */
  @DELETE
  @Path("/{environmentId}")
  @Operation(summary = "Tear an environment down (rows, containers, networks)")
  @APIResponse(responseCode = "204", description = "Torn down")
  @APIResponse(responseCode = "404", description = "No such environment")
  @APIResponse(responseCode = "409", description = "This is the platform environment")
  @APIResponse(responseCode = "401", description = "Gate on and no machine token presented")
  @APIResponse(responseCode = "403", description = "Gate on and the token is for another service")
  @jakarta.annotation.security.RolesAllowed("qits-platform:system")
  public Response delete(@PathParam("environmentId") String environmentId) {
    machineAuth.require();
    environments.delete(environmentId);
    return Response.noContent().build();
  }

  private EnvironmentResponse toResponse(PdEnvironment environment) {
    return new EnvironmentResponse(
        mapper.toDto(environment, catalog.applicationsOf(environment)));
  }
}
