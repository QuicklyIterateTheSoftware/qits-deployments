-- The whole schema of qits-platform-deployments, in one migration.
--
-- ONE V1 AND NO INHERITED LINEAGE. This component is the merge-back of qits-cd and
-- qits-serviceregistry, and the rollout is a clean start rather than a live migration — so the
-- ancestors' V1-V5 and V1 are history in their own repositories, not a prefix of this file. The
-- shape below is where those eight migrations arrived; the steps they took to get there are not
-- re-enacted against an empty database.
--
-- Four tables, two domains, one database. The topology (pd_environment, pd_service,
-- pd_service_link) is the `environments` module's; the execution history (pd_deployment) is the
-- `deployments` module's. They are one physical database because they are one component, and the
-- modules are a partition of the code rather than of the storage.
--
-- No column here holds another context's key. A repository, an epic and a ci run are named by plain
-- strings — the `ci_run.repo_id` stance, applied at every boundary this component has. The FKs
-- below are inside this component's own database, which is what the cross-context rule permits.

-- --- the topology --------------------------------------------------------------------------------

create table pd_environment (
    id varchar(255) not null primary key,
    name varchar(64) not null,
    branch varchar(255) not null,
    network varchar(255) not null,
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
-- nullable environment column, H2 treats null rows as distinct, so a platform-plane row was
-- unconstrained by the index and its uniqueness had to be enforced inside a service transaction
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
-- hangs on that pair, and getting it wrong means a cd-style self-update comes back having failed
-- its own deployment.
--
-- There is no status check constraint, and its absence is a measured decision rather than an
-- oversight: H2 2.4.240 ties a checked IN-set to the session that compiled it, and a clean
-- bootstrap of qits-cd saw a later insert fail with 23514 "Check constraint invalid" on a perfectly
-- valid enum value. PdDeploymentStatus owns validity at every Java write path.
create table pd_deployment (
    id varchar(255) not null primary key,
    application_name varchar(64) not null,
    environment_id varchar(255),
    commit_sha varchar(64) not null,
    run_id varchar(255),
    status varchar(32) not null,
    container_name varchar(255),
    detail clob,
    created_at timestamp(6) with time zone not null,
    finished_at timestamp(6) with time zone,
    -- The listing tiebreak, assigned by the database and never written by the application.
    --
    -- created_at is not unique: two rows recorded in the same tick — which is what two deployments
    -- queued by one build-succeeded event are — tied, and the secondary sort was the random-UUID
    -- id, so a listing swapped them arbitrarily between calls. It was seen failing as a flaky test,
    -- and it is worse than that: a client reads "the first row per application is the current one"
    -- straight off this order.
    seq bigint auto_increment
);

create index idx_pd_deployment_application_name on pd_deployment (application_name);
create index idx_pd_deployment_environment_id on pd_deployment (environment_id);
create index idx_pd_deployment_created_at on pd_deployment (created_at);
