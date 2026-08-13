package eu.wohlben.qits.platform.deployments.orchestration;

import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.Locale;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Which orchestrator this deployment runs against — {@code qits.platform.deployments.orchestrator},
 * {@code docker} (the default) or {@code swarm}, read once at first use.
 *
 * <p><b>A producer rather than a build-time choice</b>, because it has to be a deployment's answer:
 * a platform mid-migration wants to flip one environment's deployer to swarm, watch a bootstrap,
 * and flip it back if the answer is no. An {@code @IfBuildProperty} would make that a rebuild of
 * this component, which is a strange thing to need in order to change how it deploys everything
 * else.
 *
 * <p><b>An unknown value fails the boot</b> rather than falling back to docker. The two paths make
 * different networks, name their services differently and cut over differently; silently deploying
 * the platform with the other one because a value was misspelled is the kind of failure that is
 * only found later, in a topology nobody meant to build.
 */
@ApplicationScoped
public class DeploymentDrivers {

  private static final Logger LOG = Logger.getLogger(DeploymentDrivers.class);

  static final String DOCKER = "docker";
  static final String SWARM = "swarm";

  @ConfigProperty(name = DeploymentDriver.ORCHESTRATOR_KEY)
  String orchestrator;

  @Inject
  @Orchestrated(Orchestrated.Kind.DOCKER)
  DeploymentDriver docker;

  @Inject
  @Orchestrated(Orchestrated.Kind.SWARM)
  DeploymentDriver swarm;

  /**
   * The driver everything else injects. {@code @ApplicationScoped} so the choice is made once and
   * the same instance answers every caller — the docker driver carries the in-flight cutover of a
   * deployment across two calls, and a per-lookup instance would forget it between them.
   */
  @Produces
  @ApplicationScoped
  public DeploymentDriver selected() {
    String choice = orchestrator == null ? "" : orchestrator.strip().toLowerCase(Locale.ROOT);
    return switch (choice) {
      case DOCKER -> {
        LOG.info("Deploying with docker: this component performs the cutover and the rollback");
        yield docker;
      }
      case SWARM -> {
        LOG.info("Deploying with docker swarm: services, and the orchestrator's own cutover");
        yield swarm;
      }
      default ->
          throw new IllegalStateException(
              DeploymentDriver.ORCHESTRATOR_KEY
                  + " is '"
                  + orchestrator
                  + "', which is neither '"
                  + DOCKER
                  + "' nor '"
                  + SWARM
                  + "'. Nothing can be deployed until it names one of them.");
    };
  }
}
