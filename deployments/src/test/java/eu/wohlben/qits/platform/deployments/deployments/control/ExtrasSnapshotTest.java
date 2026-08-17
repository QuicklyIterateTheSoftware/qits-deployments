package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * The config a deployment reads, against the file a person edits on the config volume — the
 * regression suite for a boot snapshot that used to be re-stamped onto every service it updated.
 */
class ExtrasSnapshotTest {

  private static final String P = DeploymentDriver.EXTRAS_PREFIX;

  /** The boot config: what the process started with, and what a file edit has to outrank. */
  private static Config boot(Map<String, String> properties) {
    return new SmallRyeConfigBuilder()
        .withSources(new PropertiesConfigSource(properties, "boot", 260))
        .build();
  }

  private static Path file(String content) throws IOException {
    Path file = Files.createTempFile("qits-extras", ".properties");
    file.toFile().deleteOnExit();
    Files.writeString(file, content);
    return file;
  }

  private static List<String> env(Config config) {
    return ServiceExtras.of(config, "qits-ci").env();
  }

  @Test
  void aValueWrittenAfterBootIsWhatTheNextDeploymentReads() throws IOException {
    // The whole point: the file on the config volume is authority, the boot config is last boot's
    // copy of it. The two disagree exactly when somebody has edited the file since.
    Config boot = boot(Map.of(P + "qits-ci.env.QITS_EVENTS_URL", "http://dev-qits-events:8080"));
    Path file = file(P + "qits-ci.env.QITS_EVENTS_URL=http://dev-qits-events:9090\n");

    assertEquals(
        List.of("QITS_EVENTS_URL=http://dev-qits-events:9090"),
        env(ExtrasSnapshot.over(boot, file.toString())));
  }

  @Test
  void whatTheFileDoesNotStateStillComesFromTheBootConfig() throws IOException {
    // The file is layered OVER the boot config rather than replacing it: this snapshot re-reads one
    // file, and every other key answers as it did.
    Config boot =
        boot(
            Map.of(
                P + "qits-ci.env.QITS_EVENTS_URL", "http://dev-qits-events:8080",
                P + "qits-ci.mounts[0]", "volume:qits-ci-data:/data"));
    Path file = file(P + "qits-ci.env.QITS_EVENTS_URL=http://dev-qits-events:9090\n");

    ServiceExtras extras = ServiceExtras.of(ExtrasSnapshot.over(boot, file.toString()), "qits-ci");

    assertEquals(List.of("QITS_EVENTS_URL=http://dev-qits-events:9090"), extras.env());
    assertEquals(
        List.of(new ServiceExtras.Mount(ServiceExtras.MountKind.VOLUME, "qits-ci-data", "/data", false)),
        extras.mounts());
  }

  @Test
  void noFileAtAllIsTheBootConfigItself() {
    // A dev run and the clone-alone suite have no such file, and neither may notice this class
    // exists: the snapshot IS the boot config, so the behaviour is byte for byte what it was.
    Config boot = boot(Map.of(P + "qits-ci.env.FOO", "bar"));

    Config snapshot =
        ExtrasSnapshot.over(boot, Path.of("no-such-directory", "application.properties").toString());

    assertSame(boot, snapshot);
    assertEquals(List.of("FOO=bar"), env(snapshot));
  }

  @Test
  void aFilePresentAndUnreadableRefusesTheDeploymentNamingIt() throws IOException {
    // Never a fall-back to the boot values: that is the stale value this class exists to kill, and
    // it would ship invisibly — a green deployment carrying whatever the process booted with.
    // A directory is the portable unreadable file: chmod 000 is still readable to root, and the
    // suite runs as root in a CI step container.
    Path directory = Files.createTempDirectory("qits-extras");
    directory.toFile().deleteOnExit();
    Config boot = boot(Map.of(P + "qits-ci.env.FOO", "bar"));

    ServiceExtras.Refused refused =
        assertThrows(
            ServiceExtras.Refused.class, () -> ExtrasSnapshot.over(boot, directory.toString()));

    assertTrue(refused.getMessage().contains(directory.toString()), refused.getMessage());
  }

  @Test
  void oneSnapshotAnswersTheSameThingHoweverTheFileChangesUnderIt() throws IOException {
    // ServiceExtras rests on "every reading agrees", and a caller reads more than once per
    // deployment. The file is read into a map here, so a snapshot cannot answer two ways.
    Path file = file(P + "qits-ci.env.QITS_EVENTS_URL=http://dev-qits-events:8080\n");
    Config snapshot = ExtrasSnapshot.over(boot(Map.of()), file.toString());

    List<String> first = env(snapshot);
    Files.writeString(file, P + "qits-ci.env.QITS_EVENTS_URL=http://dev-qits-events:9090\n");

    assertEquals(first, env(snapshot));
    assertEquals(List.of("QITS_EVENTS_URL=http://dev-qits-events:8080"), first);
  }
}
