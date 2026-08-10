package eu.wohlben.qits.platform.deployments.environments.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

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
 * <p>{@link #branch} is <b>vestigial</b>: nothing decides a deployment on it any more. See the
 * field.
 *
 * <p><b>A {@link CausedRow}, and the insert-only stamp is exactly right for it.</b> The row is
 * created once, by the green build that first registered the repository, and every later upsert
 * updates it in place — so "which event caused this service to enter the catalogue" is one lasting
 * fact rather than a value that churns. It is filled two ways, because the two writers stand on
 * different threads: derived registration runs on {@code pd-deploy-worker} behind the intake's
 * queue hop, where no ambient scope survives, so {@code ServiceCatalog.upsert} takes the cause as
 * an argument and sets it; an operator's {@code PUT} on the service API reaches the same insert on
 * the request thread, where {@link CausationStamp} fills it from the filter's restored scope.
 */
@Entity
@Table(name = "pd_service")
@EntityListeners(CausationStamp.class)
public class PdService extends PanacheEntityBase implements CausedRow {

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

  /** dns-safe and unique: the network alias, the image path segment, part of a container name. */
  @Column(nullable = false, unique = true, length = 64)
  public String name;

  /** Environment-tiered or platform-plane. Never null; {@code ENVIRONMENT} is the default shape. */
  @Enumerated(EnumType.STRING)
  @Column(name = "deployment_target", nullable = false, length = 32)
  public PdDeploymentTarget deploymentTarget = PdDeploymentTarget.ENVIRONMENT;

  /**
   * <b>Vestigial.</b> It held a platform service's own deploy branch, back when the platform plane
   * had a deploy ref of its own ({@code platform/main}). Both planes deploy off {@code
   * environment/<name>} now — a green build deploys wherever an <i>environment</i> listens to its
   * branch — so nothing reads this to decide anything, and derived registration writes null.
   *
   * <p>The column stays, and so does the field: the value is on the read surface ({@code
   * PdServiceDto}, {@code PdApplicationDto}) and the client renders it, so dropping it here would
   * change a contract with another repository for no gain. It is nullable, so no migration is owed
   * — this is a value that stops being written, not a column that has to go.
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
   * port). Null means the deploy-time default ({@code
   * qits.platform.deployments.default-health-path}) — registration writes the derived convention
   * path instead, so null only ever reaches a row nothing has registered since.
   */
  @Column(name = "health_path")
  public String healthPath;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
