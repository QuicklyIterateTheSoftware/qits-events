package eu.wohlben.qits.events.api;

import eu.wohlben.qits.events.control.EventService;
import eu.wohlben.qits.events.dto.EventDto;
import eu.wohlben.qits.events.mapper.EventMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;

/**
 * The event log: list, read, record, amend, remove.
 *
 * <p>Served under {@code /events/api/events} — the {@code /events/api} prefix is
 * {@code quarkus.rest.path}, not spelled here, so this class carries only its own noun.
 *
 * <p>Request and response shapes are nested records, the platform's controller idiom: the wire
 * contract for one operation lives beside the method that serves it, and the generated OpenAPI
 * document names them after the operation rather than after a bag of shared DTOs.
 */
@Path("/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EventController {

  @Inject EventService eventService;

  @Inject EventMapper eventMapper;

  public record ListEventsRequest() {
    public record Response(List<EventDto> events) {}
  }

  @GET
  public ListEventsRequest.Response list() {
    return new ListEventsRequest.Response(eventService.list().stream().map(eventMapper::toDto).toList());
  }

  public record GetEventRequest() {
    public record Response(EventDto event) {}
  }

  @GET
  @Path("/{id}")
  public GetEventRequest.Response get(@PathParam("id") String id) {
    return new GetEventRequest.Response(eventMapper.toDto(eventService.get(id)));
  }

  /**
   * {@code occurredAt} is optional and defaults to now. It is the caller's time — recording
   * something that already happened is the normal case — so a value in the past is accepted as it
   * stands.
   */
  public record CreateEventRequest(@NotBlank String name, Instant occurredAt, String description) {
    public record Response(EventDto event) {}
  }

  @POST
  public CreateEventRequest.Response create(@Valid CreateEventRequest request) {
    var event =
        eventService.create(request.name(), request.occurredAt(), request.description());
    return new CreateEventRequest.Response(eventMapper.toDto(event));
  }

  /** An omitted {@code occurredAt} leaves the recorded time alone rather than moving it to now. */
  public record UpdateEventRequest(@NotBlank String name, Instant occurredAt, String description) {
    public record Response(EventDto event) {}
  }

  @PUT
  @Path("/{id}")
  public UpdateEventRequest.Response update(
      @PathParam("id") String id, @Valid UpdateEventRequest request) {
    var event =
        eventService.update(id, request.name(), request.occurredAt(), request.description());
    return new UpdateEventRequest.Response(eventMapper.toDto(event));
  }

  public record DeleteEventRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteEventRequest.Response delete(@PathParam("id") String id) {
    eventService.delete(id);
    return new DeleteEventRequest.Response(true);
  }
}
