package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdResourceRepository;
import eu.wohlben.qits.platform.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.platform.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.platform.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.platform.deployments.environments.entity.PdEnvironment;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Everything between "the repository asked for a database" and "the container is started with the
 * credential for one": read the registry, resolve which postgres to talk to, generate a candidate
 * password, drive the {@link ResourceProvisioner} seam, record what came back, and answer with the
 * bindings the argv needs.
 *
 * <p><b>It runs on the deploy worker, before the pull.</b> The worker has no request context, so
 * every read and every write brackets itself in {@link QuarkusTransaction#requiringNew()} — and,
 * just as deliberately, <b>no transaction spans the call to the seam</b>. That call opens a socket
 * to another server and runs DDL on it; holding a database transaction across it would put this
 * component's own connection pool behind somebody else's server for as long as it takes to answer.
 *
 * <p><b>Nothing here is ever dropped.</b> The failure modes are all shaped as convergence: a role
 * that is missing is created, a database that is missing is created, an ownership that drifted is
 * put back. A resource the deployment no longer declares is left exactly where it is.
 */
@ApplicationScoped
public class ResourceProvisioning {

  private static final Logger LOG = Logger.getLogger(ResourceProvisioning.class);

  /** Every platform repository carries it, and no database identifier may. */
  private static final String NAME_PREFIX = "qits-";

  /** The one resource type, and the application name the platform's postgres deploys under. */
  static final String POSTGRES_APPLICATION = "qits-oci-postgresql";

  /**
   * Postgres' own port, inside the network. It is a constant rather than a config key because the
   * address it belongs to is derived too: the only reachable postgres is the one this component
   * deploys, under the alias its own naming rule produced.
   */
  static final int POSTGRES_PORT = 5432;

  static final String RESOURCE_TYPE = "postgresql";

  /** 128 bits, hex — argv-safe, URL-safe, and it needs no quoting in a SQL string literal. */
  private static final int PASSWORD_BYTES = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  @Inject PdResourceRepository resources;
  @Inject ResourceProvisioner provisioner;
  @Inject EnvironmentService environments;

  @ConfigProperty(name = "qits.platform.deployments.postgres.admin-username")
  String adminUsername;

  /**
   * Deliberately without a default. There is no password this repository could ship that would be
   * right, and a wrong one fails at the first CREATE ROLE with an authentication error nobody reads
   * as "nothing configured this". Absent, a deployment that declares a resource fails naming the
   * key — which is the only actionable thing to say.
   */
  @ConfigProperty(name = "qits.platform.deployments.postgres.admin-password")
  Optional<String> adminPassword;

  /**
   * One resource with its database resolved — what a {@code Target} carries. The spec's null
   * database has been replaced by the convention here, so nothing downstream has to know there was
   * ever a default.
   */
  public record Resolved(String name, String database) {}

  /**
   * Fill in the databases the file left out, and refuse the collision only a resolved list can see.
   *
   * <p>The convention is {@code qits_} plus the application name without its {@code qits-} prefix,
   * dashes to underscores — so qits-artifacts gets {@code qits_artifacts} and this component gets
   * {@code qits_deployments}. It is resolved here, at registration, because this is the first place
   * that knows the application's name; the parser never does.
   *
   * <p>The parser already refused two entries naming one <b>literal</b> database. What it could not
   * see is two entries whose defaults collide, which after resolution is the same mistake, so it is
   * caught in the same shape rather than left to become two applications' worth of writes into one
   * store.
   */
  public static List<Resolved> resolve(String applicationName, List<ResourceSpec> declared) {
    if (declared == null || declared.isEmpty()) {
      return List.of();
    }
    List<Resolved> resolved = new ArrayList<>();
    Set<String> databases = new HashSet<>();
    for (ResourceSpec spec : declared) {
      String database =
          spec.database() != null ? spec.database() : conventionDatabase(applicationName);
      if (!databases.add(database)) {
        throw new ResourceException(
            "two resources of "
                + applicationName
                + " resolve to the database `"
                + database
                + "` — name one of them explicitly");
      }
      resolved.add(new Resolved(spec.name(), database));
    }
    return List.copyOf(resolved);
  }

  /** {@code qits_} + the application name without its {@code qits-} prefix, dashes underscored. */
  static String conventionDatabase(String applicationName) {
    String segment =
        applicationName.startsWith(NAME_PREFIX)
            ? applicationName.substring(NAME_PREFIX.length())
            : applicationName;
    return PdIdentifiers.requireDatabaseName(
        "qits_" + segment.replace('-', '_').toLowerCase(Locale.ROOT));
  }

  /**
   * Make every declared resource exist and answer with what to inject for it.
   *
   * @param environmentName the tier, or null for the platform plane
   * @throws ResourceException with an operator-facing sentence, and no password in it
   */
  public List<DeploymentDriver.ResourceBinding> ensureAll(
      String applicationName, String environmentName, List<Resolved> declared) {
    if (declared == null || declared.isEmpty()) {
      return List.of();
    }
    String host = postgresHost(environmentName);
    String admin =
        adminPassword
            .map(String::strip)
            .filter(password -> !password.isEmpty())
            .orElseThrow(
                () ->
                    new ResourceException(
                        "this deployment declares resources and nothing configured"
                            + " qits.platform.deployments.postgres.admin-password"));

    List<DeploymentDriver.ResourceBinding> bindings = new ArrayList<>();
    for (Resolved resource : declared) {
      bindings.add(ensure(applicationName, environmentName, host, admin, resource));
    }
    return List.copyOf(bindings);
  }

  private DeploymentDriver.ResourceBinding ensure(
      String applicationName,
      String environmentName,
      String host,
      String admin,
      Resolved resource) {
    String database = PdIdentifiers.requireDatabaseName(resource.database());
    String name = PdIdentifiers.requireResourceName(resource.name());

    // The registry read, and the cross-application check, in one bracket: the worker thread has no
    // session of its own, and the answer is a plain String that outlives the transaction.
    String stored =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  for (PdResource claim : resources.listByDatabase(database)) {
                    if (!claim.applicationName.equals(applicationName)) {
                      throw new ResourceException(
                          "the database `"
                              + database
                              + "` is already provisioned for "
                              + claim.applicationName
                              + " — two repositories cannot share one database, so name a"
                              + " different one in `resources:`");
                    }
                  }
                  // Null is tested, never compared: the platform plane's environment_name is null,
                  // and `= null` would match nothing and rotate a working password every deploy.
                  return resources
                      .findOne(applicationName, environmentName, name)
                      .map(row -> row.password)
                      .orElse(null);
                });

    // OUTSIDE any transaction, deliberately — see the class javadoc.
    String fresh = freshPassword();
    ResourceProvisioner.Result result =
        provisioner.ensure(
            new ResourceProvisioner.Request(
                host,
                POSTGRES_PORT,
                adminUsername,
                admin,
                database,
                // The role IS the database: one login per database, and nothing else may use it.
                database,
                stored,
                fresh));
    if (!result.ok()) {
      throw new ResourceException(
          "could not provision the database `" + database + "` on " + host + ": " + result.detail());
    }

    String inEffect = result.passwordInEffect();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Optional<PdResource> existing =
                  resources.findOne(applicationName, environmentName, name);
              PdResource row = existing.orElseGet(PdResource::new);
              if (existing.isEmpty()) {
                row.id = UUID.randomUUID().toString();
                row.applicationName = applicationName;
                row.environmentName = environmentName;
                row.resourceName = name;
                row.createdAt = Instant.now();
              }
              row.resourceType = RESOURCE_TYPE;
              row.databaseName = database;
              row.roleName = database;
              row.password = inEffect;
              row.lastProvisionedAt = Instant.now();
              // Persist LAST, with every not-null column set: Hibernate queues the insert with the
              // state the entity had at persist() and applies later writes as a following update.
              if (existing.isEmpty()) {
                resources.persist(row);
              }
            });

    LOG.infof(
        "Resource %s of %s (%s) is database %s on %s",
        name, applicationName, environmentName == null ? "platform" : environmentName, database, host);
    return new DeploymentDriver.ResourceBinding(
        name, "jdbc:postgresql://" + host + ":" + POSTGRES_PORT + "/" + database, database, inEffect);
  }

  /**
   * Which postgres this deployment's resources live on.
   *
   * <p>An environment application talks to its own tier's instance, at the wire alias that tier's
   * postgres answers to — derived rather than configured, because it is the same derivation every
   * other address on the platform uses. A <b>platform-plane</b> application has no tier to derive
   * from, so it takes the platform environment's, which is the same answer the plane's own
   * deployments already hang off.
   */
  private String postgresHost(String environmentName) {
    if (environmentName != null) {
      return PdNetworks.alias(environmentName, POSTGRES_APPLICATION);
    }
    PdEnvironment platform =
        environments
            .platformEnvironment()
            .orElseThrow(
                () ->
                    new ResourceException(
                        "this platform-plane deployment declares resources and no environment is"
                            + " designated the platform one, so there is no postgres to provision"
                            + " on"));
    return PdNetworks.alias(platform.name, POSTGRES_APPLICATION);
  }

  /**
   * 32 lowercase hex characters from {@link SecureRandom}. The charset is the point: it survives an
   * argv, a JDBC url and a SQL string literal without one escaping rule between them.
   */
  private static String freshPassword() {
    byte[] bytes = new byte[PASSWORD_BYTES];
    RANDOM.nextBytes(bytes);
    StringBuilder hex = new StringBuilder(PASSWORD_BYTES * 2);
    for (byte b : bytes) {
      hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    }
    return hex.toString();
  }
}
