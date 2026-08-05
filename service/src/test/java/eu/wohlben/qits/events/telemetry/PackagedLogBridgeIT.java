package eu.wohlben.qits.events.telemetry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.telemetry.OtlpLogStub.Captured;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The log bridge on the <em>packaged artifact</em> — the fast-jar under {@code ./mvnw verify
 * -DskipITs=false}, the GraalVM binary under {@code -Dnative}.
 *
 * <p>{@link OtelLogBridgeTest} proves the bridge in the build JVM, and that is not enough to ship
 * on. Every platform service runs as a native image, and the two things this proof depends on are
 * exactly the two a native image can lose: a log <b>handler</b> installed during the extension's
 * runtime initialisation, and an <b>exporter</b> that marshals protobuf and opens a connection. Both
 * are build-time-sensitive, both are invisible to a {@code @QuarkusTest} — which augments in the
 * build JVM with the whole classpath present — and a binary that dropped either would start
 * cleanly, serve every request and log nothing anywhere but its own console.
 *
 * <p>It also runs the <b>shipped</b> configuration rather than the suite's: the launched process
 * takes the {@code prod} profile, so {@code %test.quarkus.otel.sdk.disabled=true} does not apply and
 * the four {@code quarkus.otel.logs.*} keys in {@code application.properties} are what is under
 * test. Only the endpoint is redirected, at {@link OtlpLogStub}, whose base carries qits'
 * non-default {@code /observability/api/otel} prefix so that the SDK's {@code /v1/logs} suffixing is
 * exercised too.
 *
 * <p>The two records it asserts on are ones the <em>shipped</em> code produces, because a fixture
 * class in {@code src/test} is not in the artifact:
 *
 * <ul>
 *   <li>Quarkus' own startup line, an ordinary INFO record through the same JBoss Log Manager every
 *       application logger uses;
 *   <li>a genuine unhandled server error, which is the shape that matters most — an ERROR with a
 *       throwable, raised inside a request, which is what an operator goes looking for.
 * </ul>
 */
@QuarkusIntegrationTest
@TestProfile(PackagedLogBridgeIT.PackagedUnderTarget.class)
@WithTestResource(OtlpLogStub.class)
public class PackagedLogBridgeIT {

  /**
   * The same {@code user.home} relocation {@code PackagedSurfaceIT} makes, into a directory of its
   * own so the two ITs cannot clear each other's database mid-run: the events jar's shipped JDBC url
   * is {@code ${user.home}}-rooted, and without this the launched process would migrate into the
   * developer's real {@code ~/.qits}.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {
    static final Path HOME = Path.of("target", "events-log-bridge-it-home").toAbsolutePath();

    @Override
    public Map<String, String> getConfigOverrides() {
      deleteRecursively(HOME);
      return Map.of("user.home", HOME.toString());
    }
  }

  /** The launched process exports on its own schedule; every wait here is a deadline. */
  private static final Duration ARRIVAL = Duration.ofSeconds(30);

  @Test
  public void theArtifactShipsItsOrdinaryLogRecordsOverOtlp() {
    // Quarkus' own "started in" line: an org.jboss.logging record at INFO, made by the packaged
    // process before any test touched it. If the handler did not survive the image build, nothing
    // at all arrives here and the assertion says exactly that.
    Captured started =
        OtlpLogStub.await(
                c -> c.record().getSeverityNumber().getNumber() == 9 && c.body().contains("started in"),
                ARRIVAL)
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the packaged artifact exported no INFO record — the OTel log handler is "
                            + "not installed in this build. "
                            + OtlpLogStub.describe()));

    // Identity comes from quarkus.application.name through the OTel resource, which is what
    // qits-observability buckets a source by. A binary that lost the resource would report
    // "_unscoped" in the live UI, and that is a silent failure worth a test.
    assertEquals(
        Optional.of("qits-events"),
        started.resourceAttribute("service.name"),
        "the packaged process must identify itself; " + OtlpLogStub.describe());
    assertEquals("INFO", started.record().getSeverityText());
    assertTrue(started.record().getTimeUnixNano() > 0, "the record carried no timestamp");
  }

  @Test
  public void anUnhandledServerErrorArrivesWithItsThrowableAndTraceContext() {
    // A real failure rather than a staged one: `name` is varchar(512) and nothing above the database
    // bounds it, so an over-long name is a genuine unhandled exception on the write path — a 500,
    // logged by the server at ERROR with the throwable, from inside the request's span. Marshalling
    // a throwable into exception.* attributes and reading the ambient trace context are precisely
    // the two things a native image could quietly stop doing.
    String marker = "packaged-log-bridge-" + System.nanoTime();
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\""
                + marker
                + "x".repeat(600)
                + "\",\"occurredAt\":\"2026-08-05T09:00:00Z\"}")
        .when()
        .post("/events/api/events")
        .then()
        .statusCode(500);

    // MEASURED: one failure makes SEVERAL records, and the severity is what tells them apart. Two
    // Hibernate/Arjuna WARNs (severity 13) carry the same stack trace, and the ERROR (17) is
    // QuarkusErrorHandler's "HTTP Request to ... failed, error id: …" — the record an operator's
    // errors feed shows. So the predicate matches on severity as well as on the marker; matching on
    // the marker alone picks a WARN and quietly proves less.
    Captured error =
        OtlpLogStub.await(
                c ->
                    c.record().getSeverityNumber().getNumber() == 17
                        && c.attribute("exception.stacktrace")
                            .filter(s -> s.contains(marker))
                            .isPresent(),
                ARRIVAL)
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the packaged artifact exported no ERROR record carrying the failure's "
                            + "stack trace. "
                            + OtlpLogStub.describe()));

    assertEquals("ERROR", error.record().getSeverityText());

    // The three stable OTel exception attributes, the same ones OtelLogBridgeTest pins in the JVM.
    // Only the stack trace's content is asserted: the exception TYPE of a persistence failure is the
    // ORM's business and would make this test a pin on Hibernate's wrapping rather than on the
    // bridge.
    assertTrue(
        error.attribute("exception.type").isPresent(), "no exception.type on a logged throwable");
    assertTrue(
        error.attribute("exception.message").isPresent(),
        "no exception.message on a logged throwable");

    // Correlation: the record belongs to the trace of the request that failed, so the error and the
    // request that caused it are one thing in the UI rather than two.
    assertNotEquals(
        "",
        error.traceId(),
        "an error logged inside a request must carry its trace id; " + OtlpLogStub.describe());
    assertNotEquals("", error.spanId(), "an error logged inside a request must carry its span id");
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (Exception e) {
      throw new IllegalStateException("could not clear " + root, e);
    }
  }
}
