package eu.wohlben.qits.events.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.events.dto.EventCreated;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Who is listening, to what, and the fan-out itself — the half of {@link EventStreamSocket} that is
 * not the socket.
 *
 * <p>Single instance, single process, in-memory: the plan says so and it is not a shortcut waiting
 * to be paid for. A subscription is worth exactly as long as the connection holding it, so there is
 * nothing to replicate; a second instance of this service would need a real broker rather than a
 * shared table, and that is a different feature.
 */
@ApplicationScoped
public class EventStreamSubscriptions {

  private static final Logger LOG = Logger.getLogger(EventStreamSubscriptions.class);

  /** The signature that means "everything", as sent in {@code {"subscribe": ["*"]}}. */
  static final String EVERYTHING = "*";

  /**
   * Connection id → what it asked for. Concurrent because frames, closes and the fan-out all arrive
   * on different threads; the value is replaced wholesale rather than mutated, so a broadcast
   * iterating the map always reads one coherent subscription set rather than one being edited.
   */
  private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();

  @Inject ObjectMapper objectMapper;

  private record Subscriber(WebSocketConnection connection, Set<String> signatures) {}

  void opened(WebSocketConnection connection) {
    // Subscribed to nothing until it says otherwise: a connection that has not named a signature
    // has not asked for traffic, and a browser tab that merely opened the socket should not get any.
    subscribers.put(connection.id(), new Subscriber(connection, Set.of()));
  }

  void closed(WebSocketConnection connection) {
    subscribers.remove(connection.id());
  }

  /**
   * Apply one {@code {"subscribe": [...]}} frame, replacing the connection's set.
   *
   * <p>Replace rather than add: a client that wants less has no other way to say so, and a set that
   * only ever grew would make a long-lived connection's interest a function of its whole history.
   *
   * <p>A frame that is not that shape is dropped with a debug line and the connection is left open —
   * the same stance qits-ci's daemon socket takes, and for the same reason: one malformed frame from
   * a client must not cost the connection.
   *
   * <p>{@code computeIfPresent}, not {@code put}: a frame racing a close must not resurrect an entry
   * that {@link #closed} has already removed, which would leak a dead connection into every
   * subsequent broadcast.
   */
  void subscribe(WebSocketConnection connection, String frame) {
    Set<String> requested;
    try {
      requested = parseSubscribe(frame);
    } catch (RuntimeException notASubscribeFrame) {
      LOG.debugf(
          "Dropped an unreadable frame from stream connection %s: %s",
          connection.id(), notASubscribeFrame.getMessage());
      return;
    }
    subscribers.computeIfPresent(
        connection.id(), (id, existing) -> new Subscriber(existing.connection(), requested));
    LOG.debugf("Stream connection %s now subscribed to %s", connection.id(), requested);
  }

  /**
   * Push a newly created event to everyone who asked for its name.
   *
   * <p><b>{@code AFTER_SUCCESS}</b>, so a create that rolls back pushes nothing. The alternative —
   * observing the fire itself — would broadcast events that never became rows, and a subscriber has
   * no way to un-see one.
   *
   * <p>Nothing here blocks and nothing here throws upwards. The observer runs on the thread that
   * completed the create's transaction, so a slow or dead subscriber must not be able to hold it:
   * the send is a {@code Uni} subscribed to with a failure handler, never {@code sendTextAndAwait},
   * which is {@code sendText(…).await().indefinitely()} under a friendlier name. One broken socket
   * costs its own frame and nothing else — least of all the write that produced it.
   */
  void onEventCreated(
      @Observes(during = TransactionPhase.AFTER_SUCCESS) EventCreated created) {
    if (subscribers.isEmpty()) {
      return;
    }
    String frame;
    try {
      frame = objectMapper.writeValueAsString(created);
    } catch (Exception unserializable) {
      // Cannot happen for a record of Strings and an Instant, and is logged rather than thrown
      // because the row is already committed: there is no caller left to tell.
      LOG.errorf(unserializable, "Could not serialize event %s for the stream", created.id());
      return;
    }
    for (Subscriber subscriber : subscribers.values()) {
      if (matches(subscriber.signatures(), created.name())) {
        push(subscriber, frame);
      }
    }
  }

  private static boolean matches(Set<String> signatures, String name) {
    return signatures.contains(EVERYTHING) || signatures.contains(name);
  }

  /**
   * How many open connections would be pushed an event named {@code name} right now.
   *
   * <p>A seam for the suite and nothing in this service calls it. The protocol has no
   * acknowledgement — a subscribe frame takes effect when the server gets round to it — so a test
   * that recorded an event before the frame had been applied would lose the push forever and flake.
   * Public rather than package-private because this bean is normal-scoped: a client proxy forwards
   * public methods only, and a package-private call would land on the uninitialised proxy instance.
   */
  public int subscriberCountFor(String name) {
    return (int)
        subscribers.values().stream().filter(s -> matches(s.signatures(), name)).count();
  }

  private void push(Subscriber subscriber, String frame) {
    WebSocketConnection connection = subscriber.connection();
    try {
      if (!connection.isOpen()) {
        // A close this registry has not been told about yet. Drop it here rather than send into it.
        subscribers.remove(connection.id());
        return;
      }
      connection
          .sendText(frame)
          .subscribe()
          .with(
              sent -> {},
              failure ->
                  LOG.debugf(
                      "Dropped a stream frame for connection %s: %s",
                      connection.id(), failure.getMessage()));
    } catch (RuntimeException wontSend) {
      LOG.debugf(
          "Stream connection %s refused a frame: %s", connection.id(), wontSend.getMessage());
    }
  }

  /**
   * The subscribe frame's one shape. Non-textual and blank entries are ignored rather than rejected:
   * the array is a statement of interest, and there is no interest a blank string could express.
   */
  private Set<String> parseSubscribe(String frame) {
    JsonNode root;
    try {
      root = objectMapper.readTree(frame);
    } catch (Exception notJson) {
      throw new IllegalArgumentException("not JSON: " + notJson.getMessage());
    }
    JsonNode subscribe = root == null ? null : root.get("subscribe");
    if (subscribe == null || !subscribe.isArray()) {
      throw new IllegalArgumentException("no 'subscribe' array");
    }
    Set<String> signatures = new HashSet<>();
    for (JsonNode entry : subscribe) {
      if (entry.isTextual() && !entry.asText().isBlank()) {
        signatures.add(entry.asText());
      }
    }
    return Set.copyOf(signatures);
  }
}
