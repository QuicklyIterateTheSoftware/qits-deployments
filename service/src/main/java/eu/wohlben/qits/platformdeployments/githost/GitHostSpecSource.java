package eu.wohlben.qits.platformdeployments.githost;

import eu.wohlben.qits.platformdeployments.deployments.control.DeploymentSpecParser;
import eu.wohlben.qits.platformdeployments.deployments.control.SpecException;
import eu.wohlben.qits.platformdeployments.deployments.control.SpecSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@link SpecSource}: one {@code GET} against the platform
 * git host's blob endpoint, the same contract qits-ci reads a pipeline definition through.
 *
 * <pre>
 * GET &lt;qits.pd.git-host-url&gt;/git/&lt;repoId&gt;/blob/&lt;sha&gt;/.config/qits/deployments.yml
 * </pre>
 *
 * <p>This is the component's <b>only</b> outbound HTTP call, and it is deliberately made with the
 * JDK's own client rather than a generated REST client — one request, one path, no model to share.
 * Both path segments were validated at the intake (a repo-id slug, a hex sha) before anything
 * reached here, so neither can leave the path it is written into.
 *
 * <p>It used to be one of two: the topology was another service, and every registration and every
 * resolution was a second client with a second failure mode. The merge left this one.
 *
 * <p><b>404 is an answer, not a failure.</b> A repository that carries no spec gets every default
 * and deploys exactly as it did before the file existed. Every other outcome — a refused
 * connection, a 500, a timeout, an unparseable file — raises {@link SpecException} and fails the
 * deployment: the spec decides where the container runs and what may reach it, and a guess there is
 * worse than a recorded failure.
 */
@ApplicationScoped
public class GitHostSpecSource implements SpecSource {

  private static final Logger LOG = Logger.getLogger(GitHostSpecSource.class);

  @ConfigProperty(name = "qits.pd.git-host-url")
  String gitHostUrl;

  @ConfigProperty(name = "qits.pd.git-host-timeout-seconds")
  long timeoutSeconds;

  /**
   * One client for the life of the process, the sibling's arrangement (qits-ci's {@code
   * HttpGitConfigSource} holds one too). A {@code HttpClient} owns a selector thread and a
   * connection pool, so building one per green build spends both on a single request; built lazily
   * because the timeout it is configured with is a config value, and config is not injected yet
   * when the field initialiser would run.
   */
  private volatile HttpClient client;

  private HttpClient client() {
    HttpClient existing = client;
    if (existing == null) {
      synchronized (this) {
        existing = client;
        if (existing == null) {
          existing =
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
          client = existing;
        }
      }
    }
    return existing;
  }

  @Override
  public DeploymentSpec read(String repoId, String sha) {
    String url = trimTrailingSlash(gitHostUrl) + "/git/" + repoId + "/blob/" + sha + "/" + SPEC_PATH;
    HttpResponse<String> response;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .GET()
              .build();
      response = client().send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SpecException("interrupted while reading " + url, e);
    } catch (Exception e) {
      throw new SpecException("could not read " + url + ": " + e, e);
    }

    if (response.statusCode() == 404) {
      LOG.debugf("%s carries no %s at %s — deploying with the defaults", repoId, SPEC_PATH, sha);
      return DeploymentSpec.DEFAULTS;
    }
    if (response.statusCode() != 200) {
      throw new SpecException(
          "could not read " + url + ": the git host answered " + response.statusCode());
    }
    return DeploymentSpecParser.parse(response.body(), SPEC_PATH + " of " + repoId + "@" + sha);
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
