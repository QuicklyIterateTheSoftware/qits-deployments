package eu.wohlben.qits.platform.deployments.orchestration;

import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Locale;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The guard on {@code qits.platform.deployments.orchestrator}: it must say {@code swarm}.
 *
 * <p><b>It used to be a producer that picked between two drivers</b>, and it is a guard because the
 * docker path is gone: there is one implementation, so ordinary injection resolves {@code @Inject
 * DeploymentDriver} with nothing to decide. The key stays anyway, and only as a refusal — a
 * deployment carrying a value from before the migration configures an orchestrator this build does
 * not have, and failing the boot naming it is a much better answer than deploying the platform with
 * whatever is left.
 *
 * <p>A {@code StartupEvent} rather than an {@code @IfBuildProperty}, for the reason the producer was
 * one: it has to be a deployment's answer, checked where the deployment's config is.
 */
@ApplicationScoped
public class DeploymentDrivers {

  private static final Logger LOG = Logger.getLogger(DeploymentDrivers.class);

  static final String SWARM = "swarm";

  @ConfigProperty(name = DeploymentDriver.ORCHESTRATOR_KEY)
  String orchestrator;

  void onStart(@Observes StartupEvent event) {
    check(orchestrator);
    LOG.info("Deploying with docker swarm: services, and the orchestrator's own cutover");
  }

  /** Package-private so the suite can put a value in without booting an application. */
  static void check(String value) {
    String choice = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    if (!SWARM.equals(choice)) {
      throw new IllegalStateException(
          DeploymentDriver.ORCHESTRATOR_KEY
              + " is '"
              + value
              + "', and the only orchestrator this component has is '"
              + SWARM
              + "'. Nothing can be deployed until it says so.");
    }
  }
}
