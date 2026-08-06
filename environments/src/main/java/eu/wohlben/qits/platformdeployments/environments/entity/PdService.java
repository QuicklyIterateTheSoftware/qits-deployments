package eu.wohlben.qits.platformdeployments.environments.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One deployable service of the platform, named once for the whole platform rather than once per
 * tier. The name is load-bearing three times over: it is the image path in the OCI registry ({@code
 * <repository>/<name>:<sha>}), the network alias peers resolve, and part of every container name
 * the deploy orchestration derives from it.
 *
 * <p><b>There is one row per service, not one per (service, environment).</b> Which tiers a service
 * runs in is said by its {@link PdServiceLink}s, and that is what lets a reader ask both questions
 * — "what is this service" and "where does it run" — without joining a name to itself across
 * environments.
 *
 * <p>Rows here are <b>derived</b>: a green build carries the deploy orchestration to the
 * repository's {@code .config/qits/deployments.yml} at that sha, and the row is created or brought
 * up to date from what it found. Nothing registers a service by hand, and nothing needs to — a
 * repository that has never been built simply has no row.
 *
 * <p>{@link #branch} belongs to platform services only, because they have no environment to take
 * one from; an environment service takes its branch from each environment it is linked into.
 * Storing one here as well would be a second, drifting answer.
 */
@Entity
@Table(name = "pd_service")
public class PdService extends PanacheEntityBase {

  @Id public String id;

  /** dns-safe and unique: the network alias, the image path segment, part of a container name. */
  @Column(nullable = false, unique = true, length = 64)
  public String name;

  /** Environment-tiered or platform-plane. Never null; {@code ENVIRONMENT} is the default shape. */
  @Enumerated(EnumType.STRING)
  @Column(name = "deployment_target", nullable = false, length = 32)
  public PdDeploymentTarget deploymentTarget = PdDeploymentTarget.ENVIRONMENT;

  /**
   * The branch whose green builds deploy this service — <b>platform services only</b> ({@code main}
   * by convention). Null on every environment service, and left null even when an upsert states
   * one, the same way the spec parser accepts and ignores a {@code branch:} beside {@code
   * deployment_target: environment}.
   */
  @Column(length = 255)
  public String branch;

  /**
   * A public node of the environments it is linked into: it joins each environment's bundle network
   * and every one of that environment's per-service networks, so it can reach every service and
   * every service can reach it. Today that is qits-gateway and nothing else — cross-service traffic
   * is meant to flow service → gateway → target service.
   */
  @Column(name = "available_on_env", nullable = false)
  public boolean availableOnEnv;

  /**
   * The path the health gate probes on a fresh container, at port 8080 (the platform's one exposed
   * port). Null means the deploy-time default ({@code qits.pd.default-health-path}) — registration
   * writes the derived convention path instead, so null only ever reaches a row nothing has
   * registered since.
   */
  @Column(name = "health_path")
  public String healthPath;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
