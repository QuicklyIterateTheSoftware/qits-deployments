package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shipped state: {@code qits.platform.deployments.postgres.admin-password} has no default, so a
 * deployment nobody configured it for cannot provision anything.
 *
 * <p>It is its own class because the value is injected as an {@code Optional<String>} at bean
 * creation — taking it away needs a profile, and a profile needs a Quarkus restart.
 */
@QuarkusTest
@TestProfile(MissingAdminPasswordProfile.class)
public class ResourceProvisioningWithoutAdminPasswordTest {

  @Inject ResourceProvisioning provisioning;
  @Inject FakeResourceProvisioner provisioner;

  @BeforeEach
  void reset() {
    provisioner.reset();
  }

  @Test
  public void aDeploymentThatDeclaresAResourceFailsNamingTheKeyNobodySet() {
    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                provisioning.ensureAll(
                    "no-admin",
                    "no-admin-env",
                    List.of(new ResourceProvisioning.Resolved("db", "qits_no_admin"))));

    // The one actionable sentence there is: an authentication error from postgres would read like
    // anything but "nothing configured this".
    assertTrue(
        refused.getMessage().contains("qits.platform.deployments.postgres.admin-password"),
        refused.getMessage());
    assertEquals(List.of(), provisioner.requests(), "and no connection was attempted");
  }

  @Test
  public void aDeploymentThatDeclaresNothingIsUnaffected() {
    // Which is every application on the platform today: the key is read only where it is needed.
    assertEquals(List.of(), provisioning.ensureAll("no-admin", "no-admin-env", List.of()));
  }
}
