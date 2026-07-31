package eu.wohlben.qits.events.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.events.control.EventService.PublishOutcome;
import eu.wohlben.qits.events.control.EventService.Published;
import eu.wohlben.qits.events.error.BadRequestException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The bus's write path: {@code publish} under the caller's own UUID, which is what makes a
 * publisher's retry safe. The three outcomes are the wire contract, so they are pinned here at the
 * control layer as well as over HTTP — the boundary only translates them into 201/200/400.
 */
@QuarkusTest
class EventPublishTest extends EventsTestSupport {

  private static final String PAYLOAD =
      "{\"branch\":\"main\",\"commitSha\":\"abc123\",\"repoId\":\"qits-ci\"}";

  @Inject EventService eventService;

  private static String aUuid() {
    return UUID.randomUUID().toString();
  }

  @Test
  void anUnknownIdIsCreatedUnderTheCallersOwnUuid() {
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");

    Published published = eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null);

    assertEquals(PublishOutcome.CREATED, published.outcome());
    // The id is the PUBLISHER's, not one this service invented — that is the whole mechanism.
    assertEquals(id, published.event().id);
    assertEquals(when, published.event().occurredAt);
    assertEquals(PAYLOAD, published.event().payload);
  }

  @Test
  void theSameEventArrivingTwiceIsAReplayAndWritesNothing() {
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    eventService.publish(id, "BuildSuccessful", when, PAYLOAD, "first attempt");

    Published again = eventService.publish(id, "BuildSuccessful", when, PAYLOAD, "first attempt");

    assertEquals(PublishOutcome.REPLAYED, again.outcome());
    assertEquals(1, eventService.list().size(), "a replay must not land a second row");
  }

  @Test
  void aDifferentDescriptionIsStillTheSameEvent() {
    // description is the human account and deliberately outside the comparison: a publisher that
    // improved its wording between attempts has not published a different event. Nothing is
    // written, so the stored account stays the one that was committed.
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    eventService.publish(id, "BuildSuccessful", when, PAYLOAD, "as first sent");

    Published again = eventService.publish(id, "BuildSuccessful", when, PAYLOAD, "reworded");

    assertEquals(PublishOutcome.REPLAYED, again.outcome());
    inFreshTx(() -> assertEquals("as first sent", eventService.get(id).description));
  }

  @Test
  void aReplayAtFinerClockPrecisionIsStillAReplay() {
    // The trap this exists to hold shut: occurred_at is timestamp(6), so what a nanosecond-precision
    // clock sends is not what the column hands back. Compared naively, a publisher's own honest
    // retry would be told 400 — "you reused a UUID" — for a difference no storage here can even
    // represent.
    String id = aUuid();
    Instant nanos = Instant.parse("2026-07-31T12:46:03Z").plusNanos(123_456_789L);
    eventService.publish(id, "BuildSuccessful", nanos, PAYLOAD, null);

    Published again = eventService.publish(id, "BuildSuccessful", nanos, PAYLOAD, null);

    assertEquals(PublishOutcome.REPLAYED, again.outcome());
    inFreshTx(
        () -> assertEquals(eventService.get(id).occurredAt, again.event().occurredAt,
            "what was stored, what is returned and what is compared must be one value"));
  }

  @Test
  void aReusedUuidIsUnretryableRatherThanAConflictToPollOn() {
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    eventService.publish(id, "BuildSuccessful", when, PAYLOAD, null);

    for (Runnable differing :
        List.<Runnable>of(
            () -> eventService.publish(id, "SomethingElse", when, PAYLOAD, null),
            () -> eventService.publish(id, "BuildSuccessful", when.plusSeconds(1), PAYLOAD, null),
            () -> eventService.publish(id, "BuildSuccessful", when, "{\"branch\":\"other\"}", null),
            () -> eventService.publish(id, "BuildSuccessful", when, null, null))) {
      assertThrows(BadRequestException.class, differing::run);
    }
  }

  @Test
  void anIdThatIsNotAUuidIsRefusedBeforeAnythingIsStored() {
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    assertThrows(
        BadRequestException.class,
        () -> eventService.publish("not-a-uuid", "BuildSuccessful", when, PAYLOAD, null));
    // UUID.fromString alone accepts this; the round-trip check is what does not.
    assertThrows(
        BadRequestException.class,
        () -> eventService.publish("1-1-1-1-1", "BuildSuccessful", when, PAYLOAD, null));
    assertEquals(List.of(), eventService.list());
  }

  @Test
  void anOmittedOccurredAtIsRefusedRatherThanFilledIn() {
    // create() defaults it to now; publish() must not, because an event whose time this server
    // invented could never replay equal to itself.
    assertThrows(
        BadRequestException.class,
        () -> eventService.publish(aUuid(), "BuildSuccessful", null, PAYLOAD, null));
  }

  @Test
  void aPublishedEventNeedsNoPayloadEither() {
    String id = aUuid();
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    Published published = eventService.publish(id, "SomethingHappened", when, null, null);
    assertEquals(PublishOutcome.CREATED, published.outcome());
    assertNull(published.event().payload);

    // ... and null must compare equal to null on the replay, not fall into the mismatch branch.
    assertEquals(
        PublishOutcome.REPLAYED,
        eventService.publish(id, "SomethingHappened", when, null, null).outcome());
  }
}
