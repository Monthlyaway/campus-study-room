package com.campus.studyroom.security;

import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Request-scoped correlation with bounded metadata and cleanup on every outcome. */
public final class RequestTelemetry {
  private final Map<String, String> previous = MDC.getCopyOfContextMap();
  private final long start = System.nanoTime();
  private final UUID id;

  public RequestTelemetry(String incomingId) {
    UUID parsed;
    try {
      parsed = UUID.fromString(incomingId);
    } catch (Exception ignored) {
      parsed = UUID.randomUUID();
    }
    id = parsed;
    MDC.clear();
    MDC.put("requestId", id.toString());
  }

  public UUID id() {
    return id;
  }

  public void finish(int status, boolean emit, Throwable failure) {
    try {
      if (emit) {
        var logger = LoggerFactory.getLogger(RequestTelemetry.class);
        int actualStatus = failure == null ? status : 500;
        MDC.put(
            "event",
            actualStatus == 401 || actualStatus == 403
                ? "security_request_rejected"
                : "http_request");
        MDC.put("status", Integer.toString(actualStatus));
        MDC.put("durationMs", Long.toString((System.nanoTime() - start) / 1_000_000));
        if (MDC.get("errorCode") == null)
          MDC.put("errorCode", actualStatus >= 400 ? "HTTP_" + actualStatus : "");
        if (failure != null) MDC.put("exceptionType", failure.getClass().getName());
        if (actualStatus >= 500) logger.error("Request finished");
        else if (actualStatus == 401 || actualStatus == 403) logger.warn("Request rejected");
        else logger.info("Request finished");
      }
    } finally {
      MDC.clear();
      if (previous != null) MDC.setContextMap(previous);
    }
  }
}
