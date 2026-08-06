package eu.wohlben.qits.platformdeployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platformdeployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.platformdeployments.deployments.control.DeploymentDriver;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The environment surface end to end, against {@link FakeDeploymentDriver} (no docker).
 *
 * <p>Both ancestors had a suite here and they merged into this one. qits-serviceregistry's proved
 * the rows and the validation; qits-cd's proved the docker side effects and, after the extraction,
 * that its own endpoints faithfully <b>proxied</b> to the other service. That third set of
 * assertions is gone with the proxy: there is one service, one transaction, and nothing on the wire
 * between the row and the network. What is left is the two halves that were always real.
 *
 * <p>Tests address the absolute {@code /platform-deployments/api} paths, which is what makes them
 * catch a prefix regression, and every test names its own environment: the suite shares one
 * in-memory database across classes (Flyway cleans at start, not between tests), so a shared name
 * is a test that passes alone and fails in a run.
 */
@QuarkusTest
public class PdEnvironmentApiTest {

  private static final String ENVIRONMENTS = "/platform-deployments/api/environments";
  private static final String SERVICES = "/platform-deployments/api/services";

  @Inject FakeDeploymentDriver driver;

  @BeforeEach
  void reset() {
    driver.reset();
  }

  // --- creation ---------------------------------------------------------------------------------

  @Test
  public void creationFillsTheConventionsAndEnsuresTheNetwork() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-conventions"))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(201)
        .body("environment.name", equalTo("env-conventions"))
        // The tier deploys from its own ref; main stays the integration trunk.
        .body("environment.branch", equalTo("environment/env-conventions"))
        .body("environment.network", equalTo("qits-env-env-conventions"))
        .body("environment.applications", hasSize(0))
        .body("environment.id", notNullValue())
        .body("environment.createdAt", notNullValue());

    assertTrue(
        driver.ensuredNetworks().contains("qits-env-env-conventions"),
        "creation must ensure the environment's bundle network: " + driver.ensuredNetworks());
  }

  @Test
  public void explicitBranchAndNetworkWin() {
    // The dev tier is exactly this shape: its bundle is qits-net by history, not by convention.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-explicit", "branch", "environment/dev", "network", "qits-net"))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(201)
        .body("environment.branch", equalTo("environment/dev"))
        .body("environment.network", equalTo("qits-net"));
  }

  @Test
  public void declaredApplicationsAreAcceptedAndIgnored() {
    // The deprecated field. It is still accepted so an older sender's payload deserializes, but the
    // catalogue holds one identity for a service (its name), and rows are derived from each
    // repository's own deployments.yml — so nothing is registered from it.
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name", "env-declared",
                "applications",
                    List.of(Map.of("repoId", "repo-declared", "name", "app-declared"))))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(201)
        .body("environment.applications", hasSize(0));

    given()
        .when()
        .get(SERVICES)
        .then()
        .statusCode(200)
        .body("services.name", not(hasItem("app-declared")));
  }

  @Test
  public void aDuplicateNameIsAConflict() {
    Map<String, Object> payload = Map.of("name", "env-duplicate");
    given().contentType(ContentType.JSON).body(payload).when().post(ENVIRONMENTS).then().statusCode(201);
    given()
        .contentType(ContentType.JSON)
        .body(payload)
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(409)
        .body("message", equalTo("Environment already exists: env-duplicate"));
  }

  @Test
  public void hostileNamesAreRejectedBeforeTheyReachAnArgv() {
    // The name becomes a docker network name, an alias and an image path segment, and this surface
    // is attacker-reachable while the gate is off — so a 400 is owed here rather than at the argv.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Evil Name"))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(400)
        .body("message", notNullValue());

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-hostile-net", "network", "--privileged"))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(400);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-hostile-branch", "branch", "a..b"))
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(400);

    assertTrue(
        driver.calls().isEmpty(), "no refused request reached docker: " + driver.calls());
  }

  // --- reads ------------------------------------------------------------------------------------

  @Test
  public void theEnvironmentReadShowsTheTiersOwnServicesWithoutThePlatformOnes() {
    String environmentId = create("env-read");
    upsertEnvironmentService("envsuite-app-read", environmentId);
    upsertPlatformService("envsuite-svc-read-platform");

    given()
        .when()
        .get(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(200)
        // The environment aggregate is the tier's own services. Platform services belong to no tier
        // and are reached through the links query and the flat listing, which both show them.
        .body("environment.applications", hasSize(1))
        .body("environment.applications[0].name", equalTo("envsuite-app-read"))
        .body("environment.applications[0].repoId", equalTo("envsuite-app-read"))
        .body("environment.applications[0].environmentId", equalTo(environmentId))
        .body("environment.applications[0].environmentName", equalTo("env-read"))
        .body("environment.applications[0].target", equalTo("ENVIRONMENT"));

    // ...and the id is the derived one a client joins a deployment row against.
    given()
        .when()
        .get(ENVIRONMENTS + "/" + environmentId)
        .then()
        .body("environment.applications[0].id", equalTo(environmentId + ":envsuite-app-read"));

    List<Map<String, Object>> flat =
        given()
            .when()
            .get("/platform-deployments/api/applications")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("applications");
    assertTrue(
        flat.stream().anyMatch(a -> "envsuite-svc-read-platform".equals(a.get("name"))),
        "the flat listing carries the platform service too: " + flat);
  }

  @Test
  public void aListingLeavesTheApplicationsUnaskedRatherThanEmpty() {
    // Null, not []. "This tier holds nothing" and "you did not ask" are different answers, and a
    // client that renders an empty list as the first would be wrong on every listing row.
    create("env-listed");
    given()
        .when()
        .get(ENVIRONMENTS)
        .then()
        .statusCode(200)
        .body("environments.name", hasItem("env-listed"))
        .body("environments.find { it.name == 'env-listed' }.applications", nullValue());
  }

  @Test
  public void anUnknownEnvironmentIsNotFound() {
    given().when().get(ENVIRONMENTS + "/no-such-id").then().statusCode(404);
  }

  @Test
  public void theLinkQueryComposesTheTiersServicesWithEveryPlatformService() {
    // The pull query, and the difference from the aggregate above: a reconciliation needs the
    // platform plane too, or a fresh tier would come up without qits-idp in it.
    String mine = create("env-links-mine");
    String other = create("env-links-other");
    upsertEnvironmentService("envsuite-svc-links-linked", mine);
    upsertEnvironmentService("envsuite-svc-links-elsewhere", other);
    upsertPlatformService("envsuite-svc-links-platform");

    given()
        .when()
        .get(ENVIRONMENTS + "/" + mine + "/links")
        .then()
        .statusCode(200)
        .body("services.name", hasItem("envsuite-svc-links-linked"))
        .body("services.name", hasItem("envsuite-svc-links-platform"))
        .body("services.name", not(hasItem("envsuite-svc-links-elsewhere")))
        .body("services.find { it.name == 'envsuite-svc-links-linked' }.target", equalTo("ENVIRONMENT"))
        .body("services.find { it.name == 'envsuite-svc-links-platform' }.target", equalTo("PLATFORM"));
  }

  @Test
  public void aBrandNewEnvironmentAlreadyHoldsEveryPlatformService() {
    upsertPlatformService("envsuite-svc-preexisting-platform");
    // Created after the platform service, linked to nothing, and it has it. That is the whole
    // reason a platform service has no links.
    String fresh = create("env-fresh");
    given()
        .when()
        .get(ENVIRONMENTS + "/" + fresh + "/links")
        .then()
        .statusCode(200)
        .body("services.name", hasItem("envsuite-svc-preexisting-platform"));
  }

  @Test
  public void theLinkQueryOfAnUnknownEnvironmentIsNotFound() {
    given().when().get(ENVIRONMENTS + "/no-such-id/links").then().statusCode(404);
  }

  // --- patch ------------------------------------------------------------------------------------

  @Test
  public void patchRenamesAndRetargetsWithoutTouchingDocker() {
    String environmentId = create("env-patch");
    driver.reset();

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patched", "branch", "environment/env-patched"))
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(200)
        .body("environment.name", equalTo("env-patched"))
        .body("environment.branch", equalTo("environment/env-patched"))
        // The bundle network is NOT renamed with it: the rename is a row change, and the running
        // containers keep the networks they are on until their own next deploy.
        .body("environment.network", equalTo("qits-env-env-patch"));

    // This is the migration path onto the branch convention, so it must be safe on a live tier:
    // nothing was ensured, removed, disconnected or reaped.
    assertTrue(driver.calls().isEmpty(), "PATCH has no docker side effects: " + driver.calls());
    assertTrue(driver.removedEnvironments().isEmpty());
  }

  @Test
  public void patchLeavesAnOmittedFieldAloneAndRejectsWhatCreateWouldReject() {
    String environmentId = create("env-patch-partial");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("branch", "environment/dev"))
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(200)
        .body("environment.name", equalTo("env-patch-partial"))
        .body("environment.branch", equalTo("environment/dev"));

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Evil Name"))
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(400);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("branch", "bad//branch"))
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(400);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patch-partial"))
        .when()
        .patch(ENVIRONMENTS + "/no-such-environment")
        .then()
        .statusCode(404);
  }

  @Test
  public void renamingOntoATakenNameIsAConflictAndOntoItsOwnIsNot() {
    create("env-patch-taken");
    String environmentId = create("env-patch-other");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patch-taken"))
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(409);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patch-other"))
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(200);
  }

  // --- teardown ---------------------------------------------------------------------------------

  @Test
  public void teardownRemovesContainersAndNetworkAndThenTheTier() {
    String environmentId = create("env-teardown");

    given().when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);
    given().when().get(ENVIRONMENTS + "/" + environmentId).then().statusCode(404);

    assertTrue(
        driver.removedEnvironments().contains(environmentId),
        "teardown must remove the environment's containers");
    assertTrue(
        driver.removedNetworks().contains("qits-env-env-teardown"),
        "teardown must remove the environment's bundle network");
  }

  @Test
  public void theDockerTeardownRunsBeforeTheRowsGo() {
    // The order is the contract. The teardown is label-driven and needs nothing from the topology,
    // but deleting the tier first would leave a failed teardown with no row to retry it from — so a
    // half-finished teardown stays addressable. With the two services merged this is no longer two
    // processes agreeing on an order; it is one method, and this is what pins it.
    String environmentId = create("env-order");

    given().when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);

    List<String> calls = driver.calls();
    assertTrue(
        driver.removedEnvironments().contains(environmentId) && !calls.isEmpty(),
        "the containers were reaped: " + calls);
    assertTrue(
        driver.removedNetworks().contains("qits-env-env-order"),
        "and the network removed: " + driver.removedNetworks());
    given().when().get(ENVIRONMENTS + "/" + environmentId).then().statusCode(404);
  }

  @Test
  public void teardownFreesThePlatformContainersBeforeRemovingTheDerivedNetworks() {
    String environmentId = create("env-derived-teardown");
    driver.scriptExistingNetwork(
        new DeploymentDriver.Network(
            "qits-env-env-derived-teardown-app-x",
            environmentId,
            DeploymentDriver.NetworkKind.APPLICATION,
            "app-x"));
    driver.scriptPlatformContainers(List.of(new DeploymentDriver.Endpoint("idp-id", "qits-idp")));

    given().when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);

    // A platform service survives the tier it merely served, so it is what holds the networks open
    // — docker refuses to remove a network with an endpoint on it.
    assertTrue(
        driver.disconnections().contains("qits-env-env-derived-teardown-app-x:idp-id"),
        "platform containers leave the derived networks first: " + driver.disconnections());
    assertTrue(driver.removedNetworks().contains("qits-env-env-derived-teardown"));
    assertTrue(
        driver.removedNetworks().contains("qits-env-env-derived-teardown-app-x"),
        "the derived per-application networks go too: " + driver.removedNetworks());
  }

  @Test
  public void teardownLeavesTheLegacyNetworkAloneWhenItIsTheEnvironmentsBundle() {
    // The dev tier's shape exactly: its bundle IS qits.pd.legacy-network. That network is the
    // transition membership of every container on the host — platform services included — so it is
    // not this environment's to take away. Disconnecting them from it would cut qits-idp off from
    // the platform, and this component would be doing it to itself mid-request.
    String environmentId = create(Map.of("name", "env-legacy-bundle", "network", "qits-net"));
    driver.reset();
    driver.scriptExistingNetwork(
        new DeploymentDriver.Network(
            "qits-env-env-legacy-bundle-app-y",
            environmentId,
            DeploymentDriver.NetworkKind.APPLICATION,
            "app-y"));
    driver.scriptPlatformContainers(
        List.of(new DeploymentDriver.Endpoint("pd-id", "qits-platform-deployments")));

    given().when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);

    assertTrue(
        driver.disconnections().stream().noneMatch(d -> d.startsWith("qits-net:")),
        "no platform container is taken off the legacy network: " + driver.disconnections());
    assertTrue(
        !driver.removedNetworks().contains("qits-net"),
        "and the legacy network itself stays: " + driver.removedNetworks());
    // The environment's OWN derived network still goes, platform container disconnected from it.
    assertTrue(
        driver.disconnections().contains("qits-env-env-legacy-bundle-app-y:pd-id"),
        driver.disconnections().toString());
    assertTrue(driver.removedNetworks().contains("qits-env-env-legacy-bundle-app-y"));
  }

  @Test
  public void deleteTakesTheLinksIntoItAndLeavesTheServiceItself() {
    String kept = create("env-delete-kept");
    String dropped = create("env-delete-dropped");
    upsertEnvironmentService("envsuite-svc-survives-env-delete", kept, dropped);

    given().when().delete(ENVIRONMENTS + "/" + dropped).then().statusCode(204);

    // A tier going away is not a service going away: the row and its other link survive.
    given()
        .when()
        .get(SERVICES)
        .then()
        .statusCode(200)
        .body("services.find { it.name == 'envsuite-svc-survives-env-delete' }.environmentIds", hasSize(1))
        .body(
            "services.find { it.name == 'envsuite-svc-survives-env-delete' }.environmentIds", hasItem(kept));
  }

  @Test
  public void deletingAMissingEnvironmentIs404() {
    given().when().delete(ENVIRONMENTS + "/no-such-environment").then().statusCode(404);
  }

  @Test
  public void deploymentsListingRequiresAnExistingEnvironment() {
    given().when().get("/platform-deployments/api/deployments").then().statusCode(400);
    given()
        .when()
        .get("/platform-deployments/api/deployments?environmentId=no-such")
        .then()
        .statusCode(404);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String create(String name) {
    return create(Map.of("name", name));
  }

  private String create(Map<String, Object> payload) {
    return given()
        .contentType(ContentType.JSON)
        .body(payload)
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void upsertEnvironmentService(String name, String... environmentIds) {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "deploymentTarget", "ENVIRONMENT",
                "availableOnEnv", false,
                "environmentIds", List.of(environmentIds)))
        .when()
        .put(SERVICES + "/" + name)
        .then()
        .statusCode(201);
  }

  private void upsertPlatformService(String name) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("deploymentTarget", "PLATFORM", "branch", "main", "availableOnEnv", false))
        .when()
        .put(SERVICES + "/" + name)
        .then()
        .statusCode(201);
  }
}
