package eu.wohlben.qits.platform.deployments.confighost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentExtrasSource;
import eu.wohlben.qits.platform.deployments.deployments.control.ExtrasSnapshot;
import eu.wohlben.qits.platform.deployments.deployments.control.ServiceExtras;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@link DeploymentExtrasSource}: the config volume's file,
 * and — where a deployment names one — qits-configuration on top of it.
 *
 * <pre>
 * GET &lt;extras-url&gt;/configuration/api/applications/&lt;application&gt;/resolved
 *   → {"headRevision": 7, "properties": {"qits.platform.deployments.extras.&lt;app&gt;.&lt;key&gt;": "…"}}
 * </pre>
 *
 * <p><b>{@code qits.platform.deployments.extras-url} unset is today's behaviour byte for byte.</b>
 * No request is made, nothing is parsed, and the answer is {@link ExtrasSnapshot#over(Config,
 * String)} — which is what a dev run, the clone-alone suite and every platform that has not adopted
 * the service still get.
 *
 * <p><b>Set, the service is AUTHORITATIVE.</b> Its properties are layered above the file's (see
 * {@code ExtrasSnapshot.SERVED_ORDINAL}), so a half-migrated platform that still carries the old
 * file on its volume cannot have that file shadow what the service says. The map arrives in the
 * full prefixed spelling, so nothing here translates the extras grammar — {@code ServiceExtras}
 * stays its single parser.
 *
 * <p><b>An unreachable or non-200 service REFUSES the deployment.</b> There is deliberately no
 * fall-back to the file, to the boot config or to anything read earlier: a stale extras value is the
 * exact failure that cost 2026-08-16, and a fall-back would ship it as a green deployment. {@link
 * ServiceExtras.Refused} names the url and what happened, and it reaches the deployment's detail
 * through the driver's own refusal arm — the argv is never built, so the deployment changed nothing.
 *
 * <p><b>The patience is the spec read's, stated as two keys.</b> {@code
 * qits.platform.deployments.extras-timeout-seconds} bounds the connect and the read, {@code
 * qits.platform.deployments.extras-attempts} is the whole retry budget — a service being redeployed
 * is seconds of refusals and no deployment should die of one, while an outage that outlasts the
 * budget must be a loud refusal rather than an unbounded wait on the deploy worker, which is
 * single-threaded and has everything else queued behind it.
 */
@ApplicationScoped
public class ConfigHostExtrasSource implements DeploymentExtrasSource {

  private static final Logger LOG = Logger.getLogger(ConfigHostExtrasSource.class);

  /**
   * Its own mapper, and nothing is registered for reflection: the body is read as a tree and pulled
   * apart by field name, so there is no bound type for the build-time analysis to miss. That is the
   * whole reason it is a tree rather than a record — a payload record here would be a third list to
   * keep beside {@code ApiWireReflection} and {@code EventWireReflection}.
   */
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String PROPERTIES = "properties";

  private static final String HEAD_REVISION = "headRevision";

  /** Looked up per key rather than {@code @ConfigProperty}: the key carries the application name. */
  @Inject Config config;

  @ConfigProperty(name = "qits.platform.deployments.extras-file")
  String extrasFile;

  /**
   * qits-configuration, or nothing. {@code Optional<String>} because SmallRye reads an empty value
   * as absent, so a deployment turns the service back off by emptying the variable rather than by
   * having to unset it.
   */
  @ConfigProperty(name = "qits.platform.deployments.extras-url")
  Optional<String> extrasUrl;

  @ConfigProperty(name = "qits.platform.deployments.extras-timeout-seconds")
  long timeoutSeconds;

  @ConfigProperty(name = "qits.platform.deployments.extras-attempts")
  int attempts;

  @Inject ExtrasBearer bearer;

  /**
   * How long a failed attempt waits before the next. Package-private rather than a key: it is an
   * implementation detail of the budget above, and the suite zeroes it so a refusal test costs no
   * seconds.
   */
  long retryPauseMillis = 1000;

  /** One client for the life of the process, {@code GitHostSpecSource}'s arrangement and its why. */
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
  public Config forApplication(String application) {
    Config file = ExtrasSnapshot.over(config, extrasFile);
    String base = extrasUrl.map(String::trim).filter(url -> !url.isEmpty()).orElse(null);
    if (base == null) {
      return file;
    }
    String url =
        trimTrailingSlash(base)
            + "/configuration/api/applications/"
            + segment(application)
            + "/resolved";
    JsonNode body = fetch(url);
    Map<String, String> served = properties(url, body);
    // Built rather than deferred to `infof`: this is the one line that records what a deployment
    // was configured with, and a backend that keeps the format and the parameters apart would make
    // the sentence unreadable to anything grepping for it — the suite included.
    LOG.info(
        url
            + " answered "
            + served.size()
            + " extras properties for "
            + application
            + " at config-revision="
            + revision(body));
    return ExtrasSnapshot.over(file, served, url);
  }

  /** The name as it goes into a path segment, refused rather than escaped if it is not one. */
  private static String segment(String application) {
    // Every application name reaching here came out of the topology, where PdIdentifiers holds it
    // to the dns-label charset. The check is the belt at the boundary, and refusing beats escaping:
    // a name that needs escaping is a name the rest of this component could not have stored.
    if (!application.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new ServiceExtras.Refused(
          "'" + application + "' is not an application name qits-configuration can be asked about");
    }
    return application;
  }

  /**
   * The read, and the whole of the retry budget. Everything that is not a 200 with a body this can
   * parse ends as a refusal naming the url — there is no arm that answers with something older.
   */
  private JsonNode fetch(String url) {
    String lastFailure = null;
    int budget = Math.max(1, attempts);
    for (int attempt = 1; attempt <= budget; attempt++) {
      if (attempt > 1 && retryPauseMillis > 0) {
        try {
          Thread.sleep(retryPauseMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new ServiceExtras.Refused(url + " could not be read: interrupted");
        }
      }
      try {
        HttpResponse<String> response =
            client().send(request(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          return parse(url, response.body());
        }
        // A 404 is NOT an answer here, unlike the spec read's: a repository with no deployments.yml
        // deploys with the defaults, while an application qits-configuration has never heard of is
        // an application whose extras this deployment cannot know it is missing.
        lastFailure = "it answered " + response.statusCode();
      } catch (ServiceExtras.Refused e) {
        // A body that will not parse is not going to parse on the second attempt.
        throw e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ServiceExtras.Refused(url + " could not be read: interrupted");
      } catch (Exception e) {
        lastFailure = e.toString();
      }
      LOG.warnf("Could not read %s (attempt %d of %d): %s", url, attempt, budget, lastFailure);
    }
    throw new ServiceExtras.Refused(
        url
            + " is this deployment's extras and could not be read after "
            + budget
            + " attempts: "
            + lastFailure);
  }

  private HttpRequest request(String url) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            // The forward-auth half, for a platform running qits-configuration open on qits-net
            // during the transition. The bearer below is the other half and outranks it where the
            // client is enabled; a service that takes neither refuses the read, loudly.
            .header("X-Qits-User", "qits-deployments")
            .header("X-Qits-Roles", "qits:system")
            .GET();
    bearer.token().ifPresent(token -> request.header("Authorization", "Bearer " + token));
    return request.build();
  }

  private static JsonNode parse(String url, String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      throw new ServiceExtras.Refused(
          url + " is this deployment's extras and did not answer JSON: " + e.getMessage());
    }
  }

  /**
   * The properties map, in the full prefixed spelling the service already stores it in. A document
   * with no such object is a refusal rather than an empty answer: "this application states nothing"
   * and "this is not the resolved document" would otherwise be the same deployment.
   */
  private static Map<String, String> properties(String url, JsonNode body) {
    JsonNode properties = body == null ? null : body.get(PROPERTIES);
    if (properties == null || !properties.isObject()) {
      throw new ServiceExtras.Refused(
          url + " is this deployment's extras and answered no '" + PROPERTIES + "' object");
    }
    Map<String, String> served = new HashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      JsonNode value = field.getValue();
      if (!value.isValueNode()) {
        throw new ServiceExtras.Refused(
            url + ": '" + field.getKey() + "' is not a config value");
      }
      served.put(field.getKey(), value.asText());
    }
    return served;
  }

  /**
   * What was deployed with, as far as this component can record it today: the deployment row's
   * detail is written by {@code DeployService} out of the driver's verdict and there is no seam a
   * config revision could ride there, so it is stated in the log beside the read.
   */
  private static String revision(JsonNode body) {
    JsonNode revision = body == null ? null : body.get(HEAD_REVISION);
    return revision == null || !revision.isNumber() ? "unknown" : revision.asText();
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
