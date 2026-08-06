package eu.wohlben.qits.platform.deployments.environments.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

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
 */
@Entity
@Table(name = "pd_environment")
public class PdEnvironment extends PanacheEntityBase {

  @Id public String id;

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

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
