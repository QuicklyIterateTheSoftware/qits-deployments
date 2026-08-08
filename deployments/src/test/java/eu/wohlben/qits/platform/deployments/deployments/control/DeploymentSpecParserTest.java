package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The strict reader of {@code .config/qits/deployments.yml}, plain JUnit — the file decides where a
 * container runs and what may reach it, so what it rejects matters as much as what it accepts, and
 * every rejection has to name the file a person has to go and fix.
 */
class DeploymentSpecParserTest {

  private static final String SOURCE = ".config/qits/deployments.yml of qits-idp@abc1234";

  private DeploymentSpec parse(String yaml) {
    return DeploymentSpecParser.parse(yaml, SOURCE);
  }

  private String messageOf(String yaml) {
    SpecException thrown = assertThrows(SpecException.class, () -> parse(yaml));
    assertTrue(
        thrown.getMessage().startsWith(SOURCE), "every error names the file: " + thrown.getMessage());
    return thrown.getMessage();
  }

  @Test
  void anEmptyFileIsEveryDefault() {
    // The whole of backward compatibility: a repository that says nothing is an ordinary
    // environment application, exactly as every repository was before this file existed.
    assertEquals(DeploymentSpec.DEFAULTS, parse(""));
    assertEquals(DeploymentSpec.DEFAULTS, parse("# nothing to say yet\n"));
  }

  @Test
  void theFiveKeysAreReadAndCommentsAndQuotesAreNot() {
    DeploymentSpec spec =
        parse(
            """
            ---
            deployment_target: platform   # cross-environment

            deploy_branches: "environment/prod"
            health_path: /idp/q/health/ready
            """);
    assertEquals(PdDeploymentTarget.PLATFORM, spec.target());
    assertEquals(List.of("environment/prod"), spec.deployBranches());
    assertFalse(spec.availableOnEnv());
    assertEquals("/idp/q/health/ready", spec.healthPath());
    assertNull(spec.healthCmd());
  }

  @Test
  void aNonHttpImageDeclaresItsOwnProbeSpacesAndAll() {
    // The deployable-image case, and the reason the value gets no charset: postgres has neither
    // curl nor anything on 8080, so a path-shaped gate can never pass. The command is one argv
    // element that docker runs with /bin/sh -c, so its spaces, flags and || are the shell's.
    assertEquals(
        "pg_isready -U postgres || exit 1",
        parse("health_cmd: pg_isready -U postgres || exit 1\n").healthCmd());
    assertEquals(
        "test -f /var/lib/ready", parse("health_cmd: \"test -f /var/lib/ready\"\n").healthCmd());
  }

  @Test
  void aFileThatNamesNoHealthCmdGetsTheHttpProbe() {
    // Null is the statement "this image has an HTTP surface", which is every service the platform
    // had before deployable images existed.
    assertNull(parse("health_path: /idp/q/health/ready\n").healthCmd());
    assertNull(DeploymentSpec.DEFAULTS.healthCmd());
  }

  @Test
  void aHealthCmdAndAHealthPathTogetherAreAnError() {
    // Not two settings on one gate: the command replaces the whole HTTP mechanism, so a file with
    // both says two things about one thing and the writer has to pick.
    String message = messageOf("health_path: /q/health/ready\nhealth_cmd: pg_isready\n");
    assertTrue(message.contains("health_cmd"), message);
    assertTrue(message.contains("health_path"), message);
    // Either order, since neither is the one that "came second".
    assertTrue(
        messageOf("health_cmd: pg_isready\nhealth_path: /q/health/ready\n").contains("health_cmd"));
  }

  @Test
  void aBlankOrOversizedHealthCmdIsAnError() {
    // What is left to check once the charset is deliberately open: a probe that says nothing, and
    // one long enough to be a mistake.
    assertTrue(messageOf("health_cmd:\n").contains("health_cmd"));
    assertTrue(messageOf("health_cmd: \"   \"\n").contains("health_cmd"));
    assertTrue(messageOf("health_cmd: " + "x".repeat(513) + "\n").contains("health_cmd"));
    assertEquals("x".repeat(512), parse("health_cmd: " + "x".repeat(512) + "\n").healthCmd());
  }

  @Test
  void singletonIsAnAcceptedAliasForPlatformAndParsesToTheSameThing() {
    // The retired vocabulary. Repositories that carry the word were written against qits-cd and
    // must keep deploying across the cutover without a commit each, so it is a tolerance rather
    // than a second spelling: nothing downstream can tell the two apart.
    assertEquals(parse("deployment_target: platform\n"), parse("deployment_target: singleton\n"));
    assertEquals(PdDeploymentTarget.PLATFORM, parse("deployment_target: singleton\n").target());
  }

  @Test
  void theErrorMessageNamesTheCanonicalWordAndNotTheAlias() {
    // A repository being corrected is pointed at the word to use, not at the one it may keep using.
    String message = messageOf("deployment_target: Platform\n");
    assertTrue(message.contains("platform"), message);
    assertFalse(message.contains("singleton"), message);
    assertTrue(messageOf("deployment_target: everywhere\n").contains("environment"));
  }

  @Test
  void aFileThatNamesNoHealthPathLeavesItToTheConvention() {
    // Null is the statement "this repository said nothing", and registration turns that into the
    // derived path. The parser must not invent one here — it does not know the service's name.
    assertNull(parse("available_on_env: true\n").healthPath());
  }

  @Test
  void aHealthPathThatIsNotAnAbsolutePathIsAnError() {
    // The value ends up in a container's --health-cmd, so it is checked as strictly as the API's.
    assertTrue(messageOf("health_path: q/health/ready\n").contains("health_path"));
    assertTrue(messageOf("health_path: /q/health/ready?x=1\n").contains("health_path"));
    assertTrue(messageOf("health_path:\n").contains("health_path"));
  }

  @Test
  void aFileThatNamesNoDeployBranchesSaysSoWithAnEmptyList() {
    // The deployer decides nothing on this key — a build deploys wherever an environment listens to
    // its branch, on either plane — so "said nothing" has to stay distinguishable from "said none"
    // for the reader that does use it, the release flow.
    assertEquals(List.of(), parse("deployment_target: platform\n").deployBranches());
    assertEquals(List.of(), DeploymentSpec.DEFAULTS.deployBranches());
  }

  @Test
  void deployBranchesIsACommaSeparatedRefListAndEveryRefIsChecked() {
    // One line, because this file has no YAML sequences; a comma cannot occur in a ref name, which
    // is what makes the separator safe.
    assertEquals(
        List.of("environment/prod", "environment/dev"),
        parse("deploy_branches: environment/prod, environment/dev\n").deployBranches());
    assertTrue(messageOf("deploy_branches: ../../etc\n").contains("deploy_branches"));
    // A trailing comma, or the key with nothing after it, is a writer who meant to say something.
    assertTrue(messageOf("deploy_branches: environment/prod,\n").contains("deploy_branches"));
    assertTrue(messageOf("deploy_branches:\n").contains("deploy_branches"));
  }

  @Test
  void theRetiredBranchKeyIsNoLongerKnown() {
    // It named the platform plane's own deploy ref, and the plane has none: `environment/<name>` is
    // the whole set. A repository still carrying the key is corrected rather than half-obeyed.
    assertTrue(messageOf("deployment_target: platform\nbranch: release\n").contains("unknown key"));
  }

  @Test
  void aPublicNodeSaysSoAndNothingElseDoes() {
    assertTrue(parse("available_on_env: true\n").availableOnEnv());
    assertFalse(parse("available_on_env: false\n").availableOnEnv());
  }

  @Test
  void anUnknownKeyIsAnError() {
    // A lenient parser answers a typo with a default, which deploys the wrong topology in silence.
    assertTrue(messageOf("deployment_targets: platform\n").contains("unknown key"));
  }

  @Test
  void aDuplicateKeyIsAnError() {
    assertTrue(
        messageOf("deployment_target: environment\ndeployment_target: platform\n")
            .contains("duplicate key"));
  }

  @Test
  void aValueOutsideTheEnumIsAnError() {
    assertTrue(messageOf("available_on_env: yes\n").contains("true"));
  }

  @Test
  void aPublicPlatformServiceIsAContradiction() {
    // It already runs on every environment's networks, and the bundle is environment-scoped. The
    // alias has to hit the same wall, or the retired spelling would be a way around the rule.
    assertTrue(
        messageOf("deployment_target: platform\navailable_on_env: true\n")
            .contains("available_on_env"));
    assertTrue(
        messageOf("deployment_target: singleton\navailable_on_env: true\n")
            .contains("available_on_env"));
  }

  @Test
  void nestingAndNonMappingLinesAreErrors() {
    assertTrue(
        messageOf("available_on_env: false\n  deployment_target: platform\n").contains("nesting"));
    assertTrue(messageOf("just a sentence\n").contains("key: value"));
  }

  @Test
  void deployBranchesIsReadForSomebodyElseAndAcceptedOnEitherPlane() {
    // The key belongs to the release flow, which reads the same file for its promotion targets.
    // This parser is strict, so a key another reader needs is a key this reader has to know —
    // accepted-and-unused rather than a second file, and on both planes, since a strict parser that
    // refused it on one would fail those deployments outright.
    assertEquals(
        List.of("environment/prod"),
        parse("deployment_target: environment\ndeploy_branches: environment/prod\n")
            .deployBranches());
    assertEquals(
        List.of("environment/prod"),
        parse("deployment_target: platform\ndeploy_branches: environment/prod\n").deployBranches());
  }
}
