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
 * What this component records about its OWN databases at boot — the rows that make its next
 * self-deploy a no-op instead of a password rotation against pools it is holding open.
 *
 * <p>The startup observer is skipped under TEST (a suite boots dozens of times and has no
 * deployment environment), so this drives the package-private {@code record} directly — the
 * {@code PdSweepAdoptionTest} arrangement. Each test uses its own environment name, because the
 * suite shares one database and the application name is a constant here by design. The two
 * platform-plane tests have no environment name to differ in — there is one null key per resource —
 * so they take one resource each.
 */
@QuarkusTest
public class BootResourceRegistrationTest {

  @Inject BootResourceRegistration registration;
  @Inject PdResourceRepository resources;

  private PdResource rowOf(String environmentName) {
    return rowOf(environmentName, BootResourceRegistration.RESOURCE_NAME);
  }

  private PdResource rowOf(String environmentName, String resourceName) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                resources
                    .findOne(BootResourceRegistration.APPLICATION, environmentName, resourceName)
                    .orElseThrow(
                        () ->
                            new AssertionError(
                                "no " + resourceName + " resource row for " + environmentName)));
  }

  @Test
  public void aBootRecordsTheDatabaseAndTheCredentialItWasHanded() {
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
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
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-b-qits-oci-postgresql:5432/qits_deployments_b",
        "qits_deployments_b",
        "first-password-that-was-recorded",
        "boot-b");
    String id = rowOf("boot-b").id;

    // An operator rotated the password in deployment config and restarted. The environment is the truth: a
    // row still naming the old one would send the next self-deploy down the reconcile arm against
    // a credential that already works.
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
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
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-c-qits-oci-postgresql:5432/qits_deployments_c",
        "qits_deployments_c",
        "a-password",
        "boot-c");
    Instant stamped = Instant.parse("2026-08-09T10:00:00Z");
    QuarkusTransaction.requiringNew()
        .run(() -> rowOfManaged("boot-c").lastProvisionedAt = stamped);

    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-c-qits-oci-postgresql:5432/qits_deployments_c",
        "qits_deployments_c",
        "a-password",
        "boot-c");

    assertEquals(stamped, rowOf("boot-c").lastProvisionedAt);
  }

  @Test
  public void theEventstreamStoreGetsARowOfItsOwn() {
    // The second resource the spec declares, handed over by the bootstrap exactly as `db` is. Its
    // own row is what keeps the first self-deploy from rotating the bus client's password while
    // this instance's outbox pool is holding it — the same failure `db` records against, one
    // datasource over.
    registration.record(
        BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME,
        "jdbc:postgresql://boot-d-qits-oci-postgresql:5432/qits_deployments_eventstream",
        "qits_deployments_eventstream",
        "an-eventstream-password",
        "boot-d");
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-d-qits-oci-postgresql:5432/qits_deployments_d",
        "qits_deployments_d",
        "a-registry-password",
        "boot-d");

    PdResource eventstream = rowOf("boot-d", BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME);
    assertEquals("qits_deployments_eventstream", eventstream.databaseName);
    assertEquals("an-eventstream-password", eventstream.password);
    // Two rows for one tier, one per resource — neither overwrites the other.
    assertEquals("qits_deployments_d", rowOf("boot-d").databaseName);
  }

  @Test
  public void aPlatformBootRecordsOnThePlaneAndLeavesTheOldTierRowAlone() {
    // The row a platform that still ran this per tier has.
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-e-qits-oci-postgresql:5432/qits_deployments_e",
        "qits_deployments_e",
        "the-tier-password",
        "boot-e");

    // After the flip the swarm driver injects no QITS_ENVIRONMENT, so the boot records with none:
    // environment_name null is the key ResourceProvisioning looks a platform service's rows up by,
    // and a row under any other key would send the next self-deploy down the reconcile arm.
    registration.record(
        BootResourceRegistration.RESOURCE_NAME,
        "jdbc:postgresql://boot-e-qits-oci-postgresql:5432/qits_deployments_plane",
        "qits_deployments_plane",
        "the-platform-password",
        null);

    PdResource plane = rowOf(null);
    assertNull(plane.environmentName, "a platform service belongs to no tier");
    assertEquals("qits_deployments_plane", plane.databaseName);
    assertEquals("the-platform-password", plane.password);
    // The tier row is a leftover, not a conflict: same application name, so the cross-application
    // database check still passes, and nothing rewrites it.
    assertEquals("the-tier-password", rowOf("boot-e").password);
  }

  @Test
  public void aSecondPlatformBootRewritesTheOnePlaneRow() {
    registration.record(
        BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME,
        "jdbc:postgresql://boot-f-qits-oci-postgresql:5432/qits_deployments_plane_eventstream",
        "qits_deployments_plane_eventstream",
        "first-plane-password",
        null);
    String id = rowOf(null, BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME).id;

    registration.record(
        BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME,
        "jdbc:postgresql://boot-f-qits-oci-postgresql:5432/qits_deployments_plane_eventstream",
        "qits_deployments_plane_eventstream",
        "second-plane-password",
        null);

    PdResource row = rowOf(null, BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME);
    assertEquals(id, row.id, "the same row, rewritten — null is a value in the unique key");
    assertEquals("second-plane-password", row.password);
    assertEquals(
        1,
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    resources.listByDatabase("qits_deployments_plane_eventstream").size()),
        "one row for one database, however many times the process boots");
  }

  @Test
  public void theVariableNamesFollowTheResourceName() {
    // The generic contract: rename a resource in .config/qits/deployments.yml and the variables it
    // is injected as move with it. This is the one place that spelling is derived rather than typed.
    assertEquals(
        "QITS_RESOURCE_DB_URL",
        BootResourceRegistration.variable(BootResourceRegistration.RESOURCE_NAME, "URL"));
    assertEquals(
        "QITS_RESOURCE_EVENTSTREAM_PASSWORD",
        BootResourceRegistration.variable(
            BootResourceRegistration.EVENTSTREAM_RESOURCE_NAME, "PASSWORD"));
    assertEquals(
        "QITS_RESOURCE_OBJECT_STORE_USERNAME",
        BootResourceRegistration.variable("object-store", "USERNAME"),
        "a resource name is a dns label, and a hyphen is an underscore in an environment key");
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
