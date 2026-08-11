package eu.wohlben.qits.platform.deployments.environments.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.platform.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.platform.deployments.environments.error.ConflictException;
import eu.wohlben.qits.platform.deployments.environments.error.NotFoundException;
import eu.wohlben.qits.platform.deployments.environments.persistence.PdEnvironmentRepository;
import eu.wohlben.qits.platform.deployments.environments.persistence.PdServiceLinkRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Environment lifecycle, <b>rows only</b>: creation with the conventions filled in (branch {@code
 * environment/<name>}, bundle network {@code qits-env-<name>}), rename/retarget, and removal.
 *
 * <p>An environment is a <b>tier</b> and is created deliberately — nothing derives one. What is
 * derived is everything inside it: a green build on the environment's branch registers the
 * repository's service and links it, so this call creates the tier and the builds fill it.
 *
 * <p><b>Nothing here touches docker</b>, and that is the module boundary rather than a phase.
 * Creating an environment writes a row and names a network; making that network, reaping the tier's
 * containers and removing its networks belong to the execution domain, which composes this service
 * with the driver in {@code EnvironmentOperations}. Keeping the two apart is what lets the topology
 * be reasoned about — and tested — without a docker seam in front of it, and it is why the
 * dependency runs one way.
 *
 * <p>Transactions are programmatic (the platform's stance) rather than {@code @Transactional},
 * because the bracket then cannot be lost to a self-invocation that never crosses the interceptor.
 * The reads take {@link QuarkusTransaction#joiningExisting()}; <b>the three writes take {@link
 * DbRetry#inNewTx}, which IS their {@code requiringNew}</b> — see {@link #writeDeadline}.
 */
@ApplicationScoped
public class EnvironmentService {

  private static final Logger LOG = Logger.getLogger(EnvironmentService.class);

  /**
   * The branch an environment listens to when its creator names none. A tier deploys from its own
   * ref — {@code main} stays the integration trunk, and a release reaches dev by fast-forwarding
   * {@code environment/dev} onto it.
   */
  public static final String BRANCH_PREFIX = "environment/";

  /** The per-environment bundle network when the creator names none. */
  public static final String NETWORK_PREFIX = PdNetworks.BUNDLE_PREFIX;

  @Inject PdEnvironmentRepository environments;
  @Inject PdServiceLinkRepository links;

  /**
   * How long a tier write may be held while the database comes back — the same key the read surface
   * spends ({@code PdReadPatience}, 15S shipped), because these three have the same caller: a person
   * or a client holding an HTTP request open, on a thread inside no transaction and no monitor.
   *
   * <p>Not the deploy worker's thirty seconds. That budget belongs to a worker waiting out an
   * outage it caused itself, and it is explicitly not for a request thread.
   *
   * <p>No {@code defaultValue}, for the reason {@code PdReadPatience} has none: the shipped value is
   * one line in the deployable's {@code application.properties}, so there is one spelling of it.
   */
  @ConfigProperty(name = "qits.platform.deployments.db-retry-deadline")
  Duration writeDeadline;

  /**
   * {@code branch} and {@code network} are optional; each omitted one takes its convention.
   *
   * <p>{@code platform} designates this tier as the one the platform plane deploys from, and
   * designating is a <b>move</b> — see {@link #designate}.
   *
   * <p><b>Held through a short database outage</b> ({@link DbRetry#inNewTx}, {@link
   * #writeDeadline}). The retry owns the transaction, so it repeats only an attempt that certainly
   * did not commit — which is what makes retrying an insert safe at all, and it is why the body ends
   * with a {@code flush()}. Validation runs outside it: a rejected name is not worth a second
   * attempt, and a {@code ConflictException} is rethrown at once like every other business failure.
   */
  public PdEnvironment create(String name, String branch, String network, boolean platform) {
    PdIdentifiers.requireName(name, "environment name");
    String effectiveBranch =
        PdIdentifiers.requireBranch(isBlank(branch) ? BRANCH_PREFIX + name : branch);
    String effectiveNetwork =
        isBlank(network)
            ? PdNetworks.bundle(name)
            : PdIdentifiers.requireName(network, "network name");

    return DbRetry.inNewTx(
        "The creation of environment " + name,
        () -> {
          if (environments.findByName(name).isPresent()) {
            throw new ConflictException("Environment already exists: " + name);
          }
          PdEnvironment environment = new PdEnvironment();
          environment.id = UUID.randomUUID().toString();
          environment.name = name;
          environment.branch = effectiveBranch;
          environment.network = effectiveNetwork;
          environment.platform = platform;
          environment.createdAt = Instant.now();
          environments.persist(environment);
          if (platform) {
            designate(environment);
          } else if (environments.listPlatform().isEmpty()) {
            // Silent otherwise, and the silence is the problem: a platform service's green
            // build would register nothing and report no error, because "no tier is the
            // platform one" and "this branch is not the platform tier's" are the same answer.
            LOG.warnf(
                "Created environment %s and no environment is the platform one — until one is"
                    + " designated, a green build of a platform service deploys nowhere",
                name);
          }
          // Last statement, and it is load-bearing: an ORM flushes at commit by default, which
          // would put this insert on the far side of the one round trip nothing can place.
          // Flushed, a lost connection here is certainly a no-commit and is retried.
          environments.flush();
          return environment;
        },
        writeDeadline);
  }

  /**
   * Rename an environment, point it at another branch, or make it the platform environment. Every
   * field is optional; an omitted one is left alone. This is the migration path onto the {@code
   * environment/<name>} branch convention and onto new names.
   *
   * <p>{@code platform = true} moves the designation here (see {@link #designate}). {@code false} is
   * refused: the platform plane must always have a tier to deploy from, so the designation is moved
   * to another environment, never dropped.
   *
   * <p><b>No docker side effects, deliberately</b> — a rename that tore containers down would be a
   * delete in disguise, and delete is the one operation never to reach for on a live environment.
   * The bundle network is not renamed with the name either: dev's bundle is {@code qits-net} by
   * history and stays so. What a rename does change is the names the <em>next</em> deployment
   * derives ({@code qits-env-<env>-<app>}); what runs now keeps the networks it is on until its own
   * next deploy moves it.
   *
   * <p>Held through a short database outage, exactly as {@link #create} is.
   */
  public PdEnvironment update(
      String environmentId, String name, String branch, Boolean platform) {
    String newName = isBlank(name) ? null : PdIdentifiers.requireName(name, "environment name");
    String newBranch = isBlank(branch) ? null : PdIdentifiers.requireBranch(branch);
    return DbRetry.inNewTx(
        "The update of environment " + environmentId,
        () -> {
          PdEnvironment environment = require(environmentId);
          if (newName != null && !newName.equals(environment.name)) {
            if (environments.findByName(newName).isPresent()) {
              throw new ConflictException("Environment already exists: " + newName);
            }
            environment.name = newName;
          }
          if (newBranch != null) {
            environment.branch = newBranch;
          }
          if (Boolean.TRUE.equals(platform)) {
            designate(environment);
          } else if (Boolean.FALSE.equals(platform) && environment.platform) {
            throw new ConflictException(
                "The platform environment cannot be cleared, only moved: designate another"
                    + " environment instead of undesignating "
                    + environment.name);
          }
          environments.flush(); // statement phase, so a lost connection is retriable
          return environment;
        },
        writeDeadline);
  }

  /**
   * Make this the platform environment, by <b>moving</b> the designation rather than setting it:
   * whoever held it loses it here, in the caller's transaction.
   *
   * <p>The move IS the at-most-one constraint. The schema carries no partial unique index because
   * H2 has none (V2's comment, and V1 reached the same answer about null rows), so there is no
   * second enforcement to fall back on and this must stay the only way the flag goes true.
   *
   * <p>Moving is also the right shape for the operation a caller actually wants. "The platform
   * environment is prod now" is one fact, and expressing it as a clear plus a set would leave a
   * window with no platform environment at all — during which a green build of a platform service
   * would register nothing and quietly deploy nowhere.
   *
   * <p><b>Rows only, like everything else here.</b> The containers do not move: a platform service
   * has no environment id and keeps its bare wire alias, so what changes is which branch is allowed
   * to roll it, not where it runs. Actually relocating a running platform plane is a larger
   * operation and is not this.
   */
  private void designate(PdEnvironment environment) {
    for (PdEnvironment holder : environments.listPlatform()) {
      if (!holder.id.equals(environment.id)) {
        holder.platform = false;
        LOG.infof(
            "The platform environment moves from %s to %s", holder.name, environment.name);
      }
    }
    environment.platform = true;
  }

  /**
   * Remove the environment and every link into it. <b>Rows only.</b> What the deletion means is
   * that the tier is gone from the topology: the services that were linked into it keep their rows
   * and their other links, and platform services are untouched, having had no link to it in the
   * first place.
   *
   * <p>The containers and the networks are torn down by the execution domain <em>before</em> this
   * is called — {@code EnvironmentOperations.delete} owns that order, and the order is the
   * contract. That is also why this one is worth holding through an outage: the docker half has
   * already happened, so a lost row leaves a tier that exists in the topology and nowhere else.
   */
  public void delete(String environmentId) {
    DbRetry.runInNewTx(
        "The removal of environment " + environmentId,
        () -> {
          require(environmentId);
          links.deleteByEnvironment(environmentId);
          environments.deleteById(environmentId);
        },
        writeDeadline);
  }

  /**
   * <b>Every read here brackets itself with {@link QuarkusTransaction#joiningExisting()}</b>, and
   * that is not decoration. A JAX-RS caller has a request context and Hibernate would answer a read
   * without a transaction — but the topology's other caller is the deploy worker, a bare daemon
   * thread with neither, and there the same call throws {@code ContextNotActiveException}. That
   * hazard is new with the merge: the topology used to be an HTTP call, which needs no session at
   * all. Joining rather than requiring a new one keeps a caller that already has a transaction
   * (this service's own writes) in it, so an entity returned to it stays managed.
   */
  public PdEnvironment require(String environmentId) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () ->
                environments
                    .findByIdOptional(environmentId)
                    .orElseThrow(
                        () -> new NotFoundException("No such environment: " + environmentId)));
  }

  public List<PdEnvironment> list() {
    return QuarkusTransaction.joiningExisting().call(() -> List.copyOf(environments.listNewestFirst()));
  }

  /**
   * Every environment listening to exactly this branch — what a green build fans out over.
   *
   * <p>A repository query now, where the split needed an HTTP round trip and a client-side filter:
   * the previous registry API had no by-branch question, so the deployer read every environment and
   * matched them itself. One database, one query, one index ({@code idx_pd_environment_branch}).
   */
  public List<PdEnvironment> onBranch(String branch) {
    return QuarkusTransaction.joiningExisting()
        .call(() -> List.copyOf(environments.listByBranch(branch)));
  }

  /**
   * The platform environment, if one is designated — the tier whose branch deploys the platform
   * plane.
   *
   * <p>Empty is a real answer and not only a fresh-database one: an install can be mid-bootstrap,
   * and a platform build then registers nothing rather than picking a tier at random. {@link
   * #designate} is what fills it.
   */
  public Optional<PdEnvironment> platformEnvironment() {
    return QuarkusTransaction.joiningExisting()
        .call(() -> environments.listPlatform().stream().findFirst());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
