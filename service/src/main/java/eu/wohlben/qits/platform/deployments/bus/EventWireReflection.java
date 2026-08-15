package eu.wohlben.qits.platform.deployments.bus;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.platform.deployments.events.DeploymentActive;
import eu.wohlben.qits.platform.deployments.events.DeploymentEndpoint;
import eu.wohlben.qits.platform.deployments.events.DeploymentFailed;
import eu.wohlben.qits.platform.deployments.events.DeploymentQueued;
import eu.wohlben.qits.platform.deployments.events.DeploymentStarted;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type the event bus binds JSON to. A class with no
 * code, the {@code ApiWireReflection} arrangement applied to the other wire this service has.
 *
 * <p><b>Nothing registers these automatically, and the reason is deliberate on the library's
 * side.</b> {@code CanonicalJson} builds its own {@code ObjectMapper} rather than injecting the CDI
 * one — the payload string is a byte-for-byte wire contract and a consuming application's
 * customizers must not be able to reach it — so the graph that mapper binds is invisible to the
 * build step that scans for what needs reflecting on. On a JVM these records bind whether anyone
 * registered them or not, which is exactly what makes the omission survive a green suite: the
 * failure is in the binary, at runtime, on the first frame.
 *
 * <p>The CONSUMING path is two channels rather than one:
 *
 * <ul>
 *   <li>{@link EventFrame} — a live frame off {@code /events/stream}, and also every row of the
 *       catch-up log, which binds to the same record.
 *   <li>{@code EventPage} — one page of {@code GET /events/api/events}, by string name because it
 *       is package-private in the library (no consumer holds one; the sweeper does). Without it the
 *       stream works in the binary and <b>catch-up alone</b> fails — the half that only matters
 *       after a cutover, which is the half nobody would be watching.
 *   <li>{@link PdBuildSuccessfulSubscriber.BuildSuccessfulPayload} — the payload this component
 *       reads out of the frame.
 * </ul>
 *
 * <p><b>The PUBLISHING path joined it when this component got events of its own to announce</b>, and
 * it is the four lifecycle records, their nested endpoint record, and a pair from the library:
 *
 * <ul>
 *   <li>{@link DeploymentQueued}, {@link DeploymentStarted}, {@link DeploymentActive}, {@link
 *       DeploymentEndpoint} and {@link DeploymentFailed} — what {@code DeployEventAnnouncer}
 *       serializes. Unregistered, the binary publishes an empty payload rather than failing.
 *   <li>{@link EventEnvelope} — the wrapper every publish is sent as.
 *   <li>The {@code CanonicalJson$QitsEventMixin}, by string name because it is a nested type inside
 *       the library. <b>This is the quiet one</b>, and qits-ci paid for it: the mix-in is what keeps
 *       {@code eventId} out of the payload, so its absence is a payload carrying an id it is
 *       contractually supposed to omit — no crash, no log, and every consumer reading a wire
 *       contract that silently changed.
 * </ul>
 */
@RegisterForReflection(
    targets = {
      EventFrame.class,
      PdBuildSuccessfulSubscriber.BuildSuccessfulPayload.class,
      EventEnvelope.class,
      DeploymentQueued.class,
      DeploymentStarted.class,
      DeploymentActive.class,
      DeploymentEndpoint.class,
      DeploymentFailed.class
    },
    classNames = {
      "eu.wohlben.qits.eventstream.control.EventPage",
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin"
    })
final class EventWireReflection {

  private EventWireReflection() {}
}
