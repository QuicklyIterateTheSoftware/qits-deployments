package eu.wohlben.qits.platform.deployments.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the keys a
 * deployment would supply: {@code jdbc.url}, {@code username}, {@code password}.
 *
 * <p>It is a config source rather than lines in {@code src/test/resources/application.properties}
 * because the port is chosen at run time — the instance takes a free one, so nothing can be written
 * down ahead of the JVM that starts it.
 *
 * <p><b>Two datasources, six values.</b> This deployable boots two stores: its own registry
 * ({@code platformdeployments}) and the qits-eventstream jar's claim ledger and outbox
 * ({@code eventstream}). The bus is dark in {@code %test}, and dark is not absent — Quarkus opens
 * the connection and runs Flyway at boot either way — so the second one needs a database of its own
 * or the suite does not start. Separate databases rather than separate schemas, because the two
 * carry separate Flyway lineages.
 *
 * <p>The ordinal sits above application.properties (250) so this wins over both the shipped
 * defaults in the environments and eventstream jars and anything the test properties file might
 * carry, and it is registered through {@code META-INF/services}, which is how a config source joins
 * a Quarkus application without being a bean.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /** This module's database on the shared instance — {@code deployments} names its own. */
  private static final String DATABASE = "pd_svc";

  /** The bus client's, named for this module too so no sibling suite can mean the same one. */
  private static final String EVENTSTREAM_DATABASE = "pd_eventstream_svc";

  private static final String PREFIX = "quarkus.datasource.platformdeployments.";

  private static final String EVENTSTREAM_PREFIX = "quarkus.datasource.eventstream.";

  private final Map<String, String> values =
      Map.of(
          PREFIX + "jdbc.url", EmbeddedPg.url(DATABASE),
          PREFIX + "username", EmbeddedPg.USER,
          PREFIX + "password", EmbeddedPg.PASSWORD,
          EVENTSTREAM_PREFIX + "jdbc.url", EmbeddedPg.url(EVENTSTREAM_DATABASE),
          EVENTSTREAM_PREFIX + "username", EmbeddedPg.USER,
          EVENTSTREAM_PREFIX + "password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
