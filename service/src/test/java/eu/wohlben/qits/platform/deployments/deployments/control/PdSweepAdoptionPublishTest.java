package eu.wohlben.qits.platform.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The self-update's announcement, which is the half of a handed-off deployment nobody else can make.
 *
 * <p>A deployment that replaces THIS process comes back {@code HANDED_OFF} and leaves its row {@code
 * STARTING}, so {@code execute} never reaches the cutover that announces {@code DeploymentActive}.
 * The startup sweep of whichever instance survives is the only place that statement can be made —
 * and it used to make none, which cost this component its own route: the platform edge projects its
 * route table from {@code DeploymentActive} alone, so the deployer's own segment was reachable by
 * nobody and its entry was missing from the platform navigation, on every platform, forever.
 *
 * <p>The bus is aimed at a closed port for the reason {@code PdDeployPublishTest} argues: a refused
 * connection lands the event in the outbox whole, and a row IS the publish from this side.
 *
 * <p>A class of its own rather than cases in {@link PdSweepAdoptionTest} because a bus that is on is
 * a different process configuration, and {@code @TestProfile} is per class. It sits in the seam's
 * own package for the reason that test does: {@code sweepInFlight()} is package-private.
 */
@QuarkusTest
@TestProfile(PdSweepAdoptionPublishTest.BusEnabled.class)
public class PdSweepAdoptionPublishTest {

  private static final String SHA = "f".repeat(40);

  public static class BusEnabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.eventstream.enabled", "true",
          "qits.events.url", "http://localhost:1",
          "quarkus.scheduler.enabled", "false",
          "qits.eventstream.catchup-at-startup", "false");
    }
  }

  @Inject DeployService deployService;
  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject PdDeploymentRepository deployments;

  /** Read through the eventstream unit's own EntityManager — see {@code PdDeployPublishTest}. */
  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  @Test
  public void anAdoptedSelfUpdateAnnouncesItsRoutesAndNavigation() {
    // The deployer's own shape: one route it still serves under its pre-rename segment, and the
    // navigation entry that route carries. The snapshot is the ROW's, and the git host is scripted
    // to refuse — proving the announcement reaches no peer at all.
    String environmentId = createEnvironment("sweep-pub-nav");
    specs.scriptFailure("qits-sweep-pub-nav", "the git host must not be asked");
    UUID cause = UUID.randomUUID();
    String handedOff =
        deployment("qits-sweep-pub-nav", environmentId, "run-sweep-pub-nav", cause, "new-self");
    routing(handedOff, "/platform-deployments", 8080, "Deployments", 2);
    driver.scriptRunningImage("new-self", image(SHA));

    deployService.sweepInFlight();

    OutboxEvent active = only("DeploymentActive");
    assertTrue(active.payload.contains("\"deploymentId\":\"" + handedOff + "\""), active.payload);
    assertTrue(
        active.payload.contains("\"applicationName\":\"qits-sweep-pub-nav\""), active.payload);
    assertTrue(active.payload.contains("\"environmentName\":\"sweep-pub-nav\""), active.payload);
    assertTrue(active.payload.contains("\"commitSha\":\"" + SHA + "\""), active.payload);
    assertTrue(active.payload.contains("\"runId\":\"run-sweep-pub-nav\""), active.payload);
    assertTrue(active.payload.contains("\"containerName\":\"new-self\""), active.payload);
    // The snapshot the edge projects its route table from: the path, the wire alias nobody else can
    // derive, the port, and the navigation entry the primary route carries.
    assertTrue(active.payload.contains("\"path\":\"/platform-deployments\""), active.payload);
    assertTrue(
        active.payload.contains("\"upstreamHost\":\"sweep-pub-nav-qits-sweep-pub-nav\""),
        active.payload);
    assertTrue(active.payload.contains("\"upstreamPort\":8080"), active.payload);
    assertTrue(active.payload.contains("\"navigationLabel\":\"Deployments\""), active.payload);
    assertTrue(active.payload.contains("\"navigationPosition\":2"), active.payload);
    assertEquals(cause.toString(), active.parentId, "the adoption keeps the build's trace edge");
  }

  @Test
  public void navigationRidesThePrimaryRouteAlone() {
    String environmentId = createEnvironment("sweep-pub-two");
    String handedOff =
        deployment("qits-sweep-pub-two", environmentId, "run-sweep-pub-two", null, "new-two");
    routing(handedOff, "/two,/two-extra", 8080, "Two", 4);
    driver.scriptRunningImage("new-two", image(SHA));

    deployService.sweepInFlight();

    String payload = only("DeploymentActive").payload;
    assertTrue(payload.contains("\"path\":\"/two\""), payload);
    assertTrue(payload.contains("\"path\":\"/two-extra\""), payload);
    assertEquals(
        1,
        payload.split("\"navigationLabel\":\"Two\"", -1).length - 1,
        "a second route carries no navigation: " + payload);
  }

  @Test
  public void anApplicationThatDeclaresNoRoutesAnnouncesItsEmptySnapshot() {
    // The other side of the null: a row that RECORDED no routes is a real answer and is announced,
    // where a row that recorded nothing at all is not. Most applications are this one.
    String environmentId = createEnvironment("sweep-pub-none");
    String handedOff =
        deployment("qits-sweep-pub-none", environmentId, "run-sweep-pub-none", null, "new-none");
    routing(handedOff, "", 8080, null, null);
    driver.scriptRunningImage("new-none", image(SHA));

    deployService.sweepInFlight();

    String payload = only("DeploymentActive").payload;
    assertTrue(payload.contains("\"endpoints\":[]"), payload);
  }

  @Test
  public void aRowThatPredatesTheColumnsFallsBackToTheSpec() {
    // The one deployment per platform that has no snapshot to read: the one shipping the columns.
    // Its row was queued by a build that had none to fill, so the spec is read after all.
    String environmentId = createEnvironment("sweep-pub-legacy");
    specs.script(
        "qits-sweep-pub-legacy", spec(List.of("/legacy"), 8080, "Legacy", 7));
    deployment("qits-sweep-pub-legacy", environmentId, "run-sweep-pub-legacy", null, "new-legacy");
    // No routing() call: upstream_port stays null, which is what a pre-V3 row looks like.
    driver.scriptRunningImage("new-legacy", image(SHA));

    deployService.sweepInFlight();

    String payload = only("DeploymentActive").payload;
    assertTrue(payload.contains("\"path\":\"/legacy\""), payload);
    assertTrue(payload.contains("\"navigationLabel\":\"Legacy\""), payload);
  }

  @Test
  public void aPreColumnRowWhoseSpecCannotBeReadAnnouncesNothingRatherThanAnEmptySnapshot() {
    // The asymmetry that matters: a consumer REPLACES its snapshot, so an event with no endpoints
    // deletes the routes this deployment could not describe. Silence leaves them standing.
    String environmentId = createEnvironment("sweep-pub-blind");
    specs.scriptFailure("qits-sweep-pub-blind", "the git host did not answer");
    String handedOff =
        deployment("qits-sweep-pub-blind", environmentId, "run-sweep-pub-blind", null, "new-blind");
    driver.scriptRunningImage("new-blind", image(SHA));

    deployService.sweepInFlight();

    assertEquals(
        "ACTIVE",
        statusOf(handedOff),
        "the row is still settled — the announcement is what the unreadable spec costs");
    assertNull(only("DeploymentActive", 0), "an empty snapshot would delete the routes");
  }

  @Test
  public void aSupersededRowAnnouncesNothing() {
    // Only an adoption speaks. A row whose place a newer sha is already serving has nothing to say
    // about that sha's routes, and the deployment that put it there announced them itself.
    String environmentId = createEnvironment("sweep-pub-old");
    String superseded =
        deployment("qits-sweep-pub-old", environmentId, "run-sweep-pub-old", null, "rolled-back");
    routing(superseded, "/old", 8080, "Old", 5);
    driver.scriptRunningImage("rolled-back", image("a".repeat(40)));

    deployService.sweepInFlight();

    assertNull(
        only("DeploymentActive", 0),
        "a superseded row is not a statement that anything is live");
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static DeploymentSpec spec(
      List<String> routes, int upstreamPort, String navigationLabel, Integer navigationPosition) {
    return new DeploymentSpec(
        PdDeploymentTarget.ENVIRONMENT,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        routes,
        upstreamPort,
        navigationLabel,
        navigationPosition);
  }

  private static String image(String sha) {
    return "qits-platform-artifacts:8080/qits/qits-platform-deployments:" + sha;
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

  /**
   * A row in the state a handed-off self-update leaves behind: {@code STARTING}, named, and with
   * nobody left to record it.
   */
  private String deployment(
      String applicationName,
      String environmentId,
      String runId,
      UUID cause,
      String containerName) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = applicationName;
              row.environmentId = environmentId;
              row.commitSha = SHA;
              row.runId = runId;
              row.causationId = cause;
              row.status = PdDeploymentStatus.STARTING;
              row.containerName = containerName;
              row.createdAt = Instant.now();
              deployments.persist(row);
            });
    return id;
  }

  /**
   * V3's routing snapshot on an existing row — what {@code persistQueued} writes at queue time. A
   * row left without one is a row queued before those columns existed, which is the fallback case.
   */
  private void routing(
      String deploymentId,
      String routes,
      Integer upstreamPort,
      String navigationLabel,
      Integer navigationPosition) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = deployments.findById(deploymentId);
              row.routes = routes;
              row.upstreamPort = upstreamPort;
              row.navigationLabel = navigationLabel;
              row.navigationPosition = navigationPosition;
            });
  }

  private String statusOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.findById(deploymentId).status.name());
  }

  private List<OutboxEvent> rows() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery("select o from OutboxEvent o", OutboxEvent.class)
                    .getResultList());
  }

  private OutboxEvent only(String name) {
    OutboxEvent row = only(name, 1);
    assertNotNull(row, "expected one " + name + " row");
    return row;
  }

  private OutboxEvent only(String name, int expected) {
    List<OutboxEvent> matching = rows().stream().filter(row -> name.equals(row.name)).toList();
    assertEquals(expected, matching.size(), () -> "expected " + expected + " " + name + " row(s)");
    return matching.isEmpty() ? null : matching.get(0);
  }
}
