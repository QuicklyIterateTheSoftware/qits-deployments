package eu.wohlben.qits.platform.deployments.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.platform.deployments.deployments.control.BuildAnnouncements;
import eu.wohlben.qits.platform.deployments.deployments.control.BuildTips;
import eu.wohlben.qits.platform.deployments.environments.error.BadRequestException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The bus door: qits-ci's {@code BuildSuccessful}, consumed durably, announced to the same {@link
 * BuildAnnouncements#announce} the HTTP intake calls.
 *
 * <p>This is wave 3 of the arrangement {@link BuildAnnouncements} was written ahead of. Both doors
 * now exist and neither wins: they funnel into one method, and what happens after it is unchanged.
 * The difference is upstream of the seam — a POST is sent once and nobody retries it, while an
 * event is retried by its publisher, replayed from qits-events' log after a cutover, and handled
 * exactly once here whichever channel delivers it.
 *
 * <h2>What the library gives, and what it does not</h2>
 *
 * <p>Given: exactly-once <b>effect</b> per {@code (consumerId, event id)}. A live frame and a
 * catch-up row take one funnel, the claim and this handler commit together, and a duplicate arrival
 * finds the claim and is dropped. So nothing here counts, deduplicates or remembers an event id.
 *
 * <p>Not given: order. Catch-up delivers late, so a <b>different, older</b> build can arrive after a
 * newer one has already been deployed — which for this consumer is the difference between a
 * deployment and a rollback nobody asked for. {@link BuildTips} is the collapse, and it is
 * mandatory rather than defensive.
 *
 * <h2>Failure, which is the other thing that cannot be got wrong</h2>
 *
 * <p>A throw out of {@link #onFrame} rolls the claim back and leaves the event owed <b>forever</b>:
 * it is offered again on every sweep and the watermark stays behind it, so one poison event stops
 * this consumer's catch-up. So the rule here is the library's: <b>swallow what cannot be fixed by
 * retrying, throw only what can.</b> A payload that will not parse, or one whose identifiers this
 * component refuses, is warned about and settled — the next attempt would refuse it identically.
 * A database that could not answer is not caught, because the next attempt is exactly what fixes
 * it.
 *
 * <p>Registration is "be a bean": the dispatcher injects {@code
 * Instance<QitsDurableEventListener>}, which is what ArC counts as a use, so no {@code
 * @Unremovable} is needed — and {@code PdEventstreamDarknessTest} asserts that rather than trusting
 * it, since a removed listener subscribes to nothing and says nothing about it.
 */
@ApplicationScoped
public class PdBuildSuccessfulSubscriber implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(PdBuildSuccessfulSubscriber.class);

  /**
   * The event name qits-ci publishes under — {@code BuildSuccessful}'s simple class name, which is
   * what {@code QitsEvent.signature()} derives and what qits-events stores in the row's {@code
   * name} column.
   *
   * <p>A string rather than a class, because this component has <b>no compile-time dependency on
   * another context</b> and does not grow one for an event: the payload is four strings on a wire.
   * The cost is that a rename in qits-ci is silent here, which is the cost every cross-repo contract
   * in this component already carries (the intake path is the other).
   */
  static final String SIGNATURE = "BuildSuccessful";

  /**
   * This consumer's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   *
   * <p><b>Never change it.</b> A new value is a brand-new consumer: the old claims are orphaned and
   * the new id initializes at the head of the log, silently skipping everything in between. It is a
   * name a person chose precisely so it survives this class being renamed or moved.
   */
  static final String CONSUMER_ID = "pd-build-succeeded";

  /**
   * The four fields of the payload this component acts on. The event carries two more —
   * {@code imageDigest} and {@code finishedAt} — and they are deliberately not bound: the image is
   * resolved from the triple by convention here, and the finish time is read off the envelope,
   * where it is the log's own ordering key rather than a field a payload happens to repeat.
   * Unknown fields are ignored by the library's mapper, which is what lets qits-ci add a seventh.
   */
  public record BuildSuccessfulPayload(
      String runId, String repoId, String branch, String commitSha) {}

  @Inject BuildAnnouncements announcements;

  @Inject BuildTips tips;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /**
   * Only builds on a branch some tier listens to, which is a small minority of the platform's green
   * builds. An event this rejects is stored nowhere at all, which is what keeps the claim ledger
   * proportional to the work rather than to the log.
   *
   * <p>Widening it later — a new environment listening to a new branch — is safe and resurrects
   * nothing: catch-up only ever re-reads above the watermark, and the watermark has long passed
   * everything this said no to.
   *
   * <p>It reads the topology, so it can throw when the database is unreachable. That is correct: an
   * event whose selection could not be decided stays owed rather than being settled by accident.
   */
  @Override
  public boolean selects(EventFrame frame) {
    BuildSuccessfulPayload build = decode(frame);
    return build != null && !isBlank(build.branch()) && tips.anyTierListensTo(build.branch());
  }

  @Override
  public void onFrame(EventFrame frame) {
    BuildSuccessfulPayload build = decode(frame);
    if (build == null) {
      // Warned in decode. Returning settles the event: a payload that will not parse now will not
      // parse on the thousandth sweep either, and an event nothing can read must not block the
      // watermark of everything behind it.
      return;
    }
    if (isBlank(build.repoId()) || isBlank(build.branch()) || isBlank(build.commitSha())) {
      LOG.warnf(
          "%s %s carries no (repoId, branch, commitSha) to deploy; it is skipped",
          frame.name(), frame.id());
      return;
    }
    if (!tips.claim(build.repoId(), build.branch(), frame.occurredAt())) {
      LOG.infof(
          "%s %s is not the tip of %s@%s any more (build finished %s); it is skipped rather than"
              + " deployed over the newer one",
          frame.name(), frame.id(), build.repoId(), build.branch(), frame.occurredAt());
      return;
    }
    try {
      announcements.announce(
          build.runId(), build.repoId(), build.branch(), build.commitSha());
    } catch (BadRequestException e) {
      // An identifier this component refuses — a sha that could escape an argv, a branch that
      // overruns its column. Retrying refuses it again, so it is settled and said out loud.
      LOG.warnf(
          "%s %s was refused: %s (%s@%s)",
          frame.name(), frame.id(), e.getMessage(), build.repoId(), build.commitSha());
    }
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private BuildSuccessfulPayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), BuildSuccessfulPayload.class);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s has an unreadable payload: %s", frame.name(), frame.id(), e.getMessage());
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
