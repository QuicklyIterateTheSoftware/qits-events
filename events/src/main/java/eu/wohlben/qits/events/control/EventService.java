package eu.wohlben.qits.events.control;

import eu.wohlben.qits.events.dto.EventCreated;
import eu.wohlben.qits.events.entity.Event;
import eu.wohlben.qits.events.error.BadRequestException;
import eu.wohlben.qits.events.error.NotFoundException;
import eu.wohlben.qits.events.persistence.EventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The event log's behaviour: read it, record into it, and — since this context became the platform's
 * bus — accept a publisher's idempotent write.
 *
 * <p>{@code occurredAt} defaults to now when the caller omits it on {@link #create}, and is
 * otherwise taken verbatim, including a value in the past: recording something that already happened
 * is the normal case, not an edge one.
 *
 * <p><b>Every create announces itself</b> through the CDI event {@link EventCreated}, which the
 * {@code service} module's websocket registry observes {@code AFTER_SUCCESS} and fans out to
 * subscribers. The signal is fired from here rather than from the boundary so that both write paths
 * — the manual {@code POST} and the publisher's {@code PUT} — cannot diverge about it, and it is a
 * CDI event rather than a direct call because this module carries no web stack and must not learn
 * about one to notify anybody.
 */
@ApplicationScoped
public class EventService {

  @Inject EventRepository eventRepository;

  /**
   * Fired on create and on nothing else. Declared with the fully-qualified type because {@code
   * Event} in this file is the entity — the collision is worth one long name rather than an import
   * alias that would make every other line ambiguous to a reader.
   */
  @Inject jakarta.enterprise.event.Event<EventCreated> created;

  /** What an idempotent publish did, which is the whole of what the boundary needs to pick 201/200. */
  public enum PublishOutcome {
    CREATED,
    REPLAYED
  }

  /** The stored event plus what happened to it. */
  public record Published(Event event, PublishOutcome outcome) {}

  public List<Event> list() {
    return eventRepository.listNewestFirst();
  }

  public Event get(String id) {
    return eventRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Event not found: " + id));
  }

  /** Record an event under an id of this service's choosing — the manual path. */
  @Transactional
  public Event create(String name, Instant occurredAt, String payload, String description) {
    Validations.requireText(name, "name");
    Event event = new Event();
    event.id = UUID.randomUUID().toString();
    event.name = name;
    event.occurredAt = atStoredPrecision(occurredAt == null ? Instant.now() : occurredAt);
    event.payload = payload;
    event.description = description;
    eventRepository.persist(event);
    announce(event);
    return event;
  }

  /**
   * Record an event under the <b>publisher's</b> id — the bus's write path, and the reason a retry
   * is safe.
   *
   * <p>Three outcomes, and there is no fourth:
   *
   * <ul>
   *   <li>the id is unknown → the row is created and announced ({@link PublishOutcome#CREATED},
   *       201);
   *   <li>the id is known and {@code name}, {@code occurredAt} and {@code payload} are all exactly
   *       equal → this is the same event arriving twice, because the publisher's first attempt got
   *       no answer. Nothing is written and <b>nothing is announced</b> ({@link
   *       PublishOutcome#REPLAYED}, 200): a subscriber must not see an event twice because a network
   *       dropped an acknowledgement;
   *   <li>the id is known and any of the three differs → the caller reused a UUID, which is not
   *       something a retry can fix, so it is a 400 rather than a conflict to sit and poll on.
   * </ul>
   *
   * <p><b>{@code description} is deliberately outside the comparison.</b> It is the human account,
   * not part of the event's identity; a publisher that improves its wording on a retry has not
   * published a different event. The stored description is left as it was — a replay writes nothing
   * at all, which is what makes 200 free of a transaction.
   *
   * <p>{@code payload} is compared as an opaque string. This server never canonicalizes it; the
   * publisher does, and both sides of the equality are therefore the publisher's own bytes.
   */
  @Transactional
  public Published publish(
      String id, String name, Instant occurredAt, String payload, String description) {
    Validations.requireUuid(id, "id");
    Validations.requireText(name, "name");
    // Unlike create, publish will not default it: an event whose time this server invented could
    // never be replayed equal to itself, so the one field that makes idempotency decidable is
    // required rather than filled in.
    Validations.requirePresent(occurredAt, "occurredAt");
    Instant when = atStoredPrecision(occurredAt);

    Event existing = eventRepository.findById(id);
    if (existing == null) {
      Event event = new Event();
      event.id = id;
      event.name = name;
      event.occurredAt = when;
      event.payload = payload;
      event.description = description;
      eventRepository.persist(event);
      announce(event);
      return new Published(event, PublishOutcome.CREATED);
    }

    if (!name.equals(existing.name)
        || !when.equals(existing.occurredAt)
        || !Objects.equals(payload, existing.payload)) {
      throw new BadRequestException(
          "Event " + id + " already exists with different content — a UUID may not be reused");
    }
    return new Published(existing, PublishOutcome.REPLAYED);
  }

  @Transactional
  public void delete(String id) {
    eventRepository.delete(get(id));
  }

  private void announce(Event event) {
    created.fire(
        new EventCreated(
            event.id, event.name, event.occurredAt, event.payload, event.description));
  }

  /**
   * The instant as the column will hand it back: {@code timestamp(6)}, so microseconds.
   *
   * <p>Without this the equality in {@link #publish} is decided against two values of different
   * precision — the caller's, and the truncated one the database returns — and a publisher whose
   * clock has nanoseconds would get a 400 on its own honest retry. Truncating on the way in makes
   * what is stored, what is returned and what is compared the same value, which is the only way the
   * word "exactly" in the contract means anything.
   */
  private static Instant atStoredPrecision(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS);
  }
}
