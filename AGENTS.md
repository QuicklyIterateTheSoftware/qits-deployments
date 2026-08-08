# qits-deployments — working notes

Read `README.md` first: it defines the model (tiers, two planes, derived rows) and the flow (green
build → registration → health-gated cutover). This file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials, **no network**. That is why the poms duplicate versions
instead of inheriting them, and why every seam that reaches outside the process is faked rather than
skipped: `FakeDeploymentDriver` behind `DeploymentDriver` (docker) and `FakeSpecSource` behind
`SpecSource` (the git host). **Two fakes, down from three** — the ancestor also needed a stub HTTP
server for the topology, and the topology is a repository query now.

**Which command is the gate depends on whether you have the client** (`git clone … && git submodule
update --init`):

- `./mvnw test` — needs **neither node nor the webui submodule**. Quinoa is disabled by default in
  test mode, so every `@QuarkusTest` here passes against an empty `webui/` on a machine with no node.
- `./mvnw verify` — runs `package` on its way to failsafe, and `package` is where Quinoa augments. So
  verify needs **both**, and against an uninitialised submodule it fails with
  `No package.json found in Web UI directory: 'src/main/webui'`.

Always `clean verify`, and the suite takes a free port —
`service/src/test/resources/application.properties` sets `quarkus.http.test-port=0`, because on the
deployment host 8081 is the platform's own npm registry and `@QuarkusTest` restarts race for it
anywhere. Failsafe passes the same 0 to the packaged artifact.

**`service/` compiles to a GraalVM native image.** `.sdkmanrc` names `25.0.2-graalce`, so `sdk env`
gives you a `native-image` and `./mvnw verify -Dnative` produces
`service/target/qits-platform-deployments` and runs `PdPackagedSurfaceIT` against it. Consequences to
keep in your head: a missing GraalVM does not fail the build (Quarkus falls back to a container build
— grep the log for `Cannot find the native-image`); prefer what is already in the image
(`ProcessBuilder` over a docker client library — the reason `PdProcess` shells out); every config
default the app boots with is part of the native surface (the AUTO_SERVER lesson: the H2 URL carries
none, do not add it); and anything returned as `Response.entity(...)` is invisible to the build-time
Jackson analysis, which is what `api/ApiWireReflection` exists for. **A new response type joins that
list in the commit that adds it** — the failure is a 500 in the native binary while every JVM test
stays green, and it has been paid for once already.

## The partition, and the one rule that keeps it

Three maven modules, package root `eu.wohlben.qits.platform.deployments`:

- **`environments/`** (`…environments.*`) — the topology: `entity`, `persistence`, `dto`, `mapper`,
  `control`, `error`. `EnvironmentService` (tier rows), `ServiceCatalog` (services, links and the
  three rules over them), `PdIdentifiers` (what the topology stores), `PdNetworks`,
  `ApplicationKeys`. **It also owns the datasource, the persistence unit and the Flyway lineage** —
  one database, declared once, in the module both others depend on.
- **`deployments/`** (`…deployments.*`) — the execution: `DeployService`, `EnvironmentOperations`,
  `RollbackPins`, `DeploymentSpecParser`, `DeploymentIdentifiers` (what only reaches an argv),
  `ImageRefs`, `ContainerNames`, `PdProcess`, and the two seams `DeploymentDriver` / `SpecSource`
  plus the announcement port `BuildAnnouncements`.
- **`service/`** (`…api`, `…dockerhost`, `…githost`) — the adapters. Identity is not a package here:
  the forward-auth pair lives in the published `qits-auth-core`.

`eu.wohlben.qits.webui` sits outside that tree, holding `WebUiRedirect` and only that. It keeps the
sibling services' spelling rather than a component-flavoured one, so the file is recognisable across
repos.

**`platform` is a namespace qualifier, not half of a word** — hence `…qits.platform.deployments`
rather than `…qits.platformdeployments`. The execution module therefore lands at
`…qits.platform.deployments.deployments`, next to `…qits.platform.deployments.environments`: the
repetition is the price of a qualifier that names the plane, and renaming the module package to
dodge it would cost the pairing with `environments`. The artifactIds (`qits-platform-deployments-*`)
and the REST path (`/platform-deployments/api`) are unaffected and stay as they are.

**`deployments` depends on `environments` and never the reverse.** That is the partition, and it is
the thing to defend. Execution reads and writes the topology; the topology knows nothing about
containers. The concrete consequence is `EnvironmentOperations`: creating a tier is a row
(`environments`) *and* a network (`deployments`), so the composition lives on the execution side and
`EnvironmentService` stays socketless. Do not put a driver call in `environments/`.

**The seam rule is one rule, applied twice.** Everything the domain modules cannot do — shell out to
docker, fetch a file over HTTP — is an interface there and an implementation in `service/`, with a
scripted fake in the suite. Anything that grows a third follows the same shape; do not put a client
in a domain module.

## What the merge dissolved (do not bring it back)

The topology was `qits-serviceregistry` for one release, reached over HTTP. All of the following is
**gone on purpose**, and each of them is a thing an agent might reasonably try to re-add:

- **`RegistryClient` and `HttpRegistryClient`.** Registration writes rows in the same transaction;
  resolution is a repository query. `ServiceCatalog` and `EnvironmentService` are called directly.
- **`RegistryBearer` and the whole `quarkus-oidc-client` block.** There is no guarded peer to
  present a token to, so there is no client extension, no shipped-off switch and no secret a
  deployment has to supply. If this service ever calls a guarded peer again, all three arrive in
  that commit.
- **`StubRegistry`**, the `@WithTestResource(GLOBAL)` server every `@QuarkusTest` carried.
- **The registry-outage posture** — `RegistryException` (502), `lastKnownTargets` (the
  deployment-history fallback), `CdRegistryOutageTest`. There is no outage to have a posture about.
  The **spec read** keeps its posture exactly as it was, because it is still a remote call.
- **`RegistryExport`**, the one-time boot seeding of the registry from local tables, and the frozen
  `cd_environment`/`cd_application` tables it read. Clean start: one V1, no lineage inherited.

What survived from that seam is the *claims*, rewritten against the local domain:
`PdRegistrationTest` holds what a green build writes and reads back; `PdPinApiTest` holds that the
pins read nothing but deployment rows, which was the outage suite's one claim that was never about
the peer.

**The hazard the merge created, and it bit once already.** The topology is now read from the deploy
**worker** — a bare daemon thread with no request context and no transaction — where the ancestor
made an HTTP call that needed neither. Hibernate throws `ContextNotActiveException` there. So every
read on `ServiceCatalog` and `EnvironmentService` brackets itself with
`QuarkusTransaction.joiningExisting()`: joining rather than requiring a new one, so a caller that
already has a transaction keeps its entities managed. **A new read method on either class needs that
bracket**, and a `@QuarkusTest` that only drives the REST surface will not catch its absence — the
request context hides it. `PdDeploymentFlowTest` is what catches it.

## The worker

`DeployService` runs **the whole of a build-succeeded event** on a single-threaded daemon worker
(`pd-deploy-worker`), the `CiRunService` shape: the intake validates and returns, each DB transition
sits in its own `QuarkusTransaction.requiringNew()` bracket, and everything the docker calls need is
copied out of the entities into a plain `Plan` record first.

Serial execution is load-bearing twice over:

- it makes "the previous ACTIVE deployment" an uncontended read during cutover;
- it makes derived registration's read-then-write atomic against every other event. `ServiceCatalog.
  upsert` is `synchronized` as the belt for every other caller, and the unique service name is a
  third — but the worker is what makes the *pair* (read the links, write the union) atomic, and
  neither of the other two covers that. `twoIdenticalEventsArrivingTogetherRegisterOnePlatformService`
  holds it.

The cost is that the spec's HTTP read sits in the queue too;
`qits.platform.deployments.git-host-timeout-seconds` bounds it. `awaitIdle()` is public for the suite, because "nothing was registered" can only be
asserted after the worker has had the event.

Transactions are programmatic everywhere in `control`, never `@Transactional` — partly for the
worker, partly because a `this.`-invocation never crosses the interceptor and a lost bracket fails
quietly.

The startup sweep (`DeployService.onStart`) fails rows left `QUEUED`/`STARTING` by a crash and
**deliberately reaps no containers** — a deployed application outlives its deployer, and whatever
was ACTIVE before the restart is still serving. Do not "complete" the sweep with a reap.

## The vocabulary rename, and the alias

`singleton` → `platform`, everywhere: `PdDeploymentTarget.PLATFORM`, label
`qits.platform.deployments.target=platform`, network `qits-platform` (unchanged name), key stand-in
`platform:<name>` in `ApplicationKeys`.

**`deployment_target: singleton` remains an accepted alias in the spec parser and nowhere else.** It
parses to `PLATFORM` and nothing downstream can tell the two apart; the error message for an
unrecognised value names only `environment` and `platform`, so a repository being corrected is
pointed at the word to use. Do not add the alias to the API, the enum or the labels — it exists so a
repository that has not been edited yet keeps deploying across the cutover, not as a second spelling
to maintain.

**The config namespace is `qits.platform.deployments.*`** — `platform` qualifies `deployments`, it
is not half of one word. It was `qits.cd.*` in the ancestor and `qits.pd.*` for one release here; a
deployment carrying an old spelling configures nothing and fails loudly at boot (SmallRye rejects an
unsatisfied `@ConfigProperty`), which is the intended failure. The env form is
`QITS_PLATFORM_DEPLOYMENTS_*` — every wrapper and compose file that injects run-args moves with it.

## Adopting what qits-cd left behind

The labels are `qits.platform.deployments.*`. Two earlier spellings exist on the host — `qits.cd.*`
from the retired ancestor and `qits.pd.*` from this component before the namespace was written out
in full. **Nothing here reads either, and nothing should start to.** A holder with no
`qits.platform.deployments.environment` label is unclaimed — a compose original, a bootstrap seed, a
qits-cd container, a container this component started before the rename — and unclaimed means
*adoptable predecessor*. Reading a legacy label would make those containers look like another tier's
and leave them running beside their replacements, which is the one failure the cutover exists to
prevent.

The container **name** prefix is `qits-pd-` (the ancestor's was `qits-cd-`), and it **stays short
through the namespace rename**: docker's name charset has no dot, and
`qits-platform-deployments-<env>-<app>-<id8>` spends 26 characters before the two words a person
actually reads. So it is the namespace's abbreviation, spelled once in `ContainerNames`. It is how a
person reads the host and what a bootstrap greps; it is never how a predecessor is found (that is
the alias). Wrapper and component changes land together.

## Names, and the one that is an address

Two derived shapes, and only one of them resolves:

| | environment | platform |
| --- | --- | --- |
| container name (`ContainerNames`) | `qits-pd-<env>-<app>-<id8>` | `qits-pd-<app>-<id8>` |
| wire alias (`PdNetworks.alias`) | `<env>-<app>` | `<app>` |

**The wire alias is the address, and it is derived in one place because three callers have to
agree**: the `docker run --network-alias`, every `docker network connect --alias` after it
(`DeployService.join`, and `reconcile` for the hubs and platform containers it pulls onto a fresh
network), and the predecessor search. An alias that resolved on the primary network and not on the
joins would be an address that works by luck — `connect()` takes it for that reason, and always has.

The environment qualifier exists because the legacy network is shared by every tier: without it two
tiers' copies of one application hold the same address there. A platform service keeps the bare
name — one instance for the whole platform has nothing to be qualified against, and the platform
repositories carry the plane in their own names now (`qits-platform-idp`), which is also why the
platform container name **drops** the segment rather than filling it with the word.

**The predecessor search asks about the wire alias AND the bare application name.** Every container
started before the qualifier existed holds only the latter, and a search for the new spelling alone
would run a second copy beside the one serving — once per application, on the deployment that
introduces the qualifier. Same posture as `legacy-network`, and it comes out the same way: the
environment label still keeps another tier's container out, and an unlabelled one stays adoptable.
`parseHolders` matches a container's own **name** against that set too, which is what absorbs a
bootstrap-seeded original called `qits-gateway`.

## Networks are docker's bookkeeping, never a row

Hub and spoke, as README describes. Two things to leave alone unless you mean it:

- **`aliasHolders` searches the union** of every network the fresh container will be on, legacy one
  included. Narrow it and a deploy starts a second copy beside a container that holds its alias on
  `qits-net` alone — which is every container on the platform until it has been redeployed once.
- **…and the union is then filtered by the holder's `qits.platform.deployments.environment`**, the
  other half of the same thought. The legacy network is shared by every tier, so the union also returns another tier's
  healthy copy of the same application under the same alias; stopping that would be one tier reaching
  into another. This tier's label → predecessor; another tier's → left alone; **unlabelled** →
  adoptable. A platform deployment keeps only the unlabelled ones.
- **A join asked for and not granted FAILS the deployment.** `docker network connect` reports
  "already there" as an error, so the driver tells that wording apart from a refusal and only the
  refusal counts. It has to fail rather than warn: the health gate curls localhost *inside* the
  container, so it passes perfectly well on a network nobody else is on, and the cutover would then
  remove the predecessor under an unreachable successor. The *reconciliation's* joins stay
  best-effort — those are a self-heal, not this deployment's own reachability.
- **`qits.platform.deployments.legacy-network`** (default `qits-net`, `Optional<String>` because
  SmallRye reads an empty value as absent) is the transition membership. **Emptying it is the enforcement flip**, a
  later phase that needs every direct cross-application URL migrated first. `LegacyNetworkOffTest`
  already runs that posture. An environment teardown never disconnects anything from it and never
  removes it, even when it IS that environment's bundle — which is exactly the dev tier's shape.

## Untrusted input

Two validators, split by the module boundary rather than by taxonomy:

- **`PdIdentifiers`** (`environments`) — names, branches, health paths: what the topology **stores**,
  checked where it is stored. Names become docker network names, aliases and image path segments, so
  the charset is the dns-label one.
- **`DeploymentIdentifiers`** (`deployments`) — shas, repository ids, run ids, resource-attribute
  values: what only ever reaches an argv, checked beside the argv.

**The health path is the strictest and stays that way.** It is the one value interpolated into a
string a *shell inside the container* runs (`--health-cmd`), so it gets an allowlist, no exceptions,
and is re-checked at the last line before the argv (`DockerDeploymentDriver.buildArgv`). Three
callers use the same check: the API, the spec parser (repository-authored input) and the argv.

Argvs are assembled for `ProcessBuilder`, which never re-splits — but do not lean on that:
validation stays at the boundary and the belt stays at the argv.

Mounts and extra env in a *started* container's argv come from the **deployment's own config and
nowhere else** (`qits.platform.deployments.run-args.<application>`). Nothing arriving over HTTP may
contribute a token to a `docker run`; the API is deliberately open on the platform's networks, and config is the trust
domain that already holds the socket.
`DockerDeploymentDriverTest.runArgsOfAnotherApplicationDoNotLeakIn` asserts the absence as the
security property. A `docker exec`, or run-args growing an HTTP-writable source, is the regression.

This component's own env flags (`QITS_ENVIRONMENT`, `QITS_APPLICATION`, `OTEL_RESOURCE_ATTRIBUTES`
and its `QUARKUS_`-spelled twin) are written **before** the run args, and docker keeps the **last**
assignment of a repeated key — measured, not assumed. So they are defaults an operator overrides,
and the ordering is the precedence rule: never reorder them past the run args.

## Addressing and auth

`quarkus.rest.path=/platform-deployments/api` lives in the service module's
`application.properties` and the suite inherits it — a resource's `@Path` is relative to it and must
never repeat the segment; tests address the absolute path, which is what makes them catch a prefix
regression.

**Where `machineAuth.require()` goes.** On a path whose callers are machines: the build-succeeded
intake, and every topology **write** (environment create/patch/delete, service upsert/delete). Not
on any read — a person drives those through the gateway's session and the collector polls them, so a
guard there locks both out the day the gate flips on. That guarded set is the **union** of what the
two ancestors guarded, and it is the one thing the merge had to decide rather than inherit; apply
the same question to a new write and answer it in the commit that adds it.

The guard is **gated off** by `qits.auth.machine.required` (default `false`, shipped by
`qits-auth-core`). Validation follows the same gate
(`quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}`) — gate off, there is no OIDC
tenant, nothing fetches a JWKS, and a clone-alone build needs no issuer. There is no third state.

The intake path is a **cross-repo contract**: qits-ci POSTs
`/platform-deployments/api/events/build-succeeded` fire-and-forget. A mismatch raises no error
anywhere. Move one, move both.

**A new machine surface outside `/platform-deployments/api` needs a line in
`quarkus.quinoa.ignored-path-prefixes`, in the same commit.** Quinoa's SPA fallback is a catch-all
registered near-last, so a real route still wins — but a path matching *no* route is rerouted to
`index.html` and answers `200 text/html`, which a machine client parses as data. Three facts, all
measured on siblings: setting the key **replaces** Quinoa's derivation rather than extending it (so
`/api` and `/q` are repeated by hand); the values are matched **after** `ui-root-path` is stripped,
so they are **relative** (`/platform-deployments/api` written there matches nothing at all — the
failure that hides); and `@WebSocket` or anything on the Vert.x router takes a literal path and needs
its own entry.

## The client's segment, and the one probe still missing

`service/src/main/webui` is the **qits-spa-deployments** submodule. Its `angular.json` sets
`baseHref: /platform-deployments/` and its calls go to `/platform-deployments/api`, so it agrees with
this component today.

The segment is spelled in four places that move together: `quarkus.quinoa.ui-root-path`,
`quarkus.rest.path`, `quarkus.http.non-application-root-path`, and that fourth one in another repo.
**No build here checks the fourth**, so it can drift without turning anything red.

`PdPackagedSurfaceIT` still does not probe the base href. That was correct while the client
disagreed — the right assertion would have failed a build for something no change here could fix —
and it is an ordinary open debt now that it agrees.

## The event side: the direct intake now, the bus later

`POST /platform-deployments/api/events/build-succeeded` is the door that ships, and its payload shape
is the ancestor's unchanged — the bootstrap replays lost events through it by hand and qits-ci sends
it today.

`BuildAnnouncements` in `deployments/control` is the seam. The target model is bus-driven: qits-ci
already publishes `BuildSuccessful` and `SoftwareRelease` onto qits-events, and a deployment should
follow from an event that can be retried and replayed rather than from a POST nobody retries. That
is **wave 3**, and it deliberately takes no dependency yet — no eventstream on the classpath, no
stub for a bus, no test double for a subscriber nobody has written. What lands then is one class in
`service/` that decodes an event and calls `announce`; nothing in the interface changes.

## Adding a dependency on another context

Don't. This component has no compile-time dependency on any other qits module beyond the published
`qits-auth-core`, and should not grow one. Things arrive as an HTTP payload on the intake, as a URL
in config, or not at all. Never add a JPA relation to another context's entity.

The rule reaches **inside this schema** too: `pd_deployment` names its service and its tier as plain
`String` columns with **no FK**, even though the topology is two tables away. Deployment history
outlives the rows that described it, and the rollback pins read off it must keep answering whatever
the catalogue says today.

## Schema changes

`environments/src/main/resources/db/platformdeployments/migration/`, hand-written, its own lineage on
its own datasource — keep appending, never edit an applied migration. It lives in `environments/`
because the component is one database; the module split is code, not storage.

The suites run every migration against an **empty** schema, so a backfill is untested by them.
`deployments/src/test/.../PdSchemaTest` is the shape to copy: plain JUnit, a real H2, Flyway, then
the claims. A migration that backfills needs a test that migrates to the version before, writes the
rows the old code wrote, and migrates the rest of the way.

Deployment listings order by `seq`, V1's identity column, and **not** by `createdAt desc, id desc`:
the id is a random UUID, so that tiebreak swapped two rows recorded in the same tick at random —
which is what the deployments of one build-succeeded event are, and what a client reads "the current
one per application" off.

**Nulls are distinct to `=`.** A platform deployment's `environment_id` is null, so every query
matching "the same (application, tier)" tests for null explicitly. The startup sweep's adoption hangs
on it: get it wrong and a self-updating instance comes back having failed its own deployment while a
second row still claims to be ACTIVE.

## Dependencies

**The client is the only submodule.** `service/src/main/webui` is qits-spa-deployments; `git submodule update
--init` is half of a clone here, and `.config/qits/ci-post-receive.yml` runs it for that reason.
Shared auth comes from the platform Maven repository as `qits-auth-core`.

**`quarkus-undertow` must never be on the classpath.** Its presence breaks Quinoa's production static
serving — the client 404s from a build that was green — and it arrives *transitively* from anything
servlet-shaped:

    ./mvnw -pl service -am dependency:tree | grep -i undertow

**Quinoa is in no BOM**, so its version is pinned by hand in the root pom's properties. 2.8.2 is the
last release built against a Quarkus *older* than the platform's 3.34.6; 2.8.3 is built against
3.36.2, ahead of us. Bump only when the platform's Quarkus passes the version a release is built
against.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and Quarkus merges it
  into the test config. **Never re-declare an app-level setting in test resources** — the test copy
  carries only the port, the in-memory H2, and `quarkus.devservices.enabled=false`.
- **No dev services, ever.** A dev service is a container start, and the first rule here is that a
  clone tests green with no docker. `quarkus-oidc` in particular launches a real Keycloak the moment
  a profile leaves `quarkus.oidc.auth-server-url` unset — measured on the ancestors, not feared.
- **Machine-token tests mint their own tokens.** `MachineTokens` signs RS256 with the key pair in
  `service/src/test/resources/machine-token-*.pem`, and `MachineGuardEnforcedProfile` hands
  quarkus-oidc the public half, so the enforced path is exercised end to end with no
  qits-platform-idp to reach. Those PEMs are **test fixtures, not credentials**.
- `FakeDeploymentDriver` and `FakeSpecSource` are `@Mock` and application-scoped, so they are shared
  across tests: reset both in `@BeforeEach`, use distinct **environment names, repository ids and
  service names** per test, and read their state through their **methods** — the injected reference
  is a CDI client proxy, and a field read on a proxy sees the proxy's fields, not the bean's. The
  suite shares one in-memory database across classes (Flyway cleans at start, not between tests), and
  a **platform** service registered by one class shows up in every other class's link query — so
  assert with `hasItem`, never with a size.
- They live in `…deployments.control`, the seam's own package, which is also what lets
  `PdSweepAdoptionTest` drive the package-private `sweepInFlight()`.
- Flow tests poll the read surface to a deadline rather than reaching into the service — the same way
  a caller experiences the API, and immune to the worker's timing. Platform deployments are the one
  thing that surface cannot show (`/deployments` takes an environment, and a platform deployment has
  none), so those tests wait on the driver (`awaitStarted`) and read the row through `/applications`.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml`. Regenerate and commit when the surface
  changes: `./mvnw -pl service -am test -Dtest=OpenApiSchemaExportTest
  -Dsurefire.failIfNoSpecifiedTests=false`. The intake is `@Operation(hidden = true)` (a wire API);
  everything else is the document. The test classpath is indexed too, so a new `@Path` resource under
  `src/test` lands in the committed document unless it is hidden.
- `PdPackagedSurfaceIT` runs the **packaged artifact** (fast-jar under `-DskipITs=false`, binary
  under `-Dnative`) and asserts what a native build can silently lose: the build-time route prefixes,
  the shipped `${user.home}`-rooted H2 default (it relocates `user.home` rather than restating the
  URL), Flyway's migration surviving as a resource, and — the claim the ancestors could not make —
  both domains round-tripping in one process against one database. It points
  `qits.platform.deployments.container-runtime` at a binary that does not exist, which keeps it
  free of host side effects and proves every driver call degrades to a warning rather than a
  failure.
- **`PdPackagedSurfaceIT` is also the only test that ever sees the client.** Quinoa is disabled in
  test mode, so no `@QuarkusTest` here has a client at all — a unit test asserting anything about the
  segment would pass against a process serving nothing.

## The image and the pipeline

`docker/Dockerfile` and `.config/qits/ci-post-receive.yml` are two halves of one thing, and the seam
between them is the only reason either is interesting: **the client cannot be built inside a docker
build.** It depends on `@qits/ui-components`, which lives only on the platform's own npm registry,
and a `RUN` step reaches the public internet but reaches that registry by no address at all. So the
pipeline step installs and builds the bundle, and the Dockerfile's builder stage neuters Quinoa's
install/ci/build commands to `--version` and packages what it was handed.

Four things follow, each load-bearing:

- **`.dockerignore` does NOT exclude the client's `dist/`.** That departs from the platform's Quinoa
  reference — here `dist/` is the payload, and excluding it fails the build at the `test -f` guard.
- **The two `package-manager-install` flags exist only on the Dockerfile's `mvnw` line**, because the
  Mandrel builder image ships no node. They must never go into `application.properties`: a local or
  CI build must use the node on `PATH`, so no build silently downloads a toolchain. `22.22.0` is the
  platform pin.
- **The bundle is `cp`'d onto itself before the build.** Quinoa *moves* `build-dir` rather than
  copying it, and overlayfs cannot rename a directory that still lives in a lower image layer — it
  answers EXDEV and the JDK's fallback refuses a non-empty directory, dying with
  `DirectoryNotEmptyException` seconds in. The `cp` re-materialises it in the layer that is about to
  move it, which is why it has to be in that same `RUN`.
- **`docker build --network host`, never a custom network.** Buildkit is the builder format the
  platform targets and it refuses custom networks on a build; the ancestor's `--network qits-net`
  only worked because an older CLI in the step image fell back to the legacy builder. The maven
  registry URL is derived from `$QITS_REGISTRY` so no address is stated in the file.

The pipeline also rewrites `package-lock.json`'s `resolved` **origins** before `npm ci`: npm fetches
tarballs by the absolute URL in the lockfile and ignores the configured registry, and npm's own
`--replace-registry-host` is broken for a registry mounted under a path prefix. The committed
lockfile keeps the developer-host origin, which is correct locally.

**This repo is its own deployer**, and it is an **environment service** — one instance per
environment, deploying that environment. Its `.config/qits/deployments.yml` takes the default
target and names `deploy_branches: environment/prod`, so a push to `environment/prod` is a
deployment. A green run announces this component to itself and it takes the self-update handoff; the
`/platform-deployments` surface blips mid-cutover, and a successor that misses its health gate
leaves the predecessor serving.

**`environment/<name>` is the only deploy ref, on both planes.** A green build deploys wherever an
*environment* listens to its branch — `DeployService.registerPlatform` asks
`environments.onBranch(branch)`, the same question the environment arm asks, and what comes out is
still platform-shaped (no environment id, one instance, no links). `platform/main` and
`SpecSource.DEFAULT_PLATFORM_BRANCH` are gone, and so is the spec's `branch:` key. `main` stays the
integration trunk: a push to it builds and ships nothing.

The consequence to revisit: with several environments, **any** environment's branch rolls the one
platform instance. That is acceptable while one environment exists and has to be answered before
the second one is created — the plan gates environment #2 on it anyway.

**`deploy_branches:` is parsed here and used by nobody here.** The release flow reads the same file
for its promotion targets, and this parser fails a deployment on an unknown key — so a key another
reader needs is a key this reader has to know. It is validated all the same: a ref this file cannot
spell is a mistake wherever it is read.
