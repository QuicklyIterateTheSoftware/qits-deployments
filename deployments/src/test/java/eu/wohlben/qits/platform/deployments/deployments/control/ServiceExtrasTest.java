package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * The key family as read — the half of the contract that is the same on both orchestrators, and the
 * one place the refusals are stated once instead of twice.
 */
class ServiceExtrasTest {

  private static final String P = DeploymentDriver.EXTRAS_PREFIX;

  private static Config config(Map<String, String> properties) {
    return new SmallRyeConfigBuilder()
        .withSources(new PropertiesConfigSource(properties, "test", 100))
        .build();
  }

  private static ServiceExtras of(Map<String, String> properties) {
    return ServiceExtras.of(config(properties), "qits-ci");
  }

  private static ServiceExtras.Refused refused(Map<String, String> properties) {
    return assertThrows(ServiceExtras.Refused.class, () -> of(properties));
  }

  @Test
  void anApplicationThatStatedNothingGetsNothing() {
    assertTrue(of(Map.of()).isEmpty());
  }

  @Test
  void everyElementOfTheGrammarReadsBackAsWhatItSays() {
    ServiceExtras extras =
        of(
            Map.of(
                P + "qits-ci.mounts[0]", "volume:qits-ci-data:/data",
                P + "qits-ci.mounts[1]", "bind:/var/run/docker.sock:/var/run/docker.sock:ro",
                P + "qits-ci.publishes[0]", "127.0.0.1:8081:8080",
                P + "qits-ci.publishes[1]", "5353:8053/udp",
                P + "qits-ci.groups[0]", "988",
                P + "qits-ci.env.QITS_EVENTS_URL", "http://dev-qits-events:8080"));

    assertEquals(
        List.of(
            new ServiceExtras.Mount(ServiceExtras.MountKind.VOLUME, "qits-ci-data", "/data", false),
            new ServiceExtras.Mount(
                ServiceExtras.MountKind.BIND, "/var/run/docker.sock", "/var/run/docker.sock", true)),
        extras.mounts());
    assertEquals(
        List.of(
            new ServiceExtras.Publish("127.0.0.1", 8081, 8080, null),
            new ServiceExtras.Publish(null, 5353, 8053, "udp")),
        extras.publishes());
    assertEquals(List.of("988"), extras.groups());
    assertEquals(List.of("QITS_EVENTS_URL=http://dev-qits-events:8080"), extras.env());
  }

  @Test
  void theIndexOrdersTheListAndMeansNothingElse() {
    ServiceExtras extras =
        of(
            Map.of(
                P + "qits-ci.mounts[1]", "volume:second:/second",
                P + "qits-ci.mounts[0]", "volume:first:/first"));

    assertEquals(List.of("first", "second"), extras.mounts().stream().map(ServiceExtras.Mount::source).toList());
  }

  @Test
  void anEnvironmentValueMayCarryASpaceBecauseItIsItsOwnArgument() {
    // The free-form family this replaced was whitespace split, so a value with a space in it could
    // not be stated at all. Each element is one argv element now.
    assertEquals(
        List.of("JAVA_OPTS=-Xmx256m -Xms64m"),
        of(Map.of(P + "qits-ci.env.JAVA_OPTS", "-Xmx256m -Xms64m")).env());
  }

  @Test
  void anotherApplicationsKeysAreNeverRead() {
    // The security property, at the seam both drivers read: only the deployed application's own
    // keys are looked at, so one application's socket bind cannot ride along on a sibling's
    // deployment.
    ServiceExtras extras =
        of(
            Map.of(
                P + "qits-workspaces.mounts[0]", "bind:/var/run/docker.sock:/var/run/docker.sock",
                P + "qits-ci.env.FOO", "bar"));

    assertEquals(List.of(), extras.mounts());
    assertEquals(List.of("FOO=bar"), extras.env());
  }

  @Test
  void anApplicationWhoseNameExtendsThisOneIsADifferentApplication() {
    // The dot after the application name is what says so — and it is why the dotted spelling is the
    // only one this family is read in: QITS_..._EXTRAS_QITS_CI_ENV_X cannot be told apart from a
    // key of qits-ci-daemon, because a dash and a dot both become an underscore.
    assertTrue(
        of(Map.of(P + "qits-ci-daemon.mounts[0]", "bind:/var/run/docker.sock:/var/run/docker.sock"))
            .isEmpty());
  }

  @Test
  void anUnknownElementIsRefusedRatherThanDropped() {
    assertTrue(
        refused(Map.of(P + "qits-ci.cap-adds[0]", "SYS_ADMIN")).getMessage().contains("cap-adds[0]"));
    // A list element without its index, and an index that is not one.
    refused(Map.of(P + "qits-ci.mounts", "volume:qits-ci-data:/data"));
    refused(Map.of(P + "qits-ci.mounts[first]", "volume:qits-ci-data:/data"));
    // An environment variable whose key could forge a second assignment.
    refused(Map.of(P + "qits-ci.env.FOO=BAR", "baz"));
  }

  @Test
  void aMountSaysWhichKindItIsAndIsHeldToIt() {
    // The guess this replaced — a leading slash means bind — turned a mistyped volume name into a
    // silent bind mount of a directory docker creates empty.
    refused(Map.of(P + "qits-ci.mounts[0]", "qits-ci-data:/data"));
    refused(Map.of(P + "qits-ci.mounts[0]", "volume:/var/run/docker.sock:/var/run/docker.sock"));
    refused(Map.of(P + "qits-ci.mounts[0]", "bind:relative/path:/data"));
    refused(Map.of(P + "qits-ci.mounts[0]", "volume:qits-ci-data:data"));
    refused(Map.of(P + "qits-ci.mounts[0]", "volume:qits-ci-data:/data:rw"));
  }

  @Test
  void aPublishIsPortsAndAtMostAnIpAndAProtocol() {
    refused(Map.of(P + "qits-ci.publishes[0]", "8080"));
    refused(Map.of(P + "qits-ci.publishes[0]", "eighty:8080"));
    refused(Map.of(P + "qits-ci.publishes[0]", "0:8080"));
    refused(Map.of(P + "qits-ci.publishes[0]", "70000:8080"));
    refused(Map.of(P + "qits-ci.publishes[0]", "localhost:8081:8080"));
    refused(Map.of(P + "qits-ci.publishes[0]", "8081:8080/sctp"));
  }

  @Test
  void thePublishModeIsNotAMemberOfThisFamily() {
    // Where a port is HELD is publish_mode in the repository's own deployments.yml — one statement
    // for the whole service, on the service spec. Deployment config states which ports a service
    // publishes and nothing about their mode, so this key is an unknown element like any other.
    assertTrue(
        refused(Map.of(P + "qits-ci.publish_mode", "ingress")).getMessage().contains("publish_mode"));
    refused(Map.of(P + "qits-ci.publishes[0].mode", "ingress"));
  }

  @Test
  void everyInterfaceIsTheOneIpSwarmCanHonour() {
    // What the renderers ask, and the reason the artifacts publish states 0.0.0.0 rather than
    // leaving the ip out: it is a decision, and it reads like one.
    assertTrue(new ServiceExtras.Publish(null, 8080, 8080, null).bindsAllInterfaces());
    assertTrue(new ServiceExtras.Publish("0.0.0.0", 8081, 8080, null).bindsAllInterfaces());
    assertTrue(!new ServiceExtras.Publish("127.0.0.1", 9000, 9000, null).bindsAllInterfaces());
  }

  @Test
  void aValuelessKeyIsRefusedExceptForAnEmptyVariable() {
    refused(Map.of(P + "qits-ci.mounts[0]", ""));
    // An empty variable is a statement: `FLAG=` is how a container is told the flag is off.
    assertEquals(List.of("FLAG="), of(Map.of(P + "qits-ci.env.FLAG", "")).env());
  }
}
