package eu.wohlben.qits.platform.deployments.api;

import eu.wohlben.qits.platform.deployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.platform.deployments.environments.dto.PdApplicationDto;
import eu.wohlben.qits.platform.deployments.environments.mapper.EnvironmentMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Every application this component deploys, in one flat list — the environments' and the platform's
 * together.
 *
 * <p>It is flat because a platform service belongs to no environment: reading through the
 * environments would leave qits-platform-idp and this component out of it, which are the two a
 * reader most wants to find. Each row says which plane it is on ({@code target}) and, for an
 * environment application, which tier ({@code environmentId}/{@code environmentName}).
 *
 * <p>Read-only, and that is the model rather than a phase: rows here are derived from each
 * repository's own {@code .config/qits/deployments.yml} on every green build.
 *
 * <p><b>How it differs from {@code GET /services}</b>, which is the same data: this one has one
 * entry per (service, tier) and carries a derived {@code id} the client joins against a
 * deployment's {@code applicationId}; that one has one entry per service, with its link set, and
 * round-trips into the upsert. Two questions, two shapes, one catalogue.
 */
@Path("/applications")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits-platform:admin")
public class PdApplicationController {

  @Inject ServiceCatalog catalog;
  @Inject EnvironmentMapper mapper;
  @Inject PdReadPatience reads;

  public record ListApplicationsResponse(List<PdApplicationDto> applications) {}

  /** Held through a short database outage rather than answering 500 — see {@link PdReadPatience}. */
  @GET
  @Operation(summary = "Every application deployed here — environment applications and platform services")
  @APIResponse(responseCode = "200", description = "The applications, one entry per tier")
  public ListApplicationsResponse list() {
    return new ListApplicationsResponse(
        reads.call("The application listing", catalog::allApplications).stream()
            .map(mapper::toDto)
            .toList());
  }
}
