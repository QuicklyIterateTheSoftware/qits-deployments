package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.platform.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The strict reader of {@code .config/qits/deployments.yml}. Five scalar keys, no nesting, no YAML
 * lists — so this is a line reader rather than a YAML library, and being one is what makes every
 * rejection a sentence naming the file and the line.
 *
 * <pre>
 * deployment_target: environment       # default when the key or the file is absent | platform
 * available_on_env: false              # default; true = public node (bundle + hub joins)
 * health_path: /q/health/ready         # default: /&lt;name without the qits- prefix&gt;/q/health/ready
 * health_cmd: pg_isready -U postgres   # instead of health_path: the probe runs in the container
 * deploy_branches: environment/prod    # RETIRED, accepted and ignored — see below
 * </pre>
 *
 * <p><b>{@code health_cmd} and {@code health_path} are alternatives, and setting both is an
 * error.</b> They are not two settings on one gate: the path names a URL a {@code curl} inside the
 * container fetches, and the command replaces that whole mechanism. A deployable image with no
 * HTTP surface — postgres, the first of them — can pass no path-shaped gate, having neither curl
 * nor anything on 8080, so it says how it is ready in its own words instead.
 *
 * <p><b>{@code deploy_branches} is RETIRED: accepted, validated, and acted on by nobody.</b> Where
 * a build deploys was always decided by the environment rows — a green build deploys wherever an
 * environment listens to its branch — so this component never read the key back. Its one reader was
 * qits-workspaces' release flow, which promoted a release onto every branch the list named; that is
 * a fan-out rather than a ladder, and with three tiers it would have shipped a release into all
 * three at once. A release now lands on one entry branch, from that component's own configuration,
 * and no repository states it.
 *
 * <p>It stays accepted for the same reason {@code singleton} does, and the reason is sharper here.
 * <b>A spec is fetched at the BUILT sha</b>, so a rollback pin, a redeploy of an older commit or a
 * repository nobody has edited yet still presents a file carrying the key — and this parser fails a
 * deployment on an unknown one. Making it unknown would turn every such deployment red. Do not
 * write it into a new file, and do not remove the tolerance.
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
  private static final String DEPLOY_BRANCHES = "deploy_branches";
  private static final String HEALTH_PATH = "health_path";
  private static final String HEALTH_CMD = "health_cmd";

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
    List<String> deployBranches = List.of();
    String healthPath = null;
    String healthCmd = null;
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
        case DEPLOY_BRANCHES -> deployBranches = deployBranches(value, source, lineNumber);
        case HEALTH_PATH -> healthPath = healthPath(value, source, lineNumber);
        case HEALTH_CMD -> healthCmd = healthCmd(value, source, lineNumber);
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
                    + DEPLOY_BRANCHES
                    + ", "
                    + HEALTH_PATH
                    + " and "
                    + HEALTH_CMD);
      }
    }

    if (healthCmd != null && healthPath != null) {
      throw new SpecException(
          source
              + ": `"
              + HEALTH_CMD
              + "` and `"
              + HEALTH_PATH
              + "` are alternatives — the command replaces the HTTP probe rather than adjusting"
              + " it, so a file setting both says two things about one gate. Keep the one that"
              + " describes this image.");
    }
    if (availableOnEnv && target == PdDeploymentTarget.PLATFORM) {
      throw new SpecException(
          source
              + ": `"
              + AVAILABLE_ON_ENV
              + ": true` is not something a platform service can be — it already runs on every"
              + " environment's networks, and the bundle is environment-scoped");
    }
    return new DeploymentSpec(target, availableOnEnv, deployBranches, healthPath, healthCmd);
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

  /**
   * The comma-separated ref list. A YAML sequence would need a parser this file deliberately does
   * not have, so the refs share one line — and a ref cannot contain a comma, which is what makes
   * the separator safe.
   *
   * <p>Every element is validated, and a blank one fails: {@code deploy_branches:} with nothing
   * after it, or a trailing comma, is a writer who meant to say something. A silent empty answer is
   * exactly the shape this parser exists to refuse.
   */
  private static List<String> deployBranches(String value, String source, int line) {
    List<String> refs = new ArrayList<>();
    for (String candidate : value.split(",", -1)) {
      String ref = candidate.strip();
      try {
        refs.add(PdIdentifiers.requireBranch(ref));
      } catch (RuntimeException e) {
        throw error(source, line, "`" + DEPLOY_BRANCHES + "` is not a plain ref name: " + ref);
      }
    }
    return List.copyOf(refs);
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
   * The readiness probe an image declares for itself, passed to docker verbatim and run by a shell
   * inside the container. Unlike {@link #healthPath} it gets no charset — a probe worth writing is
   * a command ({@code pg_isready -U postgres || exit 1}), and an allowlist would refuse it. What
   * makes that safe is that the command grants the repository nothing it does not already have: it
   * runs in that repository's own container, and it is one argv element rather than a string this
   * component splits. See {@link DeploymentIdentifiers#requireHealthCmd}.
   */
  private static String healthCmd(String value, String source, int line) {
    try {
      return DeploymentIdentifiers.requireHealthCmd(value);
    } catch (RuntimeException e) {
      throw error(
          source,
          line,
          "`"
              + HEALTH_CMD
              + "` must be one non-blank line of at most "
              + DeploymentIdentifiers.HEALTH_CMD_MAX_CHARS
              + " characters, got: "
              + value);
    }
  }

  /**
   * Drops a {@code #} comment. A {@code #} only starts one at the beginning of the line or after
   * whitespace, which is YAML's own rule and the reason {@code deploy_branches: fix#123} keeps its
   * hash.
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
