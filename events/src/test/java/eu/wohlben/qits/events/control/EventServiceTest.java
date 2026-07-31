package eu.wohlben.qits.events.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.events.entity.Event;
import eu.wohlben.qits.events.error.BadRequestException;
import eu.wohlben.qits.events.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EventServiceTest extends EventsTestSupport {

  @Inject EventService eventService;

  @Test
  void createReadDelete() {
    Instant when = Instant.parse("2026-07-31T09:00:00Z");
    Event event =
        eventService.create("Deployed qits-events", when, "{\"version\":\"1\"}", "First boot");
    assertNotNull(event.id);
    assertEquals(when, event.occurredAt);
    assertNotNull(event.createdAt);
    assertNotNull(event.updatedAt);
    assertFalse(event.updatedAt.isBefore(event.createdAt));

    Event fetched = eventService.get(event.id);
    assertEquals("Deployed qits-events", fetched.name);
    assertEquals("{\"version\":\"1\"}", fetched.payload);

    eventService.delete(event.id);
    inFreshTx(() -> assertThrows(NotFoundException.class, () -> eventService.get(event.id)));
  }

  @Test
  void aManuallyRecordedEventNeedsNoPayload() {
    // The POST path stays what it was: a name and a time are the whole of what an event must have,
    // and the bus's structured half is optional rather than a new obligation on a person.
    Event event = eventService.create("By hand", Instant.parse("2026-07-31T09:00:00Z"), null, null);
    assertNull(event.payload);
  }

  @Test
  void anOmittedOccurredAtDefaultsToNow() {
    Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Event event = eventService.create("Right now", null, null, null);
    assertFalse(event.occurredAt.isBefore(before));
  }

  @Test
  void anEventMayBeRecordedInThePast() {
    // The normal case, not an edge one: a log is mostly written after the fact.
    Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
    Event event = eventService.create("Backfilled", longAgo, null, null);
    assertEquals(longAgo, event.occurredAt);
    // ... and the row's own timestamps do not follow it, which is the whole reason there are three.
    assertTrue(event.createdAt.isAfter(longAgo));
  }

  @Test
  void listIsNewestFirstByWhenItHappened() {
    // Insertion order deliberately disagrees with occurrence order — that is what is under test.
    eventService.create("Middle", Instant.parse("2026-06-01T00:00:00Z"), null, null);
    eventService.create("Oldest", Instant.parse("2026-01-01T00:00:00Z"), null, null);
    eventService.create("Newest", Instant.parse("2026-12-01T00:00:00Z"), null, null);

    List<String> names = eventService.list().stream().map(e -> e.name).toList();
    assertEquals(List.of("Newest", "Middle", "Oldest"), names);
  }

  @Test
  void blankNameIsRejected() {
    assertThrows(BadRequestException.class, () -> eventService.create("  ", null, null, null));
  }

  @Test
  void getUnknownEventThrowsNotFound() {
    assertThrows(NotFoundException.class, () -> eventService.get("nope"));
  }

  @Test
  void deleteUnknownEventThrowsNotFound() {
    assertThrows(NotFoundException.class, () -> eventService.delete("nope"));
  }
}
