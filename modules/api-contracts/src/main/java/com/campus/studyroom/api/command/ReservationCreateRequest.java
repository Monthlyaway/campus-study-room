package com.campus.studyroom.api.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.OffsetDateTime;
import java.util.*;

/** Parsing occurs inside the idempotent action, so completed legacy responses can replay first. */
public final class ReservationCreateRequest extends JsonCommand {
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public ReservationCreateRequest(Map<String, Object> body) {
    super(body);
  }

  public UUID seatId() {
    return uuid("seatId");
  }

  public OffsetDateTime startAt() {
    return timestamp("startAt");
  }

  public OffsetDateTime endAt() {
    return timestamp("endAt");
  }
}
