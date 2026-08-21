package eu.wohlben.qits.platform.deployments.deployments.control;

/**
 * Which repository a green build came from, in <b>both</b> coordinate systems the platform now has.
 *
 * <p>qits-githost is dumb blob storage: its repository key is an opaque UUID and {@code
 * /git/<uuid>} is an internal URL only qits-projects speaks. The public identity is {@code
 * (projectId, repoName)}, and that is what every announcement above the storage seam carries. This
 * record is the pair, travelling together from the intake down to the one place that needs the
 * address (the spec read) — so nothing below has to guess which of the two a bare string was.
 *
 * <p><b>{@link #applicationName()} is the invariant this whole component turns on.</b> The
 * application name drives the image tag {@code qits/<name>:<sha>}, the network alias, the container
 * name, the provisioned database and role names, and the GC keep-set — and every pipeline yml
 * pushes its image as a literal {@code qits/<name>}. An application name that became a storage UUID
 * would send every deployment to {@code IMAGE_MISSING} and would make the orchestrator's garbage
 * collector delete the images that are live. So the name wins whenever the event carries one.
 *
 * <p><b>An absent name falls back to the repository id, and that is compatibility rather than a
 * guess.</b> Before the identity rollback the storage id <em>was</em> the name, so an event with no
 * name fields is an older publisher and the id is the right answer; after it, qits-ci fills both on
 * every build event. A mirror-sync push on the internal id route announces without names on
 * purpose, and its deployment — if any tier listened — is the pre-cutover behaviour byte for byte.
 */
public record RepositoryRef(String repoId, String projectId, String repoName) {

  /** Blank is absent: an empty string on a wire is a field nobody filled in, not a name. */
  public RepositoryRef {
    repoId = blankToNull(repoId);
    projectId = blankToNull(projectId);
    repoName = blankToNull(repoName);
  }

  /**
   * The id-addressed reference: no public address, so the spec is read through {@code
   * /git/<repoId>} and the application name is the id. What every announcement was before the
   * rollback, and what the internal storage route still announces.
   */
  public static RepositoryRef ofId(String repoId) {
    return new RepositoryRef(repoId, null, null);
  }

  /**
   * The same reference with every field checked at the boundary — the {@link
   * DeploymentIdentifiers} slug discipline, applied to the name and the project id as well as to
   * the id, because all three reach a URL path and the name additionally reaches an argv.
   *
   * @throws eu.wohlben.qits.platform.deployments.environments.error.BadRequestException if any of
   *     the three could escape the path or the argv it is written into
   */
  public RepositoryRef validated() {
    DeploymentIdentifiers.requireRepoId(repoId);
    DeploymentIdentifiers.requireProjectId(projectId);
    DeploymentIdentifiers.requireRepoName(repoName);
    return this;
  }

  /**
   * Whether the public address is complete enough to read a blob by. Both halves or neither: {@code
   * /git/<projectId>/<repoName>} has no meaning with one of them missing, and the id route is
   * always available as the fallback.
   */
  public boolean nameAddressed() {
    return projectId != null && repoName != null;
  }

  /**
   * The name this build deploys under — the repository's own name, or its id when the event carried
   * no name. See the class javadoc: everything derived from it is a literal some pipeline already
   * wrote down.
   */
  public String applicationName() {
    return repoName != null ? repoName : repoId;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
