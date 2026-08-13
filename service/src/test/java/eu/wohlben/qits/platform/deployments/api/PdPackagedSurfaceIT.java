package eu.wohlben.qits.platform.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.testdb.EmbeddedPg;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The whole service as it is <b>packaged</b> — the fast-jar under {@code mvn verify
 * -DskipITs=false}, the GraalVM binary under {@code mvn verify -Dnative}. The assertions are chosen
 * for what a native build can silently lose rather than for API coverage (that is the
 * {@code @QuarkusTest} suite's job):
 *
 * <ul>
 *   <li>the routes are where the config says — {@code quarkus.rest.path} and {@code
 *       quarkus.http.non-application-root-path} are build-time settings baked into the artifact;
 *   <li>the shipped datasource <b>expression</b> resolves and connects, and {@code
 *       db/platformdeployments/migration/} survived as a resource — migrations are loaded by
 *       scanning a classpath location, exactly the shape native-image drops, and the claim reaches
 *       every table the component has, since one request writes the topology and another writes a
 *       deployment row;
 *   <li>both domains round-trip through Hibernate/Panache in the packaged process, in one
 *       transaction each. That is a claim the ancestors could not make: a deployment row and the
 *       topology it names lived in two databases behind an HTTP call.
 * </ul>
 *
 * <p>It is also <b>the only test here that ever sees the client</b>. Quinoa is disabled by default
 * in test mode, so no {@code @QuarkusTest} in this repo has a client in it at all — a unit test
 * asserting something about the segment would pass against a process serving nothing. What the SPA
 * is actually served as is proven here or nowhere, and the probe list is the platform's, from
 * {@code docs/project-setup-quinoa-angular.md}.
 *
 * <p><b>The base-href probe is still absent, and the reason it was absent has gone.</b> It was left
 * out while the client said {@code /cd/}: asserting the right value would have failed the build for
 * something no change in this repo could fix, and asserting {@code /cd/} would have pinned the wrong
 * value into a test. The client is qits-spa-deployments now and its {@code angular.json}
 * says {@code /platform-deployments/}, so what remains is an ordinary open debt rather than a
 * blocked one. This suite asserts what the SERVER owes — the client is served at the segment, deep
 * links reach it, machine paths never do. See AGENTS.md.
 *
 * <p>No deployment is driven here: that needs a swarm, and the packaged process carries the real
 * {@link eu.wohlben.qits.platform.deployments.swarmhost.SwarmDeploymentDriver}. The container
 * runtime is pointed at a binary that does not exist, which exercises the best-effort seam (an
 * environment must exist even when docker is unreachable) and keeps this IT free of host side
 * effects.
 */
@QuarkusIntegrationTest
@TestProfile(PdPackagedSurfaceIT.PackagedUnderTarget.class)
public class PdPackagedSurfaceIT {

  private static final String SEGMENT = "/platform-deployments";

  /**
   * Hands the launched artifact its databases the way a deployment does — as the generic resource
   * triples, not as the datasource keys. The environments jar ships {@code
   * jdbc.url=${QITS_RESOURCE_DB_URL}} and its two siblings and the qits-eventstream jar ships the
   * same three over {@code QITS_RESOURCE_EVENTSTREAM_*}, so supplying the variables leaves the
   * <b>shipped</b> expressions themselves under test (the AUTO_SERVER lesson from qits-ci, applied
   * to what replaced that URL). Expression expansion reads the whole config, and these overrides
   * reach the launched process as system properties, so the same six names resolve.
   *
   * <p><b>Both are mandatory, which is itself the claim.</b> Neither jar's expressions have a
   * default behind them, so a packaged process missing either triple dies at Flyway naming what is
   * absent rather than opening a store nobody meant — and this IT is the only place that boots the
   * shipped artifact and would find out.
   *
   * <p>The databases are an embedded postgres this JVM starts. <b>Their urls travel through system
   * properties rather than static fields</b>: a test profile is instantiated in more than one
   * classloader, so a field written by one copy is not the field the other reads, while the process
   * has exactly one property table.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {

    /** Where the urls are parked for whichever copy of this class is asked second. */
    private static final String URL_PROPERTY = "qits.test.packaged-it.db-url";

    private static final String EVENTSTREAM_URL_PROPERTY =
        "qits.test.packaged-it.eventstream-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "QITS_RESOURCE_DB_URL", databaseUrl(URL_PROPERTY, "pd_packaged_it"),
          "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD,
          // The bus client's store, and this IT is where its shipped expressions are exercised
          // too. It is not optional: the jar's three keys have no defaults, so a packaged process
          // started without this triple dies at Flyway naming what is missing — which is the
          // refuse-to-boot stance, and the reason .config/qits/deployments.yml declares the
          // resource. Dark or not, the datasource opens and migrates.
          "QITS_RESOURCE_EVENTSTREAM_URL",
              databaseUrl(EVENTSTREAM_URL_PROPERTY, "pd_eventstream_packaged_it"),
          "QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD,
          // No docker on purpose: every driver call must degrade to a warning, never a failure.
          "qits.platform.deployments.container-runtime", "docker-absent-for-this-it");
    }

    private static synchronized String databaseUrl(String property, String database) {
      String recorded = System.getProperty(property);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url(database);
      System.setProperty(property, url);
      return url;
    }
  }

  @Test
  public void theClientIsServedAtTheSegment() {
    given().when().get(SEGMENT + "/").then().statusCode(200).contentType(ContentType.HTML);
  }

  /**
   * The fourth spelling of the segment, and the only one no build here can check any other way: the
   * client's own baseHref, set in qits-spa-deployments' angular.json. A page served with
   * the wrong one loads and then fetches its own JavaScript from a segment nothing serves — green
   * build, blank screen. The other three are {@code quarkus.quinoa.ui-root-path}, {@code
   * quarkus.rest.path} and {@code quarkus.http.non-application-root-path}.
   */
  @Test
  public void theClientAsksForItsAssetsUnderThisSegment() {
    String index = given().when().get(SEGMENT + "/").then().statusCode(200).extract().asString();
    String expected = "<base href=\"" + SEGMENT + "/\">";
    assertTrue(index.contains(expected), "index.html does not carry " + expected);
  }

  @Test
  public void aDeepLinkFallsBackToTheClientSoItsRouterOwnsIt() {
    given()
        .when()
        .get(SEGMENT + "/some/route")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML);
  }

  @Test
  public void theBareSegmentRedirectsRatherThanFourOhFouring() {
    // Quinoa mounts at <segment>/*, which does not match the bare segment (upstream #960) — the
    // redirect in webui/WebUiRedirect is this service's answer, and only the packaged process has
    // both it and a real client to bounce to.
    given()
        .redirects()
        .follow(false)
        .when()
        .get(SEGMENT)
        .then()
        .statusCode(301)
        .header("Location", SEGMENT + "/");
  }

  @Test
  public void aMistypedMachinePathIsNeverTheClient() {
    // The whole reason quarkus.quinoa.ignored-path-prefixes is set: without /api in that list this
    // answers 200 with index.html, and qits-ci's intake — which swallows delivery failures at debug
    // — would parse the client's not-found page as an accepted delivery.
    //
    // The assertion is "404, and not the CLIENT" rather than "404, never HTML", because what comes
    // back here is Vert.x' own stock `<h1>Resource not found</h1>` — text/html, and correct. Every
    // sibling answers a mistyped machine path the same way; asserting on the content type alone
    // would fail against the right behaviour while still passing against the wrong one.
    String index =
        given().when().get(SEGMENT + "/").then().statusCode(200).extract().asString();

    String body =
        given().when().get(SEGMENT + "/api/nope").then().statusCode(404).extract().asString();
    assertFalse(
        body.equals(index),
        "a mistyped machine path must not be answered with the client; got: " + body);

    // /q is the second half of the ignore list, and the derivation would have covered both — this
    // pins that setting the key by hand did not drop one.
    String underQ =
        given().when().get(SEGMENT + "/q/health/nope").then().statusCode(404).extract().asString();
    assertFalse(
        underQ.equals(index),
        "a mistyped non-application path must not be answered with the client; got: " + underQ);

    // qits-gateway routes verbatim by prefix, so there is no unprefixed form to fall back to.
    given().when().get("/api/environments").then().statusCode(404);
  }

  @Test
  public void theReadinessEndpointIsWhereTheDeploymentLooksForIt() {
    // The path this component's own health gate curls for a peer, at the address the deployment
    // convention assumes — under quarkus.http.non-application-root-path, not the rest path. It is
    // also the path the health-path convention derives for this very service's name, which is what
    // makes a self-deployment gate on something that exists.
    given()
        .when()
        .get(SEGMENT + "/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", org.hamcrest.Matchers.equalTo("UP"));
  }

  @Test
  public void theApiDocumentAndItsUiAreServedUnderTheGatewaySegment() {
    // Both live under quarkus.http.non-application-root-path, which sits OUTSIDE quarkus.rest.path
    // and carries the segment on its own; at / they would be unreachable through qits-gateway.
    given().when().get(SEGMENT + "/q/openapi").then().statusCode(200);
    given().when().get(SEGMENT + "/q/swagger-ui/").then().statusCode(200);
  }

  @Test
  public void theIntakeIsAtTheAddressQitsCiPostsTo() {
    // qits-ci's notifier delivers here fire-and-forget: a wrong path raises no error on either side
    // and deployments simply never happen, so the address is asserted from the artifact. An empty
    // body must reach @Valid — a 400 proves the resource, not the router's 404.
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(SEGMENT + "/api/events/build-succeeded")
        .then()
        .statusCode(400);
  }

  @Test
  public void bothDomainsRoundTripAgainstTheShippedSchema() {
    // One request writes the topology (pd_environment, pd_service, pd_service_link) and the next
    // writes execution history (pd_deployment) — so a migration that did not make it into the
    // artifact shows up here, whichever table it was for.
    String environmentId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "packaged-env", "branch", "main"))
            .when()
            .post(SEGMENT + "/api/environments")
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");

    given()
        .when()
        .get(SEGMENT + "/api/environments")
        .then()
        .statusCode(200)
        .body("environments.name", org.hamcrest.Matchers.hasItem("packaged-env"));

    // This process has no git host, so the spec read fails and resolution falls back to what the
    // catalogue already holds — the only path that reaches a deployment row here, and one that
    // needs the topology and the history in one transaction.
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "deploymentTarget", "ENVIRONMENT",
                "availableOnEnv", false,
                "environmentIds", List.of(environmentId)))
        .when()
        .put(SEGMENT + "/api/services/packaged-repo")
        .then()
        .statusCode(201);

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "runId", "6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61",
                "repoId", "packaged-repo",
                "branch", "main",
                "commitSha", "a".repeat(40)))
        .when()
        .post(SEGMENT + "/api/events/build-succeeded")
        .then()
        .statusCode(202);

    // The whole event runs on the worker, registration included, so the row appears a moment after
    // the 202 rather than during it.
    long deadline = System.currentTimeMillis() + 30_000;
    String runId = null;
    while (runId == null && System.currentTimeMillis() < deadline) {
      runId =
          given()
              .when()
              .get(SEGMENT + "/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .path("deployments[0].runId");
      if (runId == null) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
    assertEquals("6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61", runId);
  }
}
