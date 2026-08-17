package eu.wohlben.qits.platform.deployments.deployments.control;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Deployment config as it reads NOW, for one argv.
 *
 * <p><b>The boot Config is a snapshot and it goes stale.</b> Deployment config reaches this
 * component as a properties file on its config volume, and Quarkus' config-dir convention reads
 * {@code <user.dir>/config/application.properties} exactly once, at boot. So an edit on that volume
 * changed nothing until the process was replaced — and worse, every deployment re-stamped the boot
 * snapshot onto the service it updated, so a live fix applied with {@code service update --env-add}
 * was reverted by the next deployment of any application. That happened, on 2026-08-16, and it cost
 * a day.
 *
 * <p><b>So the file is re-read per argv build</b> and layered over the boot config: it outranks
 * every source the boot config carries (system properties are 400, the environment 300), and the
 * boot config answers everything the file does not state. Edit the file, and the next deployment
 * carries it.
 *
 * <p><b>One snapshot per argv build, and that is the invariant {@link ServiceExtras} rests on.</b>
 * Its javadoc says every reading agrees, which is only true of a fixed {@link Config}: the file is
 * read into a map here, so a snapshot answers the same thing however often it is asked and however
 * the file changes under it. A caller takes one and hands it to every reading of one deployment.
 *
 * <p><b>An absent file is the boot config itself</b>, byte for byte the behaviour a dev run and the
 * clone-alone suite always had — neither has such a file.
 *
 * <p><b>A file that is there and cannot be read REFUSES the deployment</b>, naming the path. Falling
 * back to the boot values would be the stale value this class exists to kill, and it would be
 * invisible: the deployment goes green carrying whatever the process booted with.
 *
 * <p><b>There is a second shape where a platform runs qits-configuration</b> — {@link #over(Config,
 * Map, String)}, the properties that service resolved for this application, over the boot config
 * and with <b>no file under them at all</b>. Which layers exist is {@code
 * DeploymentExtrasSource}'s decision; this class only states the order.
 */
public final class ExtrasSnapshot {

  /** Above every source the boot config carries — system properties are 400, the environment 300. */
  private static final int FILE_ORDINAL = 1000;

  /**
   * Above the boot config, and there is nothing between them: qits-configuration is AUTHORITATIVE
   * where it is configured at all, so its caller hands this the boot config rather than the file.
   * The ordinal stays above {@link #FILE_ORDINAL} so a snapshot dump reads in source order.
   */
  private static final int SERVED_ORDINAL = 2000;

  private static final int BOOT_ORDINAL = 100;

  private ExtrasSnapshot() {}

  /**
   * The config one argv build reads: {@code extrasFile} over {@code boot}, or {@code boot} alone
   * when there is no such file.
   *
   * @throws ServiceExtras.Refused the file is there and could not be read
   */
  public static Config over(Config boot, String extrasFile) {
    Path path = locate(extrasFile);
    if (!Files.exists(path)) {
      return boot;
    }
    return new SmallRyeConfigBuilder()
        .withSources(
            new PropertiesConfigSource(read(path), path.toString(), FILE_ORDINAL),
            new BootConfigSource(boot))
        .build();
  }

  /**
   * The config one argv build reads when a service answered for it: {@code served} over {@code
   * base}, which is whatever {@link #over(Config, String)} already produced.
   *
   * <p>The map arrives in the full prefixed spelling — {@code
   * qits.platform.deployments.extras.<app>.<key>} — so it is layered rather than translated, and
   * {@link ServiceExtras} stays the single parser of that grammar.
   *
   * <p><b>{@code base} is the boot config, never the file.</b> An authoritative source is the SOLE
   * source: with a file under this layer, a key deleted from the service is re-served by whatever
   * the volume still carries, and deleting an entry is exactly what the service exists to make
   * possible. What the service does not state falls through to the boot config alone.
   *
   * @param source what the snapshot names this layer in a config dump — the url it was read from
   */
  public static Config over(Config base, Map<String, String> served, String source) {
    return new SmallRyeConfigBuilder()
        .withSources(
            new PropertiesConfigSource(served, source, SERVED_ORDINAL), new BootConfigSource(base))
        .build();
  }

  /**
   * Quarkus' own config-dir convention: a relative path is relative to the process's working
   * directory, which is {@code /work} on the deployment host with the config volume at
   * {@code /work/config}. So the file named here is the one the boot config already read.
   */
  private static Path locate(String extrasFile) {
    Path stated = Path.of(extrasFile);
    return stated.isAbsolute()
        ? stated
        : Path.of(System.getProperty("user.dir", ".")).resolve(stated);
  }

  private static Map<String, String> read(Path path) {
    Properties stated = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      stated.load(reader);
    } catch (IOException | IllegalArgumentException e) {
      // A malformed unicode escape is an IllegalArgumentException and is the same failure: config
      // this process cannot read. Both leave the argv unbuilt, so the deployment changed nothing —
      // and neither may reach the deploy worker as something nothing catches.
      throw new ServiceExtras.Refused(
          path + " is deployment config and could not be read: " + e.getMessage());
    }
    Map<String, String> properties = new HashMap<>();
    stated.forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));
    return properties;
  }

  /**
   * The boot config as a source, so this snapshot re-reads one file rather than rebuilding the
   * process's whole configuration: everything the file does not state answers exactly as it did.
   */
  private record BootConfigSource(Config boot) implements ConfigSource {

    @Override
    public Set<String> getPropertyNames() {
      Set<String> names = new HashSet<>();
      boot.getPropertyNames().forEach(names::add);
      return names;
    }

    @Override
    public String getValue(String name) {
      // An empty value is absent to the conversion, which is what the boot config already answered
      // for `env.FLAG=`; the name is still listed, and the reader states what an absence means.
      return boot.getOptionalValue(name, String.class).orElse(null);
    }

    @Override
    public String getName() {
      return "boot config";
    }

    @Override
    public int getOrdinal() {
      return BOOT_ORDINAL;
    }
  }
}
