package eu.wohlben.qits.webui;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * {@code /platform-deployments} → {@code /platform-deployments/}, and nothing else.
 *
 * <p>Quinoa mounts the web client at {@code /platform-deployments/*}, which does not match the bare
 * segment — so without this route, typing {@code /platform-deployments} into a browser answers 404
 * while {@code /platform-deployments/} serves the client (upstream quinoa issue #960). Not a
 * defensible surface: the segment is this service's to serve in every spelling, and the bare one
 * means "take me to the client".
 *
 * <p>GET and HEAD only — the bare segment has no meaning for a write, and a machine client POSTing
 * here gets a 405 rather than a bounce at HTML. 301, because the answer will never be anything
 * else, and the query string travels. The same route, for the same reason, exists in qits-events,
 * qits-projects, qits-ci, qits-platform-artifacts, qits-observability and qits-workspaces; the
 * platform's Quinoa reference calls a gateway-level redirect the alternative, and until there is
 * one this is the per-service answer.
 *
 * <p>This is the one route in this service that is not JAX-RS, and it deliberately needs no entry
 * in {@code quarkus.quinoa.ignored-path-prefixes}: those are matched under {@code
 * /platform-deployments/*}, and the bare segment is outside it.
 *
 * <p>The package is {@code eu.wohlben.qits.webui} rather than a component-flavoured one, so the
 * file stays recognisable across the repositories that carry a copy of it.
 */
@Singleton
public class WebUiRedirect {

  /** The gateway segment this service is served under — {@code quarkus.quinoa.ui-root-path}. */
  private static final String SEGMENT = "/platform-deployments";

  void init(@Observes Router router) {
    router
        .route(SEGMENT)
        .method(HttpMethod.GET)
        .method(HttpMethod.HEAD)
        .handler(
            rc -> {
              // Vert.x path routes are trailing-slash tolerant: route("/x") matches /x/ too, and
              // answering the slash form here would sit AHEAD of Quinoa and loop the redirect onto
              // itself. Only the exact bare segment is this route's business.
              if (!SEGMENT.equals(rc.request().path())) {
                rc.next();
                return;
              }
              String query = rc.request().query();
              rc.response()
                  .setStatusCode(301)
                  .putHeader(
                      "Location", query == null ? SEGMENT + "/" : SEGMENT + "/?" + query)
                  .end();
            });
  }
}
