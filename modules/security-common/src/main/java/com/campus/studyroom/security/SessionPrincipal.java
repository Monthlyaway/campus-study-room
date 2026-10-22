package com.campus.studyroom.security;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record SessionPrincipal(UUID userId, long authVersion, Instant authenticatedAt)
    implements Serializable, java.security.Principal {
  private static final long serialVersionUID = 1L;

  @Override
  public String getName() {
    return userId.toString();
  }
}
