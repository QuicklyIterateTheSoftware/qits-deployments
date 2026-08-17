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
 * The strict reader of {@code .config/qits/deployments.yml}. Eleven scalar keys, no nesting, no YAML
 * lists — so this is a line reader rather than a YAML library, and being one is what makes every
 * rejection a sentence naming the file and the line.
 *
 * <pre>
 * deployment_target: environment       # default when the key or the file is absent | platform
 * available_on_env: false              # default; true = public node (bundle + hub joins)
 * health_path: /q/health/ready         # default: /&lt;name without the qits- prefix&gt;/q/health/ready
 * health_cmd: pg_isready -U postgres   # instead of health_path: the probe runs in the container
 * resources: postgresql:db             # a database of its own, injected as QITS_RESOURCE_DB_*
 * update_order: start-first            # default | stop-first for anything single-writer
 * publish_mode: host                   # default | ingress for a port the routing mesh holds
 * routes: /artifacts,/v2               # optional public path prefixes, in navigation order
 * upstream_port: 8080                  # default; the service port behind every route
 * navigation: Artifacts:3              # optional label:positive-position, for the first route
 * deploy_branches: environment/prod    # RETIRED, accepted and ignored — see below
 * </pre>
 *
 * <p><b>{@code publish_mode} is the second key an orchestrator reads rather than this
 * component.</b> {@code host} is today's behaviour and the default: the task binds the port on the
 * node itself. {@code ingress} gives the port to swarm's routing mesh, which keeps holding it
 * while a replacement starts — the whole reason a front door can pull its own successor's image.
 * It reaches the orchestrator as {@code mode=} on the publish, and it means nothing at all to an
 * application that publishes no port. It does <b>not</b> touch {@code update_order}: the two are
 * independent statements and this component derives neither from the other.
 *
 * <p><b>{@code update_order} is the one key an orchestrator reads rather than this component.</b>
 * {@code start-first} overlaps the successor with the predecessor, which is what makes a rollback
 * lossless; {@code stop-first} is the opt-out for an application that cannot be two processes at
 * once — one binder per published host port, one writer per store, one holder of a config volume.
 * It reaches the orchestrator as {@code --update-order}. A repository that says nothing gets
 * {@code start-first}, so an application that must not overlap has to say so.
 *
 * <p><b>{@code health_cmd} and {@code health_path} are alternatives, and setting both is an
 * error.</b> They are not two settings on one gate: the path names a URL a {@code curl} inside the
 * container fetches, and the command replaces that whole mechanism. A deployable image with no
 * HTTP surface — postgres, the first of them — can pass no path-shaped gate, having neither curl
 * nor anything on 8080, so it says how it is ready in its own words instead.
 *
 * <p><b>{@code resources} is a flat comma-separated list, and it will never be anything else.</b>
 * The grammar is {@code postgresql:<name>[:<database>]} because this file has no YAML sequences and
 * no nesting to give it — the same reason {@code deploy_branches} is comma-separated. What a
 * repository names there is a database of its own on the platform's shared postgres, so the two
 * names are allowlisted (see {@link PdIdentifiers#requireResourceName} and {@link
 * PdIdentifiers#requireDatabaseName}), and the {@code qits_} prefix is what keeps the namespace it
 * can reach disjoint from the instance's own.
 *
 * <p><b>Once this key ships it can never become unknown again</b>, and that is worth writing down
 * in the commit that adds it rather than learning later. A spec is fetched at the BUILT sha, so a
 * rollback pin or a redeploy of an older commit presents whatever file that commit carried, and an
 * unknown key fails a deployment. {@code deploy_branches} below is the same lesson, learned the
 * expensive way; this is it applied in advance. Retiring the key later means keeping the tolerance
 * forever, exactly as that one did.
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
  private static final String RESOURCES = "resources";
  private static final String UPDATE_ORDER = "update_order";
  private static final String PUBLISH_MODE = "publish_mode";
  private static final String ROUTES = "routes";
  private static final String UPSTREAM_PORT = "upstream_port";
  private static final String NAVIGATION = "navigation";
  static final int DEFAULT_UPSTREAM_PORT = 8080;

  /** The only resource type there is. It is spelled in the file so a second one can arrive. */
  private static final String POSTGRESQL = "postgresql";

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
    List<DeploymentSpec.ResourceSpec> resources = List.of();
    DeploymentDriver.UpdateOrder updateOrder = DeploymentDriver.UpdateOrder.START_FIRST;
    DeploymentDriver.PublishMode publishMode = DeploymentDriver.PublishMode.HOST;
    List<String> routes = List.of();
    int upstreamPort = DEFAULT_UPSTREAM_PORT;
    String navigationLabel = null;
    Integer navigationPosition = null;
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
        case RESOURCES -> resources = resources(value, source, lineNumber);
        case UPDATE_ORDER -> updateOrder = updateOrder(value, source, lineNumber);
        case PUBLISH_MODE -> publishMode = publishMode(value, source, lineNumber);
        case ROUTES -> routes = routes(value, source, lineNumber);
        case UPSTREAM_PORT -> upstreamPort = upstreamPort(value, source, lineNumber);
        case NAVIGATION -> {
          Navigation navigation = navigation(value, source, lineNumber);
          navigationLabel = navigation.label();
          navigationPosition = navigation.position();
        }
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
                    + ", "
                    + HEALTH_CMD
                    + ", "
                    + RESOURCES
                    + ", "
                    + UPDATE_ORDER
                    + ", "
                    + PUBLISH_MODE
                    + ", "
                    + ROUTES
                    + ", "
                    + UPSTREAM_PORT
                    + " and "
                    + NAVIGATION);
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
    if (navigationLabel != null && routes.isEmpty()) {
      throw new SpecException(
          source
              + ": `"
              + NAVIGATION
              + "` needs at least one `"
              + ROUTES
              + "` entry — navigation has to lead to a published route");
    }
    return new DeploymentSpec(
        target,
        availableOnEnv,
        deployBranches,
        healthPath,
        healthCmd,
        resources,
        updateOrder,
        publishMode,
        routes,
        upstreamPort,
        navigationLabel,
        navigationPosition);
  }

  /** Public path prefixes, one per comma-separated entry. Navigation belongs to the first route. */
  private static List<String> routes(String value, String source, int line) {
    List<String> declared = new ArrayList<>();
    Set<String> seenRoutes = new HashSet<>();
    for (String candidate : value.split(",", -1)) {
      String path = candidate.strip();
      if (!path.matches("/(?:[a-z0-9]+(?:-[a-z0-9]+)*)?(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*")) {
        throw error(
            source,
            line,
            "`" + ROUTES + "` entries are absolute lowercase path prefixes, got: " + path);
      }
      if (!seenRoutes.add(path)) {
        throw error(source, line, "`" + ROUTES + "` names `" + path + "` twice");
      }
      declared.add(path);
    }
    return List.copyOf(declared);
  }

  private static int upstreamPort(String value, String source, int line) {
    try {
      int port = Integer.parseInt(value);
      if (port < 1 || port > 65535) {
        throw new NumberFormatException();
      }
      return port;
    } catch (NumberFormatException e) {
      throw error(
          source,
          line,
          "`" + UPSTREAM_PORT + "` must be an integer from 1 to 65535, got: " + value);
    }
  }

  /** The final colon is the separator so a visible label may itself contain a colon. */
  private static Navigation navigation(String value, String source, int line) {
    int separator = value.lastIndexOf(':');
    String label = separator < 1 ? "" : value.substring(0, separator).strip();
    String positionText = separator < 0 ? "" : value.substring(separator + 1).strip();
    if (label.isBlank() || label.length() > 120 || label.indexOf('\n') >= 0 || label.indexOf('\r') >= 0) {
      throw error(
          source,
          line,
          "`" + NAVIGATION + "` needs a non-blank label of at most 120 characters");
    }
    try {
      int position = Integer.parseInt(positionText);
      if (position < 1) {
        throw new NumberFormatException();
      }
      return new Navigation(label, position);
    } catch (NumberFormatException e) {
      throw error(
          source,
          line,
          "`" + NAVIGATION + "` ends with a positive position, got: " + value);
    }
  }

  private record Navigation(String label, int position) {}

  /**
   * Where a published host port is held. Two values and no third, and the file spells them the way
   * docker does ({@code host}, {@code ingress}) so neither side has to translate.
   *
   * <p>Refused rather than defaulted, like every other value here. The difference between the two
   * is whether a replacement can start while its predecessor still serves, and a typo answered with
   * a silent default is exactly what this parser exists not to do.
   */
  private static DeploymentDriver.PublishMode publishMode(String value, String source, int line) {
    for (DeploymentDriver.PublishMode candidate : DeploymentDriver.PublishMode.values()) {
      if (candidate.spelling().equals(value)) {
        return candidate;
      }
    }
    throw error(
        source,
        line,
        "`"
            + PUBLISH_MODE
            + "` must be `"
            + DeploymentDriver.PublishMode.HOST.spelling()
            + "` or `"
            + DeploymentDriver.PublishMode.INGRESS.spelling()
            + "`, got: "
            + value);
  }

  /**
   * How a replacement may overlap what it replaces. Two values and no third: the file is written by
   * a person in the orchestrator's own spelling ({@code start-first}, {@code stop-first}) and the
   * enum is read by a machine, so neither has to spell the other's convention.
   *
   * <p>An unrecognised value is refused rather than defaulted, like every other value here: the
   * difference between the two is whether an application is ever two processes at once, and
   * answering that with a silent default is exactly what this parser exists not to do.
   */
  private static DeploymentDriver.UpdateOrder updateOrder(String value, String source, int line) {
    for (DeploymentDriver.UpdateOrder candidate : DeploymentDriver.UpdateOrder.values()) {
      if (candidate.spelling().equals(value)) {
        return candidate;
      }
    }
    throw error(
        source,
        line,
        "`"
            + UPDATE_ORDER
            + "` must be `"
            + DeploymentDriver.UpdateOrder.START_FIRST.spelling()
            + "` or `"
            + DeploymentDriver.UpdateOrder.STOP_FIRST.spelling()
            + "`, got: "
            + value);
  }

  /**
   * The resources a repository asks to have provisioned before its container starts:
   * {@code postgresql:<name>[:<database>]}, comma-separated. One line, because this file has no
   * YAML sequences — and neither a type, a name nor a database may contain a comma or a colon,
   * which is what makes both separators safe.
   *
   * <p>A missing third segment means "the convention", not "no database": the default is {@code
   * qits_} plus the application name without its {@code qits-} prefix, and it is resolved by {@code
   * DeployService.register}, which is the first caller that knows the application's name. Null
   * travels out of here as that statement.
   *
   * <p>Both duplicates are refused, and only one of the two can be caught here. A repeated <b>name</b>
   * would make one env triple silently win over another; a repeated <b>literal database</b> would
   * point two of a repository's own resources at one store. The second form a defaulted database
   * can also take — two resources whose names both default to the same thing — is caught after
   * resolution, where the defaults exist.
   */
  private static List<DeploymentSpec.ResourceSpec> resources(String value, String source, int line) {
    List<DeploymentSpec.ResourceSpec> declared = new ArrayList<>();
    Set<String> names = new HashSet<>();
    Set<String> databases = new HashSet<>();
    for (String candidate : value.split(",", -1)) {
      String entry = candidate.strip();
      if (entry.isBlank()) {
        // `resources:` with nothing after it, or a trailing comma: a writer who meant to say
        // something. A silent empty answer is exactly what this parser exists to refuse.
        throw error(source, line, "`" + RESOURCES + "` has a blank entry");
      }
      String[] parts = entry.split(":", -1);
      if (parts.length < 2 || parts.length > 3) {
        throw error(
            source,
            line,
            "`"
                + RESOURCES
                + "` entries are `"
                + POSTGRESQL
                + ":<name>` or `"
                + POSTGRESQL
                + ":<name>:<database>`, got: "
                + entry);
      }
      if (!POSTGRESQL.equals(parts[0])) {
        throw error(
            source,
            line,
            "`" + RESOURCES + "` knows the type `" + POSTGRESQL + "` and no other, got: " + parts[0]);
      }
      String name;
      try {
        name = PdIdentifiers.requireResourceName(parts[1]);
      } catch (RuntimeException e) {
        throw error(
            source,
            line,
            "`"
                + RESOURCES
                + "` names are lowercase letters, digits and inner dashes (max 32), got: "
                + parts[1]);
      }
      String database = null;
      if (parts.length == 3) {
        try {
          database = PdIdentifiers.requireDatabaseName(parts[2]);
        } catch (RuntimeException e) {
          throw error(
              source,
              line,
              "`"
                  + RESOURCES
                  + "` databases are `qits_` followed by lowercase letters, digits and underscores"
                  + " (max 63), got: "
                  + parts[2]);
        }
      }
      if (!names.add(name)) {
        throw error(source, line, "`" + RESOURCES + "` names `" + name + "` twice");
      }
      if (database != null && !databases.add(database)) {
        throw error(source, line, "`" + RESOURCES + "` names the database `" + database + "` twice");
      }
      declared.add(new DeploymentSpec.ResourceSpec(name, database));
    }
    return List.copyOf(declared);
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
