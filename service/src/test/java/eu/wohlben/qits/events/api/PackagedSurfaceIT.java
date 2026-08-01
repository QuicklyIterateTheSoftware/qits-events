package eu.wohlben.qits.events.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.stream.FakeSubscriber;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The surface of the <em>packaged artifact</em> — the fast-jar under {@code ./mvnw verify
 * -DskipITs=false}, the GraalVM binary under {@code -Dnative} — because that is the only place a
 * whole class of failure is visible.
 *
 * <p>Every other test here is a {@code @QuarkusTest}: it augments and runs in the build JVM, with
 * the full classpath present, reflection unrestricted, an in-memory H2 — and, crucially, <b>Quinoa
 * disabled</b>. Quinoa is off by default in test mode, so no {@code @QuarkusTest} in this repo has
 * ever seen the client at all; a unit test asserting something about {@code /events/} would pass
 * against a process with no client in it. What the SPA is actually served as is proven here or
 * nowhere.
 *
 * <p>The probe list is the platform's, from {@code docs/project-setup-quinoa-angular.md}:
 *
 * <ul>
 *   <li>{@code /events/} → 200 HTML carrying the right {@code <base href>} — the client's own
 *       spelling of the segment, set in another repository's {@code angular.json}, where no build
 *       here can check it. Wrong, and the page loads and then fetches its JavaScript from nowhere.
 *   <li>a deep link → 200 {@code index.html}, so the Angular router owns it across a reload
 *   <li>{@code /events/api/<real>} → the API's own answer; {@code /events/api/nope} → 404 and
 *       <b>never</b> {@code text/html}. A machine client parses {@code index.html} as data, so the
 *       content type is as much of the assertion as the status.
 *   <li>the readiness endpoint qits-cd's health gate curls, at the address the deployment assumes
 *   <li>{@code /events/stream}: a plain GET → 404 and not the client, and the upgrade → a working
 *       socket. Two probes rather than one, because they fail for opposite reasons —
 *       websockets-next claims only the <em>handshake</em>, so the plain GET is the Quinoa question
 *       and the upgrade is the "did the endpoint survive augmentation and the native image?"
 *       question. qits-ci learned the first by measuring it: before the prefix was ignored, a plain
 *       GET on its daemon socket answered 200 {@code index.html} from a green build.
 * </ul>
 *
 * <p>ITs are skipped by default ({@code skipITs} in the root pom) because they need a `package`, and
 * a package here needs the webui submodule and a node on PATH — neither of which the clone-alone
 * rule promises. Ask for them explicitly.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedSurfaceIT.PackagedUnderTarget.class)
public class PackagedSurfaceIT {

  /**
   * Relocates the launched artifact's state under {@code target/} by moving {@code user.home}, not
   * by restating the settings — the events jar's datasource default is {@code ${user.home}}-rooted,
   * so overriding {@code user.home} leaves the <b>shipped</b> JDBC URL itself under test (the
   * AUTO_SERVER lesson: a url that a JVM opens happily and a native image dies on). Without this the
   * IT would migrate into the developer's real {@code ~/.qits}.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {
    static final Path HOME = Path.of("target", "events-packaged-it-home").toAbsolutePath();

    @Override
    public Map<String, String> getConfigOverrides() {
      deleteRecursively(HOME);
      return Map.of("user.home", HOME.toString());
    }
  }

  /**
   * The socket's absolute literal. {@code /events/stream} does not follow {@code quarkus.rest.path}
   * — it carries the segment itself — and it is the address a publisher's config derives, so it is
   * spelled here in full rather than built from a relative one.
   */
  @TestHTTPResource("/events/stream")
  URI stream;

  @Test
  public void theClientIsServedAtTheSegmentWithItsOwnBaseHref() {
    String html =
        given()
            .when()
            .get("/events/")
            .then()
            .statusCode(200)
            .contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(
        html.contains("<base href=\"/events/\">"),
        "the client's baseHref must be the segment it is mounted at; got: "
            + html.substring(0, Math.min(400, html.length())));
  }

  @Test
  public void aDeepLinkFallsBackToTheClientSoItsRouterOwnsIt() {
    String deepLink =
        given()
            .when()
            .get("/events/some/route")
            .then()
            .statusCode(200)
            .contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(
        deepLink.contains("<base href=\"/events/\">"),
        "a deep link must answer with index.html, not with a differently-shaped page");
  }

  @Test
  public void theBareSegmentRedirectsRatherThanFourOhFouring() {
    // Quinoa mounts at /events/*, which does not match the bare segment (upstream #960) — the
    // redirect in webui/WebUiRedirect is this service's answer, and it only exists in the packaged
    // process alongside a real client.
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/events")
        .then()
        .statusCode(301)
        .header("Location", "/events/");
  }

  @Test
  public void realRoutesAnswerAndAMistypedOneIsNeverHtml() {
    given().when().get("/events/api/events").then().statusCode(200).contentType(ContentType.JSON);

    // The whole reason quarkus.quinoa.ignored-path-prefixes is set: without /api in that list this
    // answers 200 with index.html, and a machine client parses the client's not-found page as data.
    //
    // The assertion is "404, and not the CLIENT" rather than the reference's shorter "404, never
    // HTML", because what actually comes back here is Vert.x' own stock 53-byte
    // `<h1>Resource not found</h1>` — text/html, and correct. Every sibling service answers a
    // mistyped machine path the same way; nothing in the platform installs a JSON 404 handler for
    // unrouted paths, and asserting on the content type alone would fail against the right
    // behaviour while still passing against the wrong one (index.html is text/html too). So the
    // status and the absence of the client are what is pinned.
    String body =
        given().when().get("/events/api/nope").then().statusCode(404).extract().asString();
    assertFalse(
        body.contains("<base href=\"/events/\">"),
        "a mistyped machine path must not be answered with the client; got: " + body);

    // qits-gateway routes verbatim by prefix, so there is no unprefixed form to fall back to.
    given().when().get("/api/events").then().statusCode(404);
  }

  @Test
  public void theVocabularyRouteAnswersJsonAndNotTheClient() {
    // /events/api/events/names is a literal beside the /{id} template, and it is the newest place
    // the two ways this can go wrong meet: JAX-RS could match the template (404, "Event not found:
    // names") or — if /api ever left quarkus.quinoa.ignored-path-prefixes — Quinoa's catch-all could
    // answer 200 index.html, which a filter's dropdown would parse as data. Both are invisible to a
    // @QuarkusTest, where Quinoa is disabled and there is no client at all.
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"PackagedVocabulary\",\"occurredAt\":\"2026-07-31T09:00:00Z\"}")
        .when()
        .post("/events/api/events")
        .then()
        .statusCode(200);

    given()
        .when()
        .get("/events/api/events/names")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("names", org.hamcrest.Matchers.hasItem("PackagedVocabulary"));
  }

  @Test
  public void aPageOfTheLogStopsAtTheLimitAndSaysWhereToResume() {
    // On the artifact because the page is a Flyway-migrated index away from being a scan, and
    // because `nextCursor` is the one field the SPA's "load more" is built on: an omit-nulls mapper
    // or a dropped record component would leave a client unable to tell "no more" from "no field".
    String mark = "packaged-page-" + System.nanoTime();
    for (int i = 1; i <= 3; i++) {
      given()
          .contentType(ContentType.JSON)
          .body(
              "{\"name\":\"PackagedPage\",\"occurredAt\":\"2026-0"
                  + i
                  + "-01T00:00:00Z\",\"payload\":\"{\\\"mark\\\":\\\""
                  + mark
                  + "\\\"}\"}")
          .when()
          .post("/events/api/events")
          .then()
          .statusCode(200);
    }

    String cursor =
        given()
            .queryParam("q", mark)
            .queryParam("limit", 2)
            .when()
            .get("/events/api/events")
            .then()
            .statusCode(200)
            .body("events", org.hamcrest.Matchers.hasSize(2))
            .body("nextCursor", org.hamcrest.Matchers.notNullValue())
            .extract()
            .path("nextCursor");

    given()
        .queryParam("q", mark)
        .queryParam("limit", 2)
        .queryParam("cursor", cursor)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", org.hamcrest.Matchers.hasSize(1))
        .body("$", org.hamcrest.Matchers.hasKey("nextCursor"))
        .body("nextCursor", org.hamcrest.Matchers.nullValue());
  }

  @Test
  public void theReadinessEndpointIsWhereTheDeploymentLooksForIt() {
    given()
        .when()
        .get("/events/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", org.hamcrest.Matchers.equalTo("UP"));
  }

  @Test
  public void theApiDocumentAndItsUiAreServedUnderTheSegment() {
    // Both live under quarkus.http.non-application-root-path, which sits OUTSIDE quarkus.rest.path
    // and carries /events on its own; at / they would be unreachable through qits-gateway.
    given().when().get("/events/q/openapi").then().statusCode(200);
    given().when().get("/events/q/swagger-ui/").then().statusCode(200);
  }

  @Test
  public void anEventRoundTripsThroughFlywayAndPanacheOnTheShippedDatasource() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"Packaged surface\",\"occurredAt\":\"2026-07-31T09:00:00Z\"}")
            .when()
            .post("/events/api/events")
            .then()
            .statusCode(200)
            .extract()
            .path("event.id");

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event.name", org.hamcrest.Matchers.equalTo("Packaged surface"));

    // The round trip above would look identical against an in-memory database, so pin that the
    // process really opened the ${user.home}-rooted file H2 the events jar ships — a migration is
    // loaded by scanning a classpath location, exactly the shape a native image drops.
    assertTrue(
        Files.isDirectory(PackagedUnderTarget.HOME.resolve(".qits/data/events/h2")),
        "the shipped file-H2 default must be what the packaged process opened");
  }

  @Test
  public void aPlainGetOnTheSocketPathIsFourOhFourAndNeverTheClient() {
    // websockets-next claims the UPGRADE and nothing else, so a GET with no Upgrade header reaches
    // no socket route at all and — without /stream in quarkus.quinoa.ignored-path-prefixes — falls
    // through to the SPA's catch-all and answers 200 index.html. Measured exactly that way on
    // qits-ci's /ci/daemon. A subscriber handed a web page parses it as data; 404 is the answer.
    //
    // As everywhere else here the assertion is "404, and not the CLIENT" rather than "404, never
    // HTML": what actually comes back is Vert.x' own stock <h1>Resource not found</h1>, which is
    // text/html and correct, so the absence of the client is what is pinned.
    String body = given().when().get("/events/stream").then().statusCode(404).extract().asString();
    assertFalse(
        body.contains("<base href=\"/events/\">"),
        "the stream path must not be answered with the client; got: " + body);

    String mistyped =
        given().when().get("/events/stream/nope").then().statusCode(404).extract().asString();
    assertFalse(mistyped.contains("<base href=\"/events/\">"));
  }

  @Test
  public void theStreamIsOnTheArtifactsRouterAndPushesWhatThePublishWrote() throws Exception {
    // Ignoring a prefix stops the SPA REROUTE; it does not unregister the real route. That is the
    // half of the previous test's arrangement that only a real upgrade can prove — and the endpoint
    // is registered at AUGMENTATION, so under -Dnative this is where "the extension is
    // native-image supported" stops being a claim. A dropped route fails the upgrade with a 404
    // instead, and every subscriber would otherwise see only a stream that never says anything.
    String signature = "PackagedProbe" + System.nanoTime();
    String envelope =
        "{\"name\":\""
            + signature
            + "\",\"occurredAt\":\"2026-07-31T12:46:03Z\",\"payload\":\"{\\\"probe\\\":true}\","
            + "\"description\":null}";

    try (FakeSubscriber subscriber = FakeSubscriber.dial(stream)) {
      subscriber.subscribe(signature);
      // The protocol has no ack and there is no bean to inspect from out here — the app under test
      // is another process. So a fresh event is published until one of them lands as a frame: each
      // attempt is its own UUID and therefore its own create, and a create is what pushes.
      String frame = null;
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (frame == null && System.nanoTime() < deadline) {
        given()
            .contentType(ContentType.JSON)
            .body(envelope)
            .when()
            .put("/events/api/events/" + UUID.randomUUID())
            .then()
            .statusCode(201);
        frame = subscriber.next(Duration.ofSeconds(2));
      }
      assertNotNull(frame, "the packaged artifact's stream pushed nothing");
      assertTrue(frame.contains("\"name\":\"" + signature + "\""), frame);
      assertTrue(frame.contains("\"payload\":\"{\\\"probe\\\":true}\""), frame);
      // The envelope above never mentions parentId — an older publisher's exact bytes — and the
      // frame carries it as an explicit null all the same. Both halves of the compatibility clause
      // in one assertion, on the artifact.
      assertTrue(frame.contains("\"parentId\":null"), frame);
    }
  }

  @Test
  public void theIdempotentPublishAnswersTwoOhOneThenTwoHundredThenFourHundred() {
    // On the artifact rather than only in a @QuarkusTest, because this is also the only place the
    // V2 payload and V3 parent_id columns are exercised against the SHIPPED file-H2 through
    // Flyway's real migration resources — the shape a native image drops silently.
    //
    // The envelope carries parentId here for exactly that reason: a migration that never ran, a
    // column MapStruct maps by a name the native image dropped, or an omit-nulls mapper are all
    // invisible to a @QuarkusTest and all fatal to the publisher that ships next.
    String parent = UUID.randomUUID().toString();
    String id = UUID.randomUUID().toString();
    String envelope =
        "{\"name\":\"PackagedPublish\",\"occurredAt\":\"2026-07-31T12:46:03Z\","
            + "\"payload\":\"{\\\"repoId\\\":\\\"qits-events\\\"}\",\"description\":null,"
            + "\"parentId\":\""
            + parent
            + "\"}";

    given()
        .contentType(ContentType.JSON)
        .body(envelope)
        .when()
        .put("/events/api/events/" + id)
        .then()
        .statusCode(201)
        .body("event.payload", org.hamcrest.Matchers.equalTo("{\"repoId\":\"qits-events\"}"))
        .body("event.parentId", org.hamcrest.Matchers.equalTo(parent));

    given()
        .contentType(ContentType.JSON)
        .body(envelope)
        .when()
        .put("/events/api/events/" + id)
        .then()
        .statusCode(200);

    // Read back through the list route's ?parentId= filter, which is the read model this feature
    // adds and the one query the V3 index exists for. The parent itself was never published — a
    // dangling cause is data, so the children query answers about it all the same.
    given()
        .queryParam("parentId", parent)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.id", org.hamcrest.Matchers.contains(id));

    given()
        .contentType(ContentType.JSON)
        .body(envelope.replace("qits-events\\\"}", "somebody-else\\\"}"))
        .when()
        .put("/events/api/events/" + id)
        .then()
        .statusCode(400);

    // The cause is inside the comparison too: one id may not be re-published under another parent.
    given()
        .contentType(ContentType.JSON)
        .body(envelope.replace(parent, UUID.randomUUID().toString()))
        .when()
        .put("/events/api/events/" + id)
        .then()
        .statusCode(400);

    given()
        .contentType(ContentType.JSON)
        .body(envelope)
        .when()
        .put("/events/api/events/not-a-uuid")
        .then()
        .statusCode(400);
  }

  @Test
  public void aRootEventCarriesTheParentIdKeyOnTheWire() {
    // hasKey, not nullValue(): an absent JSON path reads as null too, and the difference is the
    // whole of what a consumer can rely on — the publisher that ships next probes for this key to
    // decide whether this service knows about causation. Asserted on the ARTIFACT because an
    // omit-nulls Jackson customizer or a dropped record component is exactly the class of change a
    // @QuarkusTest cannot distinguish from the right behaviour.
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"PackagedRoot\",\"occurredAt\":\"2026-07-31T09:00:00Z\"}")
            .when()
            .post("/events/api/events")
            .then()
            .statusCode(200)
            .extract()
            .path("event.id");

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event", org.hamcrest.Matchers.hasKey("parentId"))
        .body("event.parentId", org.hamcrest.Matchers.nullValue());
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
