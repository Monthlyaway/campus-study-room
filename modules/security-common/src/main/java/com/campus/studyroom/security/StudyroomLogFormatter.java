package com.campus.studyroom.security;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

/**
 * An allowlist of operational metadata; request bodies, headers and exception text never enter
 * JSON.
 */
public final class StudyroomLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{1,160}");
  private static final Pattern LEGACY =
      Pattern.compile(
          "(?:^|\\s)(event|errorCode|requestId|commandId|taskId|orderId|exceptionType)=([A-Za-z0-9_.:-]+)");
  private static final JsonWriter<Map<String, Object>> JSON =
      JsonWriter.<Map<String, Object>>standard().withNewLineAtEnd();
  private final String service;
  private final String release;

  public StudyroomLogFormatter(Environment environment) {
    service = token(environment.getProperty("spring.application.name", "unknown"), "unknown");
    release = token(environment.getProperty("studyroom.release-id", "development"), "development");
  }

  @Override
  public String format(ILoggingEvent input) {
    Map<String, String> values = new LinkedHashMap<>();
    var legacy =
        LEGACY.matcher(input.getFormattedMessage() == null ? "" : input.getFormattedMessage());
    while (legacy.find()) values.put(legacy.group(1), legacy.group(2));
    if (input.getMDCPropertyMap() != null) values.putAll(input.getMDCPropertyMap());
    if (input.getKeyValuePairs() != null) {
      for (var pair : input.getKeyValuePairs()) values.put(pair.key, String.valueOf(pair.value));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("timestamp", input.getInstant().toString());
    result.put("level", input.getLevel().toString());
    result.put("service", service);
    result.put("releaseId", release);
    result.put("requestId", uuid(values.get("requestId")));
    result.put("actorId", uuid(values.get("actorId")));
    result.put("event", token(values.get("event"), "runtime"));
    result.put("errorCode", token(values.get("errorCode"), ""));
    result.put("durationMs", number(values.get("durationMs")));
    result.put("logger", input.getLoggerName());
    for (String key : new String[] {"commandId", "taskId", "orderId"}) {
      String id = uuid(values.get(key));
      if (!id.isEmpty()) result.put(key, id);
    }
    if (values.containsKey("status")) result.put("status", number(values.get("status")));
    if (input.getThrowableProxy() != null)
      result.put("exceptionType", token(input.getThrowableProxy().getClassName(), "Exception"));
    else if (values.containsKey("exceptionType"))
      result.put("exceptionType", token(values.get("exceptionType"), "Exception"));
    return JSON.writeToString(result);
  }

  private static String token(String value, String fallback) {
    return value != null && TOKEN.matcher(value).matches() ? value : fallback;
  }

  private static String uuid(String value) {
    try {
      return UUID.fromString(value).toString();
    } catch (Exception ignored) {
      return "";
    }
  }

  private static long number(String value) {
    try {
      return Math.max(0, Long.parseLong(value));
    } catch (Exception ignored) {
      return 0;
    }
  }
}
