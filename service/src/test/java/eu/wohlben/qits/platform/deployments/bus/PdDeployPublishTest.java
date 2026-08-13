package eu.wohlben.qits.platform.deployments.bus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.CausationHeader;
import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.DeployService;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeResourceProvisioner;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeSpecSource;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A real deployment, through the real intake and the real worker, ending as rows in the real
 * outbox. The producing half of this component's bus wiring, asserted end to end: what
 * {@code DeployService} announces at each of the four lifecycle points, with the payload a consumer
 * will read and the causation edge back to the build that caused it.
 *
 * <p><b>The bus is aimed at a CLOSED PORT, deliberately, and that is what makes this assertable.</b>
 * {@code QitsEventBus.publish} attempts the idempotent PUT inline and hands anything that does not
 * land to the outbox — so against a port nothing answers, every published event becomes exactly one
 * row, whole, with the canonical payload it would have been sent with. A stub qits-events would
 * prove the same thing and add a second moving part; a row IS the publish, from this side of the
 * bus.
 *
 * <p>The scheduler is off in this profile, so no sweeper retries a row out from under an assertion,
 * and the catch-up sweep does not run at startup for the same reason.
 *
 * <p>A class of its own rather than more cases in {@link PdCausationTest}, because a bus that is on
 * is a different process configuration and {@code @TestProfile} is per class.
 */
@QuarkusTest
@TestProfile(PdDeployPublishTest.BusEnabled.class)
public class PdDeployPublishTest {

  private static final String SHA = "e".repeat(40);

  public static class BusEnabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.eventstream.enabled", "true",
          // Nothing answers here. A refused connection is immediate, so the inline attempt costs
          // no wall time and the event lands in the outbox rather than on a socket.
          "qits.events.url", "http://localhost:1",
          "quarkus.scheduler.enabled", "false",
          "qits.eventstream.catchup-at-startup", "false");
    }
  }

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject DeployService deployService;

  /**
   * The outbox is read through its OWN persistence unit's EntityManager rather than through
   * Panache's static methods: {@code OutboxEvent} arrives from the qits-eventstream jar, and a
   * Panache static on an entity this application did not compile is not enhanced here — it throws
   * "did you forget to annotate your entity with @Entity?", which names the wrong problem.
   */
  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  @Test
  public void aGreenBuildAnnouncesQueuedStartedAndActiveInThatOrder() {
    String environmentId = createEnvironment("pub-green");
    String cause = UUID.randomUUID().toString();

    postBuildSucceeded("run-pub-green", "repo-pub-green", "environment/pub-green", cause);
    awaitSettled(environmentId, 1);

    OutboxEvent queued = only("DeploymentQueued");
    OutboxEvent started = only("DeploymentStarted");
    OutboxEvent active = only("DeploymentActive");

    // One deployment, one id, three statements about it.
    String deploymentId = deploymentIdOf(queued);
    assertTrue(started.payload.contains("\"deploymentId\":\"" + deploymentId + "\""),
        started.payload);
    assertTrue(active.payload.contains("\"deploymentId\":\"" + deploymentId + "\""), active.payload);

    for (OutboxEvent event : List.of(queued, started, active)) {
      assertTrue(event.payload.contains("\"applicationName\":\"repo-pub-green\""), event.payload);
      assertTrue(event.payload.contains("\"environmentId\":\"" + environmentId + "\""),
          event.payload);
      assertTrue(event.payload.contains("\"environmentName\":\"pub-green\""), event.payload);
      assertTrue(event.payload.contains("\"commitSha\":\"" + SHA + "\""), event.payload);
      assertTrue(event.payload.contains("\"runId\":\"run-pub-green\""), event.payload);
      assertNotNull(event.occurredAt);
      assertEquals(cause, event.parentId, "every event of one deployment names the same cause");
    }

    // The name is the one fact only the deployer holds: under swarm it is the wire alias, so the
    // event names the address peers dial rather than something only this host could resolve.
    String containerName = driver.applied().get(0).wireAlias();
    assertTrue(active.payload.contains("\"containerName\":\"" + containerName + "\""),
        active.payload);
    assertFalse(queued.payload.contains("containerName"), queued.payload);

    // The lifecycle in the log's own order: queued, then started, then live.
    assertTrue(queued.occurredAt.compareTo(started.occurredAt) <= 0, "queued before started");
    assertTrue(started.occurredAt.compareTo(active.occurredAt) <= 0, "started before active");
    assertNull(only("DeploymentFailed", 0), "a green deployment announces no failure");
  }

  @Test
  public void aDeploymentWithNoImageAnnouncesItsTerminalStatusAndDetail() {
    // IMAGE_MISSING rather than FAILED, carried as data: to a consumer both mean "not live", and to
    // a person they are different things — nothing publishes this application yet.
    String environmentId = createEnvironment("pub-noimage");
    driver.scriptPull(
        new DeploymentDriver.PullResult(
            DeploymentDriver.PullOutcome.IMAGE_MISSING, "manifest unknown"));

    postBuildSucceeded("run-pub-noimage", "repo-pub-noimage", "environment/pub-noimage", null);
    awaitSettled(environmentId, 1);

    OutboxEvent failed = only("DeploymentFailed");
    assertTrue(failed.payload.contains("\"status\":\"IMAGE_MISSING\""), failed.payload);
    assertTrue(failed.payload.contains("manifest unknown"), failed.payload);
    assertTrue(failed.payload.contains("\"applicationName\":\"repo-pub-noimage\""), failed.payload);
    assertTrue(failed.payload.contains("\"environmentName\":\"pub-noimage\""), failed.payload);
    assertNull(failed.parentId, "a hand-made POST with no header is a rootless deployment");

    // The two points it did reach are still announced; the one it never got to is not.
    assertNotNull(only("DeploymentQueued"));
    assertNotNull(only("DeploymentStarted"));
    assertNull(only("DeploymentActive", 0), "nothing was cut over");
  }

  @Test
  public void aDeploymentThatNeverConvergesAnnouncesTheOrchestratorsOwnWords() {
    String environmentId = createEnvironment("pub-sick");
    driver.scriptConvergence(
        DeploymentDriver.Convergence.failed(
            "service pub-sick-repo-pub-sick was still updating after 60s"));

    postBuildSucceeded("run-pub-sick", "repo-pub-sick", "environment/pub-sick", null);
    awaitSettled(environmentId, 1);

    OutboxEvent failed = only("DeploymentFailed");
    assertTrue(failed.payload.contains("\"status\":\"FAILED\""), failed.payload);
    assertTrue(failed.payload.contains("was still updating"), failed.payload);
    assertNull(only("DeploymentActive", 0), "a failed convergence leaves the predecessor serving");
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String deploymentIdOf(OutboxEvent event) {
    String key = "\"deploymentId\":\"";
    int start = event.payload.indexOf(key) + key.length();
    return event.payload.substring(start, event.payload.indexOf('"', start));
  }

  private String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "branch", "environment/" + name, "platform", false))
        .when()
        .post("/platform-deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void postBuildSucceeded(String runId, String repoId, String branch, String cause) {
    var request =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of("runId", runId, "repoId", repoId, "branch", branch, "commitSha", SHA));
    if (cause != null) {
      request = request.header(CausationHeader.NAME, cause);
    }
    request
        .when()
        .post("/platform-deployments/api/events/build-succeeded")
        .then()
        .statusCode(202);
  }

  /**
   * Wait for the tier's deployments to reach a terminal status, then for the worker to drain. The
   * second half is what this suite needs and the flow tests do not: an announcement is published
   * <b>after</b> the row a poll can see, so a test that stopped at the row would race the publish.
   */
  private void awaitSettled(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> rows =
          given()
              .when()
              .get("/platform-deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments");
      boolean settled =
          rows.size() == count
              && rows.stream()
                  .noneMatch(
                      d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")));
      if (settled) {
        try {
          deployService.awaitIdle();
        } catch (Exception e) {
          throw new IllegalStateException("the deploy worker did not drain", e);
        }
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    fail("the deployments of " + environmentId + " did not settle");
  }

  private List<OutboxEvent> rows() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery("select o from OutboxEvent o", OutboxEvent.class)
                    .getResultList());
  }

  /** The one outbox row of that event type, failing the test if there is not exactly one. */
  private OutboxEvent only(String name) {
    OutboxEvent row = only(name, 1);
    assertNotNull(row, "expected one " + name + " row");
    return row;
  }

  /** The single row of that type, or null when {@code expected} is 0 and there are none. */
  private OutboxEvent only(String name, int expected) {
    List<OutboxEvent> matching = rows().stream().filter(row -> name.equals(row.name)).toList();
    assertEquals(
        expected, matching.size(), () -> "expected " + expected + " " + name + " row(s)");
    return matching.isEmpty() ? null : matching.get(0);
  }
}
