package eu.wohlben.qits.platform.deployments.swarmhost;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jboss.logging.Logger;

/**
 * {@code qits.platform.deployments.run-args.<application>} in swarm's spelling.
 *
 * <p>The key family is docker's {@code run} argv, whitespace split, and almost none of it is valid
 * on a {@code service create}: a {@code -v} is a {@code --mount}, a {@code --group-add} is a
 * {@code --group}, a {@code -p 127.0.0.1:8081:8080} has no equivalent at all. So the string is
 * translated here rather than appended.
 *
 * <p><b>Translating rather than restructuring the key is a deliberate half-measure</b>, and the
 * other half is a later phase. The free-form string was justified by "the argv is docker's
 * vocabulary"; with two orchestrators that stopped being true, and the destination is a structured
 * key family each driver renders for itself. Until then this keeps the swarm path honest — a
 * deployed application gets its volumes and its environment — and keeps the security property
 * exactly where it was: only the deployed application's own key is ever read, so one application's
 * socket mount cannot ride along on a sibling's deployment.
 *
 * <p><b>What is dropped is dropped loudly.</b> An unrecognised token is a WARN naming it and the
 * application, because a silently ignored {@code --add-host} is a deployment that works everywhere
 * except where it matters. The one translation that changes meaning is a publish: swarm's syntax
 * has no {@code ip} field in either mode, so {@code 127.0.0.1:8081:8080} becomes {@code
 * published=8081,target=8080,mode=host} and binds every interface. That is said out loud too.
 */
final class SwarmRunArgs {

  private static final Logger LOG = Logger.getLogger(SwarmRunArgs.class);

  private SwarmRunArgs() {}

  /**
   * @param application whose key this is, for the warnings
   * @param runArgs the raw value, already whitespace split
   * @return the same intent in {@code service create} flags, in the order it was written
   */
  static List<String> translate(String application, List<String> runArgs) {
    List<String> swarm = new ArrayList<>();
    for (int i = 0; i < runArgs.size(); i++) {
      String flag = runArgs.get(i);
      String value = i + 1 < runArgs.size() ? runArgs.get(i + 1) : null;
      switch (flag) {
        case "-v", "--volume" -> {
          if (consumed(application, flag, value)) {
            swarm.add("--mount");
            swarm.add(mount(value));
            i++;
          }
        }
        case "-e", "--env" -> {
          if (consumed(application, flag, value)) {
            swarm.add("--env");
            swarm.add(value);
            i++;
          }
        }
        case "--group-add" -> {
          if (consumed(application, flag, value)) {
            swarm.add("--group");
            swarm.add(value);
            i++;
          }
        }
        case "-u", "--user" -> {
          if (consumed(application, flag, value)) {
            swarm.add("--user");
            swarm.add(value);
            i++;
          }
        }
        case "--add-host" -> {
          if (consumed(application, flag, value)) {
            swarm.add("--host");
            swarm.add(value);
            i++;
          }
        }
        case "-l", "--label" -> {
          // A `docker run --label` labels the CONTAINER; the service's own labels are this
          // component's bookkeeping and are written separately.
          if (consumed(application, flag, value)) {
            swarm.add("--container-label");
            swarm.add(value);
            i++;
          }
        }
        case "-p", "--publish" -> {
          if (consumed(application, flag, value)) {
            swarm.add("--publish");
            swarm.add(publish(application, value));
            i++;
          }
        }
        case "--restart", "--name", "--network", "--network-alias", "-d", "--detach" -> {
          // The service's own: a swarm service has a restart policy, a name that is its address,
          // and a network set declared by the driver. Dropped without a warning, because keeping
          // them would be the bug rather than losing them.
          if (!flag.equals("-d") && !flag.equals("--detach") && value != null) {
            i++;
          }
        }
        default ->
            LOG.warnf(
                "Dropping run-arg '%s' of %s: it has no swarm spelling here yet", flag, application);
      }
    }
    return List.copyOf(swarm);
  }

  /** A flag whose value ran off the end of the string is a truncated argument, not an argument. */
  private static boolean consumed(String application, String flag, String value) {
    if (value == null) {
      LOG.warnf("Dropping run-arg '%s' of %s: nothing follows it", flag, application);
      return false;
    }
    return true;
  }

  /**
   * {@code source:target[:mode]} → a {@code --mount} descriptor. A source that starts with a slash
   * or a dot is a path and therefore a bind; anything else is a named volume, which is what every
   * platform application uses.
   */
  private static String mount(String value) {
    String[] parts = value.split(":");
    String source = parts[0];
    String target = parts.length > 1 ? parts[1] : parts[0];
    boolean bind = source.startsWith("/") || source.startsWith(".");
    StringBuilder mount = new StringBuilder(bind ? "type=bind" : "type=volume");
    mount.append(",source=").append(source).append(",target=").append(target);
    for (int i = 2; i < parts.length; i++) {
      if ("ro".equals(parts[i].toLowerCase(Locale.ROOT))) {
        mount.append(",readonly");
      }
    }
    return mount.toString();
  }

  /**
   * {@code [ip:]published:target} → {@code published=…,target=…,mode=host}.
   *
   * <p>{@code mode=host} rather than the ingress default: it is per node, like a plain {@code docker
   * run}, and this platform is one node. The {@code ip} is dropped because swarm's syntax has no
   * field for it — measured with {@code ss}: a host-mode publish listens on {@code 0.0.0.0}. The
   * loopback binds this platform has are a decision of their own and the plan makes it (§4.2); what
   * this can do is say so.
   */
  private static String publish(String application, String value) {
    String[] parts = value.split(":");
    String published = parts.length >= 2 ? parts[parts.length - 2] : parts[0];
    String target = parts[parts.length - 1];
    if (parts.length >= 3) {
      LOG.warnf(
          "Publishing %s of %s on ALL interfaces: swarm's publish syntax has no ip field, so the"
              + " '%s' in the run-args is dropped",
          value, application, parts[0]);
    }
    return "published=" + published + ",target=" + target + ",mode=host";
  }
}
