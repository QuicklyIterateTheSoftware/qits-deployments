package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.platform.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The strict reader of {@code .config/qits/deployments.yml}. Four scalar keys, no nesting, no lists
 * — so this is a line reader rather than a YAML library, and being one is what makes every
 * rejection a sentence naming the file and the line.
 *
 * <pre>
 * deployment_target: environment   # default when the key or the file is absent | platform
 * available_on_env: false          # default; true = public node (bundle + hub joins)
 * branch: platform/main            # platform only: deploy branch (default platform/main)
 * health_path: /q/health/ready     # default: /&lt;name without the qits- prefix&gt;/q/health/ready
 * </pre>
 *
 * <p><b>Strict on purpose.</b> A typo in this file decides where a container runs and what can
 * reach it, and a lenient parser answers a typo with a default — silently deploying the wrong
 * topology and leaving nothing to read. So an unknown key, a repeated key, a value outside the enum
 * and a line that is not {@code key: value} are all errors, and the deployment fails on them. The
 * one thing that is <em>not</em> an error is the file's absence: no file means every default, which
 * is what every repository already behaves like.
 *
 * <p><b>{@code platform} is the canonical target and {@code singleton} is an accepted alias.</b>
 * Both parse to {@link PdDeploymentTarget#PLATFORM} and nothing downstream can tell them apart. The
 * alias exists because the repositories that carry the word were written against the retired
 * vocabulary and must keep deploying across the cutover without a commit each; documentation,
 * error messages and every new file say {@code platform}. It is a tolerance, not a second spelling
 * to maintain — and a value outside both still names only the canonical pair, so a typo is pointed
 * at the word to use.
 */
public final class DeploymentSpecParser {

  private static final String TARGET = "deployment_target";
  private static final String AVAILABLE_ON_ENV = "available_on_env";
  private static final String BRANCH = "branch";
  private static final String HEALTH_PATH = "health_path";

  /** The retired vocabulary, still understood. See the class javadoc. */
  private static final String PLATFORM_ALIAS = "singleton";

  private DeploymentSpecParser() {}

  /**
   * @param source how the file is named back to a reader — the whole point of the error messages
   * @throws SpecException on anything this schema does not describe
   */
  public static DeploymentSpec parse(String yaml, String source) {
    PdDeploymentTarget target = PdDeploymentTarget.ENVIRONMENT;
    boolean availableOnEnv = false;
    String branch = null;
    String healthPath = null;
    Set<String> seen = new HashSet<>();

    String[] lines = (yaml == null ? "" : yaml).split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      String raw = lines[i];
      int lineNumber = i + 1;
      String line = stripComment(raw);
      if (line.isBlank() || line.strip().equals("---")) {
        continue;
      }
      if (Character.isWhitespace(line.charAt(0))) {
        throw error(source, lineNumber, "indented lines — this file has no nesting");
      }
      int colon = line.indexOf(':');
      if (colon < 1) {
        throw error(source, lineNumber, "expected `key: value`, got: " + line.strip());
      }
      String key = line.substring(0, colon).strip();
      String value = unquote(line.substring(colon + 1).strip());
      if (!seen.add(key)) {
        throw error(source, lineNumber, "duplicate key `" + key + "`");
      }
      switch (key) {
        case TARGET -> target = target(value, source, lineNumber);
        case AVAILABLE_ON_ENV -> availableOnEnv = bool(key, value, source, lineNumber);
        case BRANCH -> branch = branch(value, source, lineNumber);
        case HEALTH_PATH -> healthPath = healthPath(value, source, lineNumber);
        default ->
            throw error(
                source,
                lineNumber,
                "unknown key `"
                    + key
                    + "` — this file knows "
                    + TARGET
                    + ", "
                    + AVAILABLE_ON_ENV
                    + ", "
                    + BRANCH
                    + " and "
                    + HEALTH_PATH);
      }
    }

    if (availableOnEnv && target == PdDeploymentTarget.PLATFORM) {
      throw new SpecException(
          source
              + ": `"
              + AVAILABLE_ON_ENV
              + ": true` is not something a platform service can be — it already runs on every"
              + " environment's networks, and the bundle is environment-scoped");
    }
    return new DeploymentSpec(target, availableOnEnv, branch, healthPath);
  }

  private static PdDeploymentTarget target(String value, String source, int line) {
    // Lowercase in the file, uppercase in the enum: the yaml is written by a person and the column
    // is read by a machine, and neither should have to spell the other's convention.
    if (PLATFORM_ALIAS.equals(value)) {
      return PdDeploymentTarget.PLATFORM;
    }
    for (PdDeploymentTarget candidate : PdDeploymentTarget.values()) {
      if (candidate.name().toLowerCase(Locale.ROOT).equals(value)) {
        return candidate;
      }
    }
    // The alias is deliberately absent from this message: a repository being corrected should be
    // pointed at the word to use, not at the one it may keep using.
    throw error(source, line, "`" + TARGET + "` must be `environment` or `platform`, got: " + value);
  }

  private static boolean bool(String key, String value, String source, int line) {
    if ("true".equals(value)) {
      return true;
    }
    if ("false".equals(value)) {
      return false;
    }
    throw error(source, line, "`" + key + "` must be `true` or `false`, got: " + value);
  }

  private static String branch(String value, String source, int line) {
    try {
      return PdIdentifiers.requireBranch(value);
    } catch (RuntimeException e) {
      throw error(source, line, "`" + BRANCH + "` is not a plain ref name: " + value);
    }
  }

  /**
   * The health gate's URL path inside the container, checked here with the same rule the API uses —
   * an absolute path and nothing a shell or an argv would read as punctuation, because this value
   * ends up in a {@code --health-cmd}.
   */
  private static String healthPath(String value, String source, int line) {
    try {
      return PdIdentifiers.requireHealthPath(value);
    } catch (RuntimeException e) {
      throw error(source, line, "`" + HEALTH_PATH + "` is not an absolute URL path: " + value);
    }
  }

  /**
   * Drops a {@code #} comment. A {@code #} only starts one at the beginning of the line or after
   * whitespace, which is YAML's own rule and the reason {@code branch: fix#123} keeps its hash.
   */
  private static String stripComment(String line) {
    for (int i = 0; i < line.length(); i++) {
      if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && (value.charAt(0) == '"' || value.charAt(0) == '\'')
        && value.charAt(value.length() - 1) == value.charAt(0)) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static SpecException error(String source, int line, String what) {
    return new SpecException(source + " line " + line + ": " + what);
  }
}
