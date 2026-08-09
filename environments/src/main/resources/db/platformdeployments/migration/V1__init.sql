-- The whole schema of qits-platform-deployments, in one migration.
--
-- ONE V1 AND NO INHERITED LINEAGE, FOR THE SECOND TIME. The first clean start was the merge-back of
-- qits-cd and qits-serviceregistry; this one is the move off H2. The component's store is
-- PostgreSQL now — it is adopter #1 of its own `resources:` mechanism — and the migration onto it
-- is an unwrap and a re-bootstrap rather than a data migration, so the H2 lineage has no remaining
-- consumer anywhere and is not a prefix of this file. Its V1 and V2 are history in this
-- repository's log. The shape below is where those two arrived, translated, plus the resource
-- registry the mechanism needs. FROM HERE ON THE ORDINARY RULE IS BACK: keep appending, never edit
-- an applied migration.
--
-- Five tables, two domains, one database. The topology (pd_environment, pd_service,
-- pd_service_link) is the `environments` module's; the execution history (pd_deployment) and the
-- resource registry (pd_resource) are the `deployments` module's. They are one physical database
-- because they are one component, and the modules are a partition of the code rather than of the
-- storage.
--
-- No column here holds another context's key. A repository, an epic and a ci run are named by plain
-- strings — the `ci_run.repo_id` stance, applied at every boundary this component has. The FKs
-- below are inside this component's own database, which is what the cross-context rule permits.
--
-- WHAT THE TRANSLATION DELIBERATELY DID NOT ADD, because both absences were decisions and postgres
-- would now permit either:
--   * NO CHECK CONSTRAINT ON ANY ENUM COLUMN. H2 2.4.240 tied a checked IN-set to the session that
--     compiled it and a clean bootstrap of qits-cd saw a later insert fail with 23514 on a
--     perfectly valid value. Postgres would not, but the answer that replaced it is the better one
--     and is still true: PdDeploymentStatus and PdDeploymentTarget own validity at every Java write
--     path, and a database that also enumerated them would be a second list to keep in step.
--   * NO PARTIAL UNIQUE INDEX ON pd_environment.platform. `unique (platform) where platform` is
--     available here and is deliberately not taken: `EnvironmentService.designate` moves the flag
--     by clearing the old holder and setting the new one inside ONE transaction, so there is never
--     a moment with two — and an index would additionally forbid the intermediate state of that
--     very statement order. The invariant lives where the move does.

-- --- the topology --------------------------------------------------------------------------------

-- `platform` says which tier the platform plane deploys from: `DeployService.registerPlatform` ships
-- only when THIS environment listens to the built branch, so without it any tier's branch would roll
-- the single platform instance — several tiers taking turns overwriting one container rather than a
-- fan-out. It is a designation, not a link: a platform service still belongs to no tier.
--
-- It carries no backfill and needs none. Every database reaching this file is empty, and the
-- bootstrap's environment phase creates the tier with the flag already set.
create table pd_environment (
    id varchar(255) not null primary key,
    name varchar(64) not null,
    branch varchar(255) not null,
    network varchar(255) not null,
    platform boolean default false not null,
    created_at timestamp(6) with time zone not null
);

alter table pd_environment add constraint uq_pd_environment_name unique (name);
-- Resolution asks "which tiers listen to this branch" on every green build, and the merge turned
-- that from an HTTP round trip plus a client-side filter into this index.
create index idx_pd_environment_branch on pd_environment (branch);

-- deployment_target is not null and carries no default: every writer states which plane a service
-- is on. branch is nullable and belongs to platform services only — an environment service takes
-- its branch from each environment it is linked into, and a second copy here would drift from it.
--
-- ONE ROW PER SERVICE, not one per (service, environment). That is what retires the ancestors'
-- composite `unique (environment_id, name)` and the partial-index problem it carried: with a
-- nullable environment column, null rows are distinct to a unique index, so a platform-plane row
-- was unconstrained by it and its uniqueness had to be enforced inside a service transaction
-- instead. A plain unique name needs no such reasoning.
create table pd_service (
    id varchar(255) not null primary key,
    name varchar(64) not null,
    deployment_target varchar(32) not null,
    branch varchar(255),
    available_on_env boolean not null,
    health_path varchar(255),
    created_at timestamp(6) with time zone not null
);

alter table pd_service add constraint uq_pd_service_name unique (name);
create index idx_pd_service_target on pd_service (deployment_target);

-- The topology, in one table. A platform service has NO row here: "present everywhere" is spelled
-- as the absence of a link, which is what makes an environment created tomorrow pick it up with no
-- row written anywhere. The unique constraint makes a link idempotent — the upsert replaces the
-- whole set anyway, and this is the belt.
create table pd_service_link (
    id varchar(255) not null primary key,
    service_id varchar(255) not null,
    environment_id varchar(255) not null,
    created_at timestamp(6) with time zone not null
);

alter table pd_service_link add constraint fk_pd_service_link_service
    foreign key (service_id) references pd_service;
alter table pd_service_link add constraint fk_pd_service_link_environment
    foreign key (environment_id) references pd_environment;
alter table pd_service_link add constraint uq_pd_service_link
    unique (service_id, environment_id);
create index idx_pd_service_link_environment on pd_service_link (environment_id);

-- --- the execution history -------------------------------------------------------------------

-- One attempt to put one commit of one application live.
--
-- application_name and environment_id are PLAIN COLUMNS WITH NO FK INTO THE TOPOLOGY, even though
-- the topology is two tables away in this same database. That is deliberate and it is what the
-- ancestors' V5 bought: deployment history outlives the rows that described it. A service deleted
-- from the catalogue, or a tier torn down, must not take its history with it by cascade, and the
-- rollback pins read off these rows must keep answering whatever the topology says today —
-- qits-artifacts' image GC is fail-closed on them, so an unanswerable pin query stops garbage
-- collection across the platform.
--
-- environment_id is nullable and THE NULL IS THE STATEMENT: a platform deployment belongs to no
-- tier. Nulls are distinct to every comparison, which is why the code that matches "the previous
-- deployment of the same (application, tier)" tests `environment_id is null` explicitly rather than
-- writing `= ?` and quietly matching nothing — the startup sweep's adoption of a self-update row
-- hangs on that pair, and getting it wrong means a self-update comes back having failed its own
-- deployment.
create table pd_deployment (
    id varchar(255) not null primary key,
    application_name varchar(64) not null,
    environment_id varchar(255),
    commit_sha varchar(64) not null,
    run_id varchar(255),
    status varchar(32) not null,
    container_name varchar(255),
    detail text,
    created_at timestamp(6) with time zone not null,
    finished_at timestamp(6) with time zone,
    -- The listing tiebreak, assigned by the database and never written by the application.
    --
    -- created_at is not unique: two rows recorded in the same tick — which is what two deployments
    -- queued by one build-succeeded event are — tied, and the secondary sort was the random-UUID
    -- id, so a listing swapped them arbitrarily between calls. It was seen failing as a flaky test,
    -- and it is worse than that: a client reads "the first row per application is the current one"
    -- straight off this order.
    seq bigint generated always as identity
);

create index idx_pd_deployment_application_name on pd_deployment (application_name);
create index idx_pd_deployment_environment_id on pd_deployment (environment_id);
create index idx_pd_deployment_created_at on pd_deployment (created_at);

-- --- the resource registry ---------------------------------------------------------------------

-- What this component has provisioned on the platform's postgres for a deployed application, and
-- the credential it injects for it. One row per (application, tier, resource name).
--
-- THIS DATABASE IS CREDENTIAL-BEARING FROM HERE ON. `password` is a value this component generated
-- and is the single authority for it: no file carries it, the bootstrap does not record it, and a
-- lost row is a rotated password rather than a lost one (the reconcile arm). Treat the database
-- with the sensitivity of the qits-deployments-config volume, which already holds the push token
-- and the idp secrets.
--
-- environment_name is a PLAIN STRING AND NOT AN FK, the pd_deployment stance again: a resource
-- outlives the tier row that described it, and never auto-dropping means the registry has to keep
-- answering after a teardown. NULL IS THE PLATFORM PLANE, exactly as it is on pd_deployment.
--
-- `unique nulls not distinct` is what makes the platform plane's rows unique at all — the default
-- treats every null environment as a distinct value, so the platform plane would accept one row per
-- deployment. It needs POSTGRES >= 15 (production and the suite's embedded binaries are 18.4), and
-- it is the reason this constraint could not exist before the move off H2.
--
-- role_name and database_name are two columns for one identity today (the role IS the database
-- name), and stay two because the day a resource type separates them the registry should not need a
-- migration to say so.
create table pd_resource (
    id                  varchar(255) not null primary key,
    application_name    varchar(64)  not null,
    environment_name    varchar(64),
    resource_name       varchar(64)  not null,
    resource_type       varchar(32)  not null,
    database_name       varchar(64)  not null,
    role_name           varchar(64)  not null,
    password            varchar(128) not null,
    created_at          timestamp(6) with time zone not null,
    last_provisioned_at timestamp(6) with time zone,
    constraint uq_pd_resource unique nulls not distinct (application_name, environment_name, resource_name)
);

-- "Does another application already claim this database on this instance" — the cross-check that
-- turns two repositories naming one database into a refused deployment rather than a silent
-- takeover.
create index idx_pd_resource_database_name on pd_resource (database_name);
