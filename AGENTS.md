# qits-deployments — working notes

Read `README.md` first: it defines the model (tiers, two planes, derived rows) and the flow (green
build → registration → health-gated cutover). This file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials, **no network**. That is why the poms duplicate versions
instead of inheriting them, and why every seam that reaches outside the process is faked rather than
skipped: `FakeDeploymentDriver` behind `DeploymentDriver` (docker), `FakeSpecSource` behind
`SpecSource` (the git host) and `FakeResourceProvisioner` behind `ResourceProvisioner` (the
platform's postgres). **Three fakes** — the ancestor's fourth, a stub HTTP server for the topology,
dissolved when the topology became a repository query.

The one thing the suite does start is a **postgres of its own**: the component's store is one now,
and `testdb/EmbeddedPg` spawns zonky's real binaries as a child process. A maven dependency, not a
container — the rule is no docker, and it still holds.

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
default the app boots with is part of the native surface (the AUTO_SERVER lesson, which now reads as:
the datasource ships an *expression* over `QITS_RESOURCE_DB_*` and no fallback URL at all, so there
is no default with a feature in it to lose); and anything returned as `Response.entity(...)` is invisible to the build-time
Jackson analysis, which is what `api/ApiWireReflection` exists for. **A new response type joins that
list in the commit that adds it** — the failure is a 500 in the native binary while every JVM test
stays green, and it has been paid for once already.

**There is a second such list now, `bus/EventWireReflection`, and it exists for a different
reason**: `CanonicalJson` builds its own `ObjectMapper` on purpose — the payload string is a
byte-for-byte wire contract and a consuming application's customizers must not reach it — so the
whole graph it binds is invisible to the same analysis. It registers the consuming path
(`EventFrame`, the package-private `EventPage` by string name, and the payload record). Leave
`EventPage` out and the stream works in the binary while **catch-up alone** fails, which is the half
nobody would be watching. **The publishing path joined it when this component got events of its
own**: the four `DeploymentQueued`/`Started`/`Active`/`Failed` records, `EventEnvelope`, and
`CanonicalJson$QitsEventMixin` by string name because it is nested in the library. The mix-in is the
quiet one — it is what keeps `eventId` out of the payload, so its absence is a wire contract that
changed with no crash and no log. **A fifth event joins the list in the commit that adds it.**

## The partition, and the one rule that keeps it

Four maven modules, package root `eu.wohlben.qits.platform.deployments`:

- **`environments/`** (`…environments.*`) — the topology: `entity`, `persistence`, `dto`, `mapper`,
  `control`, `error`. `EnvironmentService` (tier rows), `ServiceCatalog` (services, links and the
  three rules over them), `PdIdentifiers` (what the topology stores), `PdNetworks`,
  `ApplicationKeys`. **It also owns the datasource, the persistence unit and the Flyway lineage** —
  one database, declared once, in the module both others depend on.
- **`deployments/`** (`…deployments.*`) — the execution: `DeployService`, `EnvironmentOperations`,
  `RollbackPins`, `DeploymentSpecParser`, `DeploymentIdentifiers` (what only reaches an argv),
  `ImageRefs`, `ContainerNames`, `PdProcess`, `ResourceProvisioning` and `BootResourceRegistration`,
  and the three seams `DeploymentDriver` / `SpecSource` / `ResourceProvisioner` plus the
  announcement port `BuildAnnouncements` and the ordering collapse `BuildTips` behind it, and the
  outgoing port `DeployAnnouncer`.
- **`deployments-events/`** (`…deployments.events.*`) — the event VOCABULARY: four plain records
  over `qits-eventstream` and nothing else, not even quarkus-arc. It is a module rather than a
  package because a vocabulary is what a *consumer* needs: the day another service listens for
  `DeploymentActive` it takes this jar and gets the record plus the bus and no part of the deployer.
  The ci-events and githost-events shape, which is also why the directory carries the repo's name
  rather than a bare role word.
- **`service/`** (`…api`, `…bus`, `…dockerhost`, `…githost`, `…pghost`) — the adapters. `bus` is the
  event-bus half: the durable `BuildSuccessful` subscriber, the `DeployEventAnnouncer` that
  publishes this component's own four events, and the native-image registration for what the
  library's own `ObjectMapper` binds. Identity is not a package here: the forward-auth pair
  lives in the published `qits-auth-core`.

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

**The seam rule is one rule, applied three times.** Everything the domain modules cannot do — shell
out to docker, fetch a file over HTTP, speak DDL to somebody else's server — is an interface there
and an implementation in `service/`, with a scripted fake in the suite. `ResourceProvisioner` was
the third and took the shape unchanged; a fourth follows it. Do not put a client in a domain
module.

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

**The worker survives losing its own datasource, in exactly three brackets.** This component deploys
qits-oci-postgresql — the postgres its own registry lives in — so cutting that container over kills
every connection the deployment performing it is holding. It did: eaa34fbc cut over cleanly, went
healthy, and then ended `FAILED: [unexpected: JDBCConnectionException …]` because the post-gate
bookkeeping ran on dead connections, while a second event was dropped the same way in the worker's
own catch. `DbRetry` wraps the catalogue read an event opens with, the cutover bookkeeping and
`finish` — **connection-class failures only** (SQLState `08*`/`57P0x`, the pool's acquisition
timeout, Hibernate's `JDBCConnectionException`), thirty seconds of half-second sleeps, safe because
the worker is single-threaded and those three brackets re-read what they write.

**`DbRetry` is the platform's now** — `eu.wohlben.qits.db.DbRetry` from `qits-db-core`, published by
qits-integrations-quarkus. It was a private class here first, and the lib's is that class with the
budget moved from a constant to a per-call argument; the thirty seconds are stated at each call site
as `DeployService.CUTOVER_BUDGET` (package-private, because `DeploymentObserver` wraps its brackets
for the same reason and one budget spelled twice would drift). The lib's own suite pins every failure
shape this component ever saw, which is why the local `DbRetryTest` went with the local class.

**It has two spellings and the choice is not a style one — it is who owns the transaction.**
`DbRetry.call` wraps a block; `DbRetry.inNewTx`/`runInNewTx` **is** the `requiringNew`. Owning the
boundary is what lets the retry tell "the body threw it, so it certainly never committed" from
"the transaction manager reported it", which is the one round trip nothing can place — Narayana
spells a lost commit and a real rollback with the same `RollbackException`, measured. So:

- a **read** bracketed by the callee (`catalog.find`, `environments.onBranch`) keeps `call`;
- a **write** that used to read `DbRetry.call(…, () -> requiringNew().call(…))` is now `inNewTx`
  with the `requiringNew` gone — the worker's cutover bookkeeping and `finish`, and all three of
  `DeploymentObserver`'s brackets;
- and each of those bodies ends with a `flush()`. An ORM flushes at commit by default, which would
  put every statement on the far side of the undecidable round trip; flushed, a lost connection is a
  body failure and is retried. Without the flush the wrap reports rather than helps.

`DbRetry.call` around a write survives in exactly one place, `ServiceCatalog.upsert`, and its
javadoc argues why: the boundary there has to stay inside a `synchronized`, so what makes a second
attempt safe is the write's own converging shape rather than the retry's knowledge.

**It is the second half of a pair, and the first half is the pool.** The datasource carries the
platform's three-line baseline — `jdbc.driver=eu.wohlben.qits.db.PatientPgDriver`,
`validate-on-borrow=true`, `acquisition-timeout=15S` — so a connection request is held while postgres
comes back rather than failing at once. `DatasourceBaselineTest` (qits-arch-rules, beside
`ArchRulesTest` and in `service/` for the same reason) fails the build naming any postgresql
datasource missing a line. That includes the `eventstream` one, which the pinned qits-eventstream
release ships bare: `service/`'s `application.properties` states the three lines for it, and that
block is marked to delete when a release of that jar carries them itself.

**What is deliberately NOT retried, and it is a rule rather than an omission:** `queue`,
`recordRejection`, the `STARTING` transition and the platform conversion. They insert or move rows,
so a commit whose outcome the connection died before reporting would be duplicated by a second
attempt — and all of them run before anything docker-side has happened, so losing one drops the
event with nothing half-done. Retry what comes *after* a container is running, where dropping the
work leaves a live container with no row that admits it. Never a business failure: a 409 retried is
one visible failure turned into a slow one.

**The REST reads are patient too, and the wrap is in the CONTROLLERS.** `PdReadPatience` (in `api`)
spends `qits.platform.deployments.db-retry-deadline` — 15S shipped, not the worker's 30 — on every
read of this surface: the service listing, the applications, the environment listing and aggregate,
the link query, and the deployment listing's tier check. **A new read endpoint joins it in the
commit that adds it; no write ever joins it** — a write's patience lives one layer down, below.

It is a bean the controllers call rather than a wrap inside `ServiceCatalog`/`EnvironmentService`,
and the reason is that those reads have callers that must not sleep. `ServiceCatalog.delete` calls
`require`, `allApplications` calls `list`, `EnvironmentService`'s `update`/`delete` call `require`,
and `BuildTips` calls `onBranch` from inside two `requiringNew` brackets — one of them under
`claim`'s `synchronized`. A retry inside the read would sleep holding a transaction, and there
holding a monitor. The worker's own reads are already wrapped at `CUTOVER_BUDGET`, so a wrap inside
would nest one budget in the other. (`ServiceCatalog.upsert` is `synchronized` as well, but it is a
write and no read shares its monitor.) `PdReadPatienceTest` holds both halves — recovered after one
lost connection, still a 500 when the database stays gone — off a stand-in repository installed with
`QuarkusMock`, under `DbPatienceShortProfile` so the deadline is reachable in a suite.

**The request-path WRITES are patient too, and their wrap is in the SERVICE — because it has to be
the transaction.** `inNewTx` only knows an attempt never committed if it owns the boundary, and the
boundary is in `EnvironmentService`, not in a controller. So `create`, `update` and `delete` each
*are* a `DbRetry.inNewTx` spending the same `db-retry-deadline` the reads do (a request thread, not
the worker's 30S), with validation left outside it — a rejected name is not worth a second attempt —
and a `flush()` as the body's last statement. `PdWritePatienceTest` holds both halves: an insert
whose connection dies *after* it ran lands **exactly once** on the second attempt, and a failure
that is not the connection is reported on the first.

`ServiceCatalog.upsert` is the one write wrapped from outside instead, and the two reasons are worth
keeping straight. It is `synchronized`, and a retry inside would sleep holding the catalogue's
monitor; the monitor has to enclose the commit, because the lock is what makes "is there a row for
this name yet" atomic. So the wrap sits on the REST door — the non-`synchronized` `upsert(Upsert)`
that already exists for causation — and it is `DbRetry.call`, safe because an upsert by name
converges rather than because the retry knows anything. **The worker's door `upsert(Upsert, UUID)`
stays bare**, with `queue`, `recordRejection` and the rest of derived registration: all of it runs
before anything docker-side has happened, where losing an event leaves nothing half-done.

## The observer: the second half of the eaa34fbc story

`DbRetry` fixed the **cause** above. It did nothing for the row: eaa34fbc still says `FAILED` while
`qits-pd-prod-qits-oci-postgresql-eaa34fbc` has been `Up (healthy)` for hours holding the
`prod-qits-oci-postgresql` alias, because a status was written once at deploy time and never read
back. `DeploymentObserver` is that second half, and the mirror image it also closes: an `ACTIVE` row
whose container died an hour after the gate passed, with nothing ever noticing.

- **It runs on the deploy worker**, enqueued by a bare daemon ticker (`pd-observation-ticker`) every
  `qits.platform.deployments.observe-interval-seconds` (30; `0` is off). Not quarkus-scheduler: the
  ticker's whole job is `worker.submit`, and a scheduler extension would put a second concurrency
  model beside a component whose entire ordering story is "one worker, in queue order". An observer
  thread of its own would take away the invariant serial execution buys — "the previous ACTIVE
  deployment is an uncontended read" — and could read the state between a cutover's own brackets. A
  tick that fires while one pass is already pending **collapses** into it (`observationPending`): an
  observation is a statement about now, so ten of them stacked behind a long deploy queue would all
  answer the same question.
- **It settles the LATEST row per (application, tier) only**, latest by `seq`. History stays history:
  an older `FAILED` row describes an attempt that really did fail, and today's healthy container says
  nothing about it. `QUEUED`/`STARTING` belong to the worker's state machine and to the startup sweep
  — a self-update handoff sits in `STARTING` with a healthy successor **on purpose** —
  and `DECOMMISSIONED` is another deployment's decision.
- **`FAILED` → `ACTIVE`** when the container **the row itself names** is healthy by
  `HealthGate.healthy` (the gate's own verdict, extracted so there is one spelling of it). Only the
  row's own container: the seam asks by container **name**, never by alias, so a healthy container of
  somebody else's deployment cannot resurrect a foreign row. The detail **appends** — the original
  failure text is the diagnosis and is what made the bug findable in the first place.
- **`ACTIVE` → `FAILED`** only when the container is **absent or terminally exited/dead**, and only
  when **two consecutive** passes agree. Both halves are the health gate's patience restated:
  restarting is not dead, running-but-unhealthy is not dead (that is the postgres-alias boot race the
  gate already tolerates), and one `docker inspect` that could not answer must not flip a deployment
  that is serving. The strike count is in memory on purpose — it is a debounce, not a fact, and a
  restart that loses it spends two more passes agreeing.
- **A recovery also decommissions the prior `ACTIVE` rows of that place.** The bookkeeping that died
  in eaa34fbc was one bracket doing two things, so a recovered row often has a predecessor still
  claiming to serve, and two `ACTIVE` rows for one (application, tier) is the invariant
  `listActiveByApplication` and the rollback pins are written around.
- **It writes rows and nothing else** — no container started, stopped or removed, no network touched.
  The sweep's "deliberately reaps no containers" stance, and it applies more strongly here: the sweep
  runs once at boot, this runs forever beside a live platform. Whatever still holds an alias is
  absorbed by the next deployment's predecessor search, which is where that decision belongs.
- The reads and the writes are `DbRetry`-wrapped for the same reason the cutover bookkeeping is: this
  is bookkeeping *after* a container is running, and one day a pass will run during a postgres
  self-cutover. The docker call sits between the two brackets, never inside one.

No ticker runs under a `@QuarkusTest` — `onStart` returns early in test mode — so the interval keeps
its shipped default in the suite and `PdDeploymentObservationTest` drives `observeOnce()` and
`enqueueObservation()` directly, the `PdSweepAdoptionTest` shape. That test also holds the serialization
claim, off the fake's call log: the pass's `observe:` calls land after the deployment's last one.

## Two orchestrators, one seam

`qits.platform.deployments.orchestrator` is `docker` (shipped) or `swarm`, and it picks which
`DeploymentDriver` the whole component runs on. Both paths work; deleting the docker one is a later
phase, because the handoff referee is what makes this component able to update itself today.

**The seam is two verbs now**: `apply(ServiceSpec)` makes the described service exist at the
described image, `awaitConverged(name, timeout)` says whether it took. `start`, `stop`, `restart`,
`connect`, `disconnect`, `aliasHolders`, `handoff`, `selfContainerId` and `containerId` are gone
from it — every one of them is a statement about how *docker* replaces a container, and keeping
them would have made one orchestrator's model look like the contract.

**So `DeployService.execute` has no branches in it**: resolve → provision → pull (for the
`IMAGE_MISSING` classification, on both paths) → `apply` → `awaitConverged` → record. The
predecessor search, the alias union, the stop-before-start, the join loop, the reconciliation, the
rollback and the referee all moved into `dockerhost/DockerDeploymentDriver`. What stayed is the
bookkeeping, because it is the same on both paths: the row per place, the four announcements, the
cutover bracket, and the reap **after** the rows (`Convergence.retired()` comes back as data for
exactly that reason).

Three things about the shape, each easy to undo by accident:

- **`nameOf(spec)` is asked before `apply`**, and it is the whole of the naming difference: docker
  names a container per deployment (`qits-pd-<env>-<app>-<id8>`), a swarm service's name IS its
  address so it is the wire alias, and the row records whichever it is. A name-based check that
  assumes the first shape breaks the swarm path silently.
- **`ApplyOutcome.HANDED_OFF` is neither success nor failure.** A deployment that replaces this very
  process leaves its row `STARTING` on purpose; the instance that survives records it. Docker
  answers succession with a detached referee, swarm with the manager in the daemon.
- **`DockerHost` is the docker CLI as its own seam**, and the suite's `@Mock` fake sits there rather
  than at `DeploymentDriver`. That is what keeps every flow test driving the REAL cutover
  choreography — the stop, the joins, the reconcile, the rollback, the referee — with no docker.
  `FakeDeploymentDriver` still exists for the state-machine tests and is installed per test with
  `QuarkusMock`; making it a `@Mock` would take the choreography out of the suite.

**Under swarm the topology is flat and that is a decision, not a simplification**: every
`--network-add` recreates the task, so a service declares its whole membership at create time —
`qits.platform.deployments.swarm.flat-network` (an *attachable* overlay, which is what keeps CI
step, workspace and agent containers working on it) plus `qits-platform` for the plane. The
per-application networks the state machine still computes are dropped by the swarm driver, out
loud. A service update keeps the mounts, networks and ports it was created with: changing the shape
of a service is a `service rm` and a redeploy, not a deployment.

**`update_order` in `.config/qits/deployments.yml`** is `start-first` (default) or `stop-first`, per
repository, and only the repository knows: a published host port, a single-writer store or a held
config volume each make the overlap impossible. This repo says `stop-first`. The docker path reads
it and ignores it — its cutover is stop-first by construction.

**Phase 6 is not done and is named where it bites.** The startup sweep's adoption arm is still "is
this row's name me" (`DeploymentDriver.isSelf`); under swarm the better question is whether the
service's running image carries the row's sha, which is what tells a completed self-update from a
rolled-back one. Until then a swarm self-update adopts its row the way the docker one does.

## The health gate is patient, and that is not a tuning choice

`HealthGate` (in `deployments/control`, polled by the driver) ends early on exactly two verdicts:
**healthy**, and a container docker cannot inspect at all. **Restarting is PENDING. Running-but-
unhealthy is PENDING.** The deadline — `qits.platform.deployments.health-timeout-seconds`, unchanged
— is what fails a deployment, and the verdict then reads `container still <state> after <n>s` with
the log tail under it.

The reason is structural rather than generous. `docker run` takes **one** network, so a fresh
container starts on its primary one and every other join happens after the start
(`DeployService.join`). A PostgreSQL-backed application runs Flyway immediately, cannot resolve the
postgres wire alias yet, and dies with an acquisition timeout; `--restart unless-stopped` brings it
back seconds later into a world where the joins are done, and the second boot works. The old gate
read `restarting/unhealthy` once and failed the deployment 18 seconds in — measured on
qits-platform-idp's first PostgreSQL deployment. H2-era applications could never hit it, which is
why the instant fail survived this long.

**The follow-up this makes optional:** `docker create` → connects → `docker start` would remove the
race outright. It moves the argv off `run`, grows `StartSpec` with the join set, and drags the
self-update handoff and the cutover's call-order assertions with it. Recorded, not done — a patient
gate is the fix, and it is the one that also covers every other slow first boot.

The gate lives in the domain module rather than in `dockerhost/` so the suite's fake gate IS the
shipped gate: `FakeDeploymentDriver.scriptRestartingUntilHealthy` feeds it states and nothing else.

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

- **`PdIdentifiers`** (`environments`) — names, branches, health paths, resource names, database
  names: what the topology **stores**, checked where it is stored. Names become docker network
  names, aliases and image path segments, so the charset is the dns-label one.
- **`DeploymentIdentifiers`** (`deployments`) — shas, repository ids, run ids, resource-attribute
  values: what only ever reaches an argv, checked beside the argv.

**The health path is the strictest and stays that way.** It is the one value interpolated into a
string a *shell inside the container* runs (`--health-cmd`), so it gets an allowlist, no exceptions,
and is re-checked at the last line before the argv (`DockerDeploymentDriver.buildArgv`). Three
callers use the same check: the API, the spec parser (repository-authored input) and the argv.

**`health_cmd` is the one value with no charset, and the exception proves the rule.** It is not
interpolated into a shell string — it *is* the string, chosen by a repository for its own
container, so an allowlist would refuse the probes worth writing (`pg_isready -U postgres || exit
1`) while granting nothing: the image's entrypoint is already that repository's, and the command is
one argv element to `ProcessBuilder`, never re-split. `DeploymentIdentifiers.requireHealthCmd`
bounds it to one non-blank line of 512 characters, at the parser and again at the argv. It
**replaces** `health_path` (the parser fails a file setting both), so the path is neither used nor
checked when a command is present. It is not stored: the spec is read before every deployment, and
the one path that resolves targets from the catalogue instead records failures and deploys nothing.

**`resources:` takes the health-path treatment, and it needs one checkpoint more than the health
path does.** Both halves of an entry are repository-authored. The **resource name** becomes an
environment-variable key on a `docker run` (`QITS_RESOURCE_<NAME>_URL`), which is the health path's
situation exactly. The **database name** additionally lands in DDL run against a postgres instance
**the whole platform shares** — and DDL has no bind variables, so the allowlist is not a belt there,
it is the only guard. Hence `requireResourceName` (`[a-z][a-z0-9-]{0,31}`) and
`requireDatabaseName` (`qits_[a-z0-9_]{1,58}`), and three checkpoints rather than two: the parser,
the line before the SQL string is assembled, and the argv. The mandatory `qits_` prefix is the
structural half of the guard — it excludes `postgres`, `template0/1` and every `pg_*` name by
construction, so the namespace a repository can reach is disjoint from the instance's own.

**What a repository can NAME versus what this component INJECTS.** A repository names a database of
its own; every VALUE that reaches the container for it — the url, the role, the password — is
derived or generated here. So the rule that nothing arriving over HTTP contributes a credential to a
`docker run` is unchanged by provisioning: the credential is this component's, and the registry row
is its only copy.

**Provisioning speaks SQL, not shell.** `CREATE ROLE` / `CREATE DATABASE` / `REVOKE` / `ALTER …
OWNER` over plain JDBC — no `psql`, no `docker exec`, no process. `exec` is still not in the docker
vocabulary and must not enter it. The postgres superuser password comes from
`qits.platform.deployments.postgres.admin-password` (deployment config, the domain that already
holds the socket), has no default, is never stored in a row and never reaches an argv. **There is no
`DROP` and none is coming**: marking a resource obsolete is future work, and it will be a mark.

**The `platformdeployments` database is credential-bearing.** `pd_resource.password` is the single
authority for every provisioned application's credential. Treat it with the sensitivity of the
`qits-deployments-config` volume. No statement containing a password is logged, no failure detail
names one, and `PgResourceProvisioner.literal` refuses a password it could not quote safely rather
than escaping it cleverly.

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

## Resources: what the deployer provisions, and where the truth is

A repository's `resources: postgresql:<name>[:<database>]` is answered before the pull, by
`ResourceProvisioning` over the `ResourceProvisioner` seam. Four things decide how it behaves and
each is easy to undo by accident:

- **The registry row is the single authority for the credential.** `pd_resource.password` is not a
  cache of something postgres knows — postgres stores a hash — and no file carries it. That is what
  makes the drift arms decidable: a row without a role is a reset postgres volume and the role comes
  back with the **stored** password (running containers keep working); a role without a row is a
  reset deployer database and the role is rotated to a **fresh** one (nothing knew the old one).
  Never a `DROP`, in either direction.
- **This component is adopter #1 and cannot provision itself from cold**, which is what
  `BootResourceRegistration` exists for: the bootstrap creates its roles and databases over plain
  JDBC before the process exists, and the rows are written from the environment at every boot.
  Without them the first self-deploy takes the reconcile arm and rotates the passwords its own
  connection pools are holding open.

  **It declares TWO resources now** — `db` (this component's registry) and `eventstream` (the bus
  client's claim ledger and outbox, a store of its own with its own Flyway lineage) — so
  `BootResourceRegistration` records both, over `RESOURCES`. A third entry in
  `.config/qits/deployments.yml` is a third line there and nothing else. **The resource NAMES are
  load-bearing**: the variables follow the name (`QITS_RESOURCE_<NAME>_URL` and its two siblings)
  and the jar that owns each store reads exactly those in its own shipped defaults, so renaming one
  here silently stops matching.

  **Self-provisioning works for everything after the first container**, and that is worth being
  precise about because this component is its own deployer: the spec is read at the built sha, so
  the *running* instance reads the new `resources:` line, creates the role and the database, and
  injects the triple into the successor it starts. Only a cold bootstrap has no deployer to do it,
  which is why the bootstrap's run-args carry both triples. A missing one is not a degraded boot —
  the jars' expressions have no defaults, so the process dies at Flyway naming what is absent, and
  the health gate leaves the predecessor serving.
- **No transaction spans the seam call.** The registry read and the row upsert are two
  `requiringNew()` brackets with the DDL between them — a socket to another server must never sit
  inside this component's own transaction.
- **The host is derived, never configured.** An environment application uses
  `PdNetworks.alias(<tier>, "qits-oci-postgresql")`; a platform-plane one uses the platform
  environment's tier, and fails with a sentence when no environment is designated. There is no
  postgres-host config key and there should not be one.

The admin credential and the untrusted-input rules for the two names are in **Untrusted input**
above. `PgResourceProvisionerTest` runs the matrix against a real postgres because the arms differ
only in which statement runs — a fake there would be asserting the test's own model of the server.

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

## The event side: two doors, one seam

`BuildAnnouncements` in `deployments/control` is the seam for what comes IN, and **wave 3 has
landed**, so there are two ways in. (What goes OUT is `DeployAnnouncer`, below.) Neither wins: both call `announce`, and everything after it — the spec read, derived
registration, the queue, the health-gated cutover — cannot tell them apart.

- **`POST /platform-deployments/api/events/build-succeeded`** (`api/PdEventController`), payload
  shape the ancestor's, unchanged. It is the **manual and bootstrap** door: an operator replays a
  lost event through it, and it is the only one that works before qits-events exists. Nobody retries
  it.
- **The bus** (`bus/PdBuildSuccessfulSubscriber`), a `QitsDurableEventListener` on qits-ci's
  `BuildSuccessful`. The publisher retries it, the log replays it after a cutover, and the library
  hands it over exactly once per event whichever channel delivered it.

Three things about the subscriber that are the whole of what a durable consumer owes:

- **`consumerId()` is `pd-build-succeeded`, and it is storage.** It keys `consumed_event` and
  `consumer_watermark`. Changing it makes a brand-new consumer: the old claims are orphaned and the
  new id initializes at the head of the log, silently skipping everything in between. It is a string
  a person chose so it survives the class being renamed. `PdBusBuildIntakeTest` pins it.
- **Ordering is ours and is not optional.** Catch-up delivers late, so a *different, older* build can
  arrive after a newer one is deployed — a rollback nobody asked for. `BuildTips` collapses to the
  tip and the class javadoc argues the two answers it takes: what THIS PROCESS announced (a build's
  own finish time against a build's own finish time, exact, and what lets two builds seconds apart
  both deploy), and, only when that knows nothing, the **newest deployment row** for the tiers
  listening to that branch (the cross-restart floor). Combining them the other way round is the
  mistake: a row is stamped when it is written, minutes after the build it describes, so comparing
  an arriving build against one skips builds that are genuinely newer. Duplicates need nothing —
  the library already makes the same event id impossible twice.
- **A throw leaves the event owed forever.** It is offered again on every sweep and the watermark
  stays behind it, so one poison event stops this consumer's catch-up. So the handler **swallows
  what retrying cannot fix** — an unreadable payload, a payload with no triple, an identifier
  `DeploymentIdentifiers` refuses — each with a WARN, and throws only what a next attempt could
  succeed at.

**Two facts about the transaction the handler runs in.** It is the library's claim transaction, on
the `eventstream` datasource — so every read of *this* component's database inside it takes a
`QuarkusTransaction.requiringNew()` of its own (two non-XA resources in one transaction is a thing
Narayana refuses), which is what `BuildTips` does. And `announce` returns as soon as the event is
queued, which it must: the handler is holding that transaction open while it runs.

**qits-ci's direct POST is still live and is meant to be retired** once the subscriber is proven on
a real platform (the superproject's `event-delivery-guarantees-plan.md`, work package 6). Until then
both doors deliver every green build, which is two deployments of one commit — the same thing two
POSTs always were, and the cutover absorbs it.

### The cause rides the seam, because the scope cannot (2026-08-10)

**`announce` takes a fifth value now, `causationId`, and the domain modules hold the eventstream jar
for the causation persistence trio.** `CausedRow`, `CausationStamp` and `@Uncaused` are three
jakarta-persistence-shaped types with no publish, no subscribe and no wire in them, so the module
boundary narrows from "the bus lives in `service/`" to **"the bus's SEAMS live in `service/`"**: no
listener, no publisher, no `EventFrame`, no `QitsEventBus` and — deliberately — **no
`CausationScope`** in `environments/` or `deployments/`.

The scope is what forced the parameter. `CausationScope` is a plain ThreadLocal, and this whole
component runs a build-succeeded event on `pd-deploy-worker`: the door's scope stands on the calling
thread and is gone the instant the lambda runs elsewhere. Left to the `CausationStamp` listener,
every row this component writes would record null — measured in qits-ci on the same day, a full
trigger id beside an empty causation column. So each door reads the answer where it exists and
states it:

- `bus/PdBuildSuccessfulSubscriber` passes `frame.id()`, parsed leniently — an id that is not a UUID
  costs the trace edge and nothing else. **Causation must never be able to refuse a green build.**
- `api/PdEventController` passes `CausationScope.current()`, which `CausationServerFilter` restored
  from the caller's `X-Qits-Causation-Id`. Null is a hand-made bootstrap POST: a rootless deployment,
  which is a real answer rather than a gap.

`ServiceCatalog.upsert` has the same pair for the same reason — `upsert(Upsert)` for the REST door,
where the stamp works because nothing hops, and `upsert(Upsert, UUID)` that derived registration
calls from the worker. **A new writer on a background thread states its cause as data or it records
none**; `bus/PdCausationTest` drives `onFrame` from a scopeless thread precisely so a green
assertion can only mean the explicit set happened.

`ArchRulesTest` (qits-arch-rules, test scope) makes the decision mandatory: a new `@Entity` that
neither implements `CausedRow` nor declares `@Uncaused` fails the build naming the class. It lives
in `service/` because that is the only classpath carrying every entity of the component — both
domain jars and anything this module adds. The domain modules pay no test cost for the jar: neither
has a `@QuarkusTest`, so the eventstream persistence unit never boots there and only `service/`'s
`EmbeddedPgConfigSource` owes it a database.

### The other direction: what a deployment announces (2026-08-12)

**This component publishes now.** It consumed the bus for a release and produced nothing, so a
chain in the event log ended at `BuildSuccessful` — the container a commit ended up in was reachable
only by asking this component's API. Four events close it, one per lifecycle point, all in
`deployments-events/` and all published through `DeployAnnouncer` (`deployments/control`) by
`bus/DeployEventAnnouncer`:

| event | published from | timestamp |
| --- | --- | --- |
| `DeploymentQueued` | `DeployService.queue`, one per created row | the row's `created_at` |
| `DeploymentStarted` | after the `QUEUED`→`STARTING` transaction | taken at the transition — there is no `started_at` column |
| `DeploymentActive` | after the cutover bookkeeping, last thing in `execute` | the row's `finished_at` |
| `DeploymentFailed` | the single `finish` funnel, when the status is not `ACTIVE` | the row's `finished_at` |

Five things about it, each easy to undo by accident:

- **Every announcement happens AFTER the transaction that made it true**, so a consumer that reads
  the deployment back finds what the event said. `DeploymentActive` is deliberately the last
  statement in `execute`, after the old containers are reaped: an unreachable qits-events must delay
  nothing the deployment still has to do.
- **Announcing can never change a deployment's outcome.** `DeployService` wraps every call in a
  try/catch with a WARN, and the port says an implementation must not throw. Zero implementations is
  a supported configuration — `Instance<DeployAnnouncer>`, so a build without the bus deploys
  exactly as before.
- **The cause is data, and it does not need parsing here.** `PdDeployment.causationId` is a `uuid`
  column, set explicitly at queue time (see "The cause rides the seam"), and the announcer hands it
  to `publish(event, parent)`. qits-ci needs a defensive parse because its trigger id is a
  `varchar`; the leniency here already happened one layer up, in the subscriber.
- **`@ActivateRequestContext` is on every announcer method**, for the reason `ScmEventAnnouncer`
  carries it: `pd-deploy-worker` is a bare daemon thread with no request context, and the outbox
  needs one to open its transaction in. A `@QuarkusTest` driving the REST door has a context already
  and would not catch its absence — `PdDeployPublishTest` drives the worker.
- **`DeploymentObserver`'s later corrections announce nothing**, nor does the startup sweep's
  adoption. They restate an outcome minutes or hours later, and a consumer would first need to know
  that the second statement supersedes the first. That is a second design and it is not this one.
  So is `recordRejection`, which writes a `FAILED` row outside the `finish` funnel.

The vocabulary jar is `qits-platform-deployments-events`. It depends on `qits-eventstream` and
nothing else — see the partition above for why it is a module.


## Adding a dependency on another context

Don't. This component depends on three published qits jars — `qits-auth-core`, `qits-eventstream`
and `qits-arch-rules` (test scope) — and all three are **platform libraries rather than contexts**:
shared machinery, no domain, no entity of anyone else's. It has no dependency on another *context*
and should not grow one. Things arrive as an HTTP payload on the intake, as an event on the bus, as a URL in config, or
not at all. Never add a JPA relation to another context's entity.

**The bus does not change that, and the subscriber is written to keep it true.** qits-ci's
`BuildSuccessful` reaches this component as four strings decoded from a payload, against a signature
spelled as the literal `"BuildSuccessful"` — there is no dependency on `qits-ci-events` and there
must not be one. The cost is that a rename over there is silent here, which is the cost the intake
path already carries.

The rule reaches **inside this schema** too: `pd_deployment` names its service and its tier as plain
`String` columns with **no FK**, even though the topology is two tables away. Deployment history
outlives the rows that described it, and the rollback pins read off it must keep answering whatever
the catalogue says today.

## Schema changes

`environments/src/main/resources/db/platformdeployments/migration/`, hand-written, its own lineage on
its own datasource — keep appending, never edit an applied migration. It lives in `environments/`
because the component is one database; the module split is code, not storage.

**`V2__causation.sql` is that rule being followed, and it is also what "one lineage" looks like in
practice**: three `causation_id uuid` columns across two modules' tables in one file, because
`pd_deployment` belongs to `deployments/` and its `create table` is already here. Nullable, no
backfill, no index, and never a foreign key — the event it names lives in qits-events' store. The
per-entity decisions, each argued in the entity's own javadoc and enforced by `ArchRulesTest`:

| entity | decision | where the cause comes from |
| --- | --- | --- |
| `PdDeployment` | `CausedRow` | set explicitly in `DeployService.queue`/`recordRejection` — the whole feature, and the worker hop is why it is not stamped |
| `PdEnvironment` | `CausedRow` | the stamp, from the REST filter's restored scope; a tier is created on the request thread with no hop |
| `PdService` | `CausedRow` | explicit on the derived path, stamped on the operator's `PUT`. Created once and updated in place, which is what insert-only stamping is for |
| `PdServiceLink` | `@Uncaused` | none. Every upsert deletes and re-inserts the row, so the column would record the last rewrite rather than a cause |
| `PdResource` | `@Uncaused` | none. A converging registry entry rather than a record of an occurrence, and its other writer is boot self-registration with no event behind it |

**The store is PostgreSQL, and the lineage restarted at V1 to say so.** The H2 lineage (V1 + V2) was
deleted rather than continued, and that was a decision with one precondition: the migration onto
postgres is an **unwrap and a re-bootstrap**, so no database anywhere is on the H2 lineage and no
`V3__move_to_postgres.sql` had a reader. What the fresh V1 is, is the two H2 migrations translated —
identity columns instead of `auto_increment`, `text` instead of `clob`, V2's `platform` flag folded
into the table it belongs to with **no backfill**, since every database reaching it is empty and the
bootstrap creates the tier with the flag already set. Two parity notes are written into its header
because postgres would now permit what H2 could not, and the answers are still the code's: no check
constraint on any enum column, and no partial unique index on `pd_environment.platform`. **A second
clean start is not a precedent** — it cost a re-bootstrap, and the ordinary rule (append, never edit)
is back from V1 onward.

The suites run every migration against an **empty** schema, so a backfill is untested by them.
`deployments/src/test/.../PdSchemaTest` is the shape to copy: plain JUnit, a real postgres from
`EmbeddedPg`, Flyway, then the claims. A migration that backfills needs a test that migrates to the
version before, writes the rows the old code wrote, and migrates the rest of the way —
`Flyway.configure().target("<version>")` is how it stops halfway. (V2's backfill test was that, and
it went with V2.)

Write inserts in those tests with **named columns**. A positional one makes every later migration a
change to a test that had nothing to do with it — the H2 lineage's V2 demonstrated it by adding one
column and breaking every positional insert in the suite.

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
Shared auth comes from the platform Maven repository as `qits-auth-core`, and the event bus client
as `qits-eventstream` — ordinary Maven dependencies, never gitlinks.

**Both are version-pinned by a property in the root pom, one line each**, because a release train
step rewrites exactly that element: `.config/qits/ci-event-upstream-auth-core.yml` and
`.config/qits/ci-event-upstream-eventstream.yml` each `sed` one `<…version>` and force-push the
result onto a maintenance branch. A second spelling of either version anywhere would be left
behind. A bump lands on a branch and not on main on purpose: a library release is not a decision to
redeploy the deployer.

**`qits-eventstream` sits in all four modules now**, and only `service/` uses it for the bus: the
domain modules take it for the causation persistence trio and `deployments-events` for `QitsEvent`
itself, which is a narrowing of the boundary rather than a hole in it — see "The cause rides the
seam". `deployments/` additionally holds `qits-platform-deployments-events`, because
`DeployAnnouncer` is spelled in those records; they are plain data with no publish and no transport
in them, so that is the same narrowing rather than the bus reaching into a domain module. `qits-arch-rules` is a third published
jar, test scope, in `service/` only; it is version-pinned by a property of its own and no release
train step rewrites it.

**`qits-eventstream` brings two extensions new to this deployable** — `quarkus-scheduler` (the
outbox and catch-up sweeps) and `quarkus-websockets-next` (the stream client, which registers no
route, so `quarkus.quinoa.ignored-path-prefixes` is unchanged) — and one **mandatory deployment
resource**, below.

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
  carries only the port, the persistence-unit wiring and `quarkus.devservices.enabled=false`.
- **No dev services and no containers, ever.** A dev service is a container start, and the first rule
  here is that a clone tests green with no docker. `quarkus-oidc` in particular launches a real
  Keycloak the moment a profile leaves `quarkus.oidc.auth-server-url` unset — measured on the
  ancestors, not feared. The store being postgres does not change that answer: `testdb/EmbeddedPg`
  starts **zonky's** postgres — real binaries resolved as Maven artifacts, spawned as a child
  process — and `testdb/EmbeddedPgConfigSource` hands its url, username and password to every
  `@QuarkusTest` at an ordinal above `application.properties`, because the port is chosen at run
  time and cannot be written down. Testcontainers is not on this classpath and must not arrive.
  `EmbeddedPg` is **copied** into `deployments/` rather than shared: a test-jar dependency between
  two modules that have none is the higher price.
- **That config source hands out SIX values, not three, and the second three are easy to think
  unnecessary.** The bus is dark in `%test`, and **dark is not absent**: `qits.eventstream.enabled=false`
  stops dialling, sweeping and claiming, not the datasource — Quarkus opens the connection and runs
  Flyway at boot regardless. So the `eventstream` store gets a database of its own on the same
  embedded instance, or the suite does not start. Only `clean-at-start` is written in the test
  properties file; the locations and the persistence-unit wiring ship in the jar and a copy here
  would drift.
- **The bus darkness itself is asserted**, in `bus/PdEventstreamDarknessTest`, for the reason the
  OTel keys are: the way a missing switch fails is not a failure but a suite that redials an
  unresolvable host every thirty seconds and reads as slow. It also asserts the subscriber survives
  ArC's unused-bean removal — nothing injects it by name, and a removed listener consumes nothing
  and says nothing to admit it.
- **The bus tests drive `onFrame` directly, not a stub qits-events.** What belongs here is this
  component's half — the decode, the tip check, the call into the seam. The funnel, the claim ledger
  and the catch-up sweep are the library's and are proved in its own repository; a stub here would
  re-prove them and prove nothing about a deployment.
- **The PUBLISHING test aims the bus at a closed port**, which is the same stance from the other
  side: `PdDeployPublishTest` turns the bus on in its own `@TestProfile` (`qits.events.url` is
  `http://localhost:1`, the scheduler and the startup catch-up are off), so every publish lands as
  exactly one `outbox_event` row with the canonical payload it would have been sent with. A row IS
  the publish from this side of the bus. It reads them through the `eventstream` persistence unit's
  own `EntityManager`, never a Panache static — `OutboxEvent` comes from a jar this application did
  not compile, and the static throws naming the wrong problem.
- **Machine-token tests mint their own tokens.** `MachineTokens` signs RS256 with the key pair in
  `service/src/test/resources/machine-token-*.pem`, and `MachineGuardEnforcedProfile` hands
  quarkus-oidc the public half, so the enforced path is exercised end to end with no
  qits-platform-idp to reach. Those PEMs are **test fixtures, not credentials**.
- `FakeDeploymentDriver` and `FakeSpecSource` are `@Mock` and application-scoped, so they are shared
  across tests: reset both in `@BeforeEach`, use distinct **environment names, repository ids and
  service names** per test, and read their state through their **methods** — the injected reference
  is a CDI client proxy, and a field read on a proxy sees the proxy's fields, not the bean's. The
  suite shares one embedded database across classes (Flyway cleans at start, not between tests), and
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
  the shipped datasource *expressions* — **both** of them now, since it hands the launched process
  `QITS_RESOURCE_DB_*` and `QITS_RESOURCE_EVENTSTREAM_*`, the generic contract a deployment
  supplies, rather than restating the datasource keys, so the jars' own `${…}` indirection is what
  is under test and a packaged artifact missing either triple fails here rather than in a
  deployment — Flyway's migration surviving as
  a resource, and — the claim the ancestors could not make —
  both domains round-tripping in one process against one database. Its embedded postgres reaches
  the profile through a **system property**, because a `QuarkusTestProfile` is instantiated in two
  classloaders and a static field is not shared between them. It points
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
target and names no branch at all: a push to the branch an environment listens to is a deployment.
A green run announces this component to itself and it takes the self-update handoff; the
`/platform-deployments` surface blips mid-cutover, and a successor that misses its health gate
leaves the predecessor serving.

**`environment/<name>` is the only deploy ref, on both planes.** A green build deploys wherever an
*environment* listens to its branch, and what comes out on the platform plane is still
platform-shaped (no environment id, one instance, no links). `platform/main` and
`SpecSource.DEFAULT_PLATFORM_BRANCH` are gone, and so is the spec's `branch:` key. `main` stays the
integration trunk: a push to it builds and ships nothing.

**Which environment is the platform one is a column now** — `pd_environment.platform`, true on
exactly one row. `DeployService.registerPlatform` asks whether the *platform* environment is
among the tiers listening to the built branch; the environment arm still fans out over all of them.
That closes what used to be recorded here as the thing gating environment #2: under the old gate any
tier's branch rolled the one platform instance, which was never a fan-out — it was several tiers
taking turns overwriting one container. A second environment is an ordinary thing to create.

The flag is a designation, not a link. A platform service still belongs to no tier, still keeps the
bare wire alias, and is still reachable from every environment; what the column decides is which
branch may roll it. **`EnvironmentService.designate` moves it** — clearing the old holder and
setting the new one in one transaction — because that is where the invariant belongs. Postgres does
have a partial unique index and V1 deliberately declines it: an index would also forbid the
intermediate state of the very two statements that move the flag. Clearing the flag outright is a 409, and so
is deleting the environment holding it; both would leave the plane running with no branch able to
replace it. `PdEnvironmentApiTest` holds those claims, and
`PdDeploymentFlowTest.onlyThePlatformEnvironmentsBranchRollsThePlatformPlane` holds the gate.

**`deploy_branches:` is retired: accepted, validated, acted on by nobody.** Its one reader was
qits-workspaces' release flow, which pushed a release onto *every* branch the list named — a
fan-out rather than a ladder, and with three tiers it would have shipped into all three at once. A
release lands on one entry branch from that component's own configuration now, and no repository
states it. The parser still tolerates the key, and the reason is sharper than the `singleton`
alias's: **a spec is fetched at the BUILT sha**, so a rollback pin or a redeploy of an older commit
still presents a file carrying it, and an unknown key fails a deployment. Do not write it into a new
file; do not remove the tolerance.
