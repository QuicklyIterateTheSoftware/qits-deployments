package eu.wohlben.qits.platform.deployments.confighost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;

/**
 * A qits-configuration that answers whatever a test scripts, on a real socket.
 *
 * <p><b>It is an HTTP stub rather than a fake at the seam</b>, and that is deliberate for these
 * tests alone: what is under test here IS the request — the url it is built at, the headers it
 * carries and the patience it spends — and a fake {@code DeploymentExtrasSource} would assert the
 * test's own model of a client. Everything ABOVE the seam uses a scripted lambda instead, which is
 * the repo's ordinary fake doctrine.
 *
 * <p>The JDK's own server, so nothing arrives on the classpath and no docker is involved.
 */
public final class ExtrasStub implements AutoCloseable {

  /** One scripted answer: what the service says this time. */
  public record Answer(int status, String body) {}

  private static final Answer EMPTY = new Answer(200, "{\"headRevision\":1,\"properties\":{}}");

  private final HttpServer server;

  /** One-shot answers, consumed in order; when they run out the standing answer repeats. */
  private final Deque<Answer> scripted = new ArrayDeque<>();

  private final List<String> paths = new ArrayList<>();

  /** One entry per request, the Authorization header or null — the absence is an assertion too. */
  private final List<String> authorizations = new ArrayList<>();

  private volatile Answer standing = EMPTY;

  public ExtrasStub() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("could not start a stub qits-configuration", e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  private void handle(HttpExchange exchange) throws IOException {
    Answer answer;
    synchronized (this) {
      paths.add(exchange.getRequestURI().getPath());
      authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
      answer = scripted.poll();
    }
    if (answer == null) {
      answer = standing;
    }
    byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(answer.status(), body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  /** What this stub answers once every scripted one-shot has been consumed. */
  public ExtrasStub answers(int status, String body) {
    standing = new Answer(status, body);
    return this;
  }

  /** The resolved document for one application, in the shape the service publishes. */
  public ExtrasStub resolves(long headRevision, String... keysAndValues) {
    StringBuilder json = new StringBuilder("{\"headRevision\":").append(headRevision).append(",\"properties\":{");
    for (int i = 0; i < keysAndValues.length; i += 2) {
      if (i > 0) {
        json.append(',');
      }
      json.append('"').append(keysAndValues[i]).append("\":\"").append(keysAndValues[i + 1]).append('"');
    }
    return answers(200, json.append("}}").toString());
  }

  /** One answer that is used before the standing one — the failure a retry gets past. */
  public synchronized ExtrasStub then(int status, String body) {
    scripted.add(new Answer(status, body));
    return this;
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public synchronized List<String> paths() {
    return List.copyOf(paths);
  }

  /** A null entry is a request that carried no Authorization header, which is an assertion here. */
  public synchronized List<String> authorizations() {
    return new ArrayList<>(authorizations);
  }

  /**
   * A source pointed at this stub, with the retry pause zeroed so a refusal costs a test no seconds.
   * Here rather than in a test of its own because the source's fields are package-private and this
   * is the package they are visible in.
   */
  public ConfigHostExtrasSource source(Config boot, String extrasFile, ExtrasBearer bearer) {
    return source(boot, extrasFile, bearer, url());
  }

  /** The same, aimed anywhere — an address nothing listens on is a test of its own. */
  public static ConfigHostExtrasSource source(
      Config boot, String extrasFile, ExtrasBearer bearer, String extrasUrl) {
    ConfigHostExtrasSource source = new ConfigHostExtrasSource();
    source.config = boot;
    source.extrasFile = extrasFile;
    source.extrasUrl = Optional.ofNullable(extrasUrl);
    source.timeoutSeconds = 2;
    source.attempts = 1;
    source.bearer = bearer;
    source.retryPauseMillis = 0;
    return source;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
