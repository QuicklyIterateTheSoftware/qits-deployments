package eu.wohlben.qits.platform.deployments.deployments.entity;

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
 * One attempt to put one commit of one application live. Created {@code QUEUED} by the
 * build-succeeded intake, driven to a terminal state by the deploy worker; the previously {@code
 * ACTIVE} deployment of the same application becomes {@code DECOMMISSIONED} the moment its
 * replacement passes the health gate — never before.
 *
 * <p><b>It names its application and its tier by string, with no FK</b>, even though the topology
 * lives two tables away in the same database. Deployment history outlives the rows that described
 * it: a service removed from the catalogue, or a tier torn down, must not take its history with it,
 * and the rollback pins read off these rows must keep answering whatever the topology says today.
 *
 * <p><b>A {@link CausedRow}, and the one this component exists to trace.</b> A deployment happens
 * <em>because</em> a build went green, so {@link #causationId} names the {@code BuildSuccessful}
 * that caused it and a reader can walk from a container back into the event chain that started it.
 *
 * <p><b>The value is set EXPLICITLY, never left to the stamp</b>, and that is not a preference. The
 * intake hands the whole event to {@code pd-deploy-worker} and returns; an executor hop is exactly
 * where an ambient {@code CausationScope} dies, so the {@code CausationStamp} listener would read
 * null on every row this component writes. The cause therefore crosses the seam as data — a plain
 * {@code UUID} on {@code BuildAnnouncements.announce} — the same way {@link #runId} has always
 * crossed it. The listener stays attached because a value the author set is what it yields to, and
 * because a future writer standing in a scope should be stamped rather than silently rootless.
 *
 * <p>{@link #runId} stays what it is: the pointer into qits-ci's history, resolved against nothing.
 * The two answer different questions — "which pipeline produced the image" and "which event on the
 * bus caused this attempt" — and a manual replay through the HTTP intake can carry either without
 * the other.
 */
@Entity
@Table(name = "pd_deployment")
@EntityListeners(CausationStamp.class)
public class PdDeployment extends PanacheEntityBase implements CausedRow {

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
   * The public path prefixes this deployment was performed with, in the spec's own comma-separated
   * spelling ({@code /artifacts,/v2}) — see V3's header for why the row holds a spec value at all
   * when V1's says it does not.
   *
   * <p>Null or empty is an application that declares no public routes, which is most of them. The
   * column that says "this row predates the snapshot" is {@link #upstreamPort}, not this one.
   */
  @Column(name = "routes", columnDefinition = "text")
  public String routes;

  /**
   * The port the routes reach this deployment on, and <b>the sentinel for the whole snapshot</b>:
   * the spec always resolves a port, so a null here means the row was queued by a build that had no
   * routing columns to fill and never means "no port". {@code DeployService} reads exactly that.
   */
  @Column(name = "upstream_port")
  public Integer upstreamPort;

  /** The navigation label the PRIMARY route carries, or null when it creates no menu option. */
  @Column(name = "navigation_label", length = 64)
  public String navigationLabel;

  /** Where that label sits in the platform's navigation; null whenever the label is. */
  @Column(name = "navigation_position")
  public Integer navigationPosition;

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
