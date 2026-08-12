package eu.wohlben.qits.platform.deployments.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.DurableFunnel;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.eventstream.control.EventStreamSubscriber;
import eu.wohlben.qits.platform.deployments.dockerhost.FakeDockerHost;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Two facts about the shipped configuration that would otherwise only be discovered by their
 * consequences, and one of them silently.
 *
 * <p><b>The bus is dark in the suite, and dark means nothing happens.</b> Every other test class
 * here runs on the default test config, and this says what that means: the switch is off, no stream
 * was dialled, and an event offered through the library's funnel touches no table and reaches no
 * handler. Without the switch a clone-alone {@code ./mvnw verify} would redial an unresolvable host
 * and page a log nobody answers, once every thirty seconds — which reads as slowness rather than as
 * misconfiguration.
 *
 * <p><b>The subscriber bean survives ArC.</b> {@link PdBuildSuccessfulSubscriber} is injected
 * nowhere by name in the shipped code — it is reached only through {@code
 * Instance<QitsDurableEventListener>} — and unused-bean removal would leave a deployment that
 * subscribes to nothing, consumes nothing and says nothing to admit it. An {@code Instance}
 * injection point counts as a use, which is why no {@code @Unremovable} is needed; this is the
 * assertion that keeps that true rather than believed.
 *
 * <p>What is NOT asserted here is the datasource: dark does not mean absent, the {@code
 * eventstream} store opens and migrates at boot regardless, and the whole suite failing to start is
 * how that would be discovered.
 */
@QuarkusTest
public class PdEventstreamDarknessTest {

  @ConfigProperty(name = "qits.eventstream.enabled")
  boolean enabled;

  @Inject EventStreamSubscriber subscriber;

  @Inject DurableFunnel funnel;

  @Inject FakeDockerHost driver;

  @Inject @Any Instance<QitsDurableEventListener> durableListeners;

  @Test
  public void theBusIsDarkOutsideADeployment() {
    assertFalse(enabled, "%test must ship qits.eventstream.enabled=false");
    assertFalse(subscriber.connected(), "a dark module dials nothing");
  }

  @Test
  public void aDarkModuleDeliversNothingToTheSubscriber() {
    driver.reset();
    EventFrame frame =
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"branch\":\"main\",\"commitSha\":\"" + "c".repeat(40) + "\",\"repoId\":\"repo-dark\"}",
            null,
            null);

    assertEquals(
        DurableFunnel.Result.SKIPPED,
        funnel.offer(theSubscriber(), frame),
        "the funnel is closed while the module is dark — no claim row, no handler");
    assertEquals(List.of(), driver.started());
  }

  @Test
  public void theSubscriberIsARegisteredDurableBean() {
    assertTrue(
        StreamSupport.stream(durableListeners.spliterator(), false)
            .anyMatch(PdBuildSuccessfulSubscriber.class::isInstance),
        "the subscriber must survive unused-bean removal, or nothing consumes the bus");
  }

  private QitsDurableEventListener theSubscriber() {
    return StreamSupport.stream(durableListeners.spliterator(), false)
        .filter(PdBuildSuccessfulSubscriber.class::isInstance)
        .findFirst()
        .orElseThrow();
  }
}
