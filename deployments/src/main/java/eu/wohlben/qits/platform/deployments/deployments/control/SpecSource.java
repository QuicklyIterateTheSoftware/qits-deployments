package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import java.util.List;

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
   * What a repository declares about how it is deployed. Five keys, all optional, and the shape a
   * repository with no file at all gets is {@link #DEFAULTS}.
   *
   * <p>{@code healthPath} is the exception rather than the rule: a service that says nothing gets
   * the convention path derived from its name, and only a service whose path does not follow the
   * convention (the gateway owns the root path space) has to name one.
   *
   * <p>{@code healthCmd} <b>replaces</b> that HTTP probe rather than adjusting it: a plain image
   * with no HTTP surface — postgres is the first — declares the command that says it is ready, and
   * the parser refuses a file that sets both. Null means the HTTP probe, which is every service
   * this platform had before deployable images existed.
   *
   * <p><b>{@code deployBranches} is read and not used here</b>, and that is deliberate — see {@link
   * #deployBranches()}.
   */
  record DeploymentSpec(
      PdDeploymentTarget target,
      boolean availableOnEnv,
      List<String> deployBranches,
      String healthPath,
      String healthCmd) {

    /** A null list and an empty one are the same statement: the file named no refs. */
    public DeploymentSpec {
      deployBranches = deployBranches == null ? List.of() : List.copyOf(deployBranches);
    }

    /** No file, or a file that sets nothing: an ordinary environment application. */
    public static final DeploymentSpec DEFAULTS =
        new DeploymentSpec(PdDeploymentTarget.ENVIRONMENT, false, List.of(), null, null);

    /**
     * The refs the repository declares itself deployable from — {@code deploy_branches:} in the
     * file.
     *
     * <p><b>Nothing in this component matches on it.</b> Where a build deploys is decided by the
     * environment rows: a green build deploys wherever an environment listens to its branch, on
     * either plane. The key is parsed and validated because the <b>release flow</b> reads the same
     * file for its promotion targets, and this parser is strict — an unknown key fails a
     * deployment, so a key another reader needs has to be one this reader knows. Reading it and
     * ignoring it is cheaper than two files, and far cheaper than a lenient parser.
     */
    public List<String> deployBranches() {
      return deployBranches;
    }
  }
}
