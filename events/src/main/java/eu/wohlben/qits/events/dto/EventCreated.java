package eu.wohlben.qits.events.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

/**
 * The envelope of a <em>newly created</em> event: the exact frame {@code /events/stream} pushes, and
 * the in-process signal the control layer fires to get it pushed.
 *
 * <p>The five components and their order are the wire contract, so this record's JSON <b>is</b> the
 * frame:
 *
 * <pre>{@code {"id": "<uuid>", "name": "…", "occurredAt": "…", "payload": "…", "description": null}}</pre>
 *
 * <p>Adding a component here changes what every subscriber receives. The row's own {@code
 * createdAt}/{@code updatedAt} are absent on purpose — they are this database's bookkeeping, not
 * facts about the thing that happened — and {@code id} is present on purpose: it is what a later
 * catch-up protocol will resume from, which is why the live-only stream carries it today.
 *
 * <p><b>Fired only on create.</b> An idempotent {@code PUT} that replays an id already stored
 * answers 200 and fires nothing; a subscriber that received the event once must never receive it
 * again because the publisher retried. That is the whole reason this type is named for the
 * transition rather than for the shape — an observer site reading {@code @Observes EventCreated}
 * cannot mistake it for "an event was written in some way".
 *
 * <p>{@code @RegisterForReflection} because this record is serialized by Jackson directly rather
 * than as a JAX-RS return type, so nothing else tells the native-image builder its accessors are
 * reachable. Without it the JVM suite stays green and the binary pushes {@code {}}.
 */
@RegisterForReflection
public record EventCreated(
    String id, String name, Instant occurredAt, String payload, String description) {}
