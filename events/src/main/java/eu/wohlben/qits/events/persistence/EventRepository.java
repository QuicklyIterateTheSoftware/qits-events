package eu.wohlben.qits.events.persistence;

import eu.wohlben.qits.events.entity.Event;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class EventRepository implements PanacheRepositoryBase<Event, String> {

  /**
   * Newest first, by the caller's {@code occurredAt} rather than by insertion order — a backfilled
   * event belongs where it happened, which is the only ordering an event log can be read by.
   */
  public List<Event> listNewestFirst() {
    return listAll(Sort.by("occurredAt").descending());
  }

  /**
   * The events caused by one event, newest first — the downward half of a chain walk, and the query
   * {@code idx_event_parent_id} exists for.
   *
   * <p>An unknown parent yields an empty list rather than an absence: this log does not know whether
   * an id it has never seen is wrong or merely not here yet, and "no children" is the true answer
   * to the question that was asked either way.
   */
  public List<Event> listChildrenOf(String parentId) {
    return list("parentId", Sort.by("occurredAt").descending(), parentId);
  }
}
