package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.platform.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.platform.deployments.environments.error.ConflictException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Environment lifecycle <b>with its docker side</b>: the operational door onto {@link
 * EnvironmentService}, which owns the rows and touches nothing else.
 *
 * <p>The split is the module partition made concrete. Creating a tier is a row; making its network
 * is a container-runtime act. Deleting a tier is a row; reaping its containers and removing its
 * networks is not. Everything in the second half needs the docker seam, and the docker seam belongs
 * to the execution domain — so this class is where the two halves are composed, and it is the only
 * place that knows the order they go in.
 *
 * <p>Reads pass straight through. They are here so a caller has one door rather than two, not
 * because there is anything to add to them.
 */
@ApplicationScoped
public class EnvironmentOperations {

  private static final Logger LOG = Logger.getLogger(EnvironmentOperations.class);

  @Inject EnvironmentService environments;
  @Inject DeployService deployService;
  @Inject DeploymentDriver driver;

  /**
   * The transition network every container also joins ({@link DeployService}). Read here for one
   * reason only: a teardown must never take it down. See {@link #delete}.
   *
   * <p>{@code Optional} for the same reason it is there — SmallRye reads the enforcement flip's own
   * empty value as ABSENT rather than as an empty string.
   */
  @ConfigProperty(name = "qits.platform.deployments.legacy-network")
  Optional<String> legacyNetwork;

  /**
   * Create the tier, then make sure its bundle network exists.
   *
   * <p>The docker half is best-effort and happens <b>after</b> the row: an environment whose
   * network could not be created yet is still an environment (the driver re-ensures every network
   * before every deployment), so a momentarily unreachable docker must not make the create fail.
   */
  public PdEnvironment create(String name, String branch, String network, boolean platform) {
    PdEnvironment environment = environments.create(name, branch, network, platform);
    driver.ensureNetwork(
        new DeploymentDriver.Network(
            environment.network, environment.id, DeploymentDriver.NetworkKind.BUNDLE, null));
    return environment;
  }

  /**
   * Rename an environment or point it at another branch. <b>No docker side effects</b>, so this is
   * a pass-through and stays one: a rename that tore containers down would be a delete in disguise,
   * and delete is the one operation never to reach for on a live environment. The next deployment
   * of each application moves it onto the networks the new name derives; what runs now keeps
   * running.
   *
   * <p>Moving the platform designation is a pass-through for the same reason, and it is the one
   * place that reasoning is worth restating: a platform service has no environment id and keeps its
   * bare wire alias, so the containers of the platform plane are not this tier's to move. What the
   * flag changes is which branch is allowed to roll them.
   */
  public PdEnvironment update(String environmentId, String name, String branch, Boolean platform) {
    return environments.update(environmentId, name, branch, platform);
  }

  /**
   * Tear the environment down: the recorded deployments, then the containers and the networks, then
   * the tier itself.
   *
   * <p><b>Docker first, the rows last</b>, and the order is the contract. The teardown is
   * label-driven — it reaps by {@code qits.platform.deployments.environment} and removes the
   * networks carrying that id — so it needs nothing from the topology, but deleting the tier first
   * would leave a failed teardown with no row to retry it from. Deleting last means a half-finished
   * teardown is still addressable.
   *
   * <p>The docker half is best-effort otherwise: a teardown must succeed even when docker already
   * lost the containers.
   *
   * <p>The order between the container reap and the network removal is load-bearing too. Platform
   * services live on this environment's per-application networks without belonging to the
   * environment, so they survive the reap and would then hold every network open — docker refuses
   * to remove a network with an endpoint on it. The plane is detached first ({@link
   * DeploymentDriver#detachPlatformPlane}, which is a disconnect per container on the docker path
   * and nothing at all under swarm), and only the networks THIS environment owns (its bundle plus
   * everything labelled with its id) are removed, so a platform service keeps every other
   * environment it serves.
   *
   * <p><b>The legacy network is never one of them.</b> An environment may have been created with
   * {@code qits.platform.deployments.legacy-network} as its bundle — the dev tier IS that case, its
   * bundle is {@code qits-net} — and it is not that environment's to take away: it is the
   * transition membership of every container on the host, platform services included. Disconnecting
   * them from it would cut qits-idp off from the platform, and this component would be doing it to
   * itself mid-request. So it is skipped for both steps, and the environment's derived
   * per-application networks still go.
   *
   * <p><b>The platform environment is refused.</b> It is the tier whose branch rolls the platform
   * plane, so tearing it down would leave qits-platform-idp and the rest deployable from no branch
   * at all — running, unreachable by any future build, and with nothing in the model to say why.
   * Designate another environment first; that is a move, and it leaves the plane with a tier
   * throughout.
   */
  public void delete(String environmentId) {
    PdEnvironment environment = environments.require(environmentId);
    if (environment.platform) {
      throw new ConflictException(
          "Environment "
              + environment.name
              + " is the platform environment: the platform plane would have no branch to deploy"
              + " from. Designate another environment first.");
    }
    String legacy = legacyNetwork.map(String::strip).filter(n -> !n.isEmpty()).orElse(null);
    Set<String> networks = new LinkedHashSet<>();
    networks.add(environment.network);
    for (DeploymentDriver.Network network : driver.networks()) {
      if (environmentId.equals(network.environmentId())) {
        networks.add(network.name());
      }
    }
    if (networks.remove(legacy)) {
      LOG.infof(
          "Environment %s was on the legacy network '%s' — left in place, it is the platform's",
          environment.name, legacy);
    }

    deployService.forgetEnvironment(environmentId);
    int removed = driver.removeEnvironmentContainers(environmentId);
    if (removed > 0) {
      LOG.infof("Removed %d container(s) of environment %s", removed, environmentId);
    }
    driver.detachPlatformPlane(List.copyOf(networks));
    for (String network : networks) {
      driver.removeNetwork(network);
    }

    environments.delete(environmentId);
  }

  public PdEnvironment require(String environmentId) {
    return environments.require(environmentId);
  }

  public List<PdEnvironment> list() {
    return environments.list();
  }
}
