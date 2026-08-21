package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.environments.error.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * The one decision the identity rollback put in this component: which of the two coordinates an
 * application is named after.
 *
 * <p>Every claim here is the same claim from a different side — the NAME wins whenever the event
 * carried one, because the image tag, the wire alias, the container name, the provisioned database
 * and the GC keep-set are all derived from it and every pipeline yml pushes a literal {@code
 * qits/<name>:<sha>}. A storage UUID reaching any of them is a deployment that ends IMAGE_MISSING
 * and a garbage collector that deletes what is running.
 */
public class RepositoryRefTest {

  private static final String UUID_ID = "6d0c2b1e-3a44-4b0e-9a5b-2b1c0d9e4f88";

  @Test
  public void theNameIsTheApplicationNameWhenTheEventCarriedOne() {
    RepositoryRef ref = new RepositoryRef(UUID_ID, "qits", "qits-gateway");

    assertEquals("qits-gateway", ref.applicationName());
    assertEquals(UUID_ID, ref.repoId(), "the storage id stays the repository's reference");
    assertTrue(ref.nameAddressed());
  }

  @Test
  public void anEventWithNoNameFallsBackToTheRepositoryId() {
    // Compat, not a guess: before the rollback the storage id WAS the name, so an older publisher's
    // event names its application exactly as it always did.
    RepositoryRef ref = RepositoryRef.ofId("qits-gateway");

    assertEquals("qits-gateway", ref.applicationName());
    assertFalse(ref.nameAddressed());
  }

  @Test
  public void oneHalfOfTheAddressIsNoAddress() {
    // /git/<projectId>/<repoName> has no meaning with a half missing, so the id route is used —
    // but the NAME, if that is the half present, is still what the application is called.
    assertFalse(new RepositoryRef(UUID_ID, "qits", null).nameAddressed());
    assertFalse(new RepositoryRef(UUID_ID, null, "qits-gateway").nameAddressed());
    assertEquals(UUID_ID, new RepositoryRef(UUID_ID, "qits", null).applicationName());
    assertEquals("qits-gateway", new RepositoryRef(UUID_ID, null, "qits-gateway").applicationName());
  }

  @Test
  public void aBlankFieldOnTheWireIsAnAbsentOne() {
    RepositoryRef ref = new RepositoryRef("qits-gateway", "", "  ");

    assertNull(ref.projectId());
    assertNull(ref.repoName());
    assertEquals("qits-gateway", ref.applicationName());
  }

  @Test
  public void everyCoordinateIsCheckedAtTheBoundary() {
    // All three reach a URL path and the name additionally reaches an argv, so all three take the
    // slug discipline. A UUID is an ordinary slug and passes, which is the point of the rollback.
    new RepositoryRef(UUID_ID, "qits", "qits-gateway").validated();
    RepositoryRef.ofId(UUID_ID).validated();

    assertThrows(
        BadRequestException.class,
        () -> new RepositoryRef(UUID_ID, "qits", "../../etc").validated());
    assertThrows(
        BadRequestException.class,
        () -> new RepositoryRef(UUID_ID, "qits/other", "qits-gateway").validated());
    assertThrows(BadRequestException.class, () -> RepositoryRef.ofId(null).validated());
  }
}
