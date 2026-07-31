package eu.wohlben.qits.events.dto;

import java.time.Instant;

public record EventDto(
    String id,
    String name,
    Instant occurredAt,
    String description,
    Instant createdAt,
    Instant updatedAt) {}
