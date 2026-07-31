package eu.wohlben.qits.events.error;

/** Events error mapped to HTTP 404 by the web layer. */
public class NotFoundException extends EventsException {

  public NotFoundException(String message) {
    super(404, message);
  }
}
