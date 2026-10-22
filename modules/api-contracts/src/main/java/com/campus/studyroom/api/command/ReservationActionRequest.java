package com.campus.studyroom.api.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.*;

/** Parsing occurs inside the idempotent action, so completed legacy responses can replay first. */
public final class ReservationActionRequest extends JsonCommand {
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public ReservationActionRequest(Map<String, Object> body) {
    super(body);
  }

  public long expectedVersion() {
    return version("expectedVersion");
  }

  public String roomCode() {
    return text("roomCode");
  }
}
