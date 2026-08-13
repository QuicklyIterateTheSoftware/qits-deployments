package eu.wohlben.qits.platform.deployments.bus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.CausationHeader;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.platform.deployments.deployments.control.DeployService;
import eu.wohlben.qits.platform.deployments.dockerhost.FakeDockerHost;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeSpecSource;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.platform.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.platform.deployments.environments.entity.PdService;
import eu.wohlben.qits.platform.deployments.environments.persistence.PdEnvironmentRepository;
import eu.wohlben.qits.platform.deployments.environments.persistence.PdServiceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What ends up in {@code causation_id}, per door and per table — the claim the whole causation
 * feature reduces to: a deployment row names the {@code BuildSuccessful} that caused it.
 *
 * <p><b>Every assertion here is really about a thread.</b> {@code CausationScope} is a ThreadLocal
 * and the intake hands the event to {@code pd-deploy-worker}, so nothing the entity listener does
 * can reach across that hop. This suite calls {@code onFrame} from the JUnit thread, which stands
 * in no scope at all — so a non-null value on a deployment row can only have been set explicitly,
 * which is exactly the bug measured in qits-ci on 2026-08-10 (a full trigger id beside an empty
 * causation column) and the reason the value travels as a parameter.
 *
 * <p>The environment rows are the other half: they are written on the request thread with no hop in
 * between, so there the stamp itself is what has to work.
 */
@QuarkusTest
public class PdCausationTest {

  private static final String SHA = "c".repeat(40);

  @Inject FakeDockerHost driver;
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;
  @Inject PdBuildSuccessfulSubscriber subscriber;
  @Inject PdDeploymentRepository deployments;
  @Inject PdServiceRepository services;
  @Inject PdEnvironmentRepository environments;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
  }

  @Test
  public void aBusEventLeavesItsIdOnEveryRowItCaused() {
    createEnvironment("cause-bus", "environment/cause-bus", null);
    String eventId = UUID.randomUUID().toString();

    subscriber.onFrame(
        frame(eventId, Instant.now(), "repo-cause-bus", "environment/cause-bus", SHA));
    awaitStarted(1);

    assertEquals(
        UUID.fromString(eventId),
        causeOfDeployment("repo-cause-bus"),
        "no scope stands on this thread, so only an explicit set can have written this");
    assertEquals(
        UUID.fromString(eventId),
        causeOfService("repo-cause-bus"),
        "the catalogue row the same registration created carries the same cause");
  }

  @Test
  public void anEventIdThatIsNotAUuidCostsTheEdgeAndNothingElse() {
    // Causation is advisory. A frame whose id this component cannot read as a UUID still deploys —
    // refusing a green build over a trace column would be the one failure this must never cause.
    createEnvironment("cause-odd", "environment/cause-odd", null);

    subscriber.onFrame(
        frame("not-a-uuid", Instant.now(), "repo-cause-odd", "environment/cause-odd", SHA));
    awaitStarted(1);

    assertNull(causeOfDeployment("repo-cause-odd"));
  }

  @Test
  public void theHttpIntakeRecordsTheCauseTheCallerWasActingUnder() {
    // The other door, and the only place the restored scope can be read: on the request thread,
    // before announce() hands the event to the worker.
    createEnvironment("cause-http", "environment/cause-http", null);
    String cause = UUID.randomUUID().toString();

    given()
        .contentType(ContentType.JSON)
        .header(CausationHeader.NAME, cause)
        .body(
            Map.of(
                "runId",
                "run-cause",
                "repoId",
                "repo-cause-http",
                "branch",
                "environment/cause-http",
                "commitSha",
                SHA))
        .when()
        .post("/platform-deployments/api/events/build-succeeded")
        .then()
        .statusCode(202);
    awaitStarted(1);

    assertEquals(UUID.fromString(cause), causeOfDeployment("repo-cause-http"));
  }

  @Test
  public void aBootstrapPostWithNoHeaderIsARootlessDeployment() {
    // Absent is a real answer, not a gap: nothing on the bus caused a hand-made POST.
    createEnvironment("cause-none", "environment/cause-none", null);

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "runId",
                "run-cause",
                "repoId",
                "repo-cause-none",
                "branch",
                "environment/cause-none",
                "commitSha",
                SHA))
        .when()
        .post("/platform-deployments/api/events/build-succeeded")
        .then()
        .statusCode(202);
    awaitStarted(1);

    assertNull(causeOfDeployment("repo-cause-none"));
  }

  @Test
  public void aTierCreatedInAChainIsStampedByTheListenerItself() {
    // PdEnvironment is written on the request thread with no hop in between, so this is the one
    // entity here whose column the CausationStamp listener fills — from the scope
    // CausationServerFilter restored out of the header.
    String cause = UUID.randomUUID().toString();

    createEnvironment("cause-tier", "environment/cause-tier", cause);

    assertEquals(UUID.fromString(cause), causeOfEnvironment("cause-tier"));
  }

  @Test
  public void aTierCreatedByHandIsRootless() {
    createEnvironment("cause-bare", "environment/cause-bare", null);

    assertNull(causeOfEnvironment("cause-bare"));
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static EventFrame frame(
      String id, Instant occurredAt, String repoId, String branch, String sha) {
    String payload =
        ("{\"branch\":\"%s\",\"commitSha\":\"%s\",\"finishedAt\":\"%s\",\"repoId\":\"%s\","
                + "\"runId\":\"run-cause\"}")
            .formatted(branch, sha, occurredAt, repoId);
    return new EventFrame(id, "BuildSuccessful", occurredAt, payload, null, null);
  }

  /** Null-safe on purpose: the assertion should read the column, not a NoSuchElement. */
  private UUID causeOfDeployment(String applicationName) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<PdDeployment> rows = deployments.listByApplicationNewestFirst(applicationName);
              assertFalse(rows.isEmpty(), "no deployment row for " + applicationName);
              return rows.get(0).causationId;
            });
  }

  private UUID causeOfService(String name) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdService service =
                  services.findByName(name).orElseGet(() -> fail("no service row for " + name));
              return service.causationId;
            });
  }

  private UUID causeOfEnvironment(String name) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdEnvironment environment =
                  environments
                      .findByName(name)
                      .orElseGet(() -> fail("no environment row for " + name));
              assertNotNull(environment.id);
              return environment.causationId;
            });
  }

  private void createEnvironment(String name, String branch, String cause) {
    var request =
        given().contentType(ContentType.JSON).body(Map.of("name", name, "branch", branch, "platform", false));
    if (cause != null) {
      request = request.header(CausationHeader.NAME, cause);
    }
    request
        .when()
        .post("/platform-deployments/api/environments")
        .then()
        .statusCode(201);
  }

  private void awaitStarted(int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.started().size() < count && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    assertEquals(count, driver.started().size(), "started containers");
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }
}
