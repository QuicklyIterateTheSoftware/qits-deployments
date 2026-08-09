package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdResourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * What this component records about its OWN database at boot — the row that makes its next
 * self-deploy a no-op instead of a password rotation against a pool it is holding open.
 *
 * <p>The startup observer is skipped under TEST (a suite boots dozens of times and has no
 * deployment environment), so this drives the package-private {@code record} directly — the
 * {@code PdSweepAdoptionTest} arrangement. Each test uses its own environment name, because the
 * suite shares one database and the application name is a constant here by design.
 */
@QuarkusTest
public class BootResourceRegistrationTest {

  @Inject BootResourceRegistration registration;
  @Inject PdResourceRepository resources;

  private PdResource rowOf(String environmentName) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                resources
                    .findOne(
                        BootResourceRegistration.APPLICATION,
                        environmentName,
                        BootResourceRegistration.RESOURCE_NAME)
                    .orElseThrow(
                        () -> new AssertionError("no resource row for " + environmentName)));
  }

  @Test
  public void aBootRecordsTheDatabaseAndTheCredentialItWasHanded() {
    registration.record(
        "jdbc:postgresql://boot-a-qits-oci-postgresql:5432/qits_deployments_a",
        "qits_deployments_a",
        "0123456789abcdef0123456789abcdef",
        "boot-a");

    PdResource row = rowOf("boot-a");
    assertEquals("qits-deployments", row.applicationName);
    assertEquals("db", row.resourceName);
    assertEquals("postgresql", row.resourceType);
    assertEquals("qits_deployments_a", row.databaseName, "the database comes out of the url path");
    assertEquals("qits_deployments_a", row.roleName, "the role is the username it connects as");
    assertEquals("0123456789abcdef0123456789abcdef", row.password);
    // This component provisioned nothing — the bootstrap did — so it claims no check it never made.
    assertNull(row.lastProvisionedAt, "boot registration is a record, not a provisioning");
  }

  @Test
  public void aSecondBootRewritesTheOneRowRatherThanAddingAnother() {
    registration.record(
        "jdbc:postgresql://boot-b-qits-oci-postgresql:5432/qits_deployments_b",
        "qits_deployments_b",
        "first-password-that-was-recorded",
        "boot-b");
    String id = rowOf("boot-b").id;

    // An operator rotated the password in run-args and restarted. The environment is the truth: a
    // row still naming the old one would send the next self-deploy down the reconcile arm against
    // a credential that already works.
    registration.record(
        "jdbc:postgresql://boot-b-qits-oci-postgresql:5432/qits_deployments_b",
        "qits_deployments_b",
        "second-password-after-a-rotation",
        "boot-b");

    PdResource row = rowOf("boot-b");
    assertEquals(id, row.id, "the same row, rewritten");
    assertEquals("second-password-after-a-rotation", row.password);
    assertEquals(
        1,
        QuarkusTransaction.requiringNew()
            .call(() -> resources.listByDatabase("qits_deployments_b").size()),
        "one row for one database, however many times the process boots");
  }

  @Test
  public void aRewriteKeepsWhateverAProvisioningRecorded() {
    // The reverse order: a deploy provisioned and stamped the row, then the container restarted.
    // The stamp says when the role and the database were last CONFIRMED to exist, which a boot
    // cannot know and must not overwrite.
    registration.record(
        "jdbc:postgresql://boot-c-qits-oci-postgresql:5432/qits_deployments_c",
        "qits_deployments_c",
        "a-password",
        "boot-c");
    Instant stamped = Instant.parse("2026-08-09T10:00:00Z");
    QuarkusTransaction.requiringNew()
        .run(() -> rowOfManaged("boot-c").lastProvisionedAt = stamped);

    registration.record(
        "jdbc:postgresql://boot-c-qits-oci-postgresql:5432/qits_deployments_c",
        "qits_deployments_c",
        "a-password",
        "boot-c");

    assertEquals(stamped, rowOf("boot-c").lastProvisionedAt);
  }

  @Test
  public void theDatabaseIsTheLastPathSegmentAndAUrlWithoutOneIsRefused() {
    assertEquals(
        "qits_deployments",
        BootResourceRegistration.databaseOf(
            "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_deployments"));
    assertEquals(
        "qits_artifacts",
        BootResourceRegistration.databaseOf(
            "jdbc:postgresql://host:5432/qits_artifacts?ApplicationName=x&ssl=false"),
        "the query string is not part of the name");
    assertThrows(
        IllegalArgumentException.class,
        () -> BootResourceRegistration.databaseOf("jdbc:postgresql://host:5432/"));
  }

  /** Inside a transaction, so the returned entity is managed and a field write is persisted. */
  private PdResource rowOfManaged(String environmentName) {
    return resources
        .findOne(
            BootResourceRegistration.APPLICATION,
            environmentName,
            BootResourceRegistration.RESOURCE_NAME)
        .orElseThrow();
  }
}
