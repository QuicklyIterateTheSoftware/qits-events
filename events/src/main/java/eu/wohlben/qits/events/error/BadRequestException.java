package eu.wohlben.qits.events.error;

/** Events error mapped to HTTP 400 by the web layer. */
public class BadRequestException extends EventsException {

  public BadRequestException(String message) {
    super(400, message);
  }
}
