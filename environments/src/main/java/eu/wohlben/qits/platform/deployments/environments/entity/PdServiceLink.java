package eu.wohlben.qits.platform.deployments.environments.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * "This service runs in this environment." The whole of the topology is these rows.
 *
 * <p>A link says nothing about how the service runs — that is on {@link PdService}, once, for every
 * environment it is linked into. What a link adds is presence, and only presence: the deploy
 * orchestration asks which services are linked into an environment and reconciles the running
 * containers and networks against the answer.
 *
 * <p><b>Platform services have none.</b> No link means no particular environment, which the link
 * query reads as every environment — see {@link PdDeploymentTarget}. So an upsert that gives a
 * platform service links is refused rather than stored: the row would say the opposite of what it
 * means.
 *
 * <p>The link set of an environment service is <b>replaced</b> on every upsert, never merged. The
 * writer knows the whole set (it read the repository's own spec); a merge would keep a link to an
 * environment the repository has stopped naming, and nothing would ever remove it.
 *
 * <p><b>{@code @Uncaused} by decision, and the replace-never-merge rule above is the reason.</b>
 * Every upsert deletes this row and inserts it again, so a causation column here would not record
 * what caused the link to exist — it would record the most recent build that restated it, an
 * update timestamp wearing a trace column's name. The two questions worth asking are answered next
 * door: {@link PdService} carries the event that put the service in the catalogue, and each {@code
 * PdDeployment} carries the event that rolled it out.
 */
@Entity
@Uncaused
@Table(
    name = "pd_service_link",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_pd_service_link",
            columnNames = {"service_id", "environment_id"}))
public class PdServiceLink extends PanacheEntityBase {

  @Id public String id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "service_id", nullable = false)
  public PdService service;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "environment_id", nullable = false)
  public PdEnvironment environment;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
