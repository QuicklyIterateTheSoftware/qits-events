package eu.wohlben.qits.events.error;

/**
 * Base for events errors. Carries an HTTP-ish status code so the web layer can map it to a response
 * without this module depending on JAX-RS — the framework-free stance every qits domain jar takes
 * (qits-projects' {@code domain.error} and {@code epics.error}, qits-ci's {@code ci.error}). The
 * {@code service} module maps these via {@code EventsExceptionMapper}.
 */
public class EventsException extends RuntimeException {

  private final int statusCode;

  public EventsException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public EventsException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
