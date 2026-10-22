package com.campus.studyroom.api;

import java.util.UUID;

/** Correlation identifier scoped to one request or background transaction. */
public final class RequestIds {
  private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

  private RequestIds() {}

  public static UUID current() {
    UUID id = CURRENT.get();
    return id == null ? UUID.randomUUID() : id;
  }

  public static void set(UUID id) {
    CURRENT.set(id);
  }

  public static void clear() {
    CURRENT.remove();
  }
}
