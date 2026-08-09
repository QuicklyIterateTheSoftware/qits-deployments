package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdResourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Writes this component's own {@code pd_resource} row from the credential it was handed to boot.
 *
 * <p><b>Why it exists at all.</b> This component is adopter #1 of its own {@code resources:}
 * contract, and it is the one adopter whose database cannot have been provisioned by it: the
 * bootstrap creates the role and the database over plain JDBC before this process exists, and hands
 * them over as {@code QITS_RESOURCE_DB_URL} / {@code _USERNAME} / {@code _PASSWORD}. The registry
 * would therefore have no row for it, and the first self-deploy would read the empty registry, take
 * the <b>reconcile</b> arm of the idempotency matrix, and {@code ALTER ROLE} its own database
 * password to a fresh one — while the running instance still holds a pool of connections opened
 * with the old one. Recording what it was given makes that first self-deploy hit the <b>no-op</b>
 * arm instead.
 *
 * <p><b>And it survives everything.</b> The row is rewritten on every boot from the environment the
 * container was started with, so it is correct after all containers die, after the registry
 * database is restored, and after an operator rotates the password in run-args. The environment is
 * the truth here; the row is a copy this component keeps so it can reason about itself the way it
 * reasons about every other application.
 *
 * <p><b>Warn-only, and skipped under TEST</b> — the {@code DeployService.onStart} shape. A
 * component that cannot record its own resource must still start: it is the thing that redeploys
 * the platform, and refusing to boot over a bookkeeping row would be the worst possible trade. An
 * incomplete environment (no {@code QITS_ENVIRONMENT}, or no triple) is not an error either: that
 * is a developer running the jar, and there is nothing to record.
 */
@ApplicationScoped
public class BootResourceRegistration {

  private static final Logger LOG = Logger.getLogger(BootResourceRegistration.class);

  /**
   * This repository's id, which is the name the intake announces this component under and therefore
   * the {@code application_name} its own deployments and resources are keyed by. A constant rather
   * than a config key: a deployment that could name a different application here would write a row
   * describing somebody else's database.
   */
  static final String APPLICATION = "qits-deployments";

  /** What the spec line {@code resources: postgresql:db} calls it, and the env segment it becomes. */
  static final String RESOURCE_NAME = "db";

  static final String RESOURCE_TYPE = "postgresql";

  static final String URL_VARIABLE = "QITS_RESOURCE_DB_URL";
  static final String USERNAME_VARIABLE = "QITS_RESOURCE_DB_USERNAME";
  static final String PASSWORD_VARIABLE = "QITS_RESOURCE_DB_PASSWORD";
  static final String ENVIRONMENT_VARIABLE = "QITS_ENVIRONMENT";

  @Inject PdResourceRepository resources;

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    try {
      Optional<String> url = value(URL_VARIABLE);
      Optional<String> username = value(USERNAME_VARIABLE);
      Optional<String> password = value(PASSWORD_VARIABLE);
      Optional<String> environmentName = value(ENVIRONMENT_VARIABLE);
      if (url.isEmpty() || username.isEmpty() || password.isEmpty() || environmentName.isEmpty()) {
        LOG.debugf(
            "Not recording this instance's own resource: it was started without the full"
                + " %s/%s/%s + %s set",
            URL_VARIABLE, USERNAME_VARIABLE, PASSWORD_VARIABLE, ENVIRONMENT_VARIABLE);
        return;
      }
      record(url.get(), username.get(), password.get(), environmentName.get());
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not record this instance's own resource row");
    }
  }

  /**
   * Upsert the row for {@code (this application, this tier, db)}. Package-private because the
   * startup path is skipped under TEST and the suite drives this directly — the
   * {@code sweepInFlight()} arrangement.
   */
  void record(String url, String username, String password, String environmentName) {
    String database = databaseOf(url);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Optional<PdResource> existing =
                  resources.findOne(APPLICATION, environmentName, RESOURCE_NAME);
              PdResource resource = existing.orElseGet(PdResource::new);
              if (existing.isEmpty()) {
                resource.id = UUID.randomUUID().toString();
                resource.applicationName = APPLICATION;
                resource.environmentName = environmentName;
                resource.resourceName = RESOURCE_NAME;
                resource.createdAt = Instant.now();
              }
              resource.resourceType = RESOURCE_TYPE;
              resource.databaseName = database;
              resource.roleName = username;
              // Whatever this process was actually started with wins over whatever was recorded:
              // the environment is the truth, and a row that disagreed with it would send the next
              // self-deploy down the reconcile arm against a credential that works.
              resource.password = password;
              // Deliberately NOT touched. This component provisioned nothing — the bootstrap did —
              // and a timestamp here would claim a check that never happened.
              // resource.lastProvisionedAt stays as it is.

              // PERSIST LAST, WITH EVERY COLUMN ALREADY SET. Hibernate queues the insert with the
              // state the entity had AT persist() and only then applies later writes as an UPDATE
              // — so a not-null column filled after the call fails the insert before that update
              // can run. Measured here, on `resource_type`.
              if (existing.isEmpty()) {
                resources.persist(resource);
              }
            });
    LOG.infof(
        "Recorded this instance's own resource: %s/%s uses database %s as %s",
        environmentName, RESOURCE_NAME, database, username);
  }

  /**
   * The database a JDBC url names — the last path segment, query string dropped. Package-private
   * for its own test: the url arrives from a deployment, and reading the wrong segment out of it
   * would write a row about a database that does not exist.
   */
  static String databaseOf(String url) {
    String withoutQuery = url;
    int query = withoutQuery.indexOf('?');
    if (query >= 0) {
      withoutQuery = withoutQuery.substring(0, query);
    }
    int lastSlash = withoutQuery.lastIndexOf('/');
    String database = lastSlash < 0 ? "" : withoutQuery.substring(lastSlash + 1);
    if (database.isBlank()) {
      throw new IllegalArgumentException("no database in the jdbc url");
    }
    return database;
  }

  private static Optional<String> value(String name) {
    return ConfigProvider.getConfig()
        .getOptionalValue(name, String.class)
        .map(String::strip)
        .filter(v -> !v.isEmpty());
  }
}
