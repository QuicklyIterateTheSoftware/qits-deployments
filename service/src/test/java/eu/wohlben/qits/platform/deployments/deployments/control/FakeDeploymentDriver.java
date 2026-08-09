package eu.wohlben.qits.platform.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The suite's stand-in for the docker seam — a scripted fake, not an honest one: it performs
 * nothing, records every call, and answers what the test told it to. {@code @Mock} makes it the
 * {@link DeploymentDriver} for every {@code @QuarkusTest} in this module, which is what keeps a
 * clone's {@code mvn verify} docker-free (the FakeCiStepRunner stance).
 *
 * <p>It is one of TWO fakes the suite installs, down from three: the topology used to need a stub
 * HTTP server on a real socket, and is a repository query now.
 *
 * <p>Application-scoped and therefore shared across tests: reset it in {@code @BeforeEach} and use
 * distinct environment names per test. State is exposed through <b>methods only</b> — the injected
 * reference is a CDI client proxy, and a field read on a proxy sees the proxy's own fields, never
 * the bean's.
 */
@Mock
@ApplicationScoped
public class FakeDeploymentDriver implements DeploymentDriver {

  private final List<String> ensuredNetworks = Collections.synchronizedList(new ArrayList<>());
  private final List<Network> ensuredNetworkSpecs = Collections.synchronizedList(new ArrayList<>());
  private final List<String> removedNetworks = Collections.synchronizedList(new ArrayList<>());
  private final List<String> pulledRefs = Collections.synchronizedList(new ArrayList<>());
  private final List<StartSpec> started = Collections.synchronizedList(new ArrayList<>());
  private final List<String> awaited = Collections.synchronizedList(new ArrayList<>());
  private final List<String> removedContainers = Collections.synchronizedList(new ArrayList<>());
  private final List<String> removedEnvironments = Collections.synchronizedList(new ArrayList<>());
  private final List<String> stoppedContainers = Collections.synchronizedList(new ArrayList<>());
  private final List<String> restartedContainers = Collections.synchronizedList(new ArrayList<>());

  /** Every driver call in arrival order, tagged `kind:target` — the cutover ORDER assertions. */
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

  /** Every network join and leave, as `network:container:alias` / `network:container`. */
  private final List<String> connections = Collections.synchronizedList(new ArrayList<>());

  private final List<String> disconnections = Collections.synchronizedList(new ArrayList<>());

  private final List<HandoffSpec> handoffs = Collections.synchronizedList(new ArrayList<>());
  private final java.util.Map<String, String> containerIds = new java.util.concurrent.ConcurrentHashMap<>();

  private volatile PullResult nextPull = new PullResult(PullOutcome.OK, null);
  private volatile StartResult nextStart = new StartResult(true, null);
  private volatile HealthResult nextHealth = new HealthResult(true, null);

  /**
   * How many polls the container spends restarting before it comes up healthy. Zero — the default —
   * is the scripted answer in {@link #nextHealth}; anything else runs the REAL {@link HealthGate},
   * so a test about the gate's patience is a test of the shipped gate rather than of this fake.
   */
  private volatile int restartingPolls = 0;

  private volatile List<Holder> nextHolders = List.of();
  private volatile String selfId = "";
  private final List<Network> existingNetworks = Collections.synchronizedList(new ArrayList<>());
  private final Set<String> createdNetworks = Collections.synchronizedSet(new java.util.HashSet<>());
  private final List<Endpoint> hubs = Collections.synchronizedList(new ArrayList<>());
  private final List<Endpoint> platformServices = Collections.synchronizedList(new ArrayList<>());
  private final List<String> aliasSearches = Collections.synchronizedList(new ArrayList<>());

  /** The alias set each predecessor search asked about — the wire alias, and the bare name. */
  private final List<List<String>> searchedAliases = Collections.synchronizedList(new ArrayList<>());

  /** Networks docker refuses to join, by name — what a real join failure looks like. */
  private final java.util.Map<String, String> refusedJoins = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Runs INSIDE {@link #removeEnvironmentContainers}, so a test can observe the world at the moment
   * the docker teardown happens. It is how the "docker first, rows last" order is asserted: the
   * ancestor could watch the registry's own socket for it, and with one service the only way to see
   * the ordering is from inside a driver call.
   */
  private volatile Runnable duringContainerReap = () -> {};

  public void reset() {
    ensuredNetworks.clear();
    removedNetworks.clear();
    pulledRefs.clear();
    started.clear();
    awaited.clear();
    removedContainers.clear();
    removedEnvironments.clear();
    stoppedContainers.clear();
    restartedContainers.clear();
    calls.clear();
    connections.clear();
    disconnections.clear();
    handoffs.clear();
    containerIds.clear();
    nextPull = new PullResult(PullOutcome.OK, null);
    nextStart = new StartResult(true, null);
    nextHealth = new HealthResult(true, null);
    restartingPolls = 0;
    nextHolders = List.of();
    selfId = "";
    ensuredNetworkSpecs.clear();
    existingNetworks.clear();
    createdNetworks.clear();
    hubs.clear();
    platformServices.clear();
    aliasSearches.clear();
    searchedAliases.clear();
    refusedJoins.clear();
    duringContainerReap = () -> {};
  }

  /** What to run while the environment's containers are being reaped. See the field. */
  public void scriptDuringContainerReap(Runnable hook) {
    duringContainerReap = hook;
  }

  /** Script docker refusing to join anything to this network, with that message. */
  public void scriptRefusedJoin(String network, String detail) {
    refusedJoins.put(network, detail);
  }

  /** Networks docker already has when the test starts — ensureNetwork answers "not created". */
  public void scriptExistingNetwork(Network network) {
    existingNetworks.add(network);
  }

  public void scriptHubContainers(List<Endpoint> endpoints) {
    hubs.clear();
    hubs.addAll(endpoints);
  }

  public void scriptPlatformContainers(List<Endpoint> endpoints) {
    platformServices.clear();
    platformServices.addAll(endpoints);
  }

  /** The network sets aliasHolders was asked about, one joined string per call. */
  public List<String> aliasSearches() {
    return List.copyOf(aliasSearches);
  }

  /** The alias sets aliasHolders was asked about, one list per call. */
  public List<List<String>> searchedAliases() {
    return List.copyOf(searchedAliases);
  }

  public List<Network> ensuredNetworkSpecs() {
    return List.copyOf(ensuredNetworkSpecs);
  }

  public void scriptContainerId(String containerName, String id) {
    containerIds.put(containerName, id);
  }

  public List<HandoffSpec> handoffs() {
    return List.copyOf(handoffs);
  }

  public void scriptAliasHolders(List<Holder> holders) {
    nextHolders = holders;
  }

  public void scriptSelfId(String id) {
    selfId = id;
  }

  public List<String> stoppedContainers() {
    return List.copyOf(stoppedContainers);
  }

  public List<String> restartedContainers() {
    return List.copyOf(restartedContainers);
  }

  public List<String> calls() {
    return List.copyOf(calls);
  }

  public List<String> connections() {
    return List.copyOf(connections);
  }

  public List<String> disconnections() {
    return List.copyOf(disconnections);
  }

  public void scriptPull(PullResult result) {
    nextPull = result;
  }

  public void scriptStart(StartResult result) {
    nextStart = result;
  }

  public void scriptHealth(HealthResult result) {
    nextHealth = result;
  }

  /**
   * The boot race a PostgreSQL-backed application takes: docker answers {@code
   * restarting/unhealthy} for the first {@code polls} observations and {@code running/healthy}
   * afterwards, because the container died once before its networks were joined and its restart
   * policy brought it back.
   *
   * <p>The gate itself is the shipped {@link HealthGate}, only polled fast — the fake supplies the
   * states, not the verdict. That is what makes a flow test asserting {@code ACTIVE} an assertion
   * about the gate's patience rather than about this class.
   */
  public void scriptRestartingUntilHealthy(int polls) {
    restartingPolls = polls;
  }

  public List<String> ensuredNetworks() {
    return List.copyOf(ensuredNetworks);
  }

  public List<String> removedNetworks() {
    return List.copyOf(removedNetworks);
  }

  public List<String> pulledRefs() {
    return List.copyOf(pulledRefs);
  }

  public List<StartSpec> started() {
    return List.copyOf(started);
  }

  public List<String> awaited() {
    return List.copyOf(awaited);
  }

  public List<String> removedContainers() {
    return List.copyOf(removedContainers);
  }

  public List<String> removedEnvironments() {
    return List.copyOf(removedEnvironments);
  }

  @Override
  public boolean ensureNetwork(Network spec) {
    ensuredNetworks.add(spec.name());
    ensuredNetworkSpecs.add(spec);
    calls.add("ensureNetwork:" + spec.name());
    boolean known =
        existingNetworks.stream().anyMatch(n -> n.name().equals(spec.name()))
            || !createdNetworks.add(spec.name());
    if (!known) {
      existingNetworks.add(spec);
    }
    return !known;
  }

  @Override
  public List<Network> networks() {
    return List.copyOf(existingNetworks);
  }

  @Override
  public ConnectResult connect(String network, String container, String alias) {
    calls.add("connect:" + network + ":" + container + ":" + alias);
    connections.add(network + ":" + container + ":" + alias);
    String refusal = refusedJoins.get(network);
    return refusal == null ? new ConnectResult(true, null) : new ConnectResult(false, refusal);
  }

  @Override
  public void disconnect(String network, String container) {
    calls.add("disconnect:" + network + ":" + container);
    disconnections.add(network + ":" + container);
  }

  @Override
  public List<Endpoint> hubContainers(String environmentId) {
    return List.copyOf(hubs);
  }

  @Override
  public List<Endpoint> platformContainers() {
    return List.copyOf(platformServices);
  }

  @Override
  public void removeNetwork(String network) {
    removedNetworks.add(network);
  }

  @Override
  public PullResult pull(String imageRef) {
    pulledRefs.add(imageRef);
    return nextPull;
  }

  @Override
  public List<Holder> aliasHolders(List<String> networks, List<String> aliases) {
    calls.add("aliasHolders:" + String.join(",", aliases));
    aliasSearches.add(String.join(",", networks));
    searchedAliases.add(List.copyOf(aliases));
    return nextHolders;
  }

  @Override
  public void stop(String containerName) {
    stoppedContainers.add(containerName);
    calls.add("stop:" + containerName);
  }

  @Override
  public void restart(String containerName) {
    restartedContainers.add(containerName);
    calls.add("restart:" + containerName);
  }

  @Override
  public String selfContainerId() {
    return selfId;
  }

  @Override
  public String containerId(String containerName) {
    return containerIds.getOrDefault(containerName, "");
  }

  @Override
  public void handoff(HandoffSpec spec) {
    handoffs.add(spec);
    calls.add("handoff:" + spec.newContainerName());
  }

  @Override
  public StartResult start(StartSpec spec) {
    started.add(spec);
    calls.add("start:" + spec.containerName());
    return nextStart;
  }

  @Override
  public HealthResult awaitHealthy(String containerName, Duration timeout) {
    awaited.add(containerName);
    if (restartingPolls <= 0) {
      return nextHealth;
    }
    java.util.concurrent.atomic.AtomicInteger left =
        new java.util.concurrent.atomic.AtomicInteger(restartingPolls);
    return HealthGate.await(
        timeout,
        Duration.ofMillis(5),
        () ->
            HealthGate.Poll.of(
                left.getAndDecrement() > 0 ? "restarting/unhealthy" : "running/healthy"),
        () -> "(the fake keeps no logs)");
  }

  @Override
  public void remove(String containerName) {
    removedContainers.add(containerName);
    calls.add("remove:" + containerName);
  }

  @Override
  public int removeEnvironmentContainers(String environmentId) {
    removedEnvironments.add(environmentId);
    duringContainerReap.run();
    return 0;
  }
}
