package eu.wohlben.qits.platformdeployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.platformdeployments.environments.error.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * The boundary validation of everything that reaches an argv but is never stored — plus the one
 * intake field that reaches neither and is bounded anyway (the run id, whose length is all that
 * could hurt). The names, branches and health paths the topology keeps are checked by {@code
 * PdIdentifiersTest} next door.
 *
 * <p>The two derived conventions are pinned here too, because they are the same kind of claim: an
 * image reference and a container name are strings this component composes and a publisher, an
 * operator or a bootstrap grep has to be able to predict.
 */
class DeploymentIdentifiersTest {

  @Test
  void shasAreHexObjectIds() {
    assertEquals("a".repeat(40), DeploymentIdentifiers.requireSha("a".repeat(40)));
    assertEquals("1234abc", DeploymentIdentifiers.requireSha("1234abc"));
    for (String hostile : new String[] {null, "", "latest", "HEAD", "a".repeat(65), "12345g7"}) {
      assertThrows(BadRequestException.class, () -> DeploymentIdentifiers.requireSha(hostile));
    }
  }

  @Test
  void repositoryIdsAreSlugs() {
    assertEquals("qits-workspaces", DeploymentIdentifiers.requireRepoId("qits-workspaces"));
    for (String hostile : new String[] {null, "", "-leads", "has space", "a/b", "id;rm"}) {
      assertThrows(
          BadRequestException.class,
          () -> DeploymentIdentifiers.requireRepoId(hostile),
          String.valueOf(hostile));
    }
  }

  @Test
  void runIdsAreOptionalAndBounded() {
    // Absent is a first-class answer: it is what a sender that names no run records.
    assertNull(DeploymentIdentifiers.requireRunId(null));
    // What qits-ci actually sends, and the shape of a hand-replayed one.
    assertEquals(
        "6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61",
        DeploymentIdentifiers.requireRunId("6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61"));
    assertEquals("run-1", DeploymentIdentifiers.requireRunId("run-1"));
    // The boundary is the point of the check — the column is varchar(255) and an oversized value
    // would fail the insert of a fire-and-forget delivery instead of answering the sender.
    assertEquals("a".repeat(64), DeploymentIdentifiers.requireRunId("a".repeat(64)));
    for (String hostile : new String[] {"", "a".repeat(65), "-leads", "has space", "id;rm", "a/b"}) {
      assertThrows(
          BadRequestException.class, () -> DeploymentIdentifiers.requireRunId(hostile), hostile);
    }
  }

  @Test
  void theImageReferenceConventionIsTheOneSpelled() {
    // Pins the exact shape a publisher has to tag: <registry>/<repository>/<application>:<sha>.
    assertEquals(
        "qits-artifacts:8080/qits/qits-gateway:" + "a".repeat(40),
        ImageRefs.imageRef("qits-artifacts:8080", "qits", "qits-gateway", "a".repeat(40)));
  }

  @Test
  void containerNamesCarryTheTierTheApplicationAndTheDeployment() {
    assertEquals(
        "qits-pd-some-epic-qits-gateway-0123abcd",
        ContainerNames.of("some-epic", "qits-gateway", "0123abcd-ffff-4000-8000-0000"));
    // A platform deployment has no environment to be named after, so the plane takes that place.
    // The prefix is the ancestor's `qits-cd-` renamed with everything else, and a bootstrap that
    // greps for containers greps for this.
    assertEquals(
        "qits-pd-platform-qits-idp-0123abcd",
        ContainerNames.of(null, "qits-idp", "0123abcd-ffff-4000-8000-0000"));
  }

  @Test
  void theHealthPathConventionStripsTheQitsPrefixAndNothingElse() {
    // The debt this closed: registration had no source for a path, every row was written null, and
    // every service mounted under its own prefix failed a gate against a URL that 404s.
    assertEquals(
        "/observability/q/health/ready", DeployService.conventionHealthPath("qits-observability"));
    assertEquals(
        "/platform-deployments/q/health/ready",
        DeployService.conventionHealthPath("qits-platform-deployments"));
    // A name without the prefix keeps the whole name...
    assertEquals("/mongrel/q/health/ready", DeployService.conventionHealthPath("mongrel"));
    // ...and so does one that is nothing BUT the prefix, rather than composing an empty segment.
    assertEquals("/qits-/q/health/ready", DeployService.conventionHealthPath("qits-"));
  }

  @Test
  void resourceAttributeValuesCarryNoListPunctuation() {
    // The three values that go into OTEL_RESOURCE_ATTRIBUTES pass as they are today: a sha, an
    // environment name, a container name composed from both.
    assertEquals(
        "a".repeat(40), DeploymentIdentifiers.requireAttributeValue("a".repeat(40), "sha"));
    assertEquals(
        "some-epic", DeploymentIdentifiers.requireAttributeValue("some-epic", "environment"));
    assertEquals(
        "qits-pd-some-epic-qits-gateway-0123abcd",
        DeploymentIdentifiers.requireAttributeValue(
            "qits-pd-some-epic-qits-gateway-0123abcd", "container name"));
    // The list's own punctuation is what the check exists for: a comma starts a second pair and an
    // equals sign moves the key/value boundary.
    for (String hostile : new String[] {null, "", "a,service.name=impostor", "a=b", " "}) {
      assertThrows(
          BadRequestException.class,
          () -> DeploymentIdentifiers.requireAttributeValue(hostile, "x"),
          String.valueOf(hostile));
    }
  }
}
