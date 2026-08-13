package eu.wohlben.qits.platform.deployments.orchestration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import org.junit.jupiter.api.Test;

/**
 * The orchestrator key as a refusal. Plain JUnit: the guard is a value check, and booting an
 * application to fail its boot would prove the same thing at a hundred times the cost.
 *
 * <p>The value it most has to refuse is {@code docker}, and that is the whole reason the key
 * outlived the choice it used to make: a deployment carrying it from before the migration names an
 * orchestrator this build does not have, and a boot that fails naming the key is the only answer
 * that cannot deploy the platform onto something nobody meant.
 */
class DeploymentDriversTest {

  @Test
  void swarmIsTheOneValueThatPasses() {
    assertDoesNotThrow(() -> DeploymentDrivers.check("swarm"));
    assertDoesNotThrow(() -> DeploymentDrivers.check("  SWARM \n"), "stripped and case-folded");
  }

  @Test
  void theRetiredDockerValueFailsTheBootNamingTheKey() {
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> DeploymentDrivers.check("docker"));
    assertTrue(refused.getMessage().contains(DeploymentDriver.ORCHESTRATOR_KEY), refused.getMessage());
    assertTrue(refused.getMessage().contains("docker"), refused.getMessage());
    assertTrue(refused.getMessage().contains("swarm"), "and says what to put there instead");
  }

  @Test
  void soDoAMisspellingAndAnAbsentValue() {
    assertThrows(IllegalStateException.class, () -> DeploymentDrivers.check("swrm"));
    assertThrows(IllegalStateException.class, () -> DeploymentDrivers.check(""));
    assertThrows(IllegalStateException.class, () -> DeploymentDrivers.check(null));
  }
}
