package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.environments.error.ConflictException;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * The retry in isolation.
 *
 * <p><b>Why it is not a flow test.</b> The three fakes stand in for docker, the git host and the
 * platform's postgres — the seams that leave the process. The datasource is not one of them: the
 * suite runs against a real embedded postgres and the repositories are Panache, so "this repository
 * throws a connection failure once and then succeeds" cannot be scripted without mocking Hibernate,
 * which would assert the mock's model of a lost connection rather than the behaviour. The failure
 * shapes are pinned here instead, one per real exception the platform produced.
 */
class DbRetryTest {

  private static final Duration BUDGET = Duration.ofMillis(300);
  private static final Duration PAUSE = Duration.ofMillis(5);

  /** What Hibernate wraps a dead connection in — the exact shape deployment eaa34fbc failed with. */
  private static RuntimeException lostConnection() {
    return new JDBCConnectionException(
        "Unable to acquire JDBC Connection",
        new PSQLException(
            "An I/O error occurred while sending to the backend.", PSQLState.CONNECTION_FAILURE));
  }

  @Test
  void aConnectionThatComesBackIsRetriedRatherThanFailed() {
    AtomicInteger attempts = new AtomicInteger();

    String answer =
        DbRetry.call(
            "the cutover bookkeeping",
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw lostConnection();
              }
              return "recorded";
            },
            BUDGET,
            PAUSE);

    assertEquals("recorded", answer);
    assertEquals(3, attempts.get());
  }

  @Test
  void aBusinessFailureIsNotRetriedAtAll() {
    // The narrowness IS the feature: anything that would fail identically on the second attempt is
    // rethrown at once, so one visible failure never becomes a slow one.
    AtomicInteger attempts = new AtomicInteger();
    ConflictException thrown = new ConflictException("that name is taken");

    ConflictException caught =
        assertThrows(
            ConflictException.class,
            () ->
                DbRetry.call(
                    "a write",
                    () -> {
                      attempts.incrementAndGet();
                      throw thrown;
                    },
                    BUDGET,
                    PAUSE));

    assertSame(thrown, caught);
    assertEquals(1, attempts.get());
  }

  @Test
  void aConstraintViolationIsNotAConnectionFailureEitherThoughBothArriveAsSqlExceptions() {
    // A unique-name collision is 23505 — an integrity violation, not a connection one — and the
    // second attempt would fail exactly as the first did.
    assertFalse(
        DbRetry.isConnectionFailure(
            new PersistenceException(
                new SQLException("duplicate key value violates unique constraint", "23505"))));
  }

  @Test
  void aDatabaseThatStaysGoneFailsWhenTheBudgetRunsOut() {
    AtomicInteger attempts = new AtomicInteger();

    assertThrows(
        JDBCConnectionException.class,
        () ->
            DbRetry.call(
                "the cutover bookkeeping",
                () -> {
                  attempts.incrementAndGet();
                  throw lostConnection();
                },
                Duration.ofMillis(50),
                PAUSE));

    // It really did try more than once, and it really did stop.
    assertTrue(attempts.get() > 1, "attempts: " + attempts.get());
  }

  @Test
  void theConnectionShapesThePlatformActuallyProduces() {
    // Each of these is a way a postgres cutover reaches this process, and every one of them was a
    // FAILED deployment before.
    assertTrue(DbRetry.isConnectionFailure(lostConnection()));
    // The pool, when the server is simply not there: a SQLException with no SQLState at all, which
    // is why the wording list exists beside the state list.
    assertTrue(
        DbRetry.isConnectionFailure(
            new PersistenceException(
                new SQLTransientConnectionException(
                    "Acquisition timeout while waiting for new connection"))));
    // The server saying it is shutting down — postgres' own admin-shutdown state.
    assertTrue(
        DbRetry.isConnectionFailure(
            new PersistenceException(
                new SQLException("terminating connection due to administrator command", "57P01"))));
    // And the standard connection-exception class, whatever the wording.
    assertTrue(
        DbRetry.isConnectionFailure(new PersistenceException(new SQLException("gone", "08006"))));
    // Nothing else. A plain bug is a bug.
    assertFalse(DbRetry.isConnectionFailure(new IllegalStateException("no active transaction")));
  }

  @Test
  void aCauseChainThatLoopsDoesNotHangTheWorker() {
    // Belt on the walk: the worker is single-threaded, so a spin here would stop every deployment.
    SQLException first = new SQLException("odd", "42601");
    SQLException second = new SQLException("odder", "42601");
    first.initCause(second);
    second.initCause(first);

    assertFalse(DbRetry.isConnectionFailure(first));
  }
}
