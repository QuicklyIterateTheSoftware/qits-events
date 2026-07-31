package eu.wohlben.qits.events.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * REST round-trips for the events boundary. The addresses are the shipped ones — the suite inherits
 * {@code quarkus.rest.path=/events/api} from main's application.properties rather than re-declaring
 * it — so a change to the segment fails here rather than in a deployment.
 *
 * <p>The manual {@code POST} path only. The publisher's {@code PUT} is a different operation with
 * different semantics and lives in {@link EventPublishApiTest}.
 */
@QuarkusTest
class EventApiTest {

  private String create(String name, String occurredAt, String payload, String description) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new EventController.CreateEventRequest(
                name, occurredAt == null ? null : Instant.parse(occurredAt), payload, description))
        .when()
        .post("/events/api/events")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("event.id", notNullValue())
        .extract()
        .path("event.id");
  }

  @Test
  void createReadDelete() {
    String id =
        create("Deployed qits-events", "2026-07-31T09:00:00Z", "{\"host\":\"one\"}", "First boot");

    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event.name", equalTo("Deployed qits-events"))
        .body("event.occurredAt", equalTo("2026-07-31T09:00:00Z"))
        .body("event.payload", equalTo("{\"host\":\"one\"}"))
        .body("event.description", equalTo("First boot"));

    given()
        .when()
        .delete("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("success", equalTo(true));

    given().when().get("/events/api/events/" + id).then().statusCode(404);
  }

  @Test
  void aHandRecordedEventNeedsNoPayload() {
    String id = create("By hand", "2026-07-31T09:00:00Z", null, null);
    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event.payload", nullValue());
  }

  @Test
  void theListIsNewestFirstByWhenItHappened() {
    String prefix = "list-" + System.nanoTime() + "-";
    create(prefix + "middle", "2026-06-01T00:00:00Z", null, null);
    create(prefix + "oldest", "2026-01-01T00:00:00Z", null, null);
    create(prefix + "newest", "2026-12-01T00:00:00Z", null, null);

    given()
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body(
            "events.findAll { it.name.startsWith('" + prefix + "') }.name",
            contains(prefix + "newest", prefix + "middle", prefix + "oldest"));
  }

  @Test
  void aBlankNameIsRejectedByBeanValidationBeforeItReachesTheService() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"  \"}")
        .when()
        .post("/events/api/events")
        .then()
        .statusCode(400);
  }

  @Test
  void anUnknownEventIsFourOhFourAsJson() {
    // The exception mapper's job: the domain's framework-free NotFoundException carries the status,
    // and the body is JSON — never the SPA's index.html, which is what an unmapped path would give.
    given()
        .when()
        .get("/events/api/events/no-such-event")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
  }

  @Test
  void thereIsNoUnprefixedForm() {
    // qits-gateway routes verbatim by prefix, so there is nothing to fall back to. If this ever
    // answers, quarkus.rest.path has stopped being applied.
    given().when().get("/api/events").then().statusCode(404);
  }
}
