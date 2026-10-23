package com.campus.studyroom.api.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.OffsetDateTime;
import java.util.*;

/** Parsing occurs inside the idempotent action, so completed legacy responses can replay first. */
public final class ApplicationCreateRequest extends JsonCommand {
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public ApplicationCreateRequest(Map<String, Object> body) {
    super(body);
  }

  public UUID roomId() {
    return uuid("roomId");
  }

  public OffsetDateTime startAt() {
    return timestamp("startAt");
  }

  public OffsetDateTime endAt() {
    return timestamp("endAt");
  }

  public String purposeTitle() {
    return text("purposeTitle");
  }

  public String purposeDescription() {
    return text("purposeDescription");
  }
}
