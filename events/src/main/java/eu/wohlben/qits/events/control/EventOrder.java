package eu.wohlben.qits.events.control;

import eu.wohlben.qits.events.error.BadRequestException;
import java.util.Locale;

/**
 * Which way a page of the log runs: newest first, or oldest first.
 *
 * <p><b>Newest first is what a person reads and oldest first is what a machine catches up on.</b> A
 * durable consumer keeps a watermark — the last row it handled — and after a restart or a cutover it
 * has to read <em>forward</em> from it until it reaches the head. Descending cannot express that: it
 * walks away from the watermark, so a consumer would have to page all of history back to its own
 * position and reverse it. Ascending is the same page, the same cursor and the same filters, with the
 * comparison flipped.
 *
 * <p>The tie-safety is flipped with it, and that is the half worth naming. Sibling events published
 * by one pipeline run share the run's finish instant, so both directions need the id in the
 * comparison as well as in the sort — see {@link EventCursor}:
 *
 * <pre>
 * DESC: occurred_at &lt; :at or (occurred_at = :at and id &lt; :id)
 * ASC:  occurred_at &gt; :at or (occurred_at = :at and id &gt; :id)
 * </pre>
 */
public enum EventOrder {

  /** Newest first — the log as a person reads it, and what a request that says nothing gets. */
  DESC,

  /** Oldest first — the direction a durable consumer catches up in, from its watermark forward. */
  ASC;

  /**
   * The caller's {@code ?order=} text, or {@link #DESC} when there is none.
   *
   * <p>Blank is absent, the rule every filter here follows. A value that is neither spelling is a
   * 400 naming the parameter rather than a silent fall back to descending: {@code order} is a
   * parameter this service defined, so a misspelling is a client error, and answering it with the
   * <em>opposite</em> direction would hand a catch-up consumer the head of the log and let it record
   * a watermark it never reached.
   *
   * <p>Case is not part of the vocabulary — {@code ASC} and {@code asc} are one value — because the
   * two spellings could not mean different things and refusing one would only be ceremony.
   */
  public static EventOrder parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return DESC;
    }
    String spelling = raw.trim().toLowerCase(Locale.ROOT);
    if (spelling.equals("asc")) {
      return ASC;
    }
    if (spelling.equals("desc")) {
      return DESC;
    }
    throw new BadRequestException("order must be asc or desc");
  }
}
