# qits-platform-deployments

The platform component that owns **what runs where**. Both halves of it: the *topology* — which
environments exist, which services are linked into each of them, what shape each service has — and
the *execution* — pulling an image, starting a container, health-gating it, cutting over, and
recording what happened.

It is the merge-back of `qits-cd` and `qits-serviceregistry`, re-partitioned. Both are superseded.

## Why one component

The split looked clean and was not. The executor held the docker socket and, on every green build,
fanned out over **every** environment whose branch matched — cross-environment behaviour, wearing an
environment citizen's label. The registry that owned the topology could act on none of it and was
the passive half. So the two most closely coupled operations on the platform — *register this
service where this build belongs* and *deploy it there* — became an HTTP round trip between two
databases, with a client, a bearer to mint, a stub server in the test suite, and a recorded-FAILED
posture for the case where one half could not reach the other.

None of that was buying isolation. It was paying for a boundary drawn in the wrong place.

One component, one database, one docker socket. Registration writes rows in the same transaction as
everything else; resolution is a repository query with an index behind it. The modules below are a
partition of the **code**, which is where the partition was always useful.

## The three modules

    environments/   the topology domain — environments, services, links, and the rules over them.
                    Owns the datasource and the single Flyway lineage. Knows nothing about
                    containers.
    deployments/    the execution domain — deployment rows, the deploy orchestration, the rollback
                    pins, the resource registry, the strict spec parser, and the three SEAMS it
                    cannot implement itself.
    service/        the adapters — JAX-RS for both domains, the docker driver, the git-host spec
                    reader, the postgres provisioner, the build-succeeded intake, and the web
                    client.

`deployments` depends on `environments` and never the reverse: execution reads and writes the
topology, the topology knows nothing about execution. Neither domain module carries JAX-RS, an HTTP
client or a process shell-out.

## The model: tiers, planes, and derived rows

An **environment is a tier** — today just prod. It is created deliberately, over REST, with the
conventions filled in: branch `environment/<name>`, bundle network `qits-env-<name>`. `main` stays
the integration trunk, and a release reaches an environment by fast-forwarding `environment/<name>`
onto it — so a push to `main` alone builds and deploys nothing.

**`environment/<name>` is the only deploy ref there is.** Both planes ask the same question of a
green build — does an environment listen to this branch — and the answer decides whether anything
ships. The platform plane had a second convention (`platform/main`) and no longer does: what a
platform service gets from an environment's branch is still one platform-shaped instance.

A **service** has one row for the whole platform, not one per tier, and says where it runs by
carrying a **link** to each environment. Two planes:

- **environment** — one instance per tier, on that tier's networks.
- **platform** — one instance for the whole platform, on every environment's networks. It carries
  **no links at all**, and that absence is the mechanism: "present everywhere" is spelled as
  "linked nowhere in particular", which is what makes an environment created tomorrow pick up
  qits-platform-idp without anyone editing a row. It is also what makes it immune to an environment
  teardown — no environment label, so nothing reaps it with a tier it merely serves.

The word used to be `singleton`. It named a cardinality where the thing being said is which plane a
service lives on — and it made this very component, cross-environment from its first commit, look
like an environment citizen. Everything says `platform` now: the enum, the container name, the
labels, the network. `deployment_target: singleton` is still accepted in a repository's spec as an
alias, so a repository that has not been edited yet keeps deploying.

**Nothing declares a service.** Rows are **derived**: a green build sends this component to the
repository's `.config/qits/deployments.yml` at that sha, and the service row is created or brought
up to date from what it found there.

```yaml
deployment_target: environment       # default when the key or the file is absent | platform
available_on_env: false              # default; true = public node (bundle + hub joins)
deploy_branches: environment/prod    # comma-separated refs; read here, used by the release flow
health_path: /q/health/ready         # default: /<name without the qits- prefix>/q/health/ready
health_cmd: pg_isready -U postgres   # instead of health_path; runs inside the container
resources: postgresql:db             # a database of its own, injected as QITS_RESOURCE_DB_*
```

`deploy_branches` is parsed, validated and **not acted on**: where a build deploys is the
environment rows' answer, not the file's. It is accepted because qits-workspaces' release flow reads
the same file for its promotion targets, and this parser fails a deployment on an unknown key.

`health_cmd` and `health_path` are **alternatives, and a file setting both fails**. The path names a
URL a `curl` inside the container fetches; the command replaces that mechanism whole. It is for the
deployable images — a plain postgres, the first of them — which have neither curl nor anything on
8080 and so can pass no path-shaped gate. The value reaches `--health-cmd` verbatim, one argv
element, run by the container's own `/bin/sh -c`: spaces and `||` need no quoting. It gets no
charset allowlist, because it grants the repository nothing it does not already have over its own
container — only a length cap and one line.

`resources:` is what a repository asks to have **provisioned before its container starts**. The
grammar is flat, because this file has no YAML sequences: `postgresql:<name>[:<database>]`,
comma-separated. Omit the database and it defaults to `qits_` plus the application name without its
`qits-` prefix. Before the cutover, this component idempotently creates the login role and the
database on its tier's postgres and then starts the container with

```
QITS_RESOURCE_<NAME>_URL       jdbc:postgresql://<tier>-qits-oci-postgresql:5432/<database>
QITS_RESOURCE_<NAME>_USERNAME  <database>        # the role IS the database, one login per database
QITS_RESOURCE_<NAME>_PASSWORD  generated here, stored in pd_resource, never in a file
```

The contract is **generic on purpose**: an application maps those three in its own shipped config
defaults, so this component names no framework and no datasource key. It is idempotent because it
runs on every deployment — a redeployment changes nothing, a reset postgres volume brings the role
back with the recorded password, and a reset registry rotates a password nothing knew any more.
**Never a `DROP`.** This component is adopter #1: its own store is a database provisioned this way.

A repository with **no file** gets every default and behaves exactly as it did before the file
existed. A file that cannot be read or parsed **fails the deployment** with the cause on the row —
never a guess, because a guessed topology is a container on the wrong networks under the wrong name.

## The deployment flow

    green build ──▶ POST /platform-deployments/api/events/build-succeeded
                        (runId, repoId, branch, commitSha)
                          │
                          ▼   one single-threaded worker; the intake returns 202 immediately
                    read the spec at that sha  ─────────────▶  git host (the ONE outbound call)
                          │
                    register: upsert the service row, link it into every tier whose branch matches
                          │
                          ▼   one recorded deployment per place it addresses
                    pull ▶ stop the predecessor ▶ run ▶ join networks ▶ health gate ▶ cut over

**The cutover invariant.** Whatever holds the application's alias is *stopped* — not removed —
before the fresh container starts, and *removed* only after the new one passed its health gate. A
failed deployment (image missing, docker refused, gate expired, a network join refused) removes the
fresh container and **restarts** what was stopped, so the previous deployment stays `ACTIVE` and
serving. Stop-before-start is what makes stateful applications deployable at all: one binder per
published port, one process per single-writer store. The pull happens before the stop, so replacing the OCI
registry's own application does not depend on it being up mid-cutover.

The predecessor is whatever **holds the alias** on any network the fresh container is about to be
on, including the legacy one — so a container the bootstrap seeded outside any deployer, or one the
retired qits-cd started, is adopted rather than run beside. The search asks about the wire alias
*and* the bare application name, because every container started before the tier qualifier existed
holds only the latter. Every removal is a decision recorded on a deployment row.

**A status is written by the deployment that earned it, and then observed.** Every thirty seconds
(`qits.platform.deployments.observe-interval-seconds`, `0` to switch it off) the **latest** row of
each (application, tier) is read back against the container it names, on the same worker the
deployments run on. A row that says `FAILED` about a container that is running and healthy becomes
`ACTIVE` — with the original failure text kept under the recovery stamp — and a row that says
`ACTIVE` about a container that is absent or terminally exited on **two consecutive** passes becomes
`FAILED`. Restarting and running-but-unhealthy are neither: they are the health gate's own patience,
and a container coming back from the postgres-alias boot race must not be declared dead on the way.
Rows that are not the latest for their place are history and stay untouched, as do `QUEUED`,
`STARTING` and `DECOMMISSIONED`. **The observation writes rows only** — it starts, stops and removes
nothing.

It exists because a status used to be final: one deployment cut its own postgres over, went healthy,
lost every connection it held mid-bookkeeping, and left a row saying `FAILED` beside a container that
served for hours. The connections are retried now; the row needed reading back too.

## Networks are hub and spoke, and docker is the bookkeeping

- an environment application runs on `qits-env-<env>-<app>` — only its own containers are there;
- a **public node** (`available_on_env: true`, today just qits-gateway) additionally joins its
  environment's bundle network and *every* per-application network of that environment. That is the
  hub: cross-application traffic is meant to flow app → gateway → target app;
- a **platform** service runs on `qits-platform` and joins every per-application network of every
  environment, which is what makes it locally reachable everywhere without a gateway route;
- `qits.platform.deployments.legacy-network` (default `qits-net`) is the transition membership
  every container also joins, while the platform still holds direct cross-application URLs. **Emptying it is the
  enforcement flip** — a later phase, after the last direct URL has moved to a gateway route.

**The wire alias — the address peers dial — is `<env>-<app>` for an environment application and the
bare `<app>` for a platform service.** The qualifier is what lets two tiers hold one application's
address on the legacy network, which they all share, without either resolving as the other; a
platform service is one instance for the whole platform, so there is nothing to qualify it against
and its repository name carries the plane already (`qits-platform-idp`). Container names follow the
same shape: `qits-pd-<env>-<app>-<id8>`, and `qits-pd-<app>-<id8>` on the platform plane.

`docker run` takes one network, so everything else is a `network connect --alias <wire alias>` after
the start — the same alias, so the address resolves on every network the container is on and not
just the first — and the set is recomputed from docker on every deployment rather than remembered,
which makes it the self-heal too. **No membership is ever stored in the database.** It is written as
labels under one namespace — `qits.platform.deployments.` + `environment`, `application`,
`deployment`, `target`, `available-on-env`, `app-name`; networks carry
`qits.platform.deployments.network=bundle|application|platform` — and read back with
`--filter label=`. One record of the truth, and it is the runtime's. Containers carrying an earlier
spelling (`qits.cd.*`, `qits.pd.*`) count as unlabelled: adoptable, never protected.

## Self-update is a handoff

This component deploys itself, and it cannot stop its own container and then finish the cutover. So
the roles split three ways: this instance starts the successor and launches a **detached referee**;
the referee stops this container — freeing the published port and the socket the successor is
retrying on — awaits the successor's health gate, and removes whichever side lost; the surviving instance's startup sweep
records the outcome. A successor that misses its gate leaves the predecessor serving.

## What it answers

| Route | Who asks |
| --- | --- |
| `POST/GET/PATCH/DELETE /environments` | the bootstrap, a person through the client |
| `GET /environments/{id}/links` | a reconciliation: this tier's services, plus every platform service |
| `PUT/GET/DELETE /services/{name}` | derived registration (in-process); an operator, for the deliberate acts |
| `GET /applications` | the client — one entry per (service, tier), both planes flat |
| `GET /deployments?environmentId=` | the client — one tier's history, newest first |
| `GET /pins` | qits-platform-artifacts' OCI garbage collector, fail-closed |
| `POST /events/build-succeeded` | qits-ci, fire-and-forget |

All under `/platform-deployments/api`. The client is served at `/platform-deployments`, health at
`/platform-deployments/q/health/ready` — which is also what this component's own health-path
convention derives for its own name.

**The pins are read off deployment rows alone.** qits-platform-artifacts deletes an image tag only
when no pin names it, and deletes nothing when it cannot get an answer, so the keep-set must not
depend on anything the topology says today. Per application name, across every tier: the sha it is
serving, and the sha a rollback would put back.

## Trust boundaries

- **The docker socket is the boundary.** Mounting it hands this container control of the host's
  daemon, which is root-equivalent. It is an explicit deployment act, never baked into the image,
  and the containers this component *starts* never get it.
- **It executes nothing.** The docker vocabulary is container lifecycle — `pull`, `run`, `inspect`,
  `logs`, `rm`, `ps`, `network` create/inspect/rm. `exec` is not in it and must not enter it. What a
  deployed container runs is its image's own entrypoint, untouched.
- **Provisioning speaks SQL, not shell.** A `resources:` line is answered with `CREATE ROLE`,
  `CREATE DATABASE`, `REVOKE` and `ALTER … OWNER` over plain JDBC — no `psql`, no `docker exec`, no
  process at all. The postgres superuser credential comes from **deployment config**, the same trust
  domain that already holds the docker socket, and is never written into a row and never put in an
  argv. There is no `DROP` in the vocabulary and none is coming.
- **Argv contributions come from deployment config and from this component itself.**
  `qits.platform.deployments.run-args.<app>` is how a stateful application gets its volume and its
  extra env; the `QITS_RESOURCE_<NAME>_*` triple is generated here and injected here. **Nothing
  arriving over HTTP contributes a credential to a `docker run`** — which is the same sentence as
  before, now that a credential is a thing this component holds: what a repository can NAME is a
  database of its own, and the VALUES injected for it are ones this component generated.
- **Untrusted strings are validated at the boundary.** Names become network names, aliases and image
  path segments (dns-label charset); shas become image tags (hex only); the health path is
  interpolated into a string the *container's* shell runs, so it gets the strictest allowlist and is
  re-checked at the last line before the argv. A `health_cmd` is the one deliberate exception — it
  *is* the shell string, chosen by the repository for its own container — and is bounded to one
  non-blank line rather than a charset.
- **`resources:` gets the health-path treatment, and one checkpoint more.** Both halves are
  repository-authored: the resource name becomes an environment-variable key in a `docker run`, and
  the database name lands in **DDL against a postgres instance the whole platform shares** — where
  there is no bind variable to fall back on. So both are allowlists (`[a-z][a-z0-9-]{0,31}` and a
  mandatory `qits_` prefix, which structurally excludes `postgres`, `template0/1` and every `pg_*`),
  checked at the parser, again immediately before the SQL is assembled, and again at the argv.
- **The `platformdeployments` database is credential-bearing now.** `pd_resource.password` is the
  single authority for every provisioned application's credential — no file carries it, the
  bootstrap does not record it. Treat that database with the sensitivity of the
  `qits-deployments-config` volume, which already holds the push token and the idp secrets. No
  statement containing a password is ever logged, and no failure message names one.
- **Machine writes carry a guard, reads do not.** The build-succeeded intake and the topology writes
  call `MachineAuth.require()` (audience `qits-platform-deployments`); every read stays open,
  because a person drives it through qits-gateway's session and the collector polls it. The gate
  ships **off** — `QITS_AUTH_MACHINE_REQUIRED=true` turns it on, only once the senders are sending.

## Building it

    git clone … && cd qits-platform-deployments
    git submodule update --init          # the web client
    ./mvnw clean verify

A clone of this repo alone builds and tests green — no monorepo, no docker, no credentials. The two
seams that reach outside the process are faked in the suite. `./mvnw test` needs neither node nor
the client; `./mvnw verify` reaches `package`, where Quinoa augments, and needs both.

`AGENTS.md` carries the working conventions.
