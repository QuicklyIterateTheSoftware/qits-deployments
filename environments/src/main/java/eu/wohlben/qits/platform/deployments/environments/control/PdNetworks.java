package eu.wohlben.qits.platform.deployments.environments.control;

/**
 * The docker network names this component derives, in one place — the topology is hub-and-spoke and
 * the names are the whole of how it is addressed.
 *
 * <ul>
 *   <li><b>Per service</b> ({@link #application}): where an environment's service actually runs.
 *       Only its own containers are on it, so nothing in the environment can reach it without
 *       being joined to it deliberately.
 *   <li><b>Per environment bundle</b> (the environment row's own {@code network}): the
 *       environment's public nodes. One member today (qits-gateway) — kept because "the public
 *       nodes of this environment" is a set worth having a name for.
 *   <li><b>Platform</b> ({@link #PLATFORM}): where platform services run. They join every
 *       environment's per-service networks on top, which is what makes them locally reachable
 *       everywhere.
 * </ul>
 *
 * <p>Only the bundle name is ever stored — on the environment row, so a tier's public-node network
 * can be something other than the convention (dev's is {@code qits-net} by history). The other two
 * are computed at deploy time and read back from docker's labels. <b>Nothing here is persisted.</b>
 * A network's membership is docker's bookkeeping, never a row — a copy in this database would be a
 * second answer that goes stale the first time a container is replaced.
 *
 * <p>It lives in the topology module because the topology is what the names describe, and because
 * both halves of the component need them: environment creation fills the bundle default, and the
 * deploy orchestration derives the rest.
 */
public final class PdNetworks {

  /** Where platform services run, created on demand. They belong to no environment. */
  public static final String PLATFORM = "qits-platform";

  /** The bundle network an environment gets when its creator names none. */
  public static final String BUNDLE_PREFIX = "qits-env-";

  private PdNetworks() {}

  /** The bundle network of an environment that named none: {@code qits-env-<env>}. */
  public static String bundle(String environmentName) {
    return BUNDLE_PREFIX + environmentName;
  }

  /** One service's own network inside an environment: {@code qits-env-<env>-<service>}. */
  public static String application(String environmentName, String applicationName) {
    return BUNDLE_PREFIX + environmentName + "-" + applicationName;
  }

  /**
   * The <b>wire alias</b> a container answers to on every network it is on — the address peers dial,
   * and the thing a cutover finds a predecessor by. It is derived here rather than at the argv,
   * because three callers have to agree on it: the {@code docker run --network-alias}, every {@code
   * docker network connect --alias} after it, and the predecessor search.
   *
   * <ul>
   *   <li><b>An environment service</b> is {@code <environment>-<application>} — {@code
   *       prod-qits-gateway}. The qualifier is what lets two tiers hold the same application's
   *       address on one shared network (the legacy one is shared by all of them) without one
   *       resolving as the other.
   *   <li><b>A platform service</b> keeps the bare {@code <application>}: it is one instance for the
   *       whole platform, so there is nothing to qualify it against, and the repository names now
   *       carry the plane themselves ({@code qits-platform-idp}).
   * </ul>
   *
   * @param environmentName null for a platform service — the same null that leaves it unlabelled
   */
  public static String alias(String environmentName, String applicationName) {
    return environmentName == null ? applicationName : environmentName + "-" + applicationName;
  }
}
