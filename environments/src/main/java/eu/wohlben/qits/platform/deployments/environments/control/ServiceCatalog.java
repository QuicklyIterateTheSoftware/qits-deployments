package eu.wohlben.qits.platform.deployments.environments.control;

import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import eu.wohlben.qits.platform.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.platform.deployments.environments.entity.PdService;
import eu.wohlben.qits.platform.deployments.environments.entity.PdServiceLink;
import eu.wohlben.qits.platform.deployments.environments.error.BadRequestException;
import eu.wohlben.qits.platform.deployments.environments.error.ConflictException;
import eu.wohlben.qits.platform.deployments.environments.error.NotFoundException;
import eu.wohlben.qits.platform.deployments.environments.persistence.PdEnvironmentRepository;
import eu.wohlben.qits.platform.deployments.environments.persistence.PdServiceLinkRepository;
import eu.wohlben.qits.platform.deployments.environments.persistence.PdServiceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The catalogue of services and the environments they are linked into — the topology's other half,
 * and the one the deploy orchestration writes to.
 *
 * <p>Writes arrive as one operation, {@link #upsert}, because the writer is derived: a green build
 * reads a repository's {@code .config/qits/deployments.yml} at that sha and states the whole shape
 * it found. There is no create/update pair and no partial write — a caller that knows the file
 * knows everything about the service, so a merge could only ever preserve something the file has
 * stopped saying.
 *
 * <p>Three rules live here and nowhere else:
 *
 * <ul>
 *   <li><b>The link set is replaced, never merged.</b> An environment service's links are exactly
 *       the environments the upsert names.
 *   <li><b>A platform service carries no links.</b> An upsert that gives one links is a 400, not a
 *       silent drop: the caller and this service disagree about what the row means, and storing
 *       either reading would hide it.
 *   <li><b>The target flip is one-way.</b> environment → platform converts, dropping the links;
 *       platform → environment is refused with the remediation in the message. See {@link #upsert}.
 * </ul>
 */
@ApplicationScoped
public class ServiceCatalog {

  private static final Logger LOG = Logger.getLogger(ServiceCatalog.class);

  @Inject PdServiceRepository services;
  @Inject PdServiceLinkRepository links;
  @Inject PdEnvironmentRepository environments;

  /**
   * What an upsert states. {@code branch} is <b>vestigial</b> — nothing decides a deployment on it
   * any more, both planes deploying off {@code environment/<name>} — and derived registration sends
   * null. It is still stored beside {@code PLATFORM} and still dropped beside {@code ENVIRONMENT},
   * so an operator's write over the API round-trips as it always did. See {@code PdService.branch}.
   */
  public record Upsert(
      String name,
      PdDeploymentTarget target,
      String branch,
      boolean availableOnEnv,
      String healthPath,
      List<String> environmentIds) {}

  /** A service together with the environments it is linked into (empty for a platform service). */
  public record LinkedService(PdService service, List<String> environmentIds) {}

  /**
   * What an upsert did. {@code created} is reported rather than inferred by the caller: an
   * "exists?" read outside the write's own transaction would be a second, racing answer, and the
   * only thing it decides is 201 against 200.
   */
  public record UpsertResult(LinkedService service, boolean created) {}

  /**
   * One service as the flat read surface reports it: a row flattened into one tier. {@code
   * environmentId} and {@code environmentName} are null exactly for a platform service, and for an
   * environment service the catalogue currently links nowhere.
   */
  public record ApplicationView(PdService service, String environmentId, String environmentName) {}

  /**
   * Register or update one service, whole.
   *
   * <p><b>Synchronized, and that is load-bearing.</b> "Is there a row for this name yet, and if not
   * make one" is a read-then-write with no constraint able to turn a lost race into anything but a
   * 500 — and the writer fans a green build out over every environment tracking a branch, so it can
   * arrive twice at once. The deploy worker is single-threaded for exactly this reason and this
   * lock is the belt for every other caller; it costs nothing, since an upsert is three short
   * statements against one local database.
   *
   * <p>The flip between planes is asymmetric on purpose:
   *
   * <ul>
   *   <li><b>environment → platform converts.</b> The links are dropped and the row keeps its
   *       identity, which is exactly the one-time migration a service goes through when it becomes
   *       cross-environment.
   *   <li><b>platform → environment is a 409.</b> There is no answer to which environments a
   *       service that was everywhere should now be in — the upsert states a set, but a platform
   *       service became one by having its old set thrown away, and reinstating a guess would
   *       deploy a second copy beside the running one. The message names the remediation, and an
   *       operator does it deliberately: delete the service, then let the next green build register
   *       it afresh.
   * </ul>
   *
   * @throws BadRequestException on a failed validation, or on a platform service given links
   * @throws NotFoundException if an environment id names no environment
   * @throws ConflictException on a platform → environment flip
   */
  public synchronized UpsertResult upsert(Upsert request) {
    String name = PdIdentifiers.requireName(request.name(), "service name");
    PdDeploymentTarget target = request.target();
    if (target == null) {
      throw new BadRequestException("Missing deploymentTarget — ENVIRONMENT or PLATFORM");
    }
    List<String> requestedEnvironments =
        request.environmentIds() == null ? List.of() : request.environmentIds();
    String healthPath =
        isBlank(request.healthPath()) ? null : PdIdentifiers.requireHealthPath(request.healthPath());

    if (target == PdDeploymentTarget.PLATFORM && !requestedEnvironments.isEmpty()) {
      throw new BadRequestException(
          "A platform service carries no environment links — it is present in every environment by"
              + " having none. Send environmentIds only with deploymentTarget ENVIRONMENT.");
    }
    // Vestigial, and kept only so an operator's write round-trips: a branch stated beside
    // ENVIRONMENT is accepted and dropped rather than refused.
    String branch =
        target == PdDeploymentTarget.PLATFORM && !isBlank(request.branch())
            ? PdIdentifiers.requireBranch(request.branch())
            : null;

    // Deduplicated in request order: naming an environment twice states the same link twice, which
    // is a caller's redundancy rather than an error, and the unique constraint would otherwise turn
    // it into a 500.
    List<String> environmentIds = new ArrayList<>(new LinkedHashSet<>(requestedEnvironments));

    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdService service = services.findByName(name).orElse(null);
              if (service != null
                  && service.deploymentTarget == PdDeploymentTarget.PLATFORM
                  && target == PdDeploymentTarget.ENVIRONMENT) {
                LOG.errorf("Refused to flip platform service '%s' back to an environment service", name);
                throw new ConflictException(
                    "Service '"
                        + name
                        + "' is a platform service and cannot become an environment service. A"
                        + " platform service has no environments to go back to, and a guessed set"
                        + " would deploy a second copy beside the running one. Remediation: remove"
                        + " this service deliberately, and let the next green build register it"
                        + " afresh.");
              }
              boolean created = service == null;
              if (created) {
                service = new PdService();
                service.id = UUID.randomUUID().toString();
                service.name = name;
                service.createdAt = Instant.now();
                services.persist(service);
              } else if (service.deploymentTarget == PdDeploymentTarget.ENVIRONMENT
                  && target == PdDeploymentTarget.PLATFORM) {
                LOG.infof("Converting '%s' to a platform service — dropping its environment links", name);
              }
              service.deploymentTarget = target;
              service.branch = branch;
              service.availableOnEnv = request.availableOnEnv();
              service.healthPath = healthPath;

              // Replace, never merge. A converting service lands here with an empty set, which is
              // what drops its links.
              links.deleteByService(service.id);
              for (String environmentId : environmentIds) {
                PdEnvironment environment =
                    environments
                        .findByIdOptional(environmentId)
                        .orElseThrow(
                            () -> new NotFoundException("No such environment: " + environmentId));
                PdServiceLink link = new PdServiceLink();
                link.id = UUID.randomUUID().toString();
                link.service = service;
                link.environment = environment;
                link.createdAt = Instant.now();
                links.persist(link);
              }
              return new UpsertResult(new LinkedService(service, environmentIds), created);
            });
  }

  /** Remove a service and its links. The deliberate act the refused flip's message points at. */
  public void delete(String name) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdService service = require(name);
              links.deleteByService(service.id);
              services.delete(service);
            });
  }

  /**
   * <b>Every read below brackets itself with {@link QuarkusTransaction#joiningExisting()}</b>, and
   * that is not decoration. A JAX-RS caller has a request context and Hibernate would answer a read
   * without a transaction — but the catalogue's other caller is the deploy worker, a bare daemon
   * thread with neither, and there the same call throws {@code ContextNotActiveException}. That
   * hazard is new with the merge: derived registration used to reach the catalogue over HTTP, which
   * needs no session at all. Joining rather than requiring a new one keeps a caller that already
   * has a transaction ({@link #delete}) in it, so the entity it reads stays managed.
   */

  /** Every service, oldest first, each with the environments it is linked into. */
  public List<LinkedService> list() {
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              List<LinkedService> catalogue = new ArrayList<>();
              for (PdService service : services.listOldestFirst()) {
                catalogue.add(new LinkedService(service, links.listEnvironmentIdsOf(service.id)));
              }
              return List.copyOf(catalogue);
            });
  }

  /** The one row a name can have, or empty — the deploy orchestration's read half. */
  public Optional<LinkedService> find(String name) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () ->
                services
                    .findByName(name)
                    .map(
                        service ->
                            new LinkedService(service, links.listEnvironmentIdsOf(service.id))));
  }

  public PdService require(String name) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () ->
                services
                    .findByName(name)
                    .orElseThrow(() -> new NotFoundException("No such service: " + name)));
  }

  /**
   * The pull query: every service present in one environment — the ones linked into it, then every
   * platform service.
   *
   * <p>The composition <b>is</b> the answer, not a convenience. A reader that took the links alone
   * would leave qits-idp and this component out of the environment they are most needed in, and a
   * reader that had to add the platform services itself would be a second place the rule lives.
   * They come last so the list reads as "this tier's own, then the platform's".
   *
   * @throws NotFoundException if the environment does not exist
   */
  public List<PdService> linksOf(String environmentId) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              environments
                  .findByIdOptional(environmentId)
                  .orElseThrow(
                      () -> new NotFoundException("No such environment: " + environmentId));
              List<PdService> present = new ArrayList<>(links.listServicesOf(environmentId));
              present.addAll(services.listPlatformServices());
              return List.copyOf(present);
            });
  }

  /**
   * The services of one environment as the environment aggregate reports them: the tier's own,
   * <b>without</b> the platform ones.
   *
   * <p>{@link #linksOf} returns both — a reconciliation needs the platform services too — but this
   * is the environment aggregate, and a platform service belongs to no tier. Those are reached
   * through the flat listing, which is why that listing exists.
   */
  public List<ApplicationView> applicationsOf(PdEnvironment environment) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              List<ApplicationView> scoped = new ArrayList<>();
              for (PdService service : links.listServicesOf(environment.id)) {
                scoped.add(new ApplicationView(service, environment.id, environment.name));
              }
              return List.copyOf(scoped);
            });
  }

  /**
   * Every application this component deploys, flat: one row per environment link, one row per
   * platform service.
   *
   * <p>Flat because a platform service belongs to no environment — reading the catalogue through
   * the environments would leave qits-idp and this component out of it, which are the two a reader
   * most wants to find.
   */
  public List<ApplicationView> allApplications() {
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              Map<String, String> environmentNames = new LinkedHashMap<>();
              for (PdEnvironment environment : environments.listNewestFirst()) {
                environmentNames.put(environment.id, environment.name);
              }
              List<ApplicationView> views = new ArrayList<>();
              for (LinkedService linked : list()) {
                if (linked.service().deploymentTarget == PdDeploymentTarget.PLATFORM
                    || linked.environmentIds().isEmpty()) {
                  views.add(new ApplicationView(linked.service(), null, null));
                  continue;
                }
                for (String environmentId : linked.environmentIds()) {
                  views.add(
                      new ApplicationView(
                          linked.service(), environmentId, environmentNames.get(environmentId)));
                }
              }
              return List.copyOf(views);
            });
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
