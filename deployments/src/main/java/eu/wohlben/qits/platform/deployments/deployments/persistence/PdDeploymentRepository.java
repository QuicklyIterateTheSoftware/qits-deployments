package eu.wohlben.qits.platform.deployments.deployments.persistence;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Panache DAO for {@link PdDeployment}.
 *
 * <p>Every listing orders by {@code seq desc} — V1's identity column — rather than by {@code
 * createdAt desc, id desc}. The id is a random UUID, so the older tiebreak swapped two rows
 * recorded in the same tick at random, which is exactly what the deployments of one
 * build-succeeded event are.
 *
 * <p>Where a tier is matched, {@code null} is tested as a value rather than compared: a platform
 * deployment belongs to no tier, and {@code environment_id = null} matches nothing at all in SQL.
 * The startup sweep's adoption of a self-update row hangs on getting that right.
 */
@ApplicationScoped
public class PdDeploymentRepository implements PanacheRepositoryBase<PdDeployment, String> {

  /** An environment's deployments across all its applications, newest-first. */
  public List<PdDeployment> listByEnvironmentNewestFirst(String environmentId) {
    return list("environmentId = ?1 order by seq desc", environmentId);
  }

  /**
   * The platform plane's deployments across all its applications, newest-first — the rows no
   * environment filter can reach, because they belong to no tier.
   */
  public List<PdDeployment> listPlatformNewestFirst() {
    return list("environmentId is null order by seq desc");
  }

  /**
   * Every deployment on this instance, newest-first — the whole history the pin rule reads
   * ({@code RollbackPins}). Unscoped on purpose: a pin is per application name across all tiers.
   */
  public List<PdDeployment> listAllNewestFirst() {
    return list("order by seq desc");
  }

  /** Every deployment of one application in one tier ({@code null} environment = the platform). */
  public List<PdDeployment> listByApplication(String applicationName, String environmentId) {
    return environmentId == null
        ? list("applicationName = ?1 and environmentId is null", applicationName)
        : list("applicationName = ?1 and environmentId = ?2", applicationName, environmentId);
  }

  /** Every environment-scoped deployment of one application — what a platform conversion absorbs. */
  public List<PdDeployment> listEnvironmentScoped(String applicationName) {
    return list("applicationName = ?1 and environmentId is not null", applicationName);
  }

  /** The application's currently serving deployment(s) in one tier — by invariant at most one. */
  public List<PdDeployment> listActiveByApplication(String applicationName, String environmentId) {
    return environmentId == null
        ? list(
            "applicationName = ?1 and environmentId is null and status = ?2",
            applicationName,
            PdDeploymentStatus.ACTIVE)
        : list(
            "applicationName = ?1 and environmentId = ?2 and status = ?3",
            applicationName,
            environmentId,
            PdDeploymentStatus.ACTIVE);
  }

  /**
   * One application's whole history, newest-first — what a build falls back to when the spec at
   * that sha cannot be read at all.
   */
  public List<PdDeployment> listByApplicationNewestFirst(String applicationName) {
    return list("applicationName = ?1 order by seq desc", applicationName);
  }

  public List<PdDeployment> listByStatus(PdDeploymentStatus status) {
    return list("status = ?1", status);
  }
}
