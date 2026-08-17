package eu.wohlben.qits.platform.deployments.confighost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.ServiceExtras;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Where a deployment's extras come from when the platform runs qits-configuration — against a stub
 * on a real socket, because what is under test is the request itself.
 *
 * <p>The claim underneath all of it: <b>a service that cannot answer refuses the deployment</b>.
 * Every arm that could quietly hand back an older value is a test here, because that value is the
 * one this whole line of work exists to stop shipping.
 */
class ConfigHostExtrasSourceTest {

  private static final String P = DeploymentDriver.EXTRAS_PREFIX;

  private static final ExtrasBearer NONE = Optional::empty;

  private ExtrasStub stub;

  @BeforeEach
  void start() {
    stub = new ExtrasStub();
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  private static Config boot(Map<String, String> properties) {
    return new SmallRyeConfigBuilder()
        .withSources(new PropertiesConfigSource(properties, "boot", 260))
        .build();
  }

  /** The shipped path, which no working directory of this suite has a file at. */
  private static final String NO_FILE = "config/application.properties";

  private static List<String> env(Config config) {
    return ServiceExtras.of(config, "qits-ci").env();
  }

  @Test
  void noExtrasUrlIsTheFileBehaviourByteForByte() {
    // Unset is the shipped state and the whole compatibility claim: nothing is fetched, nothing is
    // parsed, and the answer is the config volume's file over the boot config exactly as before.
    ConfigHostExtrasSource source =
        ExtrasStub.source(boot(Map.of(P + "qits-ci.env.FOO", "bar")), NO_FILE, NONE, null);

    assertEquals(List.of("FOO=bar"), env(source.forApplication("qits-ci")));
    assertTrue(stub.paths().isEmpty(), "an unset url must reach nothing");
  }

  @Test
  void whatTheServiceStatesIsWhatTheDeploymentCarries() {
    stub.resolves(7, P + "qits-ci.env.QITS_EVENTS_URL", "http://dev-qits-events:8080");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    assertEquals(
        List.of("QITS_EVENTS_URL=http://dev-qits-events:8080"), env(source.forApplication("qits-ci")));
    assertEquals(
        List.of("/configuration/api/applications/qits-ci/resolved"),
        stub.paths(),
        "the resolved read is one GET per argv, at the application's own path");
  }

  @Test
  void theFileIsNotConsultedAtAll() throws IOException {
    // AUTHORITATIVE MEANS SOLE. The file was layered under the served map for one release, so a key
    // DELETED from the service came straight back out of a file nobody had emptied — the one
    // operation the service exists to make possible was the one it could not perform. With the url
    // set the file contributes nothing: not a shadowed value, not a value it alone states.
    Path file = Files.createTempFile("qits-extras", ".properties");
    file.toFile().deleteOnExit();
    Files.writeString(
        file,
        P
            + "qits-ci.env.QITS_EVENTS_URL=http://stale:8080\n"
            + P
            + "qits-ci.env.ONLY_IN_THE_FILE=kept\n");
    stub.resolves(3, P + "qits-ci.env.QITS_EVENTS_URL", "http://dev-qits-events:8080");

    ConfigHostExtrasSource source = stub.source(boot(Map.of()), file.toString(), NONE);

    assertEquals(
        List.of("QITS_EVENTS_URL=http://dev-qits-events:8080"),
        env(source.forApplication("qits-ci")),
        "the served map is the whole of it: the file's own key must not reach the argv");
  }

  @Test
  void anUnreachableServiceRefusesTheDeploymentNamingTheUrl() {
    // Never a fall-back to the file: a stale extras value ships as a green deployment, which is the
    // failure class this whole change exists to end.
    String unreachable = "http://127.0.0.1:1";
    ConfigHostExtrasSource source =
        ExtrasStub.source(
            boot(Map.of(P + "qits-ci.env.FOO", "stale")), NO_FILE, NONE, unreachable);

    ServiceExtras.Refused refused =
        assertThrows(ServiceExtras.Refused.class, () -> source.forApplication("qits-ci"));

    assertTrue(refused.getMessage().contains(unreachable), refused.getMessage());
  }

  @Test
  void aServiceErrorRefusesTheDeploymentNamingTheUrl() {
    stub.answers(500, "nope");
    ConfigHostExtrasSource source =
        stub.source(boot(Map.of(P + "qits-ci.env.FOO", "stale")), NO_FILE, NONE);

    ServiceExtras.Refused refused =
        assertThrows(ServiceExtras.Refused.class, () -> source.forApplication("qits-ci"));

    assertTrue(refused.getMessage().contains(stub.url()), refused.getMessage());
    assertTrue(refused.getMessage().contains("500"), refused.getMessage());
  }

  @Test
  void anApplicationTheServiceHasNeverHeardOfRefusesToo() {
    // Unlike the spec read's 404, which really is an answer: a repository with no deployments.yml
    // deploys with the defaults. An application with no entries is an application whose extras this
    // deployment cannot know it is missing.
    stub.answers(404, "{}");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    assertThrows(ServiceExtras.Refused.class, () -> source.forApplication("qits-ci"));
  }

  @Test
  void aBodyThatIsNotJsonRefusesTheDeployment() {
    stub.answers(200, "<html>a proxy answered instead</html>");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    ServiceExtras.Refused refused =
        assertThrows(ServiceExtras.Refused.class, () -> source.forApplication("qits-ci"));

    assertTrue(refused.getMessage().contains(stub.url()), refused.getMessage());
  }

  @Test
  void aDocumentWithNoPropertiesObjectRefusesTheDeployment() {
    // "This application states nothing" and "this is not the resolved document" must not be the
    // same deployment.
    stub.answers(200, "{\"headRevision\":9}");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    assertThrows(ServiceExtras.Refused.class, () -> source.forApplication("qits-ci"));
  }

  @Test
  void theBearerIsPresentedWhenTheClientHoldsOne() {
    stub.resolves(1);
    ConfigHostExtrasSource source =
        stub.source(boot(Map.of()), NO_FILE, () -> Optional.of("a-machine-token"));

    source.forApplication("qits-ci");

    assertEquals(List.of("Bearer a-machine-token"), stub.authorizations());
  }

  @Test
  void nothingIsPresentedWhenTheClientIsDisabled() {
    // The shipped posture: qits-configuration open behind forward-auth on qits-net, and this read
    // carrying the X-Qits-* pair alone. An Authorization header invented here would be a credential
    // nobody minted.
    stub.resolves(1);
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    source.forApplication("qits-ci");

    assertEquals(1, stub.authorizations().size());
    assertNull(stub.authorizations().get(0));
  }

  @Test
  void theRetryBudgetSpendsItselfBeforeRefusing() {
    // A service being redeployed is a few seconds of refusals, and no deployment should die of one.
    stub.then(503, "restarting").resolves(4, P + "qits-ci.env.FOO", "bar");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);
    source.attempts = 2;

    assertEquals(List.of("FOO=bar"), env(source.forApplication("qits-ci")));
    assertEquals(2, stub.paths().size(), "the second attempt is the one that answered");
  }

  @Test
  void theBudgetIsBoundedAndThenItRefuses() {
    stub.answers(503, "still restarting");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);
    source.attempts = 3;

    assertThrows(ServiceExtras.Refused.class, () -> source.forApplication("qits-ci"));

    assertEquals(3, stub.paths().size(), "spent, not unbounded");
  }

  @Test
  void aMalformedBodyIsNotRetried() {
    // A body that will not parse will not parse a second time, and the deploy worker is single
    // threaded with every other event queued behind it.
    stub.answers(200, "not json");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);
    source.attempts = 3;

    assertThrows(ServiceExtras.Refused.class, () -> source.forApplication("qits-ci"));

    assertEquals(1, stub.paths().size());
  }

  @Test
  void theRevisionDeployedWithIsRecorded() {
    // The deployment row's detail has no seam a revision could ride today — it is written by
    // DeployService out of the driver's verdict, and the extras are read a layer below that — so
    // what was deployed with is stated in the log beside the read. Asserted rather than assumed:
    // an unrecorded revision makes "which config is this container running" unanswerable, which is
    // half of why the service exists.
    stub.resolves(42, P + "qits-ci.env.FOO", "bar");
    ConfigHostExtrasSource source = stub.source(boot(Map.of()), NO_FILE, NONE);

    List<String> logged = capture(() -> source.forApplication("qits-ci"));

    assertTrue(
        logged.stream().anyMatch(line -> line.contains("config-revision=42")),
        "no config-revision in " + logged);
  }

  /** Everything {@link ConfigHostExtrasSource} logs while {@code body} runs. */
  private static List<String> capture(Runnable body) {
    List<String> lines = new ArrayList<>();
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(ConfigHostExtrasSource.class.getName());
    Level level = logger.getLevel();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            lines.add(String.valueOf(record.getMessage()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    logger.setLevel(Level.INFO);
    try {
      body.run();
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(level);
    }
    assertFalse(lines.isEmpty(), "nothing was logged at all");
    return lines;
  }
}
