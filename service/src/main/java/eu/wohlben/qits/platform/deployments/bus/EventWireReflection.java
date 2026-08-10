package eu.wohlben.qits.platform.deployments.bus;

import eu.wohlben.qits.eventstream.control.EventFrame;
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
 * <p>What is registered is the whole of the CONSUMING path, and it is two channels rather than one:
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
 * <p><b>The publishing trio is deliberately absent</b>: {@code EventEnvelope} and the {@code
 * CanonicalJson$QitsEventMixin} are what a publisher needs, and this component publishes nothing.
 * They join this list in the commit that gives it an event to announce — the same rule
 * {@code ApiWireReflection} states for a new response type, and worth honouring here because
 * qits-ci proved the mix-in's absence is the quiet failure of the two: a payload that carries an
 * {@code eventId} it is contractually supposed to omit, with no crash and no log.
 */
@RegisterForReflection(
    targets = {EventFrame.class, PdBuildSuccessfulSubscriber.BuildSuccessfulPayload.class},
    classNames = {"eu.wohlben.qits.eventstream.control.EventPage"})
final class EventWireReflection {

  private EventWireReflection() {}
}
