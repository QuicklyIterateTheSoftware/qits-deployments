package eu.wohlben.qits.platform.deployments.deployments.control;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * A deployment that was never told the postgres superuser's password — the shipped state, since
 * {@code qits.platform.deployments.postgres.admin-password} deliberately has no default.
 *
 * <p>The empty value is what makes it absent rather than blank: SmallRye reads an empty string as
 * ABSENT, which is the same fact {@code LegacyNetworkOffProfile} turns on its head for the network
 * key. It needs a profile, and therefore a Quarkus restart, because the value is injected as an
 * {@code Optional<String>} at bean creation and nothing in the process can take it away later.
 */
public class MissingAdminPasswordProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qits.platform.deployments.postgres.admin-password", "");
  }
}
