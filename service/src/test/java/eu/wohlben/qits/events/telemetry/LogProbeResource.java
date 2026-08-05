package eu.wohlben.qits.events.telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

/**
 * Test-only: logs an ERROR with a real throwable <em>from inside a server span</em>, and reports the
 * ids of the span it logged under.
 *
 * <p>A route rather than a plain method call, because the trace half of the proof cannot be staged:
 * the span a log record must correlate to is the one Quarkus' HTTP instrumentation opens for a
 * request, and a span opened by hand in a test would prove that the handler copies whatever context
 * happens to be current — not that a real request's context reaches a real log line. Returning the
 * ids is what lets the assertion compare the exported record against the span the server actually
 * used, rather than against a second reading of the same source.
 *
 * <p>The logger is {@code org.jboss.logging.Logger}, the one this service already uses (see {@code
 * stream/EventStreamSubscriptions}), and nothing here touches OpenTelemetry to emit — that is the
 * point. Served under {@code /events/api/test-log-probe}; hidden from the OpenAPI document like its
 * sibling fixture.
 */
@Path("/test-log-probe")
@Produces(MediaType.APPLICATION_JSON)
public class LogProbeResource {

  private static final Logger LOG = Logger.getLogger(LogProbeResource.class);

  /** The span the request ran under, as the ids a log record carries. */
  public record ProbedSpan(String traceId, String spanId) {}

  @GET
  @Operation(hidden = true)
  public ProbedSpan probe(@QueryParam("marker") String marker) {
    SpanContext span = Span.current().getSpanContext();
    LOG.errorf(
        new IllegalStateException("the cause of " + marker),
        "%s failed while serving a request",
        marker);
    return new ProbedSpan(span.getTraceId(), span.getSpanId());
  }
}
