package eu.wohlben.qits.platformdeployments.deployments.control;

/**
 * One name shape for every container this component starts: {@code qits-pd-<env>-<app>-<id8>}, and
 * {@code qits-pd-platform-<app>-<id8>} for a platform deployment, which has no environment to be
 * named after.
 *
 * <p>{@code platform} sits where an environment name would, and no environment can take that place:
 * an environment named {@code platform} would produce a container name shaped exactly like a
 * platform service's. That is a collision in the name only and not in what is deployed, but it is
 * the reason the word is spelled once, here.
 *
 * <p>The prefix is {@code qits-pd-}. Containers a retired qits-cd left behind are named
 * {@code qits-cd-…} and are adopted as predecessors like any other unlabelled holder — the naming
 * is how a person reads the host, never how a predecessor is found.
 */
public final class ContainerNames {

  /** The prefix of every container this component starts. */
  public static final String PREFIX = "qits-pd-";

  /** Where an environment name would be, for a deployment that belongs to no tier. */
  public static final String PLATFORM = "platform";

  private ContainerNames() {}

  public static String of(String environmentName, String applicationName, String deploymentId) {
    String shortId = deploymentId.length() > 8 ? deploymentId.substring(0, 8) : deploymentId;
    return PREFIX
        + (environmentName == null ? PLATFORM : environmentName)
        + "-"
        + applicationName
        + "-"
        + shortId;
  }
}
