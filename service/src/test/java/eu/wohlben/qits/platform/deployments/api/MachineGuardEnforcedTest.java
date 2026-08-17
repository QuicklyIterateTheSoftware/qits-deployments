package eu.wohlben.qits.platform.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

/**
 * The whole guarded surface with the gate on — the posture a deployment reaches by setting {@code
 * QITS_AUTH_MACHINE_REQUIRED=true} once qits-platform-idp grants the {@code
 * qits-platform-deployments} audience.
 *
 * <p>Tokens are real: signed RS256, verified by quarkus-oidc against the public key in {@link
 * MachineGuardEnforcedProfile}. So these tests fail if the OIDC configuration in
 * application.properties is wrong, not only if a guard is missing.
 *
 * <p><b>Nothing here is open any more, and that is the change this suite exists to record.</b> The
 * surface used to be split into guarded writes and open reads; every endpoint carries a {@code
 * @RolesAllowed} now, and the role is what says who the caller is meant to be:
 *
 * <ul>
 *   <li><b>{@code qits-platform:admin}</b> — the reads. It reaches this service only through the
 *       forwarded {@code X-Qits-Roles} header: the platform edge asserts it for an authenticated
 *       admin session, and the bootstrap asserts it on its own hop over qits-net. A machine token
 *       never carries it, which is the point — the read surface is a person's.
 *   <li><b>{@code qits-platform:system}</b> — the pins, the topology writes and the build-succeeded
 *       intake. qits-platform-idp copies it into every platform service client's {@code groups}
 *       claim, so a machine bearer carries it and a browser session does not.
 * </ul>
 *
 * <p><b>Three doors now, and this suite pins which shuts first.</b> A token minted for another
 * service is refused by {@code quarkus.oidc.token.audience} before any identity is built, so the
 * answer is a 401 challenge. A token addressed here but granted no roles authenticates and is
 * refused 403 by {@code @RolesAllowed}. {@link eu.wohlben.qits.auth.MachineAuth} is the third and
 * innermost, re-asking the audience question the token already passed — belt and braces, because
 * the annotation and the guard fail independently.
 */
@QuarkusTest
@TestProfile(MachineGuardEnforcedProfile.class)
class MachineGuardEnforcedTest {

  private static final String ENVIRONMENTS = "/platform-deployments/api/environments";
  private static final String SERVICES = "/platform-deployments/api/services";
  private static final String INTAKE = "/platform-deployments/api/events/build-succeeded";
  private static final String PINS = "/platform-deployments/api/pins";

  private static final String ENVIRONMENT_BODY = "{\"name\":\"guarded-env\"}";
  private static final String SERVICE_BODY =
      "{\"deploymentTarget\":\"PLATFORM\",\"branch\":\"main\",\"availableOnEnv\":false}";
  private static final String EVENT =
      """
      {"repoId":"guarded-repo","branch":"main","commitSha":"a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0"}
      """;

  // --- no credential at all: 401 everywhere -----------------------------------------------------

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

  @Test
  void everyReadWithNoCredentialIsRefused() {
    // The reads were open until the surface was protected. They are a person's now, and an
    // anonymous caller is challenged rather than served.
    given().when().get(ENVIRONMENTS).then().statusCode(401);
    given().when().get(SERVICES).then().statusCode(401);
    given().when().get("/platform-deployments/api/applications").then().statusCode(401);
    given().when().get("/platform-deployments/api/deployments?environmentId=whatever").then().statusCode(401);
    given().when().get(PINS).then().statusCode(401);
  }

  // --- a token minted for another service: refused at validation --------------------------------

  @Test
  void aTokenMintedForAnotherServiceIsRefusedOnEveryWrite() {
    given()
        .contentType(ContentType.JSON)
        .header(
            "Authorization", "Bearer " + MachineTokens.token("qits-ci", "qits-platform-artifacts"))
        .body(EVENT)
        .when()
        .post(INTAKE)
        .then()
        .statusCode(401);
    given()
        .contentType(ContentType.JSON)
        .header(
            "Authorization", "Bearer " + MachineTokens.token("qits-ci", "qits-platform-artifacts"))
        .body(ENVIRONMENT_BODY)
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(401);
    given()
        .contentType(ContentType.JSON)
        .header(
            "Authorization", "Bearer " + MachineTokens.token("qits-ci", "qits-platform-artifacts"))
        .body(SERVICE_BODY)
        .when()
        .put(SERVICES + "/guarded-wrong-aud")
        .then()
        .statusCode(401);
  }

  // --- a token addressed here but granted no roles: the second door -----------------------------

  @Test
  void aTokenGrantedNoRolesIsRefusedOnEveryGuardedCall() {
    // A client id in qits.idp.clients with no `.roles` line beside it mints exactly this: correctly
    // signed, correctly addressed, empty `groups`. It authenticates and covers nothing, which is a
    // 403 rather than the 401 an absent token gets — the distinction an operator needs to tell a
    // missing grant from a missing sender.
    String roleless = "Bearer " + MachineTokens.rolelessToken("qits-ci", "qits-platform-deployments");

    given()
        .contentType(ContentType.JSON)
        .header("Authorization", roleless)
        .body(EVENT)
        .when()
        .post(INTAKE)
        .then()
        .statusCode(403);
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", roleless)
        .body(ENVIRONMENT_BODY)
        .when()
        .post(ENVIRONMENTS)
        .then()
        .statusCode(403);
    given().header("Authorization", roleless).when().get(PINS).then().statusCode(403);
  }

  // --- the right token: every machine-facing call goes through ----------------------------------

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

  @Test
  void thePinListingAnswersTheGarbageCollectorsMachineToken() {
    // qits-platform-artifacts plans its OCI sweep fail-closed on this answer, so what it presents
    // has to be a credential it can actually hold: its idp client is granted this service's
    // audience and the two system roles, which is exactly the token minted here.
    given()
        .header(
            "Authorization",
            "Bearer "
                + MachineTokens.token("qits-platform-artifacts", "qits-platform-deployments"))
        .when()
        .get(PINS)
        .then()
        .statusCode(200);
  }

  // --- the reads are a person's, and only a person's --------------------------------------------

  @Test
  void everyReadAnswersThePlatformAdminAndRefusesAMachine() {
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

    // The headers the edge asserts for an admin session, and the ones the bootstrap asserts on its
    // own hop. Nothing mints them as a token: this is the user track, start to finish.
    admin().when().get(ENVIRONMENTS).then().statusCode(200);
    admin().when().get(ENVIRONMENTS + "/" + environmentId).then().statusCode(200);
    admin().when().get(ENVIRONMENTS + "/" + environmentId + "/links").then().statusCode(200);
    admin().when().get(SERVICES).then().statusCode(200);
    admin().when().get("/platform-deployments/api/applications").then().statusCode(200);
    admin()
        .when()
        .get("/platform-deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200);

    // A machine holds qits-platform:system and never qits-platform:admin, so the token that just
    // created the environment cannot read it back. That asymmetry is the contract, not an oversight.
    machine().when().get(ENVIRONMENTS).then().statusCode(403);
    machine().when().get(SERVICES).then().statusCode(403);

    machine().when().delete(ENVIRONMENTS + "/" + environmentId).then().statusCode(204);
  }

  /** A caller with a fresh token minted for this service, carrying the machine roles the idp grants. */
  private static RequestSpecification machine() {
    return given()
        .header(
            "Authorization",
            "Bearer " + MachineTokens.token("qits-ci", "qits-platform-deployments"));
  }

  /** A platform admin, as the edge and the bootstrap assert one. */
  private static RequestSpecification admin() {
    return given()
        .header("X-Qits-User", "qits-bootstrap")
        .header("X-Qits-Roles", "qits-platform:admin");
  }
}
