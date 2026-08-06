package eu.wohlben.qits.platform.deployments.environments.persistence;

import eu.wohlben.qits.platform.deployments.environments.entity.PdService;
import eu.wohlben.qits.platform.deployments.environments.entity.PdServiceLink;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Panache DAO for {@link PdServiceLink} — the "runs in" rows. */
@ApplicationScoped
public class PdServiceLinkRepository implements PanacheRepositoryBase<PdServiceLink, String> {

  /**
   * The services linked into one environment, oldest link first, with the service fetched so a
   * mapper outside a transaction can read it.
   */
  public List<PdService> listServicesOf(String environmentId) {
    return getEntityManager()
        .createQuery(
            "select l.service from PdServiceLink l"
                + " where l.environment.id = ?1 order by l.createdAt, l.id",
            PdService.class)
        .setParameter(1, environmentId)
        .getResultList();
  }

  /** The environment ids one service is linked into, oldest link first. Empty for a platform service. */
  public List<String> listEnvironmentIdsOf(String serviceId) {
    return getEntityManager()
        .createQuery(
            "select l.environment.id from PdServiceLink l"
                + " where l.service.id = ?1 order by l.createdAt, l.id",
            String.class)
        .setParameter(1, serviceId)
        .getResultList();
  }

  /** Drops one service's whole link set — the first half of every replacement. */
  public long deleteByService(String serviceId) {
    return delete("service.id = ?1", serviceId);
  }

  /** Drops every link into one environment — what a teardown does before the environment row. */
  public long deleteByEnvironment(String environmentId) {
    return delete("environment.id = ?1", environmentId);
  }
}
