package eu.wohlben.qits.platform.deployments.environments.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One environment — a <b>tier</b>: dev, preprod, prod. A name, the branch whose green builds deploy
 * into it, and the docker network its public nodes share.
 *
 * <p>A tier is created deliberately. Nothing derives one, and creating one here is what makes it
 * exist for the whole platform: deployment resolution fans a green build out over the tiers whose
 * branch matches, qits-idp will grant per-environment claims against it, and a future qits-dns will
 * name it.
 *
 * <p>An environment holds no list of applications, and the absence is the model rather than an
 * omission. What runs in a tier is expressed the other way round — every service is a {@link
 * PdService} with N {@link PdServiceLink}s, and a link is what puts a service in this environment. A
 * platform service has no links at all and is therefore in every environment, including the ones
 * created after it.
 *
 * <p><b>A {@link CausedRow}, and the one entity here the stamp itself fills.</b> A tier is created
 * deliberately, over {@code POST /platform-deployments/api/environments}, and there is no hop
 * between the request thread and {@code persist()} — so the scope {@code CausationServerFilter}
 * restored from the caller's {@code X-Qits-Causation-Id} is still standing and {@link
 * CausationStamp} records it. A bootstrap creating a tier as one step of a longer chain therefore
 * records what it was acting under; an operator's bare {@code curl} records null, which is the
 * right answer rather than a missing one — nothing on the bus caused that tier to exist.
 */
@Entity
@Table(name = "pd_environment")
@EntityListeners(CausationStamp.class)
public class PdEnvironment extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  /** Unique, git-and-dns-safe slug — the environment's identity everywhere a human sees it. */
  @Column(nullable = false, unique = true, length = 64)
  public String name;

  /**
   * The branch this environment listens to. A green build deploys here exactly when its branch
   * equals this value — convention fills it as {@code environment/<name>} when the creator names
   * none.
   */
  @Column(nullable = false)
  public String branch;

  /**
   * This environment's <b>bundle</b> network: the one its public nodes ({@code availableOnEnv})
   * share. It is not where an ordinary service runs — each service gets its own derived {@code
   * qits-env-<env>-<service>} network. Derived networks are never persisted; docker's own labels
   * are the runtime bookkeeping and this schema deliberately holds no copy of them.
   */
  @Column(nullable = false)
  public String network;

  /**
   * <b>The platform environment</b>, of which there is exactly one: the tier whose branch deploys
   * the platform plane. A green build of a {@link PdDeploymentTarget#PLATFORM} service ships only
   * when this environment listens to the built branch — every other tier's branch leaves the one
   * platform instance alone.
   *
   * <p>It is not a link and it does not put anything in this tier. A platform service still belongs
   * to no environment, still keeps the bare wire alias, and is still reachable from every tier. What
   * this flag decides is which branch is allowed to roll it.
   *
   * <p><b>At most one row is true, and the schema does not enforce it</b> — H2 has no partial unique
   * index, so {@link
   * eu.wohlben.qits.platform.deployments.environments.control.EnvironmentService} designates by
   * moving the flag inside one transaction. See V2's comment for why that is the same answer V1
   * reached about null rows.
   */
  @Column(nullable = false)
  public boolean platform;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
