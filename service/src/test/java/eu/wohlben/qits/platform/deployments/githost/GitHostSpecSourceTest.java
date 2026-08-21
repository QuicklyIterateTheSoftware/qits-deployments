package eu.wohlben.qits.platform.deployments.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.platform.deployments.deployments.control.RepositoryRef;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecException;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which URL the spec is read at — the one thing the identity rollback changed about this component's
 * single outbound call.
 *
 * <p>An HTTP stub on a real socket rather than a fake at the seam, for the reason {@code ExtrasStub}
 * gives next door: what is under test IS the request, and a fake would assert this test's own model
 * of a client. The JDK's own server, so nothing arrives on the classpath and no docker is involved.
 *
 * <p>Plain JUnit and no {@code @QuarkusTest}: the two config values are package-private fields, so
 * the class is built and pointed at the stub directly.
 */
public class GitHostSpecSourceTest {

  private static final String SHA = "a".repeat(40);
  private static final String UUID_ID = "6d0c2b1e-3a44-4b0e-9a5b-2b1c0d9e4f88";

  private HttpServer server;
  private final List<String> paths = new ArrayList<>();
  private volatile int status = 200;
  private volatile String body = "deployment_target: platform\n";

  @BeforeEach
  void start() throws IOException {
    paths.clear();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    synchronized (paths) {
      paths.add(exchange.getRequestURI().getPath());
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, status == 404 ? -1 : bytes.length);
    if (status != 404) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  @Test
  public void anEventCarryingTheNamePairIsReadNameAddressed() {
    // The public address. The storage id is not in the URL at all — githost serves blob and tree
    // under (projectId, repoName) since it became dumb storage, and the id route is internal.
    DeploymentSpec spec = source().read(new RepositoryRef(UUID_ID, "qits", "qits-gateway"), SHA);

    assertEquals(PdDeploymentTarget.PLATFORM, spec.target());
    assertEquals(
        "/git/qits/qits-gateway/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void anEventWithNoNamesKeepsTheIdAddressedUrlItAlwaysUsed() {
    // The regression arm: byte for byte the request this made before the name fields existed.
    source().read(RepositoryRef.ofId("qits-gateway"), SHA);

    assertEquals("/git/qits-gateway/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void halfAnAddressTakesTheIdRouteRatherThanBuildingHalfAPath() {
    source().read(new RepositoryRef(UUID_ID, "qits", null), SHA);

    assertEquals("/git/" + UUID_ID + "/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void aTrailingSlashOnTheConfiguredHostDoesNotDoubleTheSeparator() {
    GitHostSpecSource source = source();
    source.gitHostUrl = source.gitHostUrl + "/";

    source.read(new RepositoryRef(UUID_ID, "qits", "qits-gateway"), SHA);

    assertEquals(
        "/git/qits/qits-gateway/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void a404IsAnAnswerOnBothArms() {
    // A repository carrying no file deploys with every default — unchanged by which URL asked.
    status = 404;

    assertSame(
        DeploymentSpec.DEFAULTS, source().read(new RepositoryRef(UUID_ID, "qits", "gw"), SHA));
    assertSame(DeploymentSpec.DEFAULTS, source().read(RepositoryRef.ofId("gw"), SHA));
  }

  @Test
  public void anythingElseFailsTheDeploymentNamingTheUrlItAsked() {
    // The fe26a6c stance: a read that could not be answered is a failure, never an empty spec.
    status = 503;

    SpecException refused =
        assertThrows(
            SpecException.class,
            () -> source().read(new RepositoryRef(UUID_ID, "qits", "qits-gateway"), SHA));

    assertTrue(refused.getMessage().contains("/git/qits/qits-gateway/blob/"), refused.getMessage());
    assertTrue(refused.getMessage().contains("503"), refused.getMessage());
  }

  private GitHostSpecSource source() {
    GitHostSpecSource source = new GitHostSpecSource();
    source.gitHostUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    source.timeoutSeconds = 5;
    return source;
  }

  private String onlyPath() {
    synchronized (paths) {
      assertEquals(1, paths.size(), "requests made");
      return paths.get(0);
    }
  }
}
