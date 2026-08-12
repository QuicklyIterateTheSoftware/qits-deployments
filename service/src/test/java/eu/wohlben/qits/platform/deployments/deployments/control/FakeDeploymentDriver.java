package eu.wohlben.qits.platform.deployments.deployments.control;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A scripted stand-in for a whole orchestrator — what {@link DeployService} sees, with no
 * orchestrator behind it.
 *
 * <p><b>It is not the suite's default fake and is not a {@code @Mock}</b>, which is the difference
 * between this and {@code FakeDockerHost}. The docker path is faked one layer lower, at the docker
 * CLI, so every {@code @QuarkusTest} runs the real cutover choreography; this one is installed per
 * test with {@code QuarkusMock} by the tests that are about the state machine itself — the four
 * status transitions, the four announcements and the reap — rather than about either
 * orchestrator's way of getting there.
 *
 * <p>State is read through <b>methods only</b>: an injected or installed reference is a CDI client
 * proxy, and a field read on a proxy sees the proxy's fields, never the bean's.
 */
public class FakeDeploymentDriver implements DeploymentDriver {

  private final List<ServiceSpec> applied = Collections.synchronizedList(new ArrayList<>());
  private final List<String> awaited = Collections.synchronizedList(new ArrayList<>());
  private final List<String> reaped = Collections.synchronizedList(new ArrayList<>());
  private final List<String> pulled = Collections.synchronizedList(new ArrayList<>());
  private final List<Network> ensured = Collections.synchronizedList(new ArrayList<>());

  private volatile PullResult nextPull = new PullResult(PullOutcome.OK, null);
  private volatile ApplyResult nextApply = new ApplyResult(ApplyOutcome.APPLIED, null);
  private volatile Convergence nextConvergence = Convergence.converged(List.of());

  /**
   * The names this fake answers with: the wire alias, which is swarm's answer. It is the more
   * interesting of the two here — the row, the convergence and the reap all have to agree on a name
   * that is the SAME across deployments, which is where an in-place replace differs.
   */
  @Override
  public String nameOf(ServiceSpec spec) {
    return spec.wireAlias();
  }

  public void scriptPull(PullResult result) {
    nextPull = result;
  }

  public void scriptApply(ApplyResult result) {
    nextApply = result;
  }

  public void scriptConvergence(Convergence convergence) {
    nextConvergence = convergence;
  }

  public List<ServiceSpec> applied() {
    return List.copyOf(applied);
  }

  public List<String> awaited() {
    return List.copyOf(awaited);
  }

  public List<String> reaped() {
    return List.copyOf(reaped);
  }

  public List<String> pulled() {
    return List.copyOf(pulled);
  }

  public List<Network> ensured() {
    return List.copyOf(ensured);
  }

  @Override
  public ApplyResult apply(ServiceSpec spec) {
    applied.add(spec);
    return nextApply;
  }

  @Override
  public Convergence awaitConverged(String name, Duration timeout) {
    awaited.add(name);
    return nextConvergence;
  }

  @Override
  public void reap(List<String> names) {
    reaped.addAll(names);
  }

  @Override
  public boolean isSelf(String name) {
    return false;
  }

  @Override
  public boolean ensureNetwork(Network spec) {
    ensured.add(spec);
    return true;
  }

  @Override
  public void removeNetwork(String network) {
    // nothing to remove: this fake never made one
  }

  @Override
  public List<Network> networks() {
    return List.of();
  }

  @Override
  public void detachPlatformPlane(List<String> networks) {
    // nothing holds them
  }

  @Override
  public int removeEnvironmentContainers(String environmentId) {
    return 0;
  }

  @Override
  public PullResult pull(String imageRef) {
    pulled.add(imageRef);
    return nextPull;
  }

  @Override
  public HealthGate.Poll observe(String name) {
    return HealthGate.Poll.gone("this fake runs nothing, so it has no " + name);
  }
}
