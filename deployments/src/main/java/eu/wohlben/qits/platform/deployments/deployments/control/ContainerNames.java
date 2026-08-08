package eu.wohlben.qits.platform.deployments.deployments.control;

/**
 * One name shape for every container this component starts: {@code qits-pd-<env>-<app>-<id8>}, and
 * {@code qits-pd-<app>-<id8>} for a platform deployment, which has no environment to be named
 * after.
 *
 * <p><b>The platform shape drops the segment rather than filling it.</b> It used to read {@code
 * qits-pd-platform-<app>-<id8>}, which was right while a platform repository was named like any
 * other; the platform repositories carry the plane in their own names now ({@code
 * qits-platform-idp}, {@code qits-platform-artifacts}), so the word would be said twice —
 * {@code qits-pd-platform-qits-platform-artifacts-…}. What is left is unambiguous for the reason
 * that made the old shape unambiguous: an environment name sits in the same place, and the
 * application name carries the plane.
 *
 * <p><b>The prefix is {@code qits-pd-}, and it stays that way after the namespace rename.</b> The
 * config keys and labels spell the namespace in full ({@code qits.platform.deployments.*}); a
 * container name cannot, because docker's name charset has no dot, and
 * {@code qits-platform-deployments-<env>-<app>-<id8>} spends 26 characters on a prefix before the
 * two words a person actually reads. So {@code qits-pd-} is kept as the namespace's abbreviation —
 * a display convention, nothing resolves through it.
 *
 * <p>Containers a retired qits-cd left behind are named {@code qits-cd-…} and are adopted as
 * predecessors like any other unlabelled holder — the naming is how a person reads the host, never
 * how a predecessor is found (that is the wire alias, {@code PdNetworks.alias}).
 */
public final class ContainerNames {

  /** The prefix of every container this component starts. */
  public static final String PREFIX = "qits-pd-";

  private ContainerNames() {}

  public static String of(String environmentName, String applicationName, String deploymentId) {
    String shortId = deploymentId.length() > 8 ? deploymentId.substring(0, 8) : deploymentId;
    return PREFIX
        + (environmentName == null ? "" : environmentName + "-")
        + applicationName
        + "-"
        + shortId;
  }
}
