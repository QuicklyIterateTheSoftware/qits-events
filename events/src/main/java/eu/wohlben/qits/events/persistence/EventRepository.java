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
}
