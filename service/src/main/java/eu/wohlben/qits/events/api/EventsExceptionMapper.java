package eu.wohlben.qits.events.api;

import eu.wohlben.qits.events.error.EventsException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps the events module's framework-free {@link EventsException}s (each carrying a status code) to
 * HTTP responses — the sibling of qits-projects' {@code EpicsExceptionMapper} and qits-ci's
 * {@code CiExceptionMapper}, kept here in {@code service} because the {@code events} module carries
 * no JAX-RS.
 */
@Provider
public class EventsExceptionMapper implements ExceptionMapper<EventsException> {

  @Override
  public Response toResponse(EventsException exception) {
    int status = exception.statusCode();
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = Response.Status.fromStatusCode(status).getReasonPhrase();
    }
    return Response.status(status)
        .entity(Map.of("message", message))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
