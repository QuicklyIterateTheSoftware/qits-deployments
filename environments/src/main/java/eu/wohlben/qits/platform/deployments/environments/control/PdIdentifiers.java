package eu.wohlben.qits.platform.deployments.environments.control;

import eu.wohlben.qits.platform.deployments.environments.error.BadRequestException;

/**
 * Validates the untrusted strings the topology <b>stores</b>: names, branches, health paths, and
 * the resource and database names a repository asks for.
 *
 * <p>Every value checked here is read back by something that assembles an argv. A service name
 * becomes a docker network name, a network alias, an image reference and part of a container name;
 * a health path is interpolated into a container's own {@code --health-cmd} shell string. The write
 * surface is attacker-reachable by design — its machine token is off until the rollout gate flips —
 * so the check happens at the boundary that first accepts the value, not only at the one that uses
 * it.
 *
 * <p><b>Two copies of this class merged into one.</b> qits-cd and qits-serviceregistry each carried
 * the same three rules, character for character, with a comment in the registry's copy explaining
 * that they had to stay identical or a name accepted by one and rejected by the other would be a
 * deployment failing long after the mistake was made. One component, one definition.
 *
 * <p>What is <em>not</em> here is what never reaches a stored row: shas, repository ids, run ids
 * and resource-attribute values are checked by {@code DeploymentIdentifiers} in the deployments
 * module, beside the argv they guard.
 */
public final class PdIdentifiers {

  /**
   * dns-safe lowercase slug: environment, service and network names become docker network names,
   * network aliases and image path segments, and one day hostname labels ({@code
   * <service>.<env>.qits-dev.eu}) — so the charset is the hostname-label one from the start.
   */
  private static final String NAME = "[a-z0-9][a-z0-9-]{0,62}";

  /** Conservative subset of valid ref names — enough for real branches, hostile to nothing else. */
  private static final String BRANCH = "[A-Za-z0-9._][A-Za-z0-9._/-]{0,254}";

  /**
   * An absolute http path with no room for shell metacharacters — this value lands inside the
   * container's {@code --health-cmd} string, so the allowlist is the guard.
   */
  private static final String HEALTH_PATH = "/[A-Za-z0-9._/-]{0,254}";

  /**
   * What a repository may call a resource of its own. It becomes the {@code <NAME>} segment of
   * {@code QITS_RESOURCE_<NAME>_URL} (uppercased, dashes to underscores) and the registry's key, so
   * the charset is the env-key one written in lower case.
   */
  private static final String RESOURCE_NAME = "[a-z][a-z0-9-]{0,31}";

  /**
   * A database this component may create, and the role that owns it. 63 characters is postgres'
   * identifier limit, and the mandatory {@code qits_} prefix is doing the real work: it structurally
   * excludes {@code postgres}, {@code template0}, {@code template1} and every {@code pg_*} name, so
   * no repository can name a database the platform's own instance already depends on.
   */
  private static final String DATABASE_NAME = "qits_[a-z0-9_]{1,58}";

  private PdIdentifiers() {}

  /**
   * An environment, service or network name — the dns-label charset, because these become network
   * names, aliases, image path segments and (eventually) hostname labels.
   *
   * @throws BadRequestException if the name is not a lowercase dns-safe slug
   */
  public static String requireName(String name, String what) {
    if (name == null || !name.matches(NAME) || name.endsWith("-")) {
      throw new BadRequestException(
          "Invalid " + what + " — lowercase letters, digits and inner dashes, max 63 chars");
    }
    return name;
  }

  /**
   * @throws BadRequestException if the branch is not a plain, non-tricky ref name
   */
  public static String requireBranch(String branch) {
    if (branch == null
        || !branch.matches(BRANCH)
        || branch.contains("..")
        || branch.contains("//")
        || branch.endsWith("/")
        || branch.endsWith(".lock")) {
      throw new BadRequestException("Invalid branch name");
    }
    return branch;
  }

  /**
   * The health gate's probe path. This is the one stored value that ends up inside a shell string
   * the container runs, so it gets an allowlist rather than a denylist and no exceptions.
   *
   * @throws BadRequestException if the path is not an absolute, metacharacter-free http path
   */
  public static String requireHealthPath(String healthPath) {
    if (healthPath == null || !healthPath.matches(HEALTH_PATH)) {
      throw new BadRequestException(
          "Invalid health path — an absolute path of letters, digits, dots, dashes and slashes");
    }
    return healthPath;
  }

  /**
   * The name a repository gives one of its resources in {@code resources:}.
   *
   * <p><b>It gets the health-path treatment, and for the health path's reason.</b> This is
   * repository-authored input that ends up in an argv — {@code --env QITS_RESOURCE_<NAME>_URL=…} —
   * so it is an allowlist, checked at the parser and re-checked at the last line before the argv. A
   * trailing dash is refused like {@code requireName}'s: the name is uppercased and its dashes
   * become underscores, and a variable ending in one reads as a truncated word.
   *
   * @throws BadRequestException if the name is not a lowercase env-key-safe slug
   */
  public static String requireResourceName(String resourceName) {
    if (resourceName == null || !resourceName.matches(RESOURCE_NAME) || resourceName.endsWith("-")) {
      throw new BadRequestException(
          "Invalid resource name — lowercase letters, digits and inner dashes, max 32 chars");
    }
    return resourceName;
  }

  /**
   * The database a repository asks this component to create for it, which is also the login role
   * that owns it.
   *
   * <p><b>The strictest value here after the health path, and for a sharper reason than a charset.</b>
   * It is interpolated into DDL run against a postgres instance the WHOLE PLATFORM shares — DDL
   * cannot be parametrized, so the allowlist is the only guard there is — and the mandatory {@code
   * qits_} prefix is what keeps the namespace a repository can reach disjoint from the instance's
   * own. Checked at the parser, again immediately before the SQL is assembled, and again at the
   * argv, exactly like {@link #requireHealthPath}.
   *
   * @throws BadRequestException if the name is not a {@code qits_}-prefixed lowercase identifier
   */
  public static String requireDatabaseName(String databaseName) {
    if (databaseName == null || !databaseName.matches(DATABASE_NAME)) {
      throw new BadRequestException(
          "Invalid database name — `qits_` followed by lowercase letters, digits and underscores,"
              + " max 63 chars");
    }
    return databaseName;
  }
}
