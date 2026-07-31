package eu.wohlben.qits.events.mapper;

import eu.wohlben.qits.events.dto.EventDto;
import eu.wohlben.qits.events.entity.Event;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface EventMapper {
  EventDto toDto(Event entity);
}
