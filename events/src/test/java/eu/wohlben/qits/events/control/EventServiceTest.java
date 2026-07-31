package eu.wohlben.qits.events.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
  void createReadUpdateDelete() {
    Instant when = Instant.parse("2026-07-31T09:00:00Z");
    Event event = eventService.create("Deployed qits-events", when, "First boot");
    assertNotNull(event.id);
    assertEquals(when, event.occurredAt);
    assertNotNull(event.createdAt);
    assertNotNull(event.updatedAt);

    Event fetched = eventService.get(event.id);
    assertEquals("Deployed qits-events", fetched.name);

    Event updated = eventService.update(event.id, "Deployed qits-events v2", null, "Second boot");
    assertEquals("Deployed qits-events v2", updated.name);
    // created_at is immutable; update bumps updated_at only.
    assertEquals(event.createdAt, updated.createdAt);
    assertFalse(updated.updatedAt.isBefore(updated.createdAt));

    eventService.delete(event.id);
    inFreshTx(() -> assertThrows(NotFoundException.class, () -> eventService.get(event.id)));
  }

  @Test
  void anOmittedOccurredAtDefaultsToNow() {
    Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Event event = eventService.create("Right now", null, null);
    assertFalse(event.occurredAt.isBefore(before));
  }

  @Test
  void anEventMayBeRecordedInThePast() {
    // The normal case, not an edge one: a log is mostly written after the fact.
    Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
    Event event = eventService.create("Backfilled", longAgo, null);
    assertEquals(longAgo, event.occurredAt);
    // ... and the row's own timestamps do not follow it, which is the whole reason there are three.
    assertTrue(event.createdAt.isAfter(longAgo));
  }

  @Test
  void updateLeavesTheRecordedTimeAloneWhenItIsOmitted() {
    Instant when = Instant.parse("2026-07-31T09:00:00Z");
    Event event = eventService.create("Named", when, null);
    Event updated = eventService.update(event.id, "Renamed", null, null);
    assertEquals(when, updated.occurredAt);
  }

  @Test
  void listIsNewestFirstByWhenItHappened() {
    // Insertion order deliberately disagrees with occurrence order — that is what is under test.
    eventService.create("Middle", Instant.parse("2026-06-01T00:00:00Z"), null);
    eventService.create("Oldest", Instant.parse("2026-01-01T00:00:00Z"), null);
    eventService.create("Newest", Instant.parse("2026-12-01T00:00:00Z"), null);

    List<String> names = eventService.list().stream().map(e -> e.name).toList();
    assertEquals(List.of("Newest", "Middle", "Oldest"), names);
  }

  @Test
  void blankNameIsRejected() {
    assertThrows(BadRequestException.class, () -> eventService.create("  ", null, null));
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
