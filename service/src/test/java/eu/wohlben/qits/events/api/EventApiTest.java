package eu.wohlben.qits.events.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
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
    return create(name, occurredAt, payload, description, null);
  }

  private String create(
      String name, String occurredAt, String payload, String description, String parentId) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new EventController.CreateEventRequest(
                name,
                occurredAt == null ? null : Instant.parse(occurredAt),
                payload,
                description,
                parentId))
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
  void theListFilteredByParentIsThatEventsChildrenNewestFirst() {
    // The downward half of a chain walk, and the shape a release train actually has: one event fans
    // out to N. A client cannot do this without listing the whole log, which is the reason the
    // parameter exists — and it is a PARAMETER rather than a route precisely so that no new literal
    // under /events needs a quarkus.quinoa.ignored-path-prefixes entry.
    String prefix = "children-" + System.nanoTime() + "-";
    String parent = create(prefix + "parent", "2026-01-01T00:00:00Z", null, null);
    String stranger = create(prefix + "stranger", "2026-01-01T00:00:00Z", null, null);
    create(prefix + "middle", "2026-06-01T00:00:00Z", null, null, parent);
    create(prefix + "oldest", "2026-02-01T00:00:00Z", null, null, parent);
    create(prefix + "newest", "2026-12-01T00:00:00Z", null, null, parent);
    create(prefix + "somebody-elses", "2026-12-02T00:00:00Z", null, null, stranger);

    given()
        .queryParam("parentId", parent)
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.name", contains(prefix + "newest", prefix + "middle", prefix + "oldest"));
  }

  @Test
  void anUnknownParentIsAnEmptyListRatherThanAFourOhFour() {
    // This log cannot tell "wrong id" from "not here yet" from "another publisher's", and "nothing
    // was caused by it as far as I know" is the true answer in all three cases. A 404 would also
    // make a chain-walking client treat a gap as a failure rather than as the end of the chain.
    given()
        .queryParam("parentId", java.util.UUID.randomUUID().toString())
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", empty());

    // Not even a UUID: still a question with a true answer, and GET stays tolerant of any String id
    // the way it always has — only the publish path demands a canonical one.
    given()
        .queryParam("parentId", "not-a-uuid")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events", empty());
  }

  @Test
  void anEmptyParentIdParameterIsTheWholeLogRatherThanNoLogAtAll() {
    // `?parentId=` is a client that meant to ask for everything and said it clumsily. Blank is
    // absent, which keeps the parameterless behaviour the one default.
    String id = create("Whole log " + System.nanoTime(), "2026-07-31T09:00:00Z", null, null);
    given()
        .queryParam("parentId", "")
        .when()
        .get("/events/api/events")
        .then()
        .statusCode(200)
        .body("events.id", hasItem(id));
  }

  @Test
  void aHandRecordedEventCarriesTheParentIdKeyWhetherOrNotItHasOne() {
    // hasKey rather than nullValue(): an absent JSON path also reads as null, so only the key
    // proves the field is on the wire. A consumer's "does this service know about causation?" is
    // exactly that check.
    String id = create("Rootless " + System.nanoTime(), "2026-07-31T09:00:00Z", null, null);
    given()
        .when()
        .get("/events/api/events/" + id)
        .then()
        .statusCode(200)
        .body("event", hasKey("parentId"))
        .body("event.parentId", nullValue());
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
