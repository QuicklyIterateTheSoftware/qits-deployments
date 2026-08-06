package eu.wohlben.qits.platformdeployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The service catalogue: the upsert's semantics, the link-set replacement, the platform-service rules,
 * both flip directions, and the link query's composition.
 *
 * <p>Every test names its own environments and services — the suite shares one in-memory database
 * across classes, so a shared name is a test that passes alone and fails in a run. The platform-service
 * tests are the exception that proves it: a platform service shows up in <em>every</em> environment's link
 * query, including other tests', so each one asserts about the platform service it created rather than
 * about the size of the answer.
 */
@QuarkusTest
public class PdServiceApiTest {

  private static final String ENVIRONMENTS = "/platform-deployments/api/environments";
  private static final String SERVICES = "/platform-deployments/api/services";

  @Test
  void registeringAServiceForTheFirstTimeIsCreated() {
    String env = createEnvironment("svc-first-env");
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"deploymentTarget":"ENVIRONMENT","availableOnEnv":true,
             "healthPath":"/gateway/q/health/ready","environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-first")
        .then()
        .statusCode(201)
        .body("service.name", equalTo("svc-first"))
        .body("service.target", equalTo("ENVIRONMENT"))
        .body("service.availableOnEnv", equalTo(true))
        .body("service.healthPath", equalTo("/gateway/q/health/ready"))
        .body("service.environmentIds", hasItem(env))
        .body("service.id", not(nullValue()));
  }

  @Test
  void aSecondUpsertOfTheSameNameUpdatesRatherThanCreating() {
    String env = createEnvironment("svc-second-env");
    String firstId = upsertEnvironmentService("svc-second", 201, env);
    // Same name, different shape: 200, and the identity survives.
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"deploymentTarget":"ENVIRONMENT","availableOnEnv":true,"environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-second")
        .then()
        .statusCode(200)
        .body("service.id", equalTo(firstId))
        .body("service.availableOnEnv", equalTo(true));
  }

  @Test
  void theLinkSetIsReplacedRatherThanMerged() {
    String a = createEnvironment("svc-replace-a");
    String b = createEnvironment("svc-replace-b");
    upsertEnvironmentService("svc-replace", 201, a, b);

    // The writer holds the whole spec, so what it sends IS the set. A merge would keep the link to
    // `a` forever, and nothing would ever remove it.
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"deploymentTarget":"ENVIRONMENT","availableOnEnv":false,"environmentIds":["%s"]}
            """
                .formatted(b))
        .when()
        .put(SERVICES + "/svc-replace")
        .then()
        .statusCode(200)
        .body("service.environmentIds", hasSize(1))
        .body("service.environmentIds", hasItem(b));
  }

  @Test
  void anEmptyLinkSetUnlinksAnEnvironmentServiceEverywhere() {
    String env = createEnvironment("svc-unlink-env");
    upsertEnvironmentService("svc-unlink", 201, env);
    given()
        .contentType(ContentType.JSON)
        .body("{\"deploymentTarget\":\"ENVIRONMENT\",\"availableOnEnv\":false,\"environmentIds\":[]}")
        .when()
        .put(SERVICES + "/svc-unlink")
        .then()
        .statusCode(200)
        .body("service.environmentIds", empty());
    // ...and it is gone from the environment's link query, which is the point of the row.
    given()
        .when()
        .get(ENVIRONMENTS + "/" + env + "/links")
        .then()
        .statusCode(200)
        .body("services.name", not(hasItem("svc-unlink")));
  }

  @Test
  void namingAnEnvironmentTwiceStatesTheSameLinkOnce() {
    String env = createEnvironment("svc-dupe-env");
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"deploymentTarget":"ENVIRONMENT","availableOnEnv":false,"environmentIds":["%s","%s"]}
            """
                .formatted(env, env))
        .when()
        .put(SERVICES + "/svc-dupe")
        .then()
        .statusCode(201)
        .body("service.environmentIds", hasSize(1));
  }

  @Test
  void aPlatformServiceCarriesNoLinksAndItsOwnBranch() {
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"deploymentTarget":"PLATFORM","branch":"main","availableOnEnv":false,
             "healthPath":"/idp/q/health/ready"}
            """)
        .when()
        .put(SERVICES + "/svc-platform-plain")
        .then()
        .statusCode(201)
        .body("service.target", equalTo("PLATFORM"))
        .body("service.branch", equalTo("main"))
        .body("service.environmentIds", empty());
  }

  @Test
  void aPlatformServiceGivenEnvironmentLinksIsRefused() {
    String env = createEnvironment("svc-platform-linked-env");
    // Not a silent drop: the caller and this service disagree about what the row means, and
    // storing either reading would hide it.
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"deploymentTarget":"PLATFORM","availableOnEnv":false,"environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-platform-linked")
        .then()
        .statusCode(400)
        .body("message", containsString("platform service carries no environment links"));
  }

  @Test
  void aBranchBesideAnEnvironmentTargetIsAcceptedAndIgnored() {
    String env = createEnvironment("svc-branch-ignored-env");
    // The same tolerance the spec parser gives a `branch:` key beside
    // `deployment_target: environment`: a harmless extra key is not a failed build.
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"deploymentTarget":"ENVIRONMENT","branch":"main","availableOnEnv":false,
             "environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-branch-ignored")
        .then()
        .statusCode(201)
        .body("service.branch", nullValue());
  }

  @Test
  void anUnknownEnvironmentIdIsNotFound() {
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"deploymentTarget\":\"ENVIRONMENT\",\"availableOnEnv\":false,"
                + "\"environmentIds\":[\"no-such-id\"]}")
        .when()
        .put(SERVICES + "/svc-bad-env")
        .then()
        .statusCode(404);
  }

  @Test
  void aMissingDeploymentTargetIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"availableOnEnv\":false}")
        .when()
        .put(SERVICES + "/svc-no-target")
        .then()
        .statusCode(400)
        .body("message", containsString("deploymentTarget"));
  }

  @Test
  void aServiceNameOutsideTheDnsLabelCharsetIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"deploymentTarget\":\"PLATFORM\",\"availableOnEnv\":false}")
        .when()
        .put(SERVICES + "/Svc_Bad")
        .then()
        .statusCode(400);
  }

  @Test
  void aHealthPathCarryingShellPunctuationIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"deploymentTarget\":\"PLATFORM\",\"availableOnEnv\":false,"
                + "\"healthPath\":\"/q;curl evil\"}")
        .when()
        .put(SERVICES + "/svc-bad-health")
        .then()
        .statusCode(400);
  }

  @Test
  void anEnvironmentServiceConvertsToAPlatformServiceAndLosesItsLinks() {
    String env = createEnvironment("svc-convert-env");
    String id = upsertEnvironmentService("svc-convert", 201, env);
    // The one-time live migration a service goes through when it becomes platform-plane.
    given()
        .contentType(ContentType.JSON)
        .body("{\"deploymentTarget\":\"PLATFORM\",\"branch\":\"main\",\"availableOnEnv\":false}")
        .when()
        .put(SERVICES + "/svc-convert")
        .then()
        .statusCode(200)
        .body("service.id", equalTo(id))
        .body("service.target", equalTo("PLATFORM"))
        .body("service.environmentIds", empty());
    // ...and it is now in that environment's links as a platform service rather than as a link.
    given()
        .when()
        .get(ENVIRONMENTS + "/" + env + "/links")
        .then()
        .statusCode(200)
        .body("services.find { it.name == 'svc-convert' }.target", equalTo("PLATFORM"));
  }

  @Test
  void aPlatformServiceCannotBecomeAnEnvironmentServiceAgain() {
    String env = createEnvironment("svc-flip-back-env");
    given()
        .contentType(ContentType.JSON)
        .body("{\"deploymentTarget\":\"PLATFORM\",\"branch\":\"main\",\"availableOnEnv\":false}")
        .when()
        .put(SERVICES + "/svc-flip-back")
        .then()
        .statusCode(201);
    // Refused loudly, with the remediation in the message — never a silent double-run.
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"deploymentTarget":"ENVIRONMENT","availableOnEnv":false,"environmentIds":["%s"]}
            """
                .formatted(env))
        .when()
        .put(SERVICES + "/svc-flip-back")
        .then()
        .statusCode(409)
        .body("message", containsString("cannot become an environment service"))
        .body("message", containsString("Remediation"));
    // Nothing changed: the refusal is not a half-write.
    given()
        .when()
        .get(SERVICES)
        .then()
        .body("services.find { it.name == 'svc-flip-back' }.target", equalTo("PLATFORM"))
        .body("services.find { it.name == 'svc-flip-back' }.environmentIds", empty());
  }

  @Test
  void theCatalogueIsFlatAndCarriesBothPlanes() {
    String env = createEnvironment("svc-flat-env");
    upsertEnvironmentService("svc-flat-tiered", 201, env);
    given()
        .contentType(ContentType.JSON)
        .body("{\"deploymentTarget\":\"PLATFORM\",\"branch\":\"main\",\"availableOnEnv\":false}")
        .when()
        .put(SERVICES + "/svc-flat-platform")
        .then()
        .statusCode(201);

    given()
        .when()
        .get(SERVICES)
        .then()
        .statusCode(200)
        .body("services.name", hasItem("svc-flat-tiered"))
        .body("services.name", hasItem("svc-flat-platform"))
        .body("services.find { it.name == 'svc-flat-tiered' }.environmentIds", hasItem(env))
        .body("services.find { it.name == 'svc-flat-platform' }.environmentIds", empty());
  }

  @Test
  void theLinkQueryComposesTheLinkedServicesWithEveryPlatformService() {
    String mine = createEnvironment("svc-links-mine");
    String other = createEnvironment("svc-links-other");
    upsertEnvironmentService("svc-links-linked", 201, mine);
    upsertEnvironmentService("svc-links-elsewhere", 201, other);
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"deploymentTarget":"PLATFORM","branch":"main","availableOnEnv":true,
             "healthPath":"/idp/q/health/ready"}
            """)
        .when()
        .put(SERVICES + "/svc-links-platform")
        .then()
        .statusCode(201);

    given()
        .when()
        .get(ENVIRONMENTS + "/" + mine + "/links")
        .then()
        .statusCode(200)
        // Its own link...
        .body("services.name", hasItem("svc-links-linked"))
        // ...every platform service, which is what a new environment picks up with nothing written...
        .body("services.name", hasItem("svc-links-platform"))
        // ...and nothing from another tier.
        .body("services.name", not(hasItem("svc-links-elsewhere")))
        // Each entry carries what a deployer reconciles with.
        .body("services.find { it.name == 'svc-links-linked' }.target", equalTo("ENVIRONMENT"))
        .body("services.find { it.name == 'svc-links-linked' }.availableOnEnv", equalTo(false))
        .body("services.find { it.name == 'svc-links-platform' }.target", equalTo("PLATFORM"))
        .body("services.find { it.name == 'svc-links-platform' }.availableOnEnv", equalTo(true))
        .body(
            "services.find { it.name == 'svc-links-platform' }.healthPath",
            equalTo("/idp/q/health/ready"));
  }

  @Test
  void aBrandNewEnvironmentAlreadyHoldsEveryPlatformService() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"deploymentTarget\":\"PLATFORM\",\"branch\":\"main\",\"availableOnEnv\":false}")
        .when()
        .put(SERVICES + "/svc-preexisting-platform")
        .then()
        .statusCode(201);
    // Created after the platform service, linked to nothing, and it has it. That is the whole reason a
    // platform service has no links.
    String fresh = createEnvironment("svc-fresh-env");
    given()
        .when()
        .get(ENVIRONMENTS + "/" + fresh + "/links")
        .then()
        .statusCode(200)
        .body("services.name", hasItem("svc-preexisting-platform"));
  }

  @Test
  void theLinkQueryOfAnUnknownEnvironmentIsNotFound() {
    given().when().get(ENVIRONMENTS + "/no-such-id/links").then().statusCode(404);
  }

  @Test
  void deletingAServiceTakesItsLinksWithIt() {
    String env = createEnvironment("svc-delete-env");
    upsertEnvironmentService("svc-delete", 201, env);
    given().when().delete(SERVICES + "/svc-delete").then().statusCode(204);
    given()
        .when()
        .get(ENVIRONMENTS + "/" + env + "/links")
        .then()
        .body("services.name", not(hasItem("svc-delete")));
    given().when().get(SERVICES).then().body("services.name", not(hasItem("svc-delete")));
  }

  @Test
  void deletingAnUnknownServiceIsNotFound() {
    given().when().delete(SERVICES + "/no-such-service").then().statusCode(404);
  }

  private static String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"" + name + "\"}")
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private static String upsertEnvironmentService(
      String name, int expectedStatus, String... environmentIds) {
    String ids =
        String.join(",", Arrays.stream(environmentIds).map(id -> "\"" + id + "\"").toList());
    return given()
        .contentType(ContentType.JSON)
        .body(
            "{\"deploymentTarget\":\"ENVIRONMENT\",\"availableOnEnv\":false,\"environmentIds\":["
                + ids
                + "]}")
        .when()
        .put(SERVICES + "/" + name)
        .then()
        .statusCode(expectedStatus)
        .extract()
        .path("service.id");
  }
}
