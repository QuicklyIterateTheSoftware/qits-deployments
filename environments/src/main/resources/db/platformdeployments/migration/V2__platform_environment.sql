-- Which tier is THE platform environment.
--
-- The platform plane deploys one instance of a service for the whole platform, and until now
-- nothing said where. `DeployService.registerPlatform` asked only whether SOME environment listened
-- to the built branch, so with a second tier either tier's branch would roll the single platform
-- instance. That hazard is what gated environment #2; this column answers it.
--
-- It is also the seam the bootstrap's `--platform-env` writes through, and the one a later "move the
-- platform plane from dev to prod" would PATCH.
--
-- AT MOST ONE ROW IS TRUE, AND IT IS NOT AN INDEX. A partial unique index is the obvious spelling
-- and it is not available here: the production datasource is H2, which has none, and V1 already
-- records the same lesson from the other direction — the ancestors' composite `(environment_id,
-- name)` was unconstrained for null rows because H2 treats them as distinct, "so its uniqueness had
-- to be enforced inside a service transaction instead". Same answer here. `EnvironmentService`
-- designates by MOVING the flag: it clears the old holder and sets the new one in one
-- `requiringNew()` bracket, so there is never a moment with two.
alter table pd_environment add column platform boolean default false not null;

-- The backfill. Every install reaching this migration has exactly one environment — the platform's
-- own, whatever it was named — and it is by definition the platform one, so leaving the column
-- false everywhere would strand the platform plane on a branch nothing matches.
--
-- Written as "the oldest row" rather than "every row" so an install that somehow grew a second tier
-- before this lands still comes out with one holder rather than a constraint the code then has to
-- repair. Oldest, because the platform's own environment is the one the bootstrap created first.
-- An empty table updates nothing.
update pd_environment
   set platform = true
 where id = (select id from pd_environment order by created_at, id limit 1);
