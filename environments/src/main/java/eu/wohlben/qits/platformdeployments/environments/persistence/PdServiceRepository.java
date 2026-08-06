package eu.wohlben.qits.platformdeployments.environments.persistence;

import eu.wohlben.qits.platformdeployments.environments.entity.PdDeploymentTarget;
import eu.wohlben.qits.platformdeployments.environments.entity.PdService;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link PdService}. */
@ApplicationScoped
public class PdServiceRepository implements PanacheRepositoryBase<PdService, String> {

  /** The one row a service name can have — the upsert's read half. */
  public Optional<PdService> findByName(String name) {
    return find("name = ?1", name).firstResultOptional();
  }

  /** Every service, oldest first: the flat catalogue, both planes together. */
  public List<PdService> listOldestFirst() {
    return list("order by createdAt, id");
  }

  /**
   * Every platform service. This is half of the link query's answer for <b>every</b> environment: a
   * platform service is linked nowhere in particular and therefore present everywhere.
   */
  public List<PdService> listPlatformServices() {
    return list("deploymentTarget = ?1 order by createdAt, id", PdDeploymentTarget.PLATFORM);
  }
}
