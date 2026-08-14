package eu.wohlben.qits.platform.deployments.deployments.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;

/**
 * What one application needs beyond its image — mounts, published ports, extra groups, extra
 * environment — as deployment config states it, and as a driver reads it.
 *
 * <p><b>This is the contract, and it is structured rather than free-form.</b> It
 * replaces {@code qits.platform.deployments.run-args.<application>}, a free-form {@code docker run}
 * argv that was whitespace split and appended verbatim. That was justified by "the argv is docker's
 * vocabulary"; it stopped being true the moment a service create had to render the same intent —
 * {@code -v} is {@code --mount}, {@code --group-add} is {@code --group}, and a publish is a
 * different word again. Translating one orchestrator's argv into another's is guessing at intent
 * from a spelling. So config states the intent and <b>each driver renders it</b>.
 *
 * <h2>The grammar</h2>
 *
 * <pre>
 * qits.platform.deployments.extras.&lt;application&gt;.mounts[&lt;i&gt;]    = volume:&lt;name&gt;:&lt;target&gt;[:ro]
 *                                                                 | bind:&lt;host-path&gt;:&lt;target&gt;[:ro]
 * qits.platform.deployments.extras.&lt;application&gt;.publishes[&lt;i&gt;] = [&lt;ip&gt;:]&lt;host-port&gt;:&lt;port&gt;[/tcp|/udp]
 * qits.platform.deployments.extras.&lt;application&gt;.groups[&lt;i&gt;]    = &lt;gid&gt;
 * qits.platform.deployments.extras.&lt;application&gt;.env.&lt;KEY&gt;      = &lt;value&gt;
 * </pre>
 *
 * <p>The index orders a list and means nothing else. Environment is keyed by the variable instead,
 * because a variable is named once by definition — and because a generated file that renumbers
 * twenty entries to add one is a file nobody edits safely.
 *
 * <p><b>An unknown or malformed key is a refused deployment, not a warning.</b> Config is typed
 * now, so garbage in it is a bug — and the failure it used to produce was the worst kind: a
 * container that boots, passes its health gate and has lost its volume. {@link Refused} carries the
 * key and what is wrong with it into the deployment's {@code detail}.
 *
 * <p><b>Only the deployed application's own keys are ever read</b>, which is the security property
 * the free-form family had and this one keeps: one application's socket bind cannot ride along on a
 * sibling's deployment. It is the reason the <b>dotted spelling is the only one</b> — {@code
 * QITS_PLATFORM_DEPLOYMENTS_EXTRAS_QITS_CI_ENV_X} cannot be told apart from a key of {@code
 * qits-ci-daemon}, because both a dash and a dot become an underscore. Deployment config reaches
 * this component as a properties file on its config volume, where the dot is a dot.
 *
 * <p>Nothing arriving over HTTP contributes here. Deployment config is the trust domain that
 * already holds the docker socket, and it is the only source.
 */
public record ServiceExtras(
    List<Mount> mounts, List<Publish> publishes, List<String> groups, List<String> env) {

  /** The application that stated nothing — most of them. */
  public static final ServiceExtras NONE =
      new ServiceExtras(List.of(), List.of(), List.of(), List.of());

  private static final Pattern INDEXED = Pattern.compile("(mounts|publishes|groups)\\[(\\d{1,4})]");

  /** The env spelling, so a value cannot forge a second variable out of its key. */
  private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

  private static final String ENV = "env.";

  public ServiceExtras {
    mounts = List.copyOf(mounts);
    publishes = List.copyOf(publishes);
    groups = List.copyOf(groups);
    env = List.copyOf(env);
  }

  /** A named volume or a host path, and the two are not interchangeable to any orchestrator. */
  public enum MountKind {
    VOLUME,
    BIND
  }

  /** {@code source} is a volume name or a host path, {@code target} an absolute container path. */
  public record Mount(MountKind kind, String source, String target, boolean readOnly) {}

  /**
   * One published host port. {@code ip} is null unless the config named one, {@code protocol} null
   * unless it named one — both are absences a renderer states in its own way rather than guesses.
   *
   * <p><b>The publish MODE is not here.</b> Whether the port is held by the task or by swarm's
   * routing mesh is {@code publish_mode} in the repository's own {@code deployments.yml}, one
   * statement for the whole service rather than one per port, and it reaches a renderer on the
   * service spec — see {@code DeploymentDriver.PublishMode}. Deployment config states which ports
   * a service publishes; the repository states what kind of service it is.
   */
  public record Publish(String ip, int published, int target, String protocol) {

    /**
     * Whether this publish is content with every interface — no ip, or the one that says so.
     *
     * <p>It exists because <b>swarm's publish syntax has no ip field, in either mode</b> (measured:
     * a host-mode publish listens on {@code 0.0.0.0}). A spec that names a loopback address is
     * therefore not renderable there, and the swarm driver refuses it rather than binding every
     * interface and warning — the ports bound to loopback on this platform are bound there for a
     * reason, and an unauthenticated endpoint quietly reachable from the network is exactly what a
     * warning does not prevent.
     */
    public boolean bindsAllInterfaces() {
      return ip == null || "0.0.0.0".equals(ip);
    }
  }

  /**
   * Deployment config said something this component cannot render. Never answers a caller: it ends
   * one deployment, recorded FAILED with this message.
   */
  public static class Refused extends RuntimeException {
    public Refused(String message) {
      super(message);
    }
  }

  /** Whether this application stated anything at all. */
  public boolean isEmpty() {
    return mounts.isEmpty() && publishes.isEmpty() && groups.isEmpty() && env.isEmpty();
  }

  /**
   * Read one application's extras. Only keys under its own prefix are looked at, and every one of
   * them has to parse.
   *
   * <p>Called more than once per deployment, and that is deliberate: it is a pure function of
   * config, so every reading agrees, and a refusal that arrives before anything is applied is a
   * deployment that changed nothing.
   */
  public static ServiceExtras of(Config config, String application) {
    String prefix = DeploymentDriver.EXTRAS_PREFIX + application + ".";
    Map<Integer, Mount> mounts = new TreeMap<>();
    Map<Integer, Publish> publishes = new TreeMap<>();
    Map<Integer, String> groups = new TreeMap<>();
    // Sorted by variable name, so one application's argv is the same argv every time it is built.
    Map<String, String> env = new TreeMap<>();
    for (String name : config.getPropertyNames()) {
      if (!name.startsWith(prefix)) {
        continue;
      }
      String element = name.substring(prefix.length());
      // An empty value is absent to SmallRye's Optional conversion, which is right for a port and
      // wrong for `-e FLAG=`; the empty string is what that means.
      String value = config.getOptionalValue(name, String.class).orElse("");
      if (element.startsWith(ENV)) {
        String key = element.substring(ENV.length());
        if (!ENV_KEY.matcher(key).matches()) {
          throw refuse(name, "'" + key + "' is not an environment variable name");
        }
        env.put(key, value);
        continue;
      }
      Matcher indexed = INDEXED.matcher(element);
      if (!indexed.matches()) {
        throw refuse(
            name,
            "no such element — this family is env.<KEY>, mounts[i], publishes[i] and groups[i]");
      }
      int index = Integer.parseInt(indexed.group(2));
      switch (indexed.group(1)) {
        case "mounts" -> mounts.put(index, mount(name, required(name, value)));
        case "publishes" -> publishes.put(index, publish(name, required(name, value)));
        default -> groups.put(index, group(name, required(name, value)));
      }
    }
    List<String> assignments = new ArrayList<>();
    env.forEach((key, value) -> assignments.add(key + "=" + value));
    return new ServiceExtras(
        List.copyOf(mounts.values()),
        List.copyOf(publishes.values()),
        List.copyOf(groups.values()),
        assignments);
  }

  private static Mount mount(String key, String value) {
    String[] parts = value.split(":", -1);
    if (parts.length < 3 || parts.length > 4) {
      throw refuse(key, "'" + value + "' is not <volume|bind>:<source>:<target>[:ro]");
    }
    MountKind kind =
        switch (parts[0]) {
          case "volume" -> MountKind.VOLUME;
          case "bind" -> MountKind.BIND;
          default -> throw refuse(key, "'" + parts[0] + "' is neither volume nor bind");
        };
    String source = parts[1];
    String target = parts[2];
    if (source.isBlank() || target.isBlank()) {
      throw refuse(key, "'" + value + "' has no source or no target");
    }
    // The kind is stated rather than guessed from a leading slash, and then it is held to: the
    // guess is what made a mistyped volume name a silent bind mount of a directory docker creates.
    if (kind == MountKind.BIND && !source.startsWith("/")) {
      throw refuse(key, "a bind's source is an absolute host path, and '" + source + "' is not");
    }
    if (kind == MountKind.VOLUME && source.startsWith("/")) {
      throw refuse(key, "'" + source + "' is a path, so it is a bind rather than a volume");
    }
    if (!target.startsWith("/")) {
      throw refuse(key, "a mount target is an absolute path in the container, and '" + target + "' is not");
    }
    if (parts.length == 4 && !"ro".equals(parts[3])) {
      throw refuse(key, "'" + parts[3] + "' is not a mount option here — only ro is");
    }
    return new Mount(kind, source, target, parts.length == 4);
  }

  private static Publish publish(String key, String value) {
    String ports = value;
    String protocol = null;
    int slash = value.indexOf('/');
    if (slash >= 0) {
      protocol = value.substring(slash + 1).toLowerCase(Locale.ROOT);
      ports = value.substring(0, slash);
      if (!"tcp".equals(protocol) && !"udp".equals(protocol)) {
        throw refuse(key, "'" + protocol + "' is neither tcp nor udp");
      }
    }
    String[] parts = ports.split(":", -1);
    if (parts.length < 2 || parts.length > 3) {
      throw refuse(key, "'" + value + "' is not [<ip>:]<host-port>:<port>[/<protocol>]");
    }
    String ip = parts.length == 3 ? parts[0] : null;
    if (ip != null && !IPV4.matcher(ip).matches()) {
      throw refuse(key, "'" + ip + "' is not an IPv4 address");
    }
    return new Publish(
        ip, port(key, parts[parts.length - 2]), port(key, parts[parts.length - 1]), protocol);
  }

  private static int port(String key, String value) {
    int port;
    try {
      port = Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw refuse(key, "'" + value + "' is not a port number");
    }
    if (port < 1 || port > 65535) {
      throw refuse(key, value + " is not a port number");
    }
    return port;
  }

  private static String group(String key, String value) {
    if (!value.matches("[A-Za-z0-9_-]+")) {
      throw refuse(key, "'" + value + "' is not a group id or name");
    }
    return value;
  }

  private static String required(String key, String value) {
    if (value.isBlank()) {
      throw refuse(key, "it has no value");
    }
    return value;
  }

  private static Refused refuse(String key, String why) {
    return new Refused(key + ": " + why);
  }
}
