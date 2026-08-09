package eu.wohlben.qits.platform.deployments.deployments.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One attempt to put one commit of one application live. Created {@code QUEUED} by the
 * build-succeeded intake, driven to a terminal state by the deploy worker; the previously {@code
 * ACTIVE} deployment of the same application becomes {@code DECOMMISSIONED} the moment its
 * replacement passes the health gate — never before.
 *
 * <p><b>It names its application and its tier by string, with no FK</b>, even though the topology
 * lives two tables away in the same database. Deployment history outlives the rows that described
 * it: a service removed from the catalogue, or a tier torn down, must not take its history with it,
 * and the rollback pins read off these rows must keep answering whatever the topology says today.
 */
@Entity
@Table(name = "pd_deployment")
public class PdDeployment extends PanacheEntityBase {

  @Id public String id;

  /** The service this deployed, by name — the catalogue's own identity for it. */
  @Column(name = "application_name", nullable = false, length = 64)
  public String applicationName;

  /** The tier it was deployed into, or null for a platform deployment. */
  @Column(name = "environment_id")
  public String environmentId;

  @Column(name = "commit_sha", nullable = false, length = 64)
  public String commitSha;

  /**
   * The qits-ci run whose green build caused this deployment, as the intake received it — the one
   * pointer back into the pipeline that produced the image, and nothing this component ever
   * resolves itself (no FK, the repo_id stance). Null on a sender that omits it, and on anything
   * queued while running an older build; a reader must render that absence rather than invent a
   * link.
   */
  @Column(name = "run_id")
  public String runId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public PdDeploymentStatus status;

  /**
   * The container this deployment started (named after the deployment, not the sha, so re-deploying
   * the same commit never collides). Null until the worker actually ran {@code docker run}, and on
   * every deployment that failed before one existed.
   */
  @Column(name = "container_name")
  public String containerName;

  /** What went wrong (docker's own output, bounded), or null on the happy path. */
  @Column(columnDefinition = "text")
  public String detail;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "finished_at")
  public Instant finishedAt;

  /**
   * The listing tiebreak, assigned by the database (V1's identity column) and never written here —
   * which is why it reads null on a freshly persisted instance.
   *
   * <p>It exists because {@code createdAt} is not unique: two rows recorded in the same tick tied,
   * and the secondary sort was the random-UUID id, so a listing swapped them arbitrarily between
   * calls. This is monotonic, so "newest first" is one answer rather than a coin flip.
   */
  @Column(name = "seq", insertable = false, updatable = false)
  public Long seq;
}
