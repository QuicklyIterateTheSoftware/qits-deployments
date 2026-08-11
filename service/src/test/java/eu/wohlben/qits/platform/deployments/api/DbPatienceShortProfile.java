package eu.wohlben.qits.platform.deployments.api;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * The read-patience deadline, shortened so a test can watch it run out.
 *
 * <p>The shipped value is 15S ({@code application.properties}), which is the right number for a
 * request held while a container restarts and the wrong one for a suite: the "still failing after
 * the deadline" claim has to actually reach the deadline. It is overridden here rather than in
 * {@code src/test/resources/application.properties} on purpose — that file carries only what every
 * test needs, and re-declaring a shipped setting there makes the whole suite green about a value no
 * deployment gets.
 */
public class DbPatienceShortProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qits.platform.deployments.db-retry-deadline", "1S");
  }
}
