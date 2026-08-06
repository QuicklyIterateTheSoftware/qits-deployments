package eu.wohlben.qits.platform.deployments.environments.control;

import eu.wohlben.qits.platform.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.platform.deployments.environments.error.ConflictException;
import eu.wohlben.qits.platform.deployments.environments.error.NotFoundException;
import eu.wohlben.qits.platform.deployments.environments.persistence.PdEnvironmentRepository;
import eu.wohlben.qits.platform.deployments.environments.persistence.PdServiceLinkRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
 * <p>Transactions are programmatic ({@link QuarkusTransaction#requiringNew()}, the platform's
 * stance) rather than {@code @Transactional}, because the bracket then cannot be lost to a
 * self-invocation that never crosses the interceptor.
 */
@ApplicationScoped
public class EnvironmentService {

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

  /** {@code branch} and {@code network} are optional; each omitted one takes its convention. */
  public PdEnvironment create(String name, String branch, String network) {
    PdIdentifiers.requireName(name, "environment name");
    String effectiveBranch =
        PdIdentifiers.requireBranch(isBlank(branch) ? BRANCH_PREFIX + name : branch);
    String effectiveNetwork =
        isBlank(network)
            ? PdNetworks.bundle(name)
            : PdIdentifiers.requireName(network, "network name");

    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              if (environments.findByName(name).isPresent()) {
                throw new ConflictException("Environment already exists: " + name);
              }
              PdEnvironment environment = new PdEnvironment();
              environment.id = UUID.randomUUID().toString();
              environment.name = name;
              environment.branch = effectiveBranch;
              environment.network = effectiveNetwork;
              environment.createdAt = Instant.now();
              environments.persist(environment);
              return environment;
            });
  }

  /**
   * Rename an environment or point it at another branch. Both fields are optional; an omitted one
   * is left alone. This is the migration path onto the {@code environment/<name>} branch convention
   * and onto new names.
   *
   * <p><b>No docker side effects, deliberately</b> — a rename that tore containers down would be a
   * delete in disguise, and delete is the one operation never to reach for on a live environment.
   * The bundle network is not renamed with the name either: dev's bundle is {@code qits-net} by
   * history and stays so. What a rename does change is the names the <em>next</em> deployment
   * derives ({@code qits-env-<env>-<app>}); what runs now keeps the networks it is on until its own
   * next deploy moves it.
   */
  public PdEnvironment update(String environmentId, String name, String branch) {
    String newName = isBlank(name) ? null : PdIdentifiers.requireName(name, "environment name");
    String newBranch = isBlank(branch) ? null : PdIdentifiers.requireBranch(branch);
    return QuarkusTransaction.requiringNew()
        .call(
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
              return environment;
            });
  }

  /**
   * Remove the environment and every link into it. <b>Rows only.</b> What the deletion means is
   * that the tier is gone from the topology: the services that were linked into it keep their rows
   * and their other links, and platform services are untouched, having had no link to it in the
   * first place.
   *
   * <p>The containers and the networks are torn down by the execution domain <em>before</em> this
   * is called — {@code EnvironmentOperations.delete} owns that order, and the order is the
   * contract.
   */
  public void delete(String environmentId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              require(environmentId);
              links.deleteByEnvironment(environmentId);
              environments.deleteById(environmentId);
            });
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

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
