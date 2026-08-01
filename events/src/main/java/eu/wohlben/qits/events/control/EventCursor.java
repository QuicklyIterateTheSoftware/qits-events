package eu.wohlben.qits.events.control;

import eu.wohlben.qits.events.error.BadRequestException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Where a page of the log stops, and where the next one resumes: the {@code occurredAt} <b>and the
 * id</b> of the last row handed out.
 *
 * <p><b>Composite, and that is the whole point.</b> A scalar {@code before=<occurredAt>} cursor is
 * the obvious shape and it is wrong on this table: sibling events published by one pipeline run
 * share the run's finish instant <em>by construction</em>, so a page boundary that lands on a tie
 * either repeats a sibling on the next page or drops it — and those are precisely the rows a release
 * train is read for. Four of the live log's rows tie today; three of the ties are release forks. The
 * predicate the pair supports has no such gap:
 *
 * <pre>occurred_at &lt; :at or (occurred_at = :at and id &lt; :id)</pre>
 *
 * <p>It is spelled {@code <occurredAt>,<id>} — opaque to a client in spirit and legible in practice,
 * which is worth more here than a base64 wrapper that would only make a support question harder to
 * answer. An ISO-8601 instant contains no comma, so the first one splits the two halves.
 *
 * <p>The instant is truncated to microseconds on the way in for the same reason a published event's
 * is (see {@code EventService}): the column is {@code timestamp(6)}, so a cursor carrying
 * nanoseconds would compare against a value the database rounded and skip a row.
 */
public record EventCursor(Instant occurredAt, String id) {

  /**
   * The caller's {@code ?cursor=} text, or null when there is none.
   *
   * <p>Every failure is a 400 naming the parameter: a cursor is a value this service handed out, so
   * one it cannot read is a client error worth being told about rather than a filter to ignore.
   */
  public static EventCursor parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    int comma = raw.indexOf(',');
    if (comma < 0) {
      throw new BadRequestException("cursor must be <occurredAt>,<id> — the last row of the page");
    }
    Instant at;
    try {
      at = Instant.parse(raw.substring(0, comma).trim()).truncatedTo(ChronoUnit.MICROS);
    } catch (DateTimeParseException notAnInstant) {
      throw new BadRequestException("cursor's occurredAt must be an ISO-8601 instant");
    }
    String rowId = raw.substring(comma + 1).trim();
    if (rowId.isBlank()) {
      throw new BadRequestException("cursor must name the id of the last row of the page");
    }
    return new EventCursor(at, rowId);
  }

  /** The text a client sends back as {@code ?cursor=}. */
  public String format() {
    return occurredAt + "," + id;
  }
}
