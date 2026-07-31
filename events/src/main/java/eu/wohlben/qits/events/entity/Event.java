package eu.wohlben.qits.events.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A recorded thing that happened.
 *
 * <p>Panache active-record with public fields, the platform's entity idiom. The three timestamps are
 * not redundant: {@code occurredAt} is the caller's — <em>when the thing happened</em>, supplied on
 * write and freely in the past — while {@code createdAt}/{@code updatedAt} are this row's, written
 * by Hibernate. Collapsing them would make a backfilled event indistinguishable from one recorded as
 * it happened, which is the one distinction an event log exists to keep.
 *
 * <p>No relation to any other context's entity, and there will not be one: an event that names a
 * project or a repository names it by String id through this context's own column, because those
 * rows live in another physical database (the platform-wide rule — see AGENTS.md).
 */
@Entity
public class Event extends PanacheEntityBase {

  @Id public String id;

  /**
   * Short label for lists and timelines, and — since this context became the platform's bus — the
   * event's <b>signature</b>: the string a websocket subscriber names to say what it wants. One
   * column serving both is deliberate; a separate signature field would be a second name that could
   * disagree with the first.
   */
  @Column(nullable = false)
  public String name;

  /** When the thing happened, as the caller reports it — not when the row was written. */
  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  /**
   * The publisher's own fields as canonical JSON — the machine half, where {@link #description} is
   * the human one. Optional, and permanently so: an event recorded by hand through {@code POST} is
   * honestly nothing but a name and a time.
   *
   * <p>Stored and compared <b>verbatim</b>. Canonicalization (sorted keys, no insignificant
   * whitespace, absent fields omitted rather than null) happens in the publisher, and the idempotent
   * {@code PUT} decides "same event or reused UUID?" by comparing this string byte for byte — so a
   * server that reformatted the value would break the one property the retry path rests on.
   */
  public String payload;

  /** The long-form account. Optional: a name and a time are the whole of what an event must have. */
  public String description;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
