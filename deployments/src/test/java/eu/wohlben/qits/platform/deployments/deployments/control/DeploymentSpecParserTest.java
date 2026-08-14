package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
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
  void aRepositoryAsksForADatabaseOfItsOwnWithOneLine() {
    // The three shapes the grammar has, and all three are in the plan's own examples: the bare
    // default, an explicit database, and two resources on one line. The type is spelled out so a
    // second one can arrive without changing the shape.
    assertEquals(
        List.of(new ResourceSpec("db", null)), parse("resources: postgresql:db\n").resources());
    assertEquals(
        List.of(new ResourceSpec("db", "qits_artifacts")),
        parse("resources: postgresql:db:qits_artifacts\n").resources());
    assertEquals(
        List.of(new ResourceSpec("projects", null), new ResourceSpec("epics", "qits_epics")),
        parse("resources: postgresql:projects, postgresql:epics:qits_epics\n").resources());
  }

  @Test
  void anOmittedDatabaseIsNullBecauseTheParserDoesNotKnowTheApplication() {
    // Null is "the convention", not "no database": the default is qits_ plus the application name
    // without its qits- prefix, and this parser has never been told which application it is
    // reading for. DeployService.register resolves it.
    assertNull(parse("resources: postgresql:db\n").resources().get(0).database());
  }

  @Test
  void aFileThatNamesNoResourcesGetsAnEmptyListAndNotANull() {
    assertEquals(List.of(), parse("available_on_env: true\n").resources());
    assertEquals(List.of(), DeploymentSpec.DEFAULTS.resources());
  }

  @Test
  void aResourceTypeThisComponentCannotProvisionIsAnError() {
    // The strictness that matters most here: a typo answered with a default would be a deployment
    // whose application boots without the credential it declared it needs.
    String message = messageOf("resources: mysql:db\n");
    assertTrue(message.contains("postgresql"), message);
    assertTrue(messageOf("resources: db\n").contains("resources"), "a bare name is not an entry");
    assertTrue(
        messageOf("resources: postgresql:db:qits_a:extra\n").contains("resources"),
        "and neither is a fourth segment");
  }

  @Test
  void aResourceNameOrDatabaseOutsideItsCharsetIsAnError() {
    // Both values are repository-authored and both end up somewhere that cannot be parametrized —
    // the name in an env key on a docker run, the database in DDL against the platform's shared
    // postgres. So they are allowlists, and this is the parser end of the three checkpoints.
    assertTrue(messageOf("resources: postgresql:DB\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db name\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:-db\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db-\n").contains("resources"));
    // The qits_ prefix is what structurally excludes postgres, template0/1 and every pg_* name.
    assertTrue(messageOf("resources: postgresql:db:postgres\n").contains("qits_"));
    assertTrue(messageOf("resources: postgresql:db:template1\n").contains("qits_"));
    assertTrue(messageOf("resources: postgresql:db:pg_catalog\n").contains("qits_"));
    assertTrue(messageOf("resources: postgresql:db:qits_a; drop database x\n").contains("qits_"));
  }

  @Test
  void aBlankEntryIsAnError() {
    // `resources:` with nothing after it, and a trailing comma: a writer who meant to say
    // something. The deploy_branches rule, applied to the key that provisions.
    assertTrue(messageOf("resources:\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db,\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db, ,postgresql:other\n").contains("resources"));
  }

  @Test
  void namingOneThingTwiceIsAnError() {
    // A repeated name would make one env triple silently overwrite another; a repeated literal
    // database would point two of a repository's own resources at one store.
    assertTrue(
        messageOf("resources: postgresql:db, postgresql:db\n").contains("twice"),
        "the same resource name");
    assertTrue(
        messageOf("resources: postgresql:a:qits_x, postgresql:b:qits_x\n").contains("twice"),
        "the same database under two names");
  }

  @Test
  void anIndentedResourceListIsStillNesting() {
    // The grammar is flat because the file is. A YAML sequence would need a parser this file
    // deliberately does not have, which is why the entries share one comma-separated line.
    assertTrue(
        messageOf("resources: postgresql:db\n  - postgresql:other\n").contains("nesting"),
        "an indented list is refused before it can look like it works");
  }

  @Test
  void theScalarKeysAreReadAndCommentsAndQuotesAreNot() {
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
  void anApplicationThatCannotOverlapItselfSaysStopFirst() {
    // The default is the lossless one — the successor starts beside the predecessor, so an
    // orchestrator that fails it reverts to something that never stopped serving. What a repository
    // says here is that it cannot be two processes at once: one binder per published host port, one
    // writer per store, one holder of a config volume.
    assertEquals(
        DeploymentDriver.UpdateOrder.START_FIRST, parse("").updateOrder(), "the default");
    assertEquals(
        DeploymentDriver.UpdateOrder.START_FIRST,
        parse("update_order: start-first\n").updateOrder());
    assertEquals(
        DeploymentDriver.UpdateOrder.STOP_FIRST, parse("update_order: stop-first\n").updateOrder());
  }

  @Test
  void anUpdateOrderOutsideThePairIsAnError() {
    // Refused rather than defaulted, like every other value here: the difference between the two is
    // whether an application is ever two processes at once, and a silent default answers that
    // question for a repository that was trying to answer it itself.
    String message = messageOf("update_order: rolling\n");
    assertTrue(message.contains("start-first"), message);
    assertTrue(message.contains("stop-first"), message);
    assertTrue(message.contains("rolling"), "the message names what was written: " + message);
    // The enum's own spelling is the file's, so neither side has to translate.
    assertTrue(messageOf("update_order: STOP_FIRST\n").contains("update_order"));
  }

  @Test
  void aServiceWhosePortMustSurviveItsOwnReplacementSaysIngress() {
    // The default is what every publishing service does today — the task binds the port on the
    // node — so a file that says nothing is deployed byte-for-byte as before. `ingress` gives the
    // port to swarm's routing mesh, which keeps holding it while the successor starts.
    assertEquals(DeploymentDriver.PublishMode.HOST, parse("").publishMode(), "the default");
    assertEquals(
        DeploymentDriver.PublishMode.HOST, parse("publish_mode: host\n").publishMode());
    assertEquals(
        DeploymentDriver.PublishMode.INGRESS, parse("publish_mode: ingress\n").publishMode());
  }

  @Test
  void aPublishModeOutsideThePairIsAnError() {
    // Refused rather than defaulted: the difference is whether a replacement can start while the
    // predecessor still holds the port, which is the whole reason the key exists.
    String message = messageOf("publish_mode: mesh\n");
    assertTrue(message.contains("host"), message);
    assertTrue(message.contains("ingress"), message);
    assertTrue(message.contains("mesh"), "the message names what was written: " + message);
    // Docker's own spelling is the file's, so neither side has to translate.
    assertTrue(messageOf("publish_mode: INGRESS\n").contains("publish_mode"));
  }

  @Test
  void thePublishModeAndTheUpdateOrderAreIndependentStatements() {
    // Nothing here derives one from the other. An ingress-mode service keeps the default
    // start-first — that is the point of ingress — and one that declares stop-first gets it.
    DeploymentSpec ingress = parse("publish_mode: ingress\n");
    assertEquals(DeploymentDriver.UpdateOrder.START_FIRST, ingress.updateOrder());
    DeploymentSpec both = parse("publish_mode: ingress\nupdate_order: stop-first\n");
    assertEquals(DeploymentDriver.UpdateOrder.STOP_FIRST, both.updateOrder());
    assertEquals(DeploymentDriver.PublishMode.INGRESS, both.publishMode());
    // ...and a host-mode service is not pushed onto stop-first either: the repository says so.
    assertEquals(
        DeploymentDriver.UpdateOrder.START_FIRST, parse("publish_mode: host\n").updateOrder());
  }

  @Test
  void anUnknownKeyIsAnError() {
    // A lenient parser answers a typo with a default, which deploys the wrong topology in silence.
    String message = messageOf("deployment_targets: platform\n");
    assertTrue(message.contains("unknown key"), message);
    // ...and the message lists every key there is, so a typo is answered with the vocabulary.
    assertTrue(message.contains("resources"), message);
    assertTrue(message.contains("update_order"), message);
    assertTrue(message.contains("publish_mode"), message);
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
