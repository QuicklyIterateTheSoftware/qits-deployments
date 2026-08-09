package eu.wohlben.qits.platform.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The suite's stand-in for the postgres seam — a scripted fake, not an honest one: it provisions
 * nothing, records every request, and answers what the test told it to. {@code @Mock} makes it the
 * {@link ResourceProvisioner} for every {@code @QuarkusTest} in this module, which is what keeps a
 * clone's {@code mvn verify} free of any server it did not start itself.
 *
 * <p>It is one of THREE fakes now, beside {@link FakeDeploymentDriver} and {@link FakeSpecSource} —
 * the same rule applied a third time. What a real postgres does with these requests is proven
 * against a real postgres, in {@code PgResourceProvisionerTest}; what the ORCHESTRATION does with
 * the answers is proven here.
 *
 * <p>Application-scoped and therefore shared across tests: reset it in {@code @BeforeEach} and use
 * distinct application and environment names per test. State is exposed through <b>methods only</b>
 * — the injected reference is a CDI client proxy, and a field read on a proxy sees the proxy's own
 * fields, never the bean's.
 */
@Mock
@ApplicationScoped
public class FakeResourceProvisioner implements ResourceProvisioner {

  private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

  /** What the next call answers. Null means "whatever the request implies", the ordinary case. */
  private volatile Result nextResult;

  public void reset() {
    requests.clear();
    nextResult = null;
  }

  /** Every request in arrival order — the storedPassword is what the matrix arms are read off. */
  public List<Request> requests() {
    return List.copyOf(requests);
  }

  /** Script a failure, or a specific password in effect. */
  public void scriptResult(Result result) {
    nextResult = result;
  }

  @Override
  public Result ensure(Request request) {
    requests.add(request);
    if (nextResult != null) {
      return nextResult;
    }
    // The default is the no-op/create arm as a real postgres would answer it: a recorded password
    // is kept, and a resource nothing has recorded gets the fresh one.
    String inEffect =
        request.storedPassword() != null ? request.storedPassword() : request.freshPassword();
    return new Result(true, inEffect, null);
  }
}
