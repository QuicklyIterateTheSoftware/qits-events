package eu.wohlben.qits.events.dto;

import java.time.Instant;

/**
 * A read of one event, as the JSON API returns it. Carries the row's own timestamps, which {@link
 * EventCreated} — the socket frame — deliberately does not: a subscriber is told what happened, not
 * when this database learned of it.
 */
public record EventDto(
    String id,
    String name,
    Instant occurredAt,
    String payload,
    String description,
    Instant createdAt,
    Instant updatedAt) {}
