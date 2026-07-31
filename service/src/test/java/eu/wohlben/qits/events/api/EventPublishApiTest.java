package eu.wohlben.qits.events.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code PUT /events/api/events/{id}} — the bus's publish, over the shipped addresses.
 *
 * <p>Three answers and no fourth: 201 for an id this log has not seen, 200 for the same event
 * arriving twice, 400 for a UUID that has been reused for something else. The bodies here are raw
 * JSON strings rather than the request record, because what a publisher in another repository sends
 * is bytes on a wire and the point is to prove this service reads <em>those</em> — including a
 * {@code payload} that is a JSON document escaped inside a JSON string, which is the shape most
 * easily broken by a well-meaning change on either side.
 */
@QuarkusTest
class EventPublishApiTest {

  private static final String PAYLOAD =
      "{\\\"branch\\\":\\\"main\\\",\\\"commitSha\\\":\\\"abc123\\\",\\\"repoId\\\":\\\"qits-ci\\\"}";

  private static String envelope(String name, String occurredAt, String payload) {
    return "{\"name\":\""
        + name
        + "\",\"occurredAt\":\""
        + occurredAt
        + "\",\"payload\":"
        + (payload == null ? "null" : "\"" + payload + "\"")
        + ",\"description\":null}";
  }

  private static io.restassured.response.Response put(String id, String body) {
    return given().contentType(ContentType.JSON).body(body).when().put("/events/api/events/" + id);
  }

  @Test
  void anUnknownIdIsCreatedAndAnsweredTwoOhOne() {
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD))
        .then()
        .statusCode(201)
        .body("event.id", equalTo(id))
        .body("event.name", equalTo("BuildSuccessful"))
        .body("event.occurredAt", equalTo("2026-07-31T12:46:03Z"))
        // Verbatim: the canonical JSON the publisher produced, handed back unreformatted.
        .body(
            "event.payload",
            equalTo("{\"branch\":\"main\",\"commitSha\":\"abc123\",\"repoId\":\"qits-ci\"}"))
        .body("event.createdAt", notNullValue());
  }

  @Test
  void theSameBytesAgainAreAnsweredTwoHundredAndWriteNothing() {
    String id = UUID.randomUUID().toString();
    String body = envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD);
    String createdAt = put(id, body).then().statusCode(201).extract().path("event.createdAt");

    // A publisher whose first attempt got no answer sends exactly this again.
    put(id, body)
        .then()
        .statusCode(200)
        .body("event.id", equalTo(id))
        // Nothing was written, so the row's own timestamp is still the first attempt's.
        .body("event.createdAt", equalTo(createdAt));
  }

  @Test
  void aReusedUuidIsFourHundredWhateverDiffers() {
    String id = UUID.randomUUID().toString();
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD)).then().statusCode(201);

    // The caller reused a UUID. Unretryable, so 400 rather than a conflict to sit and poll on —
    // and the answer is this context's JSON error, never the client's index.html.
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", "{\\\"branch\\\":\\\"other\\\"}"))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", notNullValue());
    put(id, envelope("SomethingElse", "2026-07-31T12:46:03Z", PAYLOAD)).then().statusCode(400);
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:04Z", PAYLOAD)).then().statusCode(400);
    put(id, envelope("BuildSuccessful", "2026-07-31T12:46:03Z", null)).then().statusCode(400);
  }

  @Test
  void anIdThatIsNotAUuidIsFourHundred() {
    // The id is the idempotency key, so a caller that cannot spell one has no retry-safe identity
    // to offer. GET and DELETE stay tolerant of any String id — see EventApiTest.
    put("not-a-uuid", envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD))
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON);
    put("1-1-1-1-1", envelope("BuildSuccessful", "2026-07-31T12:46:03Z", PAYLOAD))
        .then()
        .statusCode(400);
  }

  @Test
  void anEnvelopeMissingItsRequiredFieldsIsFourHundred() {
    String id = UUID.randomUUID().toString();
    // occurredAt is required here and optional on POST: it is one of the three fields a replay is
    // compared on, so a time this server invented would make the event unable to replay as itself.
    put(id, "{\"name\":\"BuildSuccessful\"}").then().statusCode(400);
    put(id, "{\"occurredAt\":\"2026-07-31T12:46:03Z\"}").then().statusCode(400);
    put(id, "{\"name\":\"  \",\"occurredAt\":\"2026-07-31T12:46:03Z\"}").then().statusCode(400);
  }

  @Test
  void aPublishedEventNeedNotCarryAPayload() {
    String id = UUID.randomUUID().toString();
    String body = envelope("SomethingHappened", "2026-07-31T12:46:03Z", null);
    put(id, body).then().statusCode(201).body("event.payload", nullValue());
    put(id, body).then().statusCode(200);
  }
}
