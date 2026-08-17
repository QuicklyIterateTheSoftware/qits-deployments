package eu.wohlben.qits.platform.deployments.api;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.platform.deployments.deployments.control.BuildAnnouncements;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The build event intake — the wire contract with qits-ci. {@code POST
 * /platform-deployments/api/events/build-succeeded} is a cross-repo contract: qits-ci's notifier
 * POSTs exactly this path via its configured intake url, and it is fire-and-forget — a delivery
 * failure is logged at debug and nothing else. A mismatch here therefore raises no error anywhere;
 * deployments just stop happening. The path carries no segment of its own because {@code
 * quarkus.rest.path=/platform-deployments/api} already says it.
 *
 * <p><b>The payload shape is unchanged from the ancestor's</b>, deliberately: the bootstrap replays
 * lost events through it by hand and qits-ci sends it today, so the body a caller writes is the one
 * it always wrote. Only the path moved with the component's name.
 *
 * <p><b>This is the transitional and manual door.</b> The target model is bus-driven — qits-ci
 * already publishes the events, and a deployment should follow from one that can be retried and
 * replayed rather than from a POST nobody retries. {@link BuildAnnouncements} is the seam that
 * lands takes; nothing here changes when it does.
 *
 * <p>Hidden from the OpenAPI document (a wire/system API).
 *
 * <p><b>{@code qits-platform:system} and {@link MachineAuth#require()} are both here because
 * nothing human reaches this path</b> — qits-ci is its only sender, so a bearer is the only
 * credential its caller could ever hold, and the role is the one an idp-minted token carries. The
 * reads next door take the admin role no token has. That split is the rule, not a phasing.
 */
@Path("/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits-platform:system")
public class PdEventController {

  @Inject BuildAnnouncements announcements;
  @Inject MachineAuth machineAuth;

  /**
   * One green pipeline for one commit. The triple that matters is (repoId, branch, commitSha) — the
   * image is resolved from it by convention and everything after that is this component's. {@code
   * runId} is optional and drives nothing: it is recorded on each deployment this queues so a
   * reader can walk from a deployment row to {@code /ci/runs/<runId>}, the only edge between the two
   * services' histories. A sender that omits it still deploys; the row simply names no build.
   */
  public record BuildSucceededEvent(
      String runId, @NotBlank String repoId, @NotBlank String branch, @NotBlank String commitSha) {}

  /**
   * Accepts the event and returns immediately — deployments execute on the worker. 202 also when no
   * environment listens to the branch: that is the normal case for every green build on a branch
   * without an environment, not an error the fire-and-forget sender could act on.
   *
   * <p>{@code require()} and not {@code requireProject(...)}: the event names a {@code repoId}, and
   * a repository is not a project. Holding a token minted for this component is the whole claim
   * this intake needs — it queues a deployment onto whichever environment already listens to the
   * branch, and which environments exist is this component's own topology, not the caller's to
   * name.
   *
   * <p>With the gate off this line returns at once and the endpoint accepts credential-free calls
   * from the platform's networks exactly as it did before.
   */
  @POST
  @Path("/build-succeeded")
  @Operation(hidden = true)
  public Response buildSucceeded(@Valid BuildSucceededEvent event) {
    machineAuth.require();
    // The cause is read HERE, on the request thread, because that is the only place it exists:
    // CausationServerFilter restored it from the caller's X-Qits-Causation-Id before this method
    // ran, and everything after announce() is on pd-deploy-worker, where the ThreadLocal is gone.
    // Null for a hand-made bootstrap POST, which is a rootless deployment and not an error.
    announcements.announce(
        event.runId(),
        event.repoId(),
        event.branch(),
        event.commitSha(),
        CausationScope.current());
    return Response.accepted().build();
  }
}
