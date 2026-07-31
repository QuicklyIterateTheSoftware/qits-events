package eu.wohlben.qits.events.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
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
