package eu.wohlben.qits.platformdeployments.deployments.control;

import eu.wohlben.qits.platformdeployments.environments.error.BadRequestException;

/**
 * Validates the untrusted strings that reach an argv but are never stored in the topology: the
 * commit sha, the repository id, the ci run id, and one value of an OpenTelemetry attribute list.
 *
 * <p>The split from {@code PdIdentifiers} is the module partition, not a taxonomy: names, branches
 * and health paths are values the topology <b>keeps</b>, so they are checked where they are kept;
 * these four exist only for the length of one deployment, so they are checked beside the argv they
 * guard.
 *
 * <p>Defence in depth, not the only guard: argvs are assembled for {@link ProcessBuilder}, which
 * never re-splits. {@link #requireRunId} is the one exception to the sentence above and says so in
 * its own javadoc: it reaches no argv, and is bounded here only so a hostile length cannot break
 * the intake's insert.
 */
public final class DeploymentIdentifiers {

  /** Same slug the git host accepts for a repo id — no separators, no leading dash. */
  private static final String REPO_ID = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  /** A hex object id (abbreviated ids are accepted; the registry resolves the tag either way). */
  private static final String SHA = "[0-9a-f]{7,64}";

  /** A foreign opaque id: qits-ci's run ids are UUIDs, and this is wide enough to stay so. */
  private static final String RUN_ID = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

  /**
   * One value of an {@code OTEL_RESOURCE_ATTRIBUTES} pair. The list's own separators are the
   * guard's whole subject: {@code ,} would forge a second pair and {@code =} would move the
   * boundary between key and value, so neither is in the charset.
   */
  private static final String ATTRIBUTE_VALUE = "[A-Za-z0-9._/:-]{1,255}";

  private DeploymentIdentifiers() {}

  /**
   * @throws BadRequestException if the repo id could escape an argv
   */
  public static String requireRepoId(String repoId) {
    if (repoId == null || !repoId.matches(REPO_ID)) {
      throw new BadRequestException("Invalid repository id");
    }
    return repoId;
  }

  /**
   * @throws BadRequestException if the sha is not a plain hex object id
   */
  public static String requireSha(String sha) {
    if (sha == null || !sha.matches(SHA)) {
      throw new BadRequestException("Invalid commit sha");
    }
    return sha;
  }

  /**
   * The causing ci run, which is <b>optional</b> — a sender that omits it records a deployment with
   * no build to point at.
   *
   * <p>This is the one check here that guards no argv and no shell string: the run id is stored and
   * displayed, nothing more. It exists because the column is bounded — an oversized value would
   * fail the intake's insert, and the sender is fire-and-forget, so the deployment would simply
   * never happen and no one would be told why. Bounding it at the boundary turns that into a 400
   * the sender's log can show.
   *
   * @throws BadRequestException if a present run id is not a plain opaque identifier
   */
  public static String requireRunId(String runId) {
    if (runId == null) {
      return null;
    }
    if (!runId.matches(RUN_ID)) {
      throw new BadRequestException("Invalid run id");
    }
    return runId;
  }

  /**
   * One value of a resource-attribute pair, checked at the argv rather than at the boundary — the
   * second belt of the same kind as the health-path check. Every value put in that list is already
   * a validated sha, a validated name, or a container name composed out of both, so this can only
   * fail if one of those checks is ever loosened; it is here so that loosening one is a failed
   * deployment rather than a forged extra attribute.
   *
   * @throws BadRequestException if the value carries a {@code ,} or {@code =} the list would read
   *     as its own punctuation
   */
  public static String requireAttributeValue(String value, String what) {
    if (value == null || !value.matches(ATTRIBUTE_VALUE)) {
      throw new BadRequestException(
          "Invalid " + what + " — no commas or equals signs in a resource attribute value");
    }
    return value;
  }
}
