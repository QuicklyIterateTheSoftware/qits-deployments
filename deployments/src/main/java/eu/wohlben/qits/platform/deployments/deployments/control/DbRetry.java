package eu.wohlben.qits.platform.deployments.deployments.control;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.hibernate.exception.JDBCConnectionException;
import org.jboss.logging.Logger;

/**
 * A bounded retry for the deploy worker's own database access, on <b>connection-class failures
 * only</b>.
 *
 * <p><b>The failure it exists for is self-inflicted.</b> This component deploys qits-oci-postgresql
 * — the postgres its own registry lives in — so a cutover of that application kills every
 * connection this process is holding, in the middle of the very deployment that performed it. That
 * is exactly what happened: the container cut over cleanly and went healthy, and then the post-gate
 * bookkeeping (read the prior ACTIVE rows, decommission them, mark this one ACTIVE) ran on dead
 * connections and ended the deployment {@code FAILED: [unexpected: JDBCConnectionException …]}. The
 * database was back seconds later. Nothing was wrong except that nobody asked twice.
 *
 * <p><b>Narrow on purpose, in three ways.</b>
 *
 * <ul>
 *   <li><b>Only connection-class failures retry.</b> A constraint violation, a missing row, a bug —
 *       anything that would fail identically on the second attempt — is rethrown at once. Retrying
 *       business logic would turn one visible failure into a slow one.
 *   <li><b>The budget is small</b> ({@value #BUDGET_SECONDS}s of short sleeps). It covers a
 *       container restarting, not an outage; a database that is still gone after that is a failure
 *       worth recording.
 *   <li><b>The worker is single-threaded</b> ({@code pd-deploy-worker}), which is what makes
 *       sleeping in it safe: nothing else is queued behind a lock, and the next event simply waits.
 *       Do not lift this onto a request thread.
 * </ul>
 *
 * <p>The retried block must be re-runnable. Every caller in {@link DeployService} is: they re-read
 * the entities they touch and set them to the same values. A bracket that <i>inserts</i> a row is
 * deliberately NOT retried — a commit whose outcome the connection died before reporting would be
 * duplicated — and it does not need to be: those all run before anything docker-side has happened,
 * so the event is dropped with nothing half-done and a resend replays it.
 */
final class DbRetry {

  private static final Logger LOG = Logger.getLogger(DbRetry.class);

  /** How long a self-inflicted blip may last before it is a failure worth recording. */
  static final long BUDGET_SECONDS = 30;

  private static final Duration BUDGET = Duration.ofSeconds(BUDGET_SECONDS);
  private static final Duration PAUSE = Duration.ofMillis(500);

  /**
   * SQLState classes that mean "the connection", not "the statement": {@code 08xxx} is the standard
   * connection-exception class, and postgres' {@code 57P0x} is the server telling a client it is
   * shutting down or has terminated its backend — which is a postgres cutover, seen from here.
   */
  private static final List<String> CONNECTION_STATES = List.of("08", "57P01", "57P02", "57P03");

  /**
   * The wordings left over when nothing in the chain carries a SQLState — chiefly the connection
   * pool's own acquisition timeout, which is what a datasource whose server is gone answers with.
   * Matched against {@link SQLException} messages only, never against arbitrary throwables, so a
   * business failure that happens to say "connection" somewhere is not swept in.
   */
  private static final List<String> CONNECTION_MARKERS =
      List.of(
          "acquisition timeout",
          "connection is closed",
          "connection has been closed",
          "connection reset",
          "no connection currently available",
          "i/o error occurred while sending to the backend",
          "terminating connection",
          "the connection attempt failed");

  private DbRetry() {}

  static void run(String what, Runnable action) {
    call(
        what,
        () -> {
          action.run();
          return null;
        });
  }

  static <T> T call(String what, Supplier<T> action) {
    return call(what, action, BUDGET, PAUSE);
  }

  /** Package-private with the budget handed in, so the test does not spend thirty seconds. */
  static <T> T call(String what, Supplier<T> action, Duration budget, Duration pause) {
    long deadline = System.nanoTime() + budget.toNanos();
    int attempt = 0;
    while (true) {
      attempt++;
      try {
        T answer = action.get();
        if (attempt > 1) {
          LOG.infof("%s succeeded on attempt %d — the datasource came back", what, attempt);
        }
        return answer;
      } catch (RuntimeException e) {
        if (!isConnectionFailure(e) || System.nanoTime() >= deadline) {
          throw e;
        }
        LOG.warnf(
            "%s lost its database connection (attempt %d): %s — retrying within %ds",
            what, attempt, e.getMessage(), budget.toSeconds());
      }
      try {
        Thread.sleep(pause.toMillis());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(what + " was interrupted while waiting for its database");
      }
    }
  }

  /**
   * Whether anything in the cause chain says the connection failed rather than the statement.
   * Package-private for the test that pins each shape.
   */
  static boolean isConnectionFailure(Throwable thrown) {
    Throwable cause = thrown;
    // Bounded rather than "until null": a cause chain that loops would otherwise hang the worker,
    // and no real one is thirty deep.
    for (int depth = 0; cause != null && depth < 30; depth++, cause = cause.getCause()) {
      if (cause instanceof JDBCConnectionException
          || cause instanceof SQLTransientConnectionException
          || cause instanceof SQLNonTransientConnectionException
          || cause instanceof SQLRecoverableException) {
        return true;
      }
      if (cause instanceof SQLException sql && isConnectionSql(sql)) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  private static boolean isConnectionSql(SQLException sql) {
    String state = sql.getSQLState();
    if (state != null && CONNECTION_STATES.stream().anyMatch(state::startsWith)) {
      return true;
    }
    String message = sql.getMessage() == null ? "" : sql.getMessage().toLowerCase(Locale.ROOT);
    return !message.isEmpty() && CONNECTION_MARKERS.stream().anyMatch(message::contains);
  }
}
