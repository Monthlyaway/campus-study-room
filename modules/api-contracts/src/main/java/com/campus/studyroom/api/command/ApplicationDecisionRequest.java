package com.campus.studyroom.api.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.*;

/** Parsing occurs inside the idempotent action, so completed legacy responses can replay first. */
public final class ApplicationDecisionRequest extends JsonCommand {
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public ApplicationDecisionRequest(Map<String, Object> body) {
    super(body);
  }

  public long expectedVersion() {
    return version("expectedVersion");
  }

  public String decision() {
    return text("decision");
  }

  public String comment() {
    return text("comment");
  }
}
