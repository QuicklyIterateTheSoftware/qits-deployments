package eu.wohlben.qits.platform.deployments.bus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.platform.deployments.deployments.control.DeployService;
import eu.wohlben.qits.platform.deployments.dockerhost.FakeDockerHost;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeSpecSource;
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
 * The bus door, end to end from a frame: what a {@code BuildSuccessful} event deploys, what it
 * refuses to deploy, and what it swallows.
 *
 * <p><b>It drives {@code onFrame} directly rather than through a stream.</b> The bus is dark in the
 * suite — no socket is dialled and no log is paged, which {@code PdEventstreamDarknessTest} is what
 * asserts — and what belongs here is this component's half: the decode, the tip check and the call
 * into {@code BuildAnnouncements}. The claim ledger, the funnel and the catch-up sweep are the
 * library's and are tested in its own repository; a stub qits-events here would re-prove them and
 * prove nothing about the deployment.
 *
 * <p>Every method uses its own environment name and repository id, because the suite shares one
 * database across classes and {@code BuildTips} keeps a per-process memory of what it announced.
 */
@QuarkusTest
public class PdBusBuildIntakeTest {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);

  @Inject FakeDockerHost driver;
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;
  @Inject PdBuildSuccessfulSubscriber subscriber;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
  }

  @Test
  public void theConsumerIdIsTheStorageKeyAndDoesNotDriftWithTheClassName() {
    // Changing it makes a brand-new consumer: the old claims are orphaned and the new id starts at
    // the head of the log, silently skipping everything in between. It is pinned here so a rename
    // of the class cannot take it along.
    assertEquals("pd-build-succeeded", subscriber.consumerId());
    assertEquals(java.util.Set.of("BuildSuccessful"), subscriber.signatures());
    assertFalse(
        subscriber.replayFromEpoch(),
        "a first deployment of this subscriber must not re-announce every build ever run");
  }

  @Test
  public void aBusEventDeploysTheCommitItNames() {
    createEnvironment("bus-plain", "environment/bus-plain");

    subscriber.onFrame(frame(Instant.now(), "repo-bus-plain", "environment/bus-plain", SHA_A));

    awaitStarted(1);
    assertEquals(SHA_A, driver.started().get(0).commitSha());
    assertTrue(
        driver.started().get(0).containerName().startsWith("qits-pd-bus-plain-repo-bus-plain-"),
        driver.started().get(0).containerName());
  }

  @Test
  public void aReplayedOlderBuildIsSkippedRatherThanRolledOverTheNewerOne() {
    // The failure this whole guard exists for: after a restart or a reconnect the catch-up sweep
    // hands over an event the stream never delivered, and it can be OLDER than one already
    // deployed. Applying it would be a rollback nobody asked for.
    createEnvironment("bus-order", "environment/bus-order");
    Instant newer = Instant.now();

    subscriber.onFrame(frame(newer, "repo-bus-order", "environment/bus-order", SHA_B));
    awaitStarted(1);

    subscriber.onFrame(
        frame(newer.minusSeconds(600), "repo-bus-order", "environment/bus-order", SHA_A));
    awaitWorkerIdle();

    assertEquals(1, driver.started().size(), "the stale build was not deployed");
    assertEquals(SHA_B, driver.started().get(0).commitSha(), "the newer commit is still what runs");
  }

  @Test
  public void twoGreenBuildsSecondsApartBothDeployInOrder() {
    // The other half of the same check, and the one a naive implementation gets wrong: a build's
    // own finish time is minutes older than the deployment row it will produce, so comparing an
    // arriving build against a ROW would skip a build that is genuinely newer. What this process
    // announced is remembered as an instant of the same kind, which is why it can tell these apart.
    createEnvironment("bus-pair", "environment/bus-pair");
    Instant first = Instant.now();

    subscriber.onFrame(frame(first, "repo-bus-pair", "environment/bus-pair", SHA_A));
    awaitStarted(1);
    subscriber.onFrame(frame(first.plusSeconds(1), "repo-bus-pair", "environment/bus-pair", SHA_B));
    awaitStarted(2);

    assertEquals(SHA_B, driver.started().get(1).commitSha());
  }

  @Test
  public void aBuildOlderThanWhatTheOtherDoorAlreadyDeployedIsSkipped() {
    // The cross-restart floor, staged without a restart: the HTTP intake deployed something and
    // this process never announced it, which is exactly the state a freshly booted subscriber is
    // in. The deployment row is then the only thing that knows, and it is enough.
    String environmentId = createEnvironment("bus-floor", "environment/bus-floor");
    postBuildSucceeded("repo-bus-floor", "environment/bus-floor", SHA_B);
    awaitDeployments(environmentId, 1);
    driver.reset();

    subscriber.onFrame(
        frame(
            Instant.now().minusSeconds(3600), "repo-bus-floor", "environment/bus-floor", SHA_A));
    awaitWorkerIdle();

    assertEquals(List.of(), driver.started(), "an hour-old build did not roll the running one");
  }

  @Test
  public void aBuildNewerThanTheLastDeploymentRowStillDeploys() {
    // The floor is a floor, not a stop: with nothing remembered, a build that finished after the
    // last deployment row was written is the tip and deploys.
    String environmentId = createEnvironment("bus-fresh", "environment/bus-fresh");
    postBuildSucceeded("repo-bus-fresh", "environment/bus-fresh", SHA_A);
    awaitDeployments(environmentId, 1);
    driver.reset();

    subscriber.onFrame(
        frame(Instant.now().plusSeconds(60), "repo-bus-fresh", "environment/bus-fresh", SHA_B));

    awaitStarted(1);
    assertEquals(SHA_B, driver.started().get(0).commitSha());
  }

  @Test
  public void anUnreadablePayloadIsSwallowedRatherThanThrown() {
    // A throw here rolls the library's claim back and leaves the event owed FOREVER — offered
    // again on every sweep, with the watermark stuck behind it, so one poison event stops this
    // consumer's catch-up. Retrying a payload that will not parse changes nothing, so it is warned
    // about and settled.
    createEnvironment("bus-poison", "environment/bus-poison");
    EventFrame poison =
        new EventFrame(
            UUID.randomUUID().toString(), "BuildSuccessful", Instant.now(), "not json", null, null);

    assertFalse(subscriber.selects(poison), "an unreadable payload selects nothing");
    subscriber.onFrame(poison);
    awaitWorkerIdle();

    assertEquals(List.of(), driver.started());
  }

  @Test
  public void aPayloadMissingTheTripleIsSwallowedToo() {
    // Same reasoning, one step later: the payload parses but names nothing this component could
    // deploy. Announcing it would only reach the identifier validation and be refused there.
    createEnvironment("bus-partial", "environment/bus-partial");
    EventFrame partial =
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"runId\":\"run-1\",\"branch\":\"environment/bus-partial\"}",
            null,
            null);

    subscriber.onFrame(partial);
    awaitWorkerIdle();

    assertEquals(List.of(), driver.started());
  }

  @Test
  public void aBranchNoTierListensToIsNotEvenClaimed() {
    // Most green builds on the platform are on such a branch. Answering false leaves no claim row
    // at all, which is what keeps the ledger proportional to the work rather than to the log.
    createEnvironment("bus-quiet", "environment/bus-quiet");

    assertFalse(subscriber.selects(frame(Instant.now(), "repo-bus-quiet", "main", SHA_A)));
    assertTrue(
        subscriber.selects(
            frame(Instant.now(), "repo-bus-quiet", "environment/bus-quiet", SHA_A)));
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** One frame as qits-events pushes it: a fresh id, the signature, and the canonical payload. */
  private static EventFrame frame(Instant occurredAt, String repoId, String branch, String sha) {
    String payload =
        ("{\"branch\":\"%s\",\"commitSha\":\"%s\",\"finishedAt\":\"%s\",\"repoId\":\"%s\","
                + "\"runId\":\"run-bus\"}")
            .formatted(branch, sha, occurredAt, repoId);
    return new EventFrame(
        UUID.randomUUID().toString(), "BuildSuccessful", occurredAt, payload, null, null);
  }

  private String createEnvironment(String name, String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "branch", branch, "platform", false))
        .when()
        .post("/platform-deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void postBuildSucceeded(String repoId, String branch, String sha) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-http", "repoId", repoId, "branch", branch, "commitSha", sha))
        .when()
        .post("/platform-deployments/api/events/build-succeeded")
        .then()
        .statusCode(202);
  }

  private void awaitStarted(int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.started().size() < count && System.currentTimeMillis() < deadline) {
      sleep();
    }
    assertEquals(count, driver.started().size(), "started containers");
  }

  private void awaitDeployments(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> deployments =
          given()
              .when()
              .get("/platform-deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments");
      if (deployments.size() == count
          && deployments.stream()
              .noneMatch(
                  d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")))) {
        return;
      }
      sleep();
    }
    fail("deployments of " + environmentId + " did not settle to " + count);
  }

  private void awaitWorkerIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
