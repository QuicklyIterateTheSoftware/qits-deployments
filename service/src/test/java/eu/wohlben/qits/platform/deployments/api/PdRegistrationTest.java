package eu.wohlben.qits.platform.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.platform.deployments.deployments.control.DeployService;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeSpecSource;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Derived registration and deploy resolution: what a green build <b>writes</b> into the catalogue,
 * and what it <b>reads back</b> to decide where to deploy.
 *
 * <p>The ancestor's version of this suite ran against a stub HTTP server standing in for
 * qits-serviceregistry, and had a twin ({@code CdRegistryOutageTest}) for the case where the peer
 * was down. Both dissolved with the merge: registration is a local transaction, so there is no wire
 * to stub and no outage to have a posture about. Every claim the first suite made survives here,
 * asserted through this component's own read surface instead of through a stub's recorded calls.
 * The claims of the second are gone with the failure mode — except one, which was never about the
 * peer at all and is held by {@code PdPinApiTest}: the pins read nothing but deployment rows.
 */
@QuarkusTest
public class PdRegistrationTest {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);

  /** What a githost repository key looks like since the identity rollback: opaque, and internal. */
  private static final String STORAGE_UUID = "6d0c2b1e-3a44-4b0e-9a5b-2b1c0d9e4f88";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
  }

  @Test
  public void aGreenBuildRegistersTheServiceAndLinksItIntoEveryMatchingTier() {
    // Two tiers on one branch is legitimate, and the link set written has to carry both: the upsert
    // replaces the whole set, so one link at a time would unlink the other.
    String one = createEnvironment("reg-one", "environment/reg-shared");
    String two = createEnvironment("reg-two", "environment/reg-shared");
    postBuildSucceeded("repo-reg", "environment/reg-shared", SHA_A);
    awaitApplied(2);

    Map<String, Object> service = service("repo-reg");
    assertEquals("ENVIRONMENT", service.get("target"));
    assertNull(service.get("branch"), "an environment service takes its branch from its tier");
    assertEquals(List.of(one, two), service.get("environmentIds"));
  }

  @Test
  public void aBuildOnOneBranchKeepsTheLinksAnotherTierAlreadyHad() {
    // The regression this guards: the upsert replaces the link set, so a build on environment/dev
    // that sent only dev's id would silently unlink preprod.
    String dev = createEnvironment("reg-dev", "environment/reg-dev");
    String preprod = createEnvironment("reg-preprod", "environment/reg-preprod");
    postBuildSucceeded("repo-both", "environment/reg-preprod", SHA_A);
    awaitApplied(1);
    postBuildSucceeded("repo-both", "environment/reg-dev", SHA_B);
    awaitApplied(2);

    assertEquals(
        List.of(preprod, dev),
        service("repo-both").get("environmentIds"),
        "the preprod link survived the dev build");
  }

  @Test
  public void aPublicNodeIsRegisteredWithAvailableOnEnv() {
    String environmentId = createEnvironment("reg-hub", "environment/reg-hub");
    specs.script(
        "repo-reg-gw",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.ENVIRONMENT, true, null, null, null, null));
    postBuildSucceeded("repo-reg-gw", "environment/reg-hub", SHA_A);
    awaitApplied(1);

    Map<String, Object> service = service("repo-reg-gw");
    assertEquals(true, service.get("availableOnEnv"), "the spec's availableOnEnv is written down");
    assertEquals(List.of(environmentId), service.get("environmentIds"));
  }

  @Test
  public void aPlatformServiceIsRegisteredWithNoBranchAndNoLinks() {
    // A platform service has NO links, and that absence is the model: it is implicitly present
    // everywhere, which is what makes a new environment pick it up without anyone editing it. It
    // carries no branch either, and that is the newer half: the plane has no deploy ref of its own,
    // so there is nothing to write down.
    createPlatformEnvironment("reg-platform", "environment/reg-platform");
    specs.script(
        "repo-reg-idp",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postBuildSucceeded("repo-reg-idp", "environment/reg-platform", SHA_A);
    awaitApplied(1);

    Map<String, Object> service = service("repo-reg-idp");
    assertEquals("PLATFORM", service.get("target"));
    assertNull(service.get("branch"), "the plane has no deploy ref of its own any more");
    assertEquals(List.of(), service.get("environmentIds"));
  }

  @Test
  public void aPlatformServiceDeploysFromAnEnvironmentsBranch() {
    // The deploy refs are `environment/<name>` and nothing else — one set for the whole platform,
    // asked the same way on both planes. What it deploys is still platform-shaped: one instance,
    // no environment, no links.
    createPlatformEnvironment("reg-trunk", "environment/reg-trunk");
    specs.script(
        "repo-reg-trunk",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postBuildSucceeded("repo-reg-trunk", "environment/reg-trunk", SHA_A);
    awaitApplied(1);

    Map<String, Object> service = service("repo-reg-trunk");
    assertEquals("PLATFORM", service.get("target"));
    assertEquals(List.of(), service.get("environmentIds"));
    assertNull(driver.applied().get(0).environmentId(), "one instance, on no tier");
  }

  @Test
  public void aPlatformServiceBuiltOnMainDeploysNothingAtAll() {
    // `main` is the integration trunk and no environment listens to it: it builds and ships
    // nothing. A release reaches an environment by fast-forwarding `environment/<name>` onto it,
    // and until that push the event on the trunk must leave no row and start no container.
    createPlatformEnvironment("reg-mainonly", "environment/reg-mainonly");
    specs.script(
        "repo-reg-mainonly",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postBuildSucceeded("repo-reg-mainonly", "main", SHA_A);
    awaitWorkerIdle();

    assertNull(service("repo-reg-mainonly"), "nothing was registered");
    assertEquals(List.of(), driver.applied());
    assertEquals(List.of(), driver.pulled());
  }

  @Test
  public void theRetiredSingletonSpellingStillRegistersAPlatformService() {
    // The alias is not a parser curiosity: a repository still carrying the word must keep deploying
    // across the cutover, and what it gets has to be the platform plane in every respect.
    createPlatformEnvironment("reg-alias", "environment/reg-alias");
    specs.script(
        "repo-alias", DeploymentSpecParserAlias.parse("deployment_target: singleton\n"));
    postBuildSucceeded("repo-alias", "environment/reg-alias", SHA_A);
    awaitApplied(1);

    assertEquals("PLATFORM", service("repo-alias").get("target"));
    // No environment segment in the derived name: a platform service belongs to no tier, and the
    // word that used to fill the gap is in the repository names now.
    assertTrue(
        driver.applied().get(0).deploymentName().startsWith("qits-pd-repo-alias-"),
        driver.applied().get(0).deploymentName());
  }

  @Test
  public void aPlatformDeploymentIsReadBackByAskingForThePlaneByName() {
    // The gap this closes: the deployment listing's filter is required, so the plane that has no
    // environment id could not be asked for at all — every platform row was recorded and then
    // unreadable, and a client drawing "what is deployed" showed the tiers and nothing else.
    // `platform` is the stand-in the application id already carries, reused as the filter value.
    createPlatformEnvironment("reg-plane", "environment/reg-plane");
    specs.script(
        "repo-reg-plane",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postBuildSucceeded("repo-reg-plane", "environment/reg-plane", SHA_A);
    awaitApplied(1);
    awaitWorkerIdle();

    Map<String, Object> deployment =
        platformDeployments().stream()
            .filter(d -> "repo-reg-plane".equals(d.get("applicationName")))
            .findFirst()
            .orElseGet(() -> fail("the platform listing did not carry the deployment"));
    assertEquals("ACTIVE", deployment.get("status"));
    assertEquals(
        "platform:repo-reg-plane",
        deployment.get("applicationId"),
        "the id a client joins against the applications listing");
  }

  @Test
  public void thePlatformPlaneCarriesNoTieredDeployment() {
    // The filter is a filter, not a widening: asking for the plane must not answer with the rows of
    // every tier as well. An environment deployment and a platform one, and only the latter comes
    // back.
    createPlatformEnvironment("reg-planes", "environment/reg-planes");
    postBuildSucceeded("repo-reg-tiered", "environment/reg-planes", SHA_A);
    awaitApplied(1);
    specs.script(
        "repo-reg-crossplane",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postBuildSucceeded("repo-reg-crossplane", "environment/reg-planes", SHA_A);
    awaitApplied(2);
    awaitWorkerIdle();

    List<String> names = platformDeployments().stream().map(d -> (String) d.get("applicationName")).toList();
    assertTrue(names.contains("repo-reg-crossplane"), names.toString());
    assertTrue(!names.contains("repo-reg-tiered"), "a tier's rows stayed out of the plane: " + names);
  }

  @Test
  public void aRepositoryThatNamesNoHealthPathGetsTheConventionOne() {
    // The debt this closes: registration once had no source for the path, so every row was written
    // null and every service mounted under its own prefix failed a gate against a URL that 404s.
    createEnvironment("reg-health", "environment/reg-health");
    postBuildSucceeded("qits-observability", "environment/reg-health", SHA_A);
    awaitApplied(1);

    assertEquals(
        "/observability/q/health/ready",
        service("qits-observability").get("healthPath"),
        "the convention is derived from the name and WRITTEN, not left to the deploy default");
    assertEquals("/observability/q/health/ready", driver.applied().get(0).healthPath());
  }

  @Test
  public void theRepositorysOwnHealthPathWinsOverTheConvention() {
    // The gateway owns the root path space, so the convention would send its gate to a 404.
    createEnvironment("reg-health-gw", "environment/reg-health-gw");
    specs.script(
        "qits-gateway",
        new SpecSource.DeploymentSpec(
            PdDeploymentTarget.ENVIRONMENT, true, null, "/q/health/ready", null, null));
    postBuildSucceeded("qits-gateway", "environment/reg-health-gw", SHA_A);
    awaitApplied(1);

    assertEquals("/q/health/ready", service("qits-gateway").get("healthPath"));
  }

  @Test
  public void aDeclaredHealthCmdReachesTheDriverAndNoRowHoldsIt() {
    // The deployable-image case end to end: a plain image names its own probe, the driver is
    // handed it, and nothing is written down — the spec is read again before every
    // deployment, so a column would only be a second copy to keep right.
    createEnvironment("reg-health-cmd", "environment/reg-health-cmd");
    specs.script(
        "qits-db",
        new SpecSource.DeploymentSpec(
            PdDeploymentTarget.ENVIRONMENT,
            false,
            null,
            null,
            "pg_isready -U postgres || exit 1",
            null));
    postBuildSucceeded("qits-db", "environment/reg-health-cmd", SHA_A);
    awaitApplied(1);

    assertEquals("pg_isready -U postgres || exit 1", driver.applied().get(0).healthCmd());
    // The row keeps the convention path it always would have: the command is this deployment's,
    // not the service's identity.
    assertEquals("/db/q/health/ready", service("qits-db").get("healthPath"));
  }

  @Test
  public void anOperatorsHealthPathSurvivesAReRegistration() {
    // A path already on the row is somebody's fix for a service the convention could not guess. A
    // later green build that says nothing about the path must leave it alone.
    String environmentId = createEnvironment("reg-health-keep", "environment/reg-health-keep");
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "deploymentTarget", "ENVIRONMENT",
                "availableOnEnv", false,
                "healthPath", "/hand/placed/health",
                "environmentIds", List.of(environmentId)))
        .when()
        .put("/platform-deployments/api/services/qits-odd")
        .then()
        .statusCode(201);

    postBuildSucceeded("qits-odd", "environment/reg-health-keep", SHA_A);
    awaitApplied(1);

    assertEquals("/hand/placed/health", service("qits-odd").get("healthPath"));
  }

  @Test
  public void aPlatformServiceGetsTheSameHealthPathResolution() {
    // The platform plane is not a different rule: the convention, the spec and an existing value
    // rank the same way there.
    createPlatformEnvironment("reg-health-plane", "environment/reg-health-plane");
    specs.script(
        "qits-idp",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postBuildSucceeded("qits-idp", "environment/reg-health-plane", SHA_A);
    awaitApplied(1);

    assertEquals("/idp/q/health/ready", service("qits-idp").get("healthPath"));
    assertEquals("/idp/q/health/ready", driver.applied().get(0).healthPath());
  }

  @Test
  public void aBranchNoTierTracksWritesNothingAtAll() {
    // 202 and silence is the normal answer for a green build on a branch without an environment,
    // and it must not leave a service row behind that later looks like a registration.
    createEnvironment("reg-quiet", "environment/reg-quiet");
    postBuildSucceeded("repo-quiet", "main", SHA_A);
    awaitWorkerIdle();

    assertNull(service("repo-quiet"), "nothing was registered");
    assertEquals(List.of(), driver.applied());
  }

  @Test
  public void aRepositoryWhoseIdIsNotADnsLabelRegistersNothing() {
    // The name is the image path segment and the network alias, so a repository that cannot be one
    // cannot be deployed by convention at all. The intake is fire-and-forget, so this is a log line
    // and a silence rather than a row nobody can act on.
    createEnvironment("reg-badname", "environment/reg-badname");
    postBuildSucceeded("Repo-Bad", "environment/reg-badname", SHA_A);
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied());
  }

  @Test
  public void aBuildCarryingTheNamePairIsNamedAfterTheNameAndNeverAfterTheStorageId() {
    // THE invariant of the identity rollback. The git host's repository key is an opaque UUID now,
    // and every pipeline yml still pushes a literal qits/<name>:<sha> — so the moment the deployer
    // named an application after the storage id, every deployment would end IMAGE_MISSING and the
    // orchestrator's garbage collector would delete the images that are live. The name wins, and
    // it wins in all five derived places at once: the catalogue key, the image reference, the wire
    // alias, the container name and the row the pins are read off.
    String environmentId = createEnvironment("reg-named", "environment/reg-named");
    specs.script(
        "repo-reg-named",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.ENVIRONMENT, true, null, null, null, null));

    postBuildSucceeded(STORAGE_UUID, "qits", "repo-reg-named", "environment/reg-named", SHA_A);
    awaitApplied(1);

    Map<String, Object> service = service("repo-reg-named");
    assertEquals(List.of(environmentId), service.get("environmentIds"));
    assertEquals(true, service.get("availableOnEnv"), "the spec was read for the NAME");
    assertNull(service(STORAGE_UUID), "the storage id registered nothing of its own");

    DeploymentDriver.ServiceSpec applied = driver.applied().get(0);
    assertEquals("repo-reg-named", applied.applicationName());
    assertEquals("reg-named-repo-reg-named", applied.wireAlias());
    assertTrue(
        applied.imageRef().endsWith("/repo-reg-named:" + SHA_A), "image ref: " + applied.imageRef());
    assertTrue(
        applied.deploymentName().startsWith("qits-pd-reg-named-repo-reg-named-"),
        applied.deploymentName());
    assertFalse(applied.imageRef().contains(STORAGE_UUID), "no UUID anywhere in the image path");
    assertFalse(applied.deploymentName().contains(STORAGE_UUID), applied.deploymentName());
  }

  @Test
  public void aBuildWithNoNamesIsNamedAfterItsRepositoryIdExactlyAsBefore() {
    // The compatibility arm, byte for byte: before the rollback the storage id WAS the name, so an
    // announcement carrying neither field derives every identifier from the id, as it always did.
    createEnvironment("reg-unnamed", "environment/reg-unnamed");

    postBuildSucceeded("repo-reg-unnamed", "environment/reg-unnamed", SHA_A);
    awaitApplied(1);

    DeploymentDriver.ServiceSpec applied = driver.applied().get(0);
    assertEquals("repo-reg-unnamed", applied.applicationName());
    assertEquals("reg-unnamed-repo-reg-unnamed", applied.wireAlias());
    assertTrue(
        applied.imageRef().endsWith("/repo-reg-unnamed:" + SHA_A),
        "image ref: " + applied.imageRef());
    assertEquals(
        List.of("repo-reg-unnamed"),
        List.of((String) service("repo-reg-unnamed").get("name")),
        "the catalogue is keyed by the same string it always was");
  }

  @Test
  public void anUnreadableSpecResolvesFromTheLinksTheServiceAlreadyHas() {
    // The spec read is the one remote call left, so it is the one that can still fail. When it
    // does, the failure is recorded where the service is already registered — never guessed at.
    String environmentId = createEnvironment("reg-fallback", "environment/reg-fallback");
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "deploymentTarget", "ENVIRONMENT",
                "availableOnEnv", false,
                "environmentIds", List.of(environmentId)))
        .when()
        .put("/platform-deployments/api/services/repo-fallback")
        .then()
        .statusCode(201);
    specs.scriptFailure("repo-fallback", "the git host answered 500");

    postBuildSucceeded("repo-fallback", "environment/reg-fallback", SHA_A);
    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("FAILED", deployments.get(0).get("status"));
    assertTrue(
        ((String) deployments.get(0).get("detail")).contains("the git host answered 500"),
        "the cause is on the row: " + deployments.get(0).get("detail"));
    assertEquals(List.of(), driver.pulled(), "a topology is never guessed");
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** The spec parser, reached through its own package so the alias case reads as a real file. */
  private static final class DeploymentSpecParserAlias {
    static SpecSource.DeploymentSpec parse(String yaml) {
      return eu.wohlben.qits.platform.deployments.deployments.control.DeploymentSpecParser.parse(
          yaml, "a test file");
    }
  }

  private String createEnvironment(String name, String branch) {
    return createEnvironment(name, branch, false);
  }

  /**
   * The tier the platform plane deploys from. A platform build ships only when THIS environment
   * listens to the built branch, so every platform-plane test here designates its own — and
   * designating moves the flag, so the suite's shared database never holds two.
   */
  private String createPlatformEnvironment(String name, String branch) {
    return createEnvironment(name, branch, true);
  }

  private String createEnvironment(String name, String branch, boolean platform) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "branch", branch, "platform", platform))
        .when()
        .post("/platform-deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void postBuildSucceeded(String repoId, String branch, String sha) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-reg", "repoId", repoId, "branch", branch, "commitSha", sha))
        .when()
        .post("/platform-deployments/api/events/build-succeeded")
        .then()
        .statusCode(202);
  }

  /** The post-rollback payload: the storage id, plus the public address qits-ci fills in. */
  private void postBuildSucceeded(
      String repoId, String projectId, String repoName, String branch, String sha) {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "runId", "run-reg",
                "repoId", repoId,
                "projectId", projectId,
                "repoName", repoName,
                "branch", branch,
                "commitSha", sha))
        .when()
        .post("/platform-deployments/api/events/build-succeeded")
        .then()
        .statusCode(202);
  }

  /** One service off the catalogue read, or null — the surface a caller checks registration on. */
  private Map<String, Object> service(String name) {
    return given()
        .when()
        .get("/platform-deployments/api/services")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .<Map<String, Object>>getList("services")
        .stream()
        .filter(s -> name.equals(s.get("name")))
        .findFirst()
        .orElse(null);
  }

  private void awaitApplied(int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.applied().size() < count && System.currentTimeMillis() < deadline) {
      sleep();
    }
    assertEquals(count, driver.applied().size(), "applied services");
  }

  private List<Map<String, Object>> awaitDeployments(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> deployments =
          given()
              .when()
              .get("/platform-deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments");
      if (deployments.size() == count
          && deployments.stream()
              .noneMatch(
                  d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")))) {
        return deployments;
      }
      sleep();
    }
    return fail("deployments of " + environmentId + " did not settle to " + count);
  }

  /** The plane's own rows, asked for the way a client asks — by the word, not by a tier's id. */
  private List<Map<String, Object>> platformDeployments() {
    return given()
        .when()
        .get("/platform-deployments/api/deployments?environmentId=platform")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deployments");
  }

  private void awaitWorkerIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
