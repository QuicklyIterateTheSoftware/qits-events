package eu.wohlben.qits.events.telemetry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.telemetry.OtlpLogStub.Captured;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The decision gate: does an <em>unchanged</em> {@code org.jboss.logging.Logger} call leave this
 * service as OTLP, with everything qits-observability reads off it?
 *
 * <p>The platform's whole application-logging design rests on one claim — that Quarkus' OpenTelemetry
 * logging handler bridges ordinary JBoss Logging records onto the OTLP exporter this service already
 * configures for traces and metrics — and that claim is <b>preview</b> in Quarkus. The live system
 * having been seen carrying telemetry is not evidence a repository keeps. This is: a named canary
 * record, decoded out of the actual {@code ExportLogsServiceRequest}, with the exact fields asserted.
 *
 * <p><b>What is asserted here is what was measured, not what the specification suggests.</b> The
 * exception attribute keys in particular are the ones Quarkus 3.34.6's {@code OpenTelemetryLogHandler}
 * really writes ({@code exception.type} / {@code exception.message} / {@code exception.stacktrace},
 * from stable {@code ExceptionAttributes}); it writes several more that this proof deliberately does
 * not pin, because they are incubating and would turn a Quarkus upgrade into a red suite over a
 * rename nothing in qits reads — {@code code.function.name}, {@code code.line.number}, {@code
 * thread.name}, {@code thread.id}, {@code log.logger.namespace}.
 *
 * <p>{@code OtelLogExporterUnreachableTest} is the other half: this class proves the records arrive,
 * that one proves the service does not care when they cannot.
 */
@QuarkusTest
@TestProfile(OtelLogBridgeTest.ExportingProfile.class)
@WithTestResource(OtlpLogStub.class)
public class OtelLogBridgeTest {

  /**
   * Turns the exporter back on for this class alone.
   *
   * <p>{@code application.properties} ships {@code %test.quarkus.otel.sdk.disabled=true} so that an
   * ordinary suite does not spend itself retrying against a {@code qits-observability} that does not
   * resolve outside a deployment. That is the right default and stays; the classes that are ABOUT
   * the exporter say so themselves. {@link OtlpLogStub} sets the same key — this is here because a
   * profile override outranks a profiled key in the shipped file with no ordering to reason about,
   * and the point of the class is lost if the SDK is quietly off.
   */
  public static class ExportingProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.otel.sdk.disabled", "false");
    }
  }

  /** The logger a service class would use, spelled the way they spell it. */
  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(OtelLogBridgeTest.class);

  /**
   * Export is asynchronous by design — the batch processor ships on {@code
   * quarkus.otel.blrp.schedule.delay}, one second by default — so every wait here is a deadline
   * rather than an expectation of promptness.
   */
  private static final Duration ARRIVAL = Duration.ofSeconds(20);

  @BeforeEach
  void forgetEarlierRecords() {
    OtlpLogStub.reset();
  }

  @Test
  public void anOrdinaryInfoCallArrivesAsOtlpCarryingThisServicesIdentity() {
    String marker = "qits-events-canary-info-" + System.nanoTime();
    long before = System.currentTimeMillis();
    LOG.infof("%s recorded by the canary", marker);
    long after = System.currentTimeMillis();

    Captured info = awaitBody(marker);

    // Identity is a RESOURCE concern, not a prefix on the message: this is the value
    // qits-observability buckets a source by, and it comes from quarkus.application.name.
    assertEquals(
        Optional.of("qits-events"),
        info.resourceAttribute("service.name"),
        "the exported resource must name this service; " + dump(info));

    // Severity, both halves. The number is the OTel data model's (INFO = 9) and is what a query
    // layer filters on; the text is the log manager's own level name and is what a person reads.
    assertEquals(9, info.record().getSeverityNumber().getNumber(), dump(info));
    assertEquals("INFO", info.record().getSeverityText(), dump(info));

    // The body is the FORMATTED message. That matters: a bridge that shipped the format string and
    // its parameters separately would look correct in a decoder and be unreadable in the UI.
    assertEquals(marker + " recorded by the canary", info.body(), dump(info));

    long stamped = TimeUnit.NANOSECONDS.toMillis(info.record().getTimeUnixNano());
    assertTrue(
        stamped >= before && stamped <= after,
        "the record's timestamp must be when it was logged, not when it was exported: "
            + stamped
            + " outside ["
            + before
            + ", "
            + after
            + "]");
    // observedTime is stamped too, and it is NOT simply a copy: the two are taken from different
    // clocks a hair apart — measured here landing microseconds either side of `time`, in both
    // directions — so a receiver that ordered records by their difference would be reading noise.
    // Both are the moment of the call, which is the only property worth pinning.
    long observedAt = TimeUnit.NANOSECONDS.toMillis(info.record().getObservedTimeUnixNano());
    assertTrue(
        observedAt >= before && observedAt <= after,
        "observedTime must also be the moment the record was made: "
            + observedAt
            + " outside ["
            + before
            + ", "
            + after
            + "]; "
            + dump(info));

    // Logged outside any span. MEASURED: the absence is an ABSENT protobuf field — an empty byte
    // string, not sixteen or thirty-two zero bytes — so a receiver testing for a zero-filled id
    // would read "no trace context" as garbage. This is the shape the correlated case below is
    // measured against.
    assertEquals("", info.traceId(), "a record logged outside a span must carry no trace id");
    assertEquals("", info.spanId(), "a record logged outside a span must carry no span id");
  }

  @Test
  public void anErrorInsideARequestCarriesItsThrowableAndThatRequestsTraceContext() {
    String marker = "qits-events-canary-error-" + System.nanoTime();

    // A real request, so the span is a real server span opened by Quarkus' HTTP instrumentation.
    // The route hands back the ids it logged under; nothing here reads the trace context twice.
    LogProbeResource.ProbedSpan span =
        given()
            .queryParam("marker", marker)
            .when()
            .get("/events/api/test-log-probe")
            .then()
            .statusCode(200)
            .extract()
            .as(LogProbeResource.ProbedSpan.class);

    Captured error = awaitBody(marker);

    assertEquals(
        Optional.of("qits-events"), error.resourceAttribute("service.name"), dump(error));
    assertEquals(17, error.record().getSeverityNumber().getNumber(), dump(error));
    assertEquals("ERROR", error.record().getSeverityText(), dump(error));
    assertEquals(marker + " failed while serving a request", error.body(), dump(error));

    // The throwable as STRUCTURE rather than as text appended to the body — the three stable OTel
    // exception attributes, which is what lets the errors feed show a type and a stack trace without
    // parsing a formatted line. These three names were read off Quarkus' handler, not recalled.
    assertEquals(
        Optional.of(IllegalStateException.class.getName()),
        error.attribute("exception.type"),
        dump(error));
    assertEquals(
        Optional.of("the cause of " + marker), error.attribute("exception.message"), dump(error));
    String stacktrace =
        error
            .attribute("exception.stacktrace")
            .orElseThrow(() -> new AssertionError("no exception.stacktrace; " + dump(error)));
    assertTrue(
        stacktrace.contains(IllegalStateException.class.getName())
            && stacktrace.contains(LogProbeResource.class.getName()),
        "the stack trace must be the throwable's own; got: " + stacktrace);

    // The body must NOT have swallowed the stack trace: the two are separate fields on the wire, and
    // a body carrying a formatted trace is the shape that makes structured errors impossible.
    assertTrue(
        !error.body().contains("java.lang.IllegalStateException"),
        "the body must stay the message; got: " + error.body());

    // Trace correlation: the ids on the record are the ids of the span the request ran under, so the
    // log lands on that trace's page rather than beside it.
    assertNotEquals(
        "0".repeat(32), span.traceId(), "the request opened no span at all — nothing to correlate");
    assertEquals(span.traceId(), error.traceId(), dump(error));
    assertEquals(span.spanId(), error.spanId(), dump(error));
  }

  @Test
  public void theOtelHandlerIsAnAdditionalCopyAndNeverTheOnlyOne() {
    // Remote shipping is a SECOND destination for a record, not a redirection of it. If enabling
    // OTel logs ever displaced the console handler, a container's stdout — the durable-at-host copy,
    // and the only one that survives the receiver being down — would go silent from a green build.
    // Asserted on the live log context rather than by capturing streams, because the console handler
    // holds the real console it was built with and a swapped System.out would not see it.
    List<Handler> handlers = flatten(LogContext.getLogContext().getLogger("").getHandlers());
    List<String> names = handlers.stream().map(h -> h.getClass().getName()).toList();

    assertTrue(
        names.stream().anyMatch(n -> n.endsWith("OpenTelemetryLogHandler")),
        "the OTel handler must be attached — nothing else ships application logs; got " + names);
    assertTrue(
        names.stream().anyMatch(n -> n.endsWith("ConsoleHandler")),
        "the console handler must still be attached beside it; got " + names);

    // And the console really is still emitting, not merely present with everything filtered out.
    Handler console =
        handlers.stream()
            .filter(h -> h.getClass().getName().endsWith("ConsoleHandler"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        console.isLoggable(
            new org.jboss.logmanager.ExtLogRecord(
                org.jboss.logmanager.Level.INFO, "still on the console", getClass().getName())),
        "the console handler must still accept INFO");
  }

  private static Captured awaitBody(String marker) {
    return OtlpLogStub.await(c -> c.body().contains(marker), ARRIVAL)
        .orElseThrow(
            () ->
                new AssertionError(
                    "no exported log record carried "
                        + marker
                        + " — the bridge did not export it. "
                        + OtlpLogStub.describe()));
  }

  /** Handlers can nest ({@code ExtHandler} holds children), so the tree is walked, not the top. */
  private static List<Handler> flatten(Handler[] handlers) {
    List<Handler> out = new ArrayList<>();
    for (Handler handler : handlers) {
      out.add(handler);
      if (handler instanceof ExtHandler nested) {
        out.addAll(flatten(nested.getHandlers()));
      }
    }
    return out;
  }

  /** Everything about one record, for an assertion message that says what actually arrived. */
  private static String dump(Captured captured) {
    return "\n  scope="
        + captured.scope().getName()
        + "\n  severity="
        + captured.record().getSeverityText()
        + "/"
        + captured.record().getSeverityNumber().getNumber()
        + "\n  body="
        + captured.body()
        + "\n  time="
        + captured.record().getTimeUnixNano()
        + " observedTime="
        + captured.record().getObservedTimeUnixNano()
        + "\n  traceId="
        + captured.traceId()
        + " spanId="
        + captured.spanId()
        + "\n  resource="
        + captured.resource().getAttributesList()
        + "\n  attributes="
        + captured.record().getAttributesList();
  }
}
