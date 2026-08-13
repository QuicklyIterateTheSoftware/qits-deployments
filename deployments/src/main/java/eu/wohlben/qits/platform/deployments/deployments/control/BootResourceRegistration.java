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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Writes this component's own {@code pd_resource} rows from the credentials it was handed to boot.
 *
 * <p><b>Why it exists at all.</b> This component is adopter #1 of its own {@code resources:}
 * contract, and it is the one adopter whose databases cannot have been provisioned by it: the
 * bootstrap creates the roles and the databases over plain JDBC before this process exists, and
 * hands them over as {@code QITS_RESOURCE_<NAME>_URL} / {@code _USERNAME} / {@code _PASSWORD}. The
 * registry would therefore have no row for them, and the first self-deploy would read the empty
 * registry, take the <b>reconcile</b> arm of the idempotency matrix, and {@code ALTER ROLE} its own
 * passwords to fresh ones — while the running instance still holds pools of connections opened with
 * the old ones. Recording what it was given makes that first self-deploy hit the <b>no-op</b> arm
 * instead.
 *
 * <p><b>Two resources, because the spec declares two.</b> {@code db} is this component's own
 * registry; {@code eventstream} is the bus client's claim ledger and outbox, which arrives with the
 * qits-eventstream jar and is a store of its own with its own Flyway lineage. Both are handed over
 * by the bootstrap and both would be rotated by the first self-deploy, so both are recorded. A
 * third entry in {@code .config/qits/deployments.yml} adds a third line to {@link #RESOURCES}, and
 * that is the whole of the change.
 *
 * <p><b>And it survives everything.</b> The row is rewritten on every boot from the environment the
 * container was started with, so it is correct after all containers die, after the registry
 * database is restored, and after an operator rotates the password in deployment config. The environment is
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

  /** The qits-eventstream jar's store, the second entry of the same spec line. */
  static final String EVENTSTREAM_RESOURCE_NAME = "eventstream";

  static final String RESOURCE_TYPE = "postgresql";

  /**
   * Every resource this component is handed at boot, in the spelling the spec uses. The variable
   * names follow the NAME — {@code QITS_RESOURCE_<NAME>_URL} and its two siblings, upper-cased —
   * which is the generic contract and the reason a resource cannot be renamed on one side alone.
   */
  static final List<String> RESOURCES = List.of(RESOURCE_NAME, EVENTSTREAM_RESOURCE_NAME);

  static final String ENVIRONMENT_VARIABLE = "QITS_ENVIRONMENT";

  @Inject PdResourceRepository resources;

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    Optional<String> environmentName = value(ENVIRONMENT_VARIABLE);
    if (environmentName.isEmpty()) {
      LOG.debugf("Not recording this instance's own resources: no %s is set", ENVIRONMENT_VARIABLE);
      return;
    }
    for (String resourceName : RESOURCES) {
      try {
        Optional<String> url = value(variable(resourceName, "URL"));
        Optional<String> username = value(variable(resourceName, "USERNAME"));
        Optional<String> password = value(variable(resourceName, "PASSWORD"));
        if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
          LOG.debugf(
              "Not recording this instance's own %s resource: it was started without the full"
                  + " %s triple",
              resourceName, variable(resourceName, "*"));
          continue;
        }
        // Per resource rather than around the loop: one missing triple must not cost the others
        // their rows, for the same reason this whole observer is warn-only.
        record(resourceName, url.get(), username.get(), password.get(), environmentName.get());
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not record this instance's own %s resource row", resourceName);
      }
    }
  }

  /** {@code QITS_RESOURCE_<NAME>_<SUFFIX>}, with the name's hyphens spelled as underscores. */
  static String variable(String resourceName, String suffix) {
    return "QITS_RESOURCE_"
        + resourceName.toUpperCase(Locale.ROOT).replace('-', '_')
        + "_"
        + suffix;
  }

  /**
   * Upsert the row for {@code (this application, this tier, this resource)}. Package-private
   * because the startup path is skipped under TEST and the suite drives this directly — the
   * {@code sweepInFlight()} arrangement.
   */
  void record(
      String resourceName,
      String url,
      String username,
      String password,
      String environmentName) {
    String database = databaseOf(url);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Optional<PdResource> existing =
                  resources.findOne(APPLICATION, environmentName, resourceName);
              PdResource resource = existing.orElseGet(PdResource::new);
              if (existing.isEmpty()) {
                resource.id = UUID.randomUUID().toString();
                resource.applicationName = APPLICATION;
                resource.environmentName = environmentName;
                resource.resourceName = resourceName;
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
        environmentName, resourceName, database, username);
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
