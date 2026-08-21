package eu.wohlben.qits.platform.deployments.githost;

import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentSpecParser;
import eu.wohlben.qits.platform.deployments.deployments.control.RepositoryRef;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecException;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource;
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
 * git host's blob endpoint ({@code qits.platform.deployments.git-host-url}), the same contract
 * qits-ci reads a pipeline definition through.
 *
 * <pre>
 * GET &lt;git-host-url&gt;/git/&lt;projectId&gt;/&lt;repoName&gt;/blob/&lt;sha&gt;/.config/qits/deployments.yml
 * GET &lt;git-host-url&gt;/git/&lt;repoId&gt;/blob/&lt;sha&gt;/.config/qits/deployments.yml
 * </pre>
 *
 * <p><b>Two addresses for one blob, and the first is the one to use.</b> The git host's repository
 * key is an opaque storage UUID now, and {@code /git/<repoId>} is its internal scheme; the public
 * address is {@code (projectId, repoName)} and it is what a build event carries. So an announcement
 * with the name pair is read name-addressed, and one without it — an older publisher, or a push
 * that arrived on the internal route — keeps the id URL, which is exactly the request this made
 * before the pair existed.
 *
 * <p>This is the component's <b>only</b> outbound HTTP call, and it is deliberately made with the
 * JDK's own client rather than a generated REST client — one request, one path, no model to share.
 * Every path segment was validated at the intake (the slug discipline for the id, the project and
 * the name; a hex sha) before anything reached here, so none can leave the path it is written into.
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

  @ConfigProperty(name = "qits.platform.deployments.git-host-url")
  String gitHostUrl;

  @ConfigProperty(name = "qits.platform.deployments.git-host-timeout-seconds")
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
  public DeploymentSpec read(RepositoryRef repository, String sha) {
    String url = blobUrl(address(repository), sha);
    HttpResponse<String> response = get(url);

    if (response.statusCode() == 404) {
      // A NAME-addressed 404 can be a false miss rather than "no spec". The name route resolves
      // through qits-projects and its database, and the very read that decides how to deploy an
      // infrastructure service (the database, or qits-projects itself) can land in the window that
      // service is being cut over — the resolver answers 404 and this deploys the DEFAULTS, whose
      // HTTP health gate a plain postgres cannot pass, so it crash-loops and never recovers. The
      // id-addressed route needs no resolver, so a name-addressed miss is retried there before it
      // is believed. A true no-spec repository 404s on both and still gets the defaults.
      if (repository.nameAddressed()) {
        String idUrl = blobUrl(repository.repoId(), sha);
        HttpResponse<String> byId = get(idUrl);
        if (byId.statusCode() == 200) {
          return DeploymentSpecParser.parse(
              byId.body(), SPEC_PATH + " of " + repository.applicationName() + "@" + sha);
        }
        if (byId.statusCode() != 404) {
          throw new SpecException(
              "could not read " + idUrl + ": the git host answered " + byId.statusCode());
        }
      }
      LOG.debugf(
          "%s carries no %s at %s — deploying with the defaults",
          repository.applicationName(), SPEC_PATH, sha);
      return DeploymentSpec.DEFAULTS;
    }
    if (response.statusCode() != 200) {
      throw new SpecException(
          "could not read " + url + ": the git host answered " + response.statusCode());
    }
    return DeploymentSpecParser.parse(
        response.body(), SPEC_PATH + " of " + repository.applicationName() + "@" + sha);
  }

  private String blobUrl(String addressSegment, String sha) {
    return trimTrailingSlash(gitHostUrl) + "/git/" + addressSegment + "/blob/" + sha + "/" + SPEC_PATH;
  }

  private HttpResponse<String> get(String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .header("X-Qits-User", "qits-deployments")
              .header("X-Qits-Roles", "qits:system")
              .GET()
              .build();
      return client().send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SpecException("interrupted while reading " + url, e);
    } catch (Exception e) {
      throw new SpecException("could not read " + url + ": " + e, e);
    }
  }

  /**
   * The path segments that name the repository: the public pair when the event carried one, the
   * internal storage id when it did not. Package-private so {@code GitHostSpecSourceTest} can hold
   * both arms without a socket.
   */
  static String address(RepositoryRef repository) {
    return repository.nameAddressed()
        ? repository.projectId() + "/" + repository.repoName()
        : repository.repoId();
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
