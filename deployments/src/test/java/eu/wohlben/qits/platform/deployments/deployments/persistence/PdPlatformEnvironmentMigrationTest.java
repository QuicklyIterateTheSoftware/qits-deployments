package eu.wohlben.qits.platform.deployments.deployments.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V2's backfill, against a real H2 — plain JUnit, no Quarkus, because the subject is the SQL.
 *
 * <p><b>The suites otherwise never see a backfill.</b> Every {@code @QuarkusTest} here migrates an
 * empty schema, where "update the oldest row" updates nothing and passes for the wrong reason. So
 * this migrates to V1, writes the rows the pre-V2 code wrote, and then migrates the rest of the way
 * — the shape {@code PdSchemaTest}'s class comment asks for.
 *
 * <p>What the backfill protects: every install reaching V2 has exactly one environment, and it is
 * the platform's own. Leaving the column false everywhere would strand the platform plane —
 * {@code DeployService.registerPlatform} deploys only when the platform environment listens to the
 * built branch, so qits-platform-idp and the rest would go quietly undeployable on the first green
 * build after the upgrade.
 */
public class PdPlatformEnvironmentMigrationTest {

  @Test
  public void theOneEnvironmentOfAnUpgradedInstallBecomesThePlatformOne() throws Exception {
    String url = atV1();
    try (Connection connection = DriverManager.getConnection(url, "sa", "");
        Statement sql = connection.createStatement()) {
      // What the bootstrap wrote before V2 existed: one row, no platform column to set.
      environmentAtV1(sql, "env-prod", "prod", "environment/prod", "2026-08-06 10:00:00Z");
    }

    migrate(url);

    try (Connection connection = DriverManager.getConnection(url, "sa", "");
        Statement sql = connection.createStatement()) {
      assertEquals(
          List.of("prod"),
          rows(sql, "select name from pd_environment where platform"),
          "the install's one environment is the platform environment after the upgrade");
    }
  }

  @Test
  public void aSecondTierDoesNotProduceASecondPlatformEnvironment() throws Exception {
    // Not the expected state — environment #2 was gated on this very column — but the backfill is
    // written as "the oldest row" rather than "every row" precisely so an install that got there
    // early comes out with one holder rather than a broken invariant the code then has to repair.
    String url = atV1();
    try (Connection connection = DriverManager.getConnection(url, "sa", "");
        Statement sql = connection.createStatement()) {
      environmentAtV1(sql, "env-prod", "prod", "environment/prod", "2026-08-06 10:00:00Z");
      environmentAtV1(sql, "env-dev", "dev", "environment/dev", "2026-08-07 10:00:00Z");
    }

    migrate(url);

    try (Connection connection = DriverManager.getConnection(url, "sa", "");
        Statement sql = connection.createStatement()) {
      assertEquals(
          List.of("prod"),
          rows(sql, "select name from pd_environment where platform"),
          "exactly one holder, and it is the tier the bootstrap created first");
    }
  }

  @Test
  public void anEmptyDatabaseDesignatesNothing() throws Exception {
    // The fresh-install path: the bootstrap creates the environment through the API with
    // platform: true, so the migration has nothing to designate and must not invent a row.
    String url = atV1();
    migrate(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "");
        Statement sql = connection.createStatement()) {
      assertTrue(
          rows(sql, "select name from pd_environment").isEmpty(),
          "the migration writes no environment of its own");
    }
  }

  @Test
  public void theColumnIsNotNullSoEveryLaterWriterHasAnAnswer() throws Exception {
    String url = atV1();
    migrate(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "");
        Statement sql = connection.createStatement()) {
      // The default is what lets a writer that names no platform column still insert. A new tier is
      // not the platform one until something designates it.
      sql.execute(
          "insert into pd_environment (id, name, branch, network, created_at) values ('env-new',"
              + " 'preprod', 'environment/preprod', 'qits-env-preprod', timestamp with time zone"
              + " '2026-08-09 10:00:00Z')");
      assertEquals(
          List.of("preprod"),
          rows(sql, "select name from pd_environment where not platform"),
          "a tier created without saying is not the platform one");
    }
  }

  private static String atV1() {
    String url = "jdbc:h2:mem:pd-platform-env-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    flyway(url).target("1").load().migrate();
    return url;
  }

  private static void migrate(String url) {
    flyway(url).load().migrate();
  }

  private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String url) {
    return Flyway.configure()
        .dataSource(url, "sa", "")
        .locations("classpath:db/platformdeployments/migration");
  }

  private static void environmentAtV1(
      Statement sql, String id, String name, String branch, String createdAt) throws Exception {
    sql.execute(
        "insert into pd_environment (id, name, branch, network, created_at) values ('"
            + id
            + "', '"
            + name
            + "', '"
            + branch
            + "', 'qits-net', timestamp with time zone '"
            + createdAt
            + "')");
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
