package eu.wohlben.qits.events.control;

import eu.wohlben.qits.events.entity.Event;
import eu.wohlben.qits.events.error.NotFoundException;
import eu.wohlben.qits.events.persistence.EventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for {@link Event} — the whole of this context's behaviour today, and deliberately so. The
 * service exists so that the boundary above it (the JAX-RS controller in {@code service}) has
 * something to call that is not a repository, which is the seam every later feature — projections,
 * subscriptions, retention — grows out of.
 *
 * <p>{@code occurredAt} defaults to now when the caller omits it, and is otherwise taken verbatim,
 * including a value in the past: recording something that already happened is the normal case, not
 * an edge one.
 */
@ApplicationScoped
public class EventService {

  @Inject EventRepository eventRepository;

  public List<Event> list() {
    return eventRepository.listNewestFirst();
  }

  public Event get(String id) {
    return eventRepository
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Event not found: " + id));
  }

  @Transactional
  public Event create(String name, Instant occurredAt, String description) {
    Validations.requireText(name, "name");
    Event event = new Event();
    event.id = UUID.randomUUID().toString();
    event.name = name;
    event.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    event.description = description;
    eventRepository.persist(event);
    return event;
  }

  @Transactional
  public Event update(String id, String name, Instant occurredAt, String description) {
    Event event = get(id);
    Validations.requireText(name, "name");
    event.name = name;
    // occurredAt is a fact about the world, not a field a blank form may clear: an update that
    // omits it leaves the recorded time alone rather than silently moving the event to now.
    if (occurredAt != null) {
      event.occurredAt = occurredAt;
    }
    event.description = description;
    return event;
  }

  @Transactional
  public void delete(String id) {
    eventRepository.delete(get(id));
  }
}
