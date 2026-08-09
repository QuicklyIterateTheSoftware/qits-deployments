package eu.wohlben.qits.platform.deployments.deployments.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * What the schema itself promises, against a real PostgreSQL with the real migration — plain JUnit,
 * no Quarkus, because the subject is the SQL.
 *
 * <p><b>Real postgres, not an in-memory stand-in.</b> The store moved off H2 with the resource
 * mechanism, and two of the claims below exist only on the database that ships: identity columns
 * and {@code unique nulls not distinct}. {@link EmbeddedPg} spawns the binaries as a child process,
 * so this still needs no docker.
 *
 * <p>Each test migrates its OWN database, so one test's rows are never another's starting state.
 * The claims:
 *
 * <ul>
 *   <li>the ordering column is the <b>database's</b> and is monotonic, because the deployment
 *       listings read "the current one per application" straight off it and {@code created_at} ties
 *       for the rows one build-succeeded event writes;
 *   <li>a deployment row needs <b>no topology row</b> — no FK, so history outlives the service and
 *       the tier it names, which is what keeps the rollback pins answering;
 *   <li>the startup sweep's predecessor lookup, as SQL: {@code (application_name, environment_id)}
 *       has to match across a <b>null</b> tier. This is the query that decides whether a
 *       self-updating deployer comes back ACTIVE or comes back having failed its own deployment,
 *       and nulls are distinct to {@code =} — so it is written with an explicit null test and
 *       pinned here;
 *   <li>and the same fact from the other side on {@code pd_resource}: its uniqueness is declared
 *       {@code nulls not distinct}, so the platform plane — whose {@code environment_name} is null
 *       — gets one row per resource rather than one per deployment.
 * </ul>
 */
public class PdSchemaTest {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);
  private static final String SHA_C = "c".repeat(40);

  @Test
  public void theOrderingColumnIsTheDatabasesAndItIsMonotonic() throws Exception {
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      // A deliberately identical created_at: two deployments queued by ONE build-succeeded event
      // land in the same tick, and nothing but the tiebreak may decide their order.
      deployment(sql, "d-1", "qits-workspaces", "'env-1'", SHA_A, "DECOMMISSIONED");
      deployment(sql, "d-2", "qits-workspaces", "'env-1'", SHA_B, "ACTIVE");
      deployment(sql, "d-3", "qits-idp", "null", SHA_C, "ACTIVE");

      assertEquals(
          List.of("d-1", "d-2", "d-3"),
          rows(sql, "select id from pd_deployment order by seq"),
          "the ordering follows the order the history was recorded in");

      try (ResultSet answered = sql.executeQuery("select seq from pd_deployment where id = 'd-3'")) {
        assertTrue(answered.next());
        assertNotNull(answered.getObject(1), "the database assigns the ordering column itself");
        assertTrue(answered.getLong(1) >= 3, "and it counts up: " + answered.getLong(1));
      }
    }
  }

  @Test
  public void aDeploymentNeedsNoServiceRowAndNoEnvironmentRow() throws Exception {
    // No FK into the topology, deliberately: a service removed from the catalogue or a tier torn
    // down must not take its deployment history with it, and the rollback pins are read off that
    // history while qits-artifacts' image GC waits on the answer.
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      deployment(sql, "d-orphan", "a-service-no-row-describes", "'a-tier-no-row-describes'", SHA_A, "ACTIVE");
      assertEquals(List.of("d-orphan"), rows(sql, "select id from pd_deployment"));
    }
  }

  @Test
  public void thePredecessorLookupMatchesAcrossANullTier() throws Exception {
    // The sweep's adoption, as SQL. A platform deployment's environment_id is null on both rows,
    // and `p.environment_id = o.environment_id` matches NOTHING when both are null — so the query
    // tests for null explicitly. Getting this wrong is silent: the successor of a handoff comes up,
    // finds no predecessor to decommission, and two rows claim to be ACTIVE.
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      deployment(sql, "p-env", "qits-platform-deployments", "'env-1'", SHA_A, "ACTIVE");
      deployment(sql, "p-platform", "qits-platform-deployments", "null", SHA_A, "ACTIVE");
      deployment(sql, "o-platform", "qits-platform-deployments", "null", SHA_B, "STARTING");

      assertEquals(
          List.of("p-platform"),
          rows(
              sql,
              "select p.id from pd_deployment p join pd_deployment o on o.id = 'o-platform'"
                  + " where p.application_name = o.application_name"
                  + " and (p.environment_id = o.environment_id"
                  + "      or (p.environment_id is null and o.environment_id is null))"
                  + " and p.status = 'ACTIVE'"),
          "the platform plane's own predecessor, and not the tier's copy of the same service");
    }
  }

  @Test
  public void theTopologyKeepsOneRowPerServiceAndOneLinkPerTier() throws Exception {
    // The shape that retired the ancestors' composite (environment_id, name) uniqueness and the
    // partial-index problem it carried: a service name is unique outright, so nothing has to be
    // enforced inside a transaction because a null made two rows "distinct".
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      // Named columns, not positional: a later migration that adds one must not become a change to
      // this file.
      sql.execute(
          "insert into pd_environment (id, name, branch, network, platform, created_at) values"
              + " ('env-1', 'dev', 'environment/dev', 'qits-net', true, timestamp with time zone"
              + " '2026-08-06 10:00:00Z')");
      sql.execute(
          "insert into pd_service (id, name, deployment_target, branch, available_on_env,"
              + " health_path, created_at) values ('svc-1', 'qits-gateway', 'ENVIRONMENT', null,"
              + " true, '/q/health/ready', timestamp with time zone '2026-08-06 10:00:00Z')");
      sql.execute(
          "insert into pd_service_link (id, service_id, environment_id, created_at) values"
              + " ('link-1', 'svc-1', 'env-1', timestamp with time zone '2026-08-06 10:00:00Z')");

      assertEquals(
          List.of("qits-gateway|env-1"),
          rows(
              sql,
              "select s.name || '|' || l.environment_id from pd_service s"
                  + " join pd_service_link l on l.service_id = s.id"));

      // A platform service has NO link row at all, and that absence is what makes an environment
      // created tomorrow pick it up.
      sql.execute(
          "insert into pd_service (id, name, deployment_target, branch, available_on_env,"
              + " health_path, created_at) values ('svc-2', 'qits-idp', 'PLATFORM', 'main', false,"
              + " '/idp/q/health/ready', timestamp with time zone '2026-08-06 10:00:00Z')");
      assertEquals(
          List.of("qits-idp"),
          rows(
              sql,
              "select name from pd_service where id not in"
                  + " (select service_id from pd_service_link)"));
    }
  }

  @Test
  public void aPlatformPlaneResourceIsUniqueBecauseItsNullsAreNotDistinct() throws Exception {
    // The claim `unique nulls not distinct` exists for, and the reason this table could not have
    // been written before the move off H2. A platform-plane resource has a null environment_name,
    // and under the DEFAULT rule every null is distinct — so the registry would take one row per
    // deployment, the "row exists" arm of the idempotency matrix would never be reached, and every
    // self-deploy would rotate a password that was working.
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      resource(sql, "r-1", "qits-artifacts", "null", "db", "qits_artifacts");
      assertThrows(
          SQLException.class,
          () -> resource(sql, "r-2", "qits-artifacts", "null", "db", "qits_artifacts"),
          "a second platform-plane row for the same (application, resource) is refused");
    }

    // The same triple in two different tiers is two different resources, which is the whole point
    // of the environment column being part of the key.
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      resource(sql, "r-1", "qits-projects", "'dev'", "db", "qits_projects");
      resource(sql, "r-2", "qits-projects", "'prod'", "db", "qits_projects");
      assertEquals(
          List.of("dev", "prod"),
          rows(sql, "select environment_name from pd_resource order by environment_name"));
    }
  }

  /** A freshly created, freshly migrated database — one per test, so no test inherits rows. */
  private static Connection migrated() throws Exception {
    String database = "pd_deployments_" + UUID.randomUUID().toString().replace("-", "");
    String url = EmbeddedPg.url(database);
    Flyway.configure()
        .dataSource(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD)
        .locations("classpath:db/platformdeployments/migration")
        .load()
        .migrate();
    return DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
  }

  private static void deployment(
      Statement sql, String id, String applicationName, String environmentId, String sha, String status)
      throws Exception {
    sql.execute(
        "insert into pd_deployment (id, application_name, environment_id, commit_sha, status,"
            + " container_name, created_at) values ('"
            + id
            + "', '"
            + applicationName
            + "', "
            + environmentId
            + ", '"
            + sha
            + "', '"
            + status
            + "', 'container-"
            + id
            + "', timestamp with time zone '2026-08-06 12:00:00Z')");
  }

  private static void resource(
      Statement sql,
      String id,
      String applicationName,
      String environmentName,
      String resourceName,
      String databaseName)
      throws SQLException {
    sql.execute(
        "insert into pd_resource (id, application_name, environment_name, resource_name,"
            + " resource_type, database_name, role_name, password, created_at) values ('"
            + id
            + "', '"
            + applicationName
            + "', "
            + environmentName
            + ", '"
            + resourceName
            + "', 'postgresql', '"
            + databaseName
            + "', '"
            + databaseName
            + "', 'not-a-real-secret', timestamp with time zone '2026-08-09 12:00:00Z')");
  }

  private static List<String> rows(Statement sql, String query) throws Exception {
    List<String> values = new ArrayList<>();
    try (ResultSet answered = sql.executeQuery(query)) {
      while (answered.next()) {
        values.add(answered.getString(1));
      }
    }
    return values;
  }
}
