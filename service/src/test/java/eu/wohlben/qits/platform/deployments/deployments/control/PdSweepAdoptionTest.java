package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.platform.deployments.dockerhost.FakeDockerHost;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The startup sweep's two verdicts on an in-flight row, package-local so the suite can drive
 * {@link DeployService#sweepInFlight()} without a real StartupEvent: a {@code STARTING} row whose
 * container is THIS process is a self-update handoff that succeeded and is adopted; anything else
 * in flight was interrupted and fails, exactly as before.
 *
 * <p>The rows are written straight to the table rather than through an environment, and that is the
 * point since the extraction: the sweep must read nothing but cd's own columns, so it keeps working
 * while qits-serviceregistry is down. Nothing here calls the registry, and the stub is only present
 * because the application shares one.
 */
@QuarkusTest
public class PdSweepAdoptionTest {

  @Inject DeployService deployService;
  @Inject FakeDockerHost driver;
  @Inject PdDeploymentRepository deployments;

  @BeforeEach
  void reset() {
    driver.reset();
  }

  private String deployment(
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = applicationName;
              row.environmentId = environmentId;
              row.commitSha = "c".repeat(40);
              row.status = status;
              row.containerName = containerName;
              row.createdAt = Instant.now();
              if (status == PdDeploymentStatus.ACTIVE) {
                row.finishedAt = Instant.now();
              }
              deployments.persist(row);
            });
    return id;
  }

  private String statusOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.findById(deploymentId).status.name());
  }

  @Test
  public void aStartingRowWhoseContainerIsThisProcessIsAdoptedAndItsPredecessorDecommissioned() {
    String environmentId = "env-sweep-adopt";
    String predecessor =
        deployment("qits-platform-deployments", environmentId, PdDeploymentStatus.ACTIVE, "old-cd");
    String handedOff =
        deployment("qits-platform-deployments", environmentId, PdDeploymentStatus.STARTING, "new-cd");
    driver.scriptSelfId("abcdef123456");
    driver.scriptContainerId("new-cd", "abcdef123456" + "0".repeat(52));

    deployService.sweepInFlight();

    assertEquals("ACTIVE", statusOf(handedOff));
    assertEquals("DECOMMISSIONED", statusOf(predecessor));
  }

  @Test
  public void aPlatformHandoffIsAdoptedToo() {
    // A platform row carries no environment, and the predecessor lookup has to treat that null as
    // a value rather than as "any tier" — nulls are distinct to `=`, so the query tests for null
    // explicitly. Getting it wrong means this component's own self-update comes back having failed
    // its own deployment while a second row still claims to be ACTIVE.
    String predecessor = deployment("qits-platform-deployments", null, PdDeploymentStatus.ACTIVE, "old-single");
    String otherTier =
        deployment("qits-platform-deployments", "some-tier", PdDeploymentStatus.ACTIVE, "another-tiers-cd");
    String handedOff = deployment("qits-platform-deployments", null, PdDeploymentStatus.STARTING, "new-single");
    driver.scriptSelfId("bbccdd112233");
    driver.scriptContainerId("new-single", "bbccdd112233" + "0".repeat(52));

    deployService.sweepInFlight();

    assertEquals("ACTIVE", statusOf(handedOff));
    assertEquals("DECOMMISSIONED", statusOf(predecessor));
    assertEquals("ACTIVE", statusOf(otherTier), "another tier's copy is not this plane's to retire");
  }

  @Test
  public void aStartingRowWhoseContainerIsGoneStillFails() {
    String interrupted =
        deployment("qits-platform-deployments", "env-sweep-fail", PdDeploymentStatus.STARTING, "vanished");
    driver.scriptSelfId("abcdef123456");
    // containerId("vanished") answers blank: the referee rolled back and removed the successor.

    deployService.sweepInFlight();

    assertEquals("FAILED", statusOf(interrupted));
  }
}
