package eu.wohlben.qits.platformdeployments.deployments.control;

import eu.wohlben.qits.platformdeployments.environments.entity.PdDeploymentTarget;

/**
 * The seam that fetches a repository's deployment spec at a commit — the {@link DeploymentDriver}
 * arrangement again: this module owns the port and the state machine that calls it, {@code service}
 * owns the one implementation that speaks HTTP, and the suites install a scripted fake so a clone's
 * {@code mvn verify} reaches no network.
 *
 * <p>The seam exists because this is the component's <b>one outbound HTTP call</b>. Keeping the
 * client out of a domain module is the same rule that keeps docker out of one: the orchestration
 * must be testable without either. The merge removed the second such client — the topology used to
 * be another service and is now a repository query — so this is the only one left.
 */
public interface SpecSource {

  /** The file every repository may carry, at the path this reads it from. */
  String SPEC_PATH = ".config/qits/deployments.yml";

  /**
   * Read the spec a repository declares at {@code sha}.
   *
   * @return {@link DeploymentSpec#DEFAULTS} when the repository carries no such file at that commit
   * @throws SpecException when the file exists but could not be fetched or understood — the
   *     deployment fails on it rather than guessing a topology
   */
  DeploymentSpec read(String repoId, String sha);

  /**
   * What a repository declares about how it is deployed. Four keys, all optional, and the shape a
   * repository with no file at all gets is {@link #DEFAULTS}.
   *
   * <p>{@code healthPath} is the exception rather than the rule: a service that says nothing gets
   * the convention path derived from its name, and only a service whose path does not follow the
   * convention (the gateway owns the root path space) has to name one.
   */
  record DeploymentSpec(
      PdDeploymentTarget target, boolean availableOnEnv, String branch, String healthPath) {

    /** The branch a platform service deploys from when it names none. */
    public static final String DEFAULT_PLATFORM_BRANCH = "main";

    /** No file, or a file that sets nothing: an ordinary environment application. */
    public static final DeploymentSpec DEFAULTS =
        new DeploymentSpec(PdDeploymentTarget.ENVIRONMENT, false, null, null);

    /** The branch this platform service deploys from — its own, or the convention. */
    public String platformBranch() {
      return branch == null ? DEFAULT_PLATFORM_BRANCH : branch;
    }
  }
}
