package eu.wohlben.qits.platform.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The suite's stand-in for the git host — {@code @Mock}, so no {@code @QuarkusTest} in this module
 * ever makes cd's one outbound HTTP call.
 *
 * <p><b>Its default answer is {@link DeploymentSpec#DEFAULTS}</b>, and that is the backward
 * compatibility contract in one line: a repository that declares nothing behaves exactly as every
 * repository did before the file existed. A test that wants a spec scripts one for its repository
 * by name.
 *
 * <p>Application-scoped and therefore shared: reset it in {@code @BeforeEach} and use distinct
 * repository ids per test. State is read through methods only — the injected reference is a CDI
 * client proxy.
 */
@Mock
@ApplicationScoped
public class FakeSpecSource implements SpecSource {

  private final Map<String, DeploymentSpec> specs = new ConcurrentHashMap<>();
  private final Map<String, String> failures = new ConcurrentHashMap<>();

  public void reset() {
    specs.clear();
    failures.clear();
  }

  /**
   * What this application declares, whatever sha is asked for.
   *
   * <p><b>Keyed by the APPLICATION NAME</b>, which is the repository's name when the announcement
   * carried one and its storage id when it did not — the same answer {@link
   * RepositoryRef#applicationName()} gives the deployment. A test that announces an id alone
   * scripts by that id, exactly as it always did; a test that announces the name pair scripts by
   * the name. Keying by the raw id instead would make a name-addressed test script a spec no read
   * could find.
   */
  public void script(String applicationName, DeploymentSpec spec) {
    specs.put(applicationName, spec);
  }

  /** Script the git host being unreachable, or the file being unreadable, for this application. */
  public void scriptFailure(String applicationName, String message) {
    failures.put(applicationName, message);
  }

  @Override
  public DeploymentSpec read(RepositoryRef repository, String sha) {
    String failure = failures.get(repository.applicationName());
    if (failure != null) {
      throw new SpecException(failure);
    }
    return specs.getOrDefault(repository.applicationName(), DeploymentSpec.DEFAULTS);
  }
}
