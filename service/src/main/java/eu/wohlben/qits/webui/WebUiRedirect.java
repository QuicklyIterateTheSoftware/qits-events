package eu.wohlben.qits.webui;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * {@code /events} → {@code /events/}, and nothing else.
 *
 * <p>Quinoa mounts the web client at {@code /events/*}, which does not match the bare segment — so
 * without this route, typing {@code /events} into a browser answers 404 while {@code /events/}
 * serves the client (upstream quinoa issue #960). Not a defensible surface: the segment is this
 * service's to serve in every spelling, and the bare one means "take me to the client".
 *
 * <p>GET and HEAD only — the bare segment has no meaning for a write, and a machine client POSTing
 * here gets a 405 rather than a bounce at HTML. 301, because the answer will never be anything
 * else, and the query string travels. The same route, for the same reason, exists in qits-projects,
 * qits-ci, qits-artifacts, qits-observability and qits-workspaces; the platform's Quinoa reference
 * calls a gateway-level redirect the alternative, and until there is one this is the per-service
 * answer.
 */
@Singleton
public class WebUiRedirect {

  void init(@Observes Router router) {
    router
        .route("/events")
        .method(HttpMethod.GET)
        .method(HttpMethod.HEAD)
        .handler(
            rc -> {
              // Vert.x path routes are trailing-slash tolerant: route("/events") matches /events/
              // too, and answering the slash form here would sit AHEAD of Quinoa and loop the
              // redirect onto itself. Only the exact bare segment is this route's business.
              if (!"/events".equals(rc.request().path())) {
                rc.next();
                return;
              }
              String query = rc.request().query();
              rc.response()
                  .setStatusCode(301)
                  .putHeader("Location", query == null ? "/events/" : "/events/?" + query)
                  .end();
            });
  }
}
