package eu.wohlben.qits.events.api;

import eu.wohlben.qits.events.control.EventService;
import eu.wohlben.qits.events.dto.EventDto;
import eu.wohlben.qits.events.mapper.EventMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The event log: list, read, record, publish, remove.
 *
 * <p>Served under {@code /events/api/events} — the {@code /events/api} prefix is
 * {@code quarkus.rest.path}, not spelled here, so this class carries only its own noun.
 *
 * <p>Request and response shapes are nested records, the platform's controller idiom: the wire
 * contract for one operation lives beside the method that serves it, and the generated OpenAPI
 * document names them after the operation rather than after a bag of shared DTOs.
 *
 * <p><b>There are two writes and they are not two spellings of one.</b> {@code POST} records an
 * event under an id this service picks — the manual path, for a person or a script with nothing to
 * retry. {@code PUT /{id}} is the bus's publish: the id is the <em>publisher's</em> UUID and is the
 * idempotency key, which is what lets a publisher that lost the answer to its first attempt send the
 * same bytes again and be told 200 rather than write a second row.
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
   * stands. {@code payload} is optional too: an event recorded by hand is honestly nothing but a
   * name and a time.
   */
  public record CreateEventRequest(
      @NotBlank String name, Instant occurredAt, String payload, String description) {
    public record Response(EventDto event) {}
  }

  @POST
  public CreateEventRequest.Response create(@Valid CreateEventRequest request) {
    var event =
        eventService.create(
            request.name(), request.occurredAt(), request.payload(), request.description());
    return new CreateEventRequest.Response(eventMapper.toDto(event));
  }

  /**
   * The publish envelope — the same five fields the {@code /events/stream} frame carries, minus the
   * id, which is in the path.
   *
   * <p>{@code occurredAt} is <b>required</b> here, unlike on {@code POST}: it is one of the three
   * fields a replay is compared on, so an event whose time this server invented could never replay
   * equal to itself. {@code payload} arrives as canonical JSON <em>in a string</em> and is stored
   * and compared verbatim — this server does not canonicalize, the publisher does.
   */
  public record PublishEventRequest(
      @NotBlank String name, @NotNull Instant occurredAt, String payload, String description) {
    public record Response(EventDto event) {}
  }

  /**
   * Idempotent publish under the caller's UUID.
   *
   * <ul>
   *   <li><b>201</b> — the id was unknown; the row was created and pushed to matching subscribers
   *   <li><b>200</b> — the id was known and {@code name}/{@code occurredAt}/{@code payload} match
   *       exactly: the same event arriving twice. Nothing written, nothing pushed
   *   <li><b>400</b> — the id was known and something differs (a reused UUID, which no retry fixes),
   *       or the id is not a UUID at all
   * </ul>
   *
   * <p>{@code RestResponse<T>} rather than a bare {@code Response}: the status has to vary and the
   * body type has to stay visible to the OpenAPI document, and only the typed form gives both.
   */
  @PUT
  @Path("/{id}")
  public RestResponse<PublishEventRequest.Response> publish(
      @PathParam("id") String id, @Valid PublishEventRequest request) {
    var published =
        eventService.publish(
            id,
            request.name(),
            request.occurredAt(),
            request.payload(),
            request.description());
    var body = new PublishEventRequest.Response(eventMapper.toDto(published.event()));
    return RestResponse.status(
        published.outcome() == EventService.PublishOutcome.CREATED
            ? Response.Status.CREATED
            : Response.Status.OK,
        body);
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
