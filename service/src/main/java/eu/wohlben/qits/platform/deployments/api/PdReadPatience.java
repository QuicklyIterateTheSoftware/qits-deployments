package eu.wohlben.qits.platform.deployments.api;

import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Hold a caller's read through a short postgres outage instead of answering it wrong.
 *
 * <p>This component deploys the postgres its own catalogue lives in, so a cutover of
 * qits-oci-postgresql kills every connection this process holds — including the ones a GET is
 * halfway through. {@link eu.wohlben.qits.db.PatientPgDriver} already holds a request that has not
 * opened a connection yet; what it cannot help is a request whose connection died <em>after</em>
 * statements ran. Re-running is safe for a read and only for a read, so this is a read-only wrapper
 * and every write on this surface is deliberately left alone.
 *
 * <p><b>Why it is a bean the CONTROLLERS call, rather than a wrap inside {@code ServiceCatalog} and
 * {@code EnvironmentService}.</b> Those reads have three kinds of caller and only one of them may
 * sleep:
 *
 * <ul>
 *   <li><b>Inside an open transaction.</b> {@code ServiceCatalog.delete} calls {@code require},
 *       {@code ServiceCatalog.allApplications} calls {@code list}, {@code EnvironmentService}'s
 *       {@code update}/{@code delete} call {@code require}, and {@code BuildTips} calls {@code
 *       onBranch} from inside two {@code requiringNew} brackets. A retry there would sleep holding a
 *       transaction, which is the one placement the platform's db-patience rules forbid.
 *   <li><b>Inside a monitor.</b> {@code BuildTips.claim} is {@code synchronized} and reaches {@code
 *       onBranch} under it. Sleeping there stalls the other delivery channel. ({@code
 *       ServiceCatalog.upsert} is {@code synchronized} too, but it is a write and no read of that
 *       class shares its monitor.)
 *   <li><b>On the deploy worker.</b> Those reads are already wrapped, at {@code
 *       DeployService.CUTOVER_BUDGET} — thirty seconds rather than this deadline, because the worker
 *       waits out an outage it caused itself. Wrapping inside the read as well would nest one budget
 *       in the other.
 * </ul>
 *
 * <p>So the wrap goes where the caller is a person or a client holding an HTTP request open: the
 * REST layer, outside every transaction and every monitor. A new read endpoint on this surface
 * belongs in it.
 *
 * <p>The deadline is {@code qits.platform.deployments.db-retry-deadline} (15S shipped) — a request
 * held that long is the trade being made, and it stays under any sane client timeout. Connection-
 * class failures only: a 404 or a validation failure is rethrown on the first attempt.
 */
@ApplicationScoped
public class PdReadPatience {

  /**
   * How long a read may be held while the database comes back. No {@code defaultValue} here on
   * purpose — the shipped value is a line in {@code application.properties}, so there is one
   * spelling of it and a deployment overrides it by env like every other key.
   */
  @ConfigProperty(name = "qits.platform.deployments.db-retry-deadline")
  Duration deadline;

  /**
   * @param what names the read in the log — it is read by a person after an outage
   */
  public <T> T call(String what, Supplier<T> read) {
    return DbRetry.call(what, read, deadline);
  }

  /** The same, for a read whose answer the caller drops (an existence check). */
  public void run(String what, Runnable read) {
    DbRetry.run(what, read, deadline);
  }
}
