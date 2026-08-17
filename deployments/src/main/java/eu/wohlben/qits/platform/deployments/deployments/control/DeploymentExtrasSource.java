package eu.wohlben.qits.platform.deployments.deployments.control;

import org.eclipse.microprofile.config.Config;

/**
 * Where one argv build reads {@code qits.platform.deployments.extras.<application>.*} from.
 *
 * <p><b>It is a seam because the answer may be another service's.</b> The extras were the config
 * volume's properties file and nothing else, re-read per argv by {@link ExtrasSnapshot}; a platform
 * that sets {@code qits.platform.deployments.extras-url} moves the authority to qits-configuration,
 * which is an HTTP call and therefore belongs in {@code service/} — the {@code SpecSource} rule
 * applied a fourth time.
 *
 * <p><b>One call is one snapshot, and that is the invariant {@link ServiceExtras} rests on.</b> Its
 * javadoc says every reading agrees, which is only true of a fixed {@link Config}: a caller takes
 * one snapshot per argv build and hands it to every reading of that deployment. Calling this twice
 * for one argv is the bug it is shaped to prevent.
 *
 * <p><b>An implementation that cannot answer REFUSES the deployment</b> — {@link
 * ServiceExtras.Refused}, naming what it could not read. It never falls back to a value it read
 * earlier or to the boot config: a stale extras value is the exact failure class this whole line of
 * work exists to kill, and it ships invisibly, as a green deployment carrying last boot's config.
 */
@FunctionalInterface
public interface DeploymentExtrasSource {

  /**
   * The config this application's extras are read out of, as it reads now.
   *
   * @param application the deployed application's name — the segment in the key family
   * @throws ServiceExtras.Refused the extras could not be read
   */
  Config forApplication(String application);
}
