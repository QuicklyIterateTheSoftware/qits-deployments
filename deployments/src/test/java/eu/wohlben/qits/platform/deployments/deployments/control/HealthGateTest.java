package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The gate's patience, as plain JUnit over the states docker prints — no docker, and no clock but
 * the deadline the test hands in.
 *
 * <p>Every claim here is one the old gate broke: it ended the moment a container was anything other
 * than {@code running/…}, so the deployment of a PostgreSQL-backed application failed 18 seconds in
 * on a container its own restart policy was seconds from fixing.
 */
class HealthGateTest {

  /** A scripted sequence of states, with the last one repeating once the script runs out. */
  private static java.util.function.Supplier<HealthGate.Poll> states(String... scripted) {
    Deque<String> queue = new ArrayDeque<>(List.of(scripted));
    return () -> HealthGate.Poll.of(queue.size() > 1 ? queue.poll() : queue.peek());
  }

  @Test
  void aRestartingContainerIsPendingAndPassesTheGateWhenItComesBack() {
    // The defect, exactly: a PG-backed application boots Flyway before its networks are joined,
    // dies, and `--restart unless-stopped` brings it back into a world where they are. Three polls
    // of `restarting/unhealthy` and the fourth is the deployment working.
    HealthGate.Poll[] script = {
      HealthGate.Poll.of("restarting/unhealthy"),
      HealthGate.Poll.of("restarting/unhealthy"),
      HealthGate.Poll.of("restarting/starting"),
      HealthGate.Poll.of("running/healthy")
    };
    AtomicInteger polled = new AtomicInteger();

    HealthGate.Result result =
        HealthGate.await(
            Duration.ofSeconds(5),
            Duration.ofMillis(1),
            () -> script[polled.getAndIncrement()],
            () -> "logs must not be read on a gate that passed");

    assertTrue(result.healthy());
    assertNull(result.detail());
    assertEquals(4, polled.get());
  }

  @Test
  void aRunningButUnhealthyContainerIsPendingToo() {
    // The other half of the old instant fail. A container that is up and answering its probe with a
    // failure is a container whose probe may yet succeed — Flyway on a cold database takes longer
    // than the first health interval, and the deadline is what decides, not the first answer.
    HealthGate.Result result =
        HealthGate.await(
            Duration.ofSeconds(5),
            Duration.ofMillis(1),
            states("running/unhealthy", "running/unhealthy", "running/healthy"),
            () -> "");

    assertTrue(result.healthy());
  }

  @Test
  void onlyTheDeadlineFailsAContainerThatNeverComesUp() {
    AtomicInteger logsRead = new AtomicInteger();

    HealthGate.Result result =
        HealthGate.await(
            Duration.ofMillis(200),
            Duration.ofMillis(5),
            states("restarting/unhealthy"),
            () -> {
              logsRead.incrementAndGet();
              return "Acquisition timeout while waiting for new connection";
            });

    assertFalse(result.healthy());
    // The verdict names the state it gave up on: "restarting" and "unhealthy" send an operator
    // looking in two different places.
    assertTrue(
        result.detail().startsWith("container still restarting/unhealthy after "),
        result.detail());
    // The log tail is the diagnosis and rides along, read once and only here — a fetch per poll
    // would spend the deadline on docker calls.
    assertTrue(result.detail().contains("Acquisition timeout"));
    assertEquals(1, logsRead.get());
  }

  @Test
  void aContainerDockerCannotFindEndsTheGateAtOnce() {
    // The one early failure left. There is no restart policy behind a container that is not there,
    // so waiting out the deadline would only make the deployment slow as well as failed.
    AtomicInteger polled = new AtomicInteger();

    HealthGate.Result result =
        HealthGate.await(
            Duration.ofSeconds(30),
            Duration.ofMillis(1),
            () -> {
              polled.incrementAndGet();
              return HealthGate.Poll.gone("Error: No such object: qits-pd-prod-qits-idp-1234abcd");
            },
            () -> "");

    assertFalse(result.healthy());
    assertTrue(result.detail().startsWith("container vanished: "), result.detail());
    assertEquals(1, polled.get());
  }

  @Test
  void anAlreadyHealthyContainerIsAnsweredOnTheFirstPoll() {
    AtomicInteger polled = new AtomicInteger();

    HealthGate.Result result =
        HealthGate.await(
            Duration.ZERO,
            Duration.ofMillis(1),
            () -> {
              polled.incrementAndGet();
              return HealthGate.Poll.of("running/healthy");
            },
            () -> "");

    // A zero budget still buys one observation: the gate asks before it decides.
    assertTrue(result.healthy());
    assertEquals(1, polled.get());
  }
}
