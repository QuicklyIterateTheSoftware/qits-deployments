package eu.wohlben.qits.platformdeployments.environments.persistence;

import eu.wohlben.qits.platformdeployments.environments.entity.PdEnvironment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link PdEnvironment} (keyed by its String UUID row id). */
@ApplicationScoped
public class PdEnvironmentRepository implements PanacheRepositoryBase<PdEnvironment, String> {

  public Optional<PdEnvironment> findByName(String name) {
    return find("name = ?1", name).firstResultOptional();
  }

  /** All environments, newest-first. */
  public List<PdEnvironment> listNewestFirst() {
    return list("order by createdAt desc, id desc");
  }

  /**
   * Every environment listening to exactly this branch — what a green build fans out over. Usually
   * one; two tiers may legitimately track the same ref.
   */
  public List<PdEnvironment> listByBranch(String branch) {
    return list("branch = ?1 order by createdAt, id", branch);
  }
}
