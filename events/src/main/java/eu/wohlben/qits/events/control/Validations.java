package eu.wohlben.qits.events.control;

import eu.wohlben.qits.events.error.BadRequestException;

/** Small shared control-layer guards for the events services. */
final class Validations {

  private Validations() {}

  /** Throws {@link BadRequestException} if {@code value} is null or blank. */
  static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new BadRequestException(field + " is required");
    }
  }
}
