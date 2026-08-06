package eu.wohlben.qits.platform.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

/**
 * Every guarded write with the gate on — the posture a deployment reaches by setting {@code
 * QITS_AUTH_MACHINE_REQUIRED=true} once qits-idp grants the {@code qits-platform-deployments}
 * audience.
 *
 * <p>Tokens are real: signed RS256, verified by quarkus-oidc against the public key in {@link
 * MachineGuardEnforcedProfile}. So these tests fail if the OIDC configuration in
 * application.properties is wrong, not only if a guard is missing.
 *
 * <p><b>The guarded set is the union of what both ancestors guarded</b>, which is the one thing the
 * merge had to decide rather than inherit: qits-cd guarded its build-succeeded intake and
 * deliberately left its environment surface open to a person, while qits-serviceregistry guarded
 * every write it had, because its only writer was a machine. The union wins, on the ancestors'
 * shared reasoning applied to the merged surface: the environment and service writes are driven by
 * the bootstrap and the deploy path, so a bearer is a credential their callers can hold. The reads
 * stay open, for the reason both gave.
 *
 * <p><b>Two doors, and this suite pins which shuts first.</b> A token minted for another service is
 * refused by {@code quarkus.oidc.token.audience} before {@code MachineAuth} ever sees the identity,
 * so the answer is a 401 challenge rather than the guard's own 403. The guard's 403 is the second
 * belt, reachable only if that validation is ever loosened, and it is asserted nowhere here because
 * it cannot be produced without loosening it.
 */
@QuarkusTest
@TestProfile(MachineGuardEnforcedProfile.class)
class MachineGuardEnforcedTest {

  private static final String ENVIRONMENTS = "/platform-deployments/api/environments";
  private static final String SERVICES = "/platform-deployments/api/services";
  private static final String INTAKE = "/platform-deployments/api/events/build-succeeded";

  private static final String ENVIRONMENT_BODY = "{\"name\":\"guarded-env\"}";
  private static final String SERVICE_BODY =
      "{\"deploymentTarget\":\"PLATFORM\",\"branch\":\"main\",\"availableOnEnv\":false}";
  private static final String EVENT =
      """
      {"repoId":"guarded-repo","branch":"main","commitSha":"a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0"}
      """;

  // --- no token at all: 401 on every guarded write ----------------------------------------------

  @Test
  void theIntakeWithNoTokenIsRefused() {
    // This is the exact call qits-ci makes today, and it stops working the moment the gate is on —
    // which is why the sender has to be sending before a deployment flips it.
    given().contentType(ContentType.JSON).body(EVENT).when().post(INTAKE).then().statusCode(401);
  }

  @Test
  void creatingAnEnvironmentWithNoTokenIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(ENVIRONMENT_BODY)
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(401);
  }

  @Test
  void patchingAnEnvironmentWithNoTokenIsRefused() {
    // The guard runs before the lookup, so an unknown id still answers 401 rather than 404 — which
    // is the right order: an unauthenticated caller learns nothing about what exists.
    given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"main\"}")
        .when()
        .patch(ENVIRONMENTS + "/whatever")
        .then()
        .statusCode(401);
  }

  @Test
  void deletingAnEnvironmentWithNoTokenIsRefused() {
    given().when().delete(ENVIRONMENTS + "/whatever").then().statusCode(401);
  }

  @Test
  void upsertingAServiceWithNoTokenIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(SERVICE_BODY)
        .when()
        .put(SERVICES + "/guarded-none")
        .then()
        .statusCode(401);
  }

  @Test
  void deletingAServiceWithNoTokenIsRefused() {
    given().when().delete(SERVICES + "/guarded-none").then().statusCode(401);
  }

  // --- a token minted for another service: refused at validation --------------------------------

  @Test
  void aTokenMintedForAnotherServiceIsRefusedOnEveryWrite() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.token("qits-ci", "qits-artifacts"))
        .body(EVENT)
        .when()
        .post(INTAKE)
        .then()
        .statusCode(401);
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.token("qits-ci", "qits-artifacts"))
        .body(ENVIRONMENT_BODY)
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(401);
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.token("qits-ci", "qits-artifacts"))
        .body(SERVICE_BODY)
        .when()
        .put(SERVICES + "/guarded-wrong-aud")
        .then()
        .statusCode(401);
  }

  // --- the right token: every write goes through ------------------------------------------------

  @Test
  void theIntakeAcceptsATokenMintedForThisService() {
    machine()
        .contentType(ContentType.JSON)
        .body(EVENT)
        .when()
        .post(INTAKE)
        .then()
        // 202 and nothing deploys: no environment listens to this branch, which is the intake's
        // normal answer. What is asserted is that the guard let the caller through.
        .statusCode(202);
  }

  @Test
  void everyTopologyWriteAcceptsATokenMintedForThisService() {
    String environmentId =
        machine()
            .contentType(ContentType.JSON)
            .body(ENVIRONMENT_BODY)
            .when()
            .post(ENVIRONMENTS)
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");

    machine()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"environment/guarded\"}")
        .when()
        .patch(ENVIRONMENTS + "/" + environmentId)
        .then()
        .statusCode(200)
        .body("environment.branch", equalTo("environment/guarded"));

    machine()
        .contentType(ContentType.JSON)
        .body(
            "{\"deploymentTarget\":\"ENVIRONMENT\",\"availableOnEnv\":false,"
                + "\"environmentIds\":[\""
                + environmentId
                + "\"]}")
        .when()
        .put(SERVICES + "/guarded-service")
        .then()
        .statusCode(201);

    machine().when().delete(SERVICES + "/guarded-service").then().statusCode(204);
    machine().when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);
  }

  // --- the other half of the rule: the reads stay open ------------------------------------------

  @Test
  void everyReadStaysOpenToACallerHoldingNothing() {
    // Enforcement is per call site, and these are the calls a person makes through the gateway's
    // session and the web client polls. Guarding them would close the component for both the day
    // the gate flips on.
    String environmentId =
        machine()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"guarded-readable\"}")
            .when()
            .post(ENVIRONMENTS)
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");

    given().when().get(ENVIRONMENTS).then().statusCode(200);
    given().when().get(ENVIRONMENTS + "/" + environmentId).then().statusCode(200);
    given().when().get(ENVIRONMENTS + "/" + environmentId + "/links").then().statusCode(200);
    given().when().get(SERVICES).then().statusCode(200);
    given().when().get("/platform-deployments/api/applications").then().statusCode(200);
    given()
        .when()
        .get("/platform-deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200);
  }

  @Test
  void thePinListingStaysOpenToTheGarbageCollectorThatReadsIt() {
    // Same rule from the machine side: the pins are a read, so they carry no guard. qits-artifacts
    // plans its OCI sweep fail-closed on this answer — a 401 here would abort every sweep, and the
    // gate flipping on must not be the thing that does it.
    given().when().get("/platform-deployments/api/pins").then().statusCode(200);
  }

  /** A caller with a fresh token minted for this service. */
  private static RequestSpecification machine() {
    return given()
        .header(
            "Authorization",
            "Bearer " + MachineTokens.token("qits-ci", "qits-platform-deployments"));
  }
}
