package eu.wohlben.qits.platform.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.platform.deployments.dockerhost.DockerHost;
import eu.wohlben.qits.platform.deployments.dockerhost.FakeDockerHost;
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
 * What the periodic observation settles, and — as importantly — what it refuses to touch. Package-local
 * so the pass is driven directly ({@link DeploymentObserver#observeOnce()}) and enqueued directly
 * ({@link DeployService#enqueueObservation()}), the {@link PdSweepAdoptionTest} shape: no tick runs
 * under a {@code @QuarkusTest}, because {@code onStart} returns early in test mode.
 *
 * <p>Rows are written straight to the table, like the sweep's tests, because the states under test are
 * ones no deployment can be talked into producing on demand — a {@code FAILED} row whose container is
 * nonetheless healthy is precisely the accident (eaa34fbc) this class exists for.
 */
@QuarkusTest
public class PdDeploymentObservationTest {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);

  @Inject FakeDockerHost driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject DeploymentObserver observer;
  @Inject DeployService deployService;
  @Inject PdDeploymentRepository deployments;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
  }

  private String deployment(
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName,
      String detail) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = applicationName;
              row.environmentId = environmentId;
              row.commitSha = SHA_A;
              row.status = status;
              row.containerName = containerName;
              row.detail = detail;
              row.createdAt = Instant.now();
              if (status != PdDeploymentStatus.QUEUED && status != PdDeploymentStatus.STARTING) {
                row.finishedAt = Instant.now();
              }
              deployments.persist(row);
            });
    return id;
  }

  private PdDeployment rowOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdDeployment row = deployments.findById(deploymentId);
              assertNotNull(row, "the row is still there");
              // Read every field the assertions need while the session is open.
              PdDeployment copy = new PdDeployment();
              copy.id = row.id;
              copy.status = row.status;
              copy.detail = row.detail;
              copy.containerName = row.containerName;
              copy.finishedAt = row.finishedAt;
              return copy;
            });
  }

  private String statusOf(String deploymentId) {
    return rowOf(deploymentId).status.name();
  }

  @Test
  public void aFailedLatestRowWhoseOwnContainerIsHealthyIsRecoveredByObservation() {
    // eaa34fbc itself: the cutover went healthy, the bookkeeping died on connections the cutover had
    // just killed, and the row said FAILED about a container that was serving.
    String container = "qits-pd-prod-obs-recovered-eaa34fbc";
    String failed =
        deployment(
            "obs-recovered",
            "env-obs-recovered",
            PdDeploymentStatus.FAILED,
            container,
            "[unexpected: JDBCConnectionException: Unable to acquire JDBC Connection]");
    driver.scriptContainerState(container, "running/healthy");

    observer.observeOnce();

    PdDeployment row = rowOf(failed);
    assertEquals(PdDeploymentStatus.ACTIVE, row.status);
    assertTrue(
        row.detail.contains("recovered by observation"),
        "the detail says an observation did this: " + row.detail);
    assertTrue(row.detail.contains(container), "and names the container it read: " + row.detail);
    assertTrue(
        row.detail.contains("JDBCConnectionException"),
        "the original failure is kept, not erased — it is the diagnosis: " + row.detail);
    // Rows only. A recovery is bookkeeping, and this class starts, stops and removes nothing.
    assertNoContainerWasTouched();
  }

  @Test
  public void aHealthyContainerOfAnotherDeploymentDoesNotResurrectAFailedRow() {
    // The row names the container that was removed when its deployment failed; something else on the
    // host is healthy. Only the row's OWN container may settle the row.
    String failed =
        deployment(
            "obs-foreign",
            "env-obs-foreign",
            PdDeploymentStatus.FAILED,
            "qits-pd-prod-obs-foreign-deadbeef",
            "[container exited]");
    driver.scriptContainerState("qits-pd-prod-obs-foreign-somebodyelse", "running/healthy");

    observer.observeOnce();

    assertEquals("FAILED", statusOf(failed), "a foreign container proves nothing about this row");
  }

  @Test
  public void anActiveRowWhoseContainerVanishedIsDemotedOnlyOnTheSecondPass() {
    // Patience in the other direction: one docker call that could not answer must never flip a
    // deployment that is serving, so a single pass is not a verdict.
    String active =
        deployment(
            "obs-vanished",
            "env-obs-vanished",
            PdDeploymentStatus.ACTIVE,
            "qits-pd-prod-obs-vanished-00000001",
            null);

    observer.observeOnce();
    assertEquals("ACTIVE", statusOf(active), "one absent observation is a hiccup, not a death");

    observer.observeOnce();
    PdDeployment row = rowOf(active);
    assertEquals(PdDeploymentStatus.FAILED, row.status);
    assertTrue(
        row.detail.contains("failed by observation"),
        "the detail says an observation did this: " + row.detail);
    assertTrue(
        row.detail.contains("qits-pd-prod-obs-vanished-00000001"),
        "and names what it observed: " + row.detail);
    assertNoContainerWasTouched();
  }

  @Test
  public void anActiveRowWhoseContainerExitedIsDemotedOnTheSecondPass() {
    // The other terminal answer: docker still has the container, and it is not coming back.
    String container = "qits-pd-prod-obs-exited-00000002";
    String active =
        deployment("obs-exited", "env-obs-exited", PdDeploymentStatus.ACTIVE, container, null);
    driver.scriptContainerState(container, "exited/unhealthy");

    observer.observeOnce();
    observer.observeOnce();

    PdDeployment row = rowOf(active);
    assertEquals(PdDeploymentStatus.FAILED, row.status);
    assertTrue(row.detail.contains("exited/unhealthy"), "docker's own words: " + row.detail);
  }

  @Test
  public void aRestartingOrUnhealthyContainerLeavesItsActiveRowAlone() {
    // The health gate's patience, applied to the observation: `--restart unless-stopped` brings a
    // PostgreSQL-backed container back seconds after its first boot died on an unresolvable alias,
    // and a container answering its probe with a failure is up. Neither is a dead deployment.
    String restarting = "qits-pd-prod-obs-restarting-00000003";
    String unhealthy = "qits-pd-prod-obs-unhealthy-00000004";
    String restartingRow =
        deployment(
            "obs-restarting", "env-obs-patience", PdDeploymentStatus.ACTIVE, restarting, null);
    String unhealthyRow =
        deployment("obs-unhealthy", "env-obs-patience", PdDeploymentStatus.ACTIVE, unhealthy, null);
    driver.scriptContainerState(restarting, "restarting/unhealthy");
    driver.scriptContainerState(unhealthy, "running/unhealthy");

    observer.observeOnce();
    observer.observeOnce();
    observer.observeOnce();

    assertEquals("ACTIVE", statusOf(restartingRow), "restarting is not dead");
    assertEquals("ACTIVE", statusOf(unhealthyRow), "running-but-unhealthy is not dead either");
  }

  @Test
  public void aFailedRowThatIsNotTheLatestForItsPlaceIsLeftAlone() {
    // History stays history. The older attempt really did fail; the container that is healthy today
    // belongs to the successor that replaced it, and reusing it to rewrite the past would erase the
    // only record that anything went wrong.
    String olderContainer = "qits-pd-prod-obs-history-00000005";
    String older =
        deployment(
            "obs-history",
            "env-obs-history",
            PdDeploymentStatus.FAILED,
            olderContainer,
            "[container exited]");
    String newerContainer = "qits-pd-prod-obs-history-00000006";
    String newer =
        deployment("obs-history", "env-obs-history", PdDeploymentStatus.ACTIVE, newerContainer, null);
    driver.scriptContainerState(olderContainer, "running/healthy");
    driver.scriptContainerState(newerContainer, "running/healthy");

    observer.observeOnce();

    assertEquals("FAILED", statusOf(older), "not the latest row for its place — never revisited");
    assertEquals("ACTIVE", statusOf(newer));
  }

  @Test
  public void aQueuedOrStartingRowIsNeverTouched() {
    // Those two belong to the worker's own state machine and to the startup sweep. A self-update
    // handoff sits in STARTING with a healthy successor on purpose, and an observation that promoted
    // it would take the surviving instance's decision away.
    String queued =
        deployment(
            "obs-inflight-q", "env-obs-inflight", PdDeploymentStatus.QUEUED, "qits-pd-q-00000007", null);
    String starting =
        deployment(
            "obs-inflight-s",
            "env-obs-inflight",
            PdDeploymentStatus.STARTING,
            "qits-pd-s-00000008",
            null);
    driver.scriptContainerState("qits-pd-s-00000008", "running/healthy");

    observer.observeOnce();
    observer.observeOnce();

    assertEquals("QUEUED", statusOf(queued));
    assertEquals("STARTING", statusOf(starting), "the handoff's outcome is the survivor's to record");
  }

  @Test
  public void aRecoveredRowRetiresThePredecessorItsOwnCutoverNeverDecommissioned() {
    // The bookkeeping that died in eaa34fbc was ONE bracket doing two things, so a row recovered
    // here may still have an older row claiming to serve. Two ACTIVE rows for one (application,
    // tier) is the invariant listActiveByApplication and the rollback pins are written around.
    String environmentId = "env-obs-pair";
    String predecessor =
        deployment(
            "obs-pair", environmentId, PdDeploymentStatus.ACTIVE, "qits-pd-prod-obs-pair-old", null);
    String container = "qits-pd-prod-obs-pair-new";
    String recovered =
        deployment(
            "obs-pair",
            environmentId,
            PdDeploymentStatus.FAILED,
            container,
            "[unexpected: JDBCConnectionException …]");
    driver.scriptContainerState(container, "running/healthy");

    observer.observeOnce();

    assertEquals("ACTIVE", statusOf(recovered));
    assertEquals(
        "DECOMMISSIONED", statusOf(predecessor), "one ACTIVE row per (application, tier), still");
    // ...and its container was NOT reaped. The startup sweep's stance: whatever still holds the
    // alias is absorbed by the next deployment's predecessor search.
    assertNoContainerWasTouched();
  }

  @Test
  public void anObservationPassNeverInterleavesWithAQueuedDeployment() {
    // Both go through the single deploy worker, which is what keeps "the previous ACTIVE deployment"
    // an uncontended read during a cutover. The deployment here is deliberately slow (a container
    // that restarts its way into health), and the pass is enqueued while it runs: every observation
    // has to land AFTER the deployment's last driver call, not between two of them.
    String environmentId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "obs-serial", "platform", false))
            .when()
            .post("/platform-deployments/api/environments")
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");
    // Two rows of this test's own, so a pass makes more than one observation and an interleaving
    // would be visible.
    deployment(
        "obs-serial-a", "env-obs-serial", PdDeploymentStatus.ACTIVE, "qits-pd-obs-serial-a", null);
    deployment(
        "obs-serial-b", "env-obs-serial", PdDeploymentStatus.ACTIVE, "qits-pd-obs-serial-b", null);
    // A predecessor to remove, so the deployment's own last driver call is unambiguous.
    driver.scriptAliasHolders(
        List.of(new DockerHost.Holder("ab".repeat(32), "obs-serial-predecessor", environmentId)));
    driver.scriptRestartingUntilHealthy(40);

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "runId", "run-obs-serial",
                "repoId", "repo-obs-serial",
                "branch", "environment/obs-serial",
                "commitSha", SHA_B))
        .when()
        .post("/platform-deployments/api/events/build-succeeded")
        .then()
        .statusCode(202);
    // The intake has already submitted the event, so this lands strictly behind it in the queue.
    deployService.enqueueObservation();
    awaitWorkerIdle();

    List<String> calls = driver.calls();
    int lastDeployCall = calls.indexOf("remove:obs-serial-predecessor");
    assertTrue(lastDeployCall >= 0, "the deployment ran to its cutover: " + calls);
    List<Integer> observations = new java.util.ArrayList<>();
    for (int i = 0; i < calls.size(); i++) {
      if (calls.get(i).startsWith("observe:")) {
        observations.add(i);
      }
    }
    assertTrue(observations.size() >= 2, "the pass observed more than one row: " + calls);
    assertTrue(
        observations.get(0) > lastDeployCall,
        "the pass waited for the whole deployment rather than interleaving with it: " + calls);
  }

  /**
   * The reaping stance, asserted rather than trusted: the observer writes rows and does nothing to a
   * container. It is the startup sweep's rule, and it matters more here — this runs forever, beside a
   * live platform.
   */
  private void assertNoContainerWasTouched() {
    assertEquals(List.of(), driver.started(), "nothing was started");
    assertEquals(List.of(), driver.stoppedContainers(), "nothing was stopped");
    assertEquals(List.of(), driver.removedContainers(), "nothing was removed");
  }

  /** Drain the worker — the pass is queued behind the event, so idle means both are done. */
  private void awaitWorkerIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }
}
