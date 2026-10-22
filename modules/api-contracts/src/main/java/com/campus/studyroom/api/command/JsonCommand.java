package com.campus.studyroom.api.command;

import com.campus.studyroom.api.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/** Typed protocol access with an immutable, lossless snapshot for existing idempotency hashes. */
public abstract class JsonCommand {
  private final Map<String, Object> original;

  protected JsonCommand(Map<String, Object> body) {
    Objects.requireNonNull(body, "JSON object required");
    original = snapshot(body);
  }

  /** Do not rebuild this from typed values: missing/null/unknown fields are significant. */
  @JsonIgnore
  public final Map<String, Object> originalBody() {
    return original;
  }

  private static Map<String, Object> snapshot(Map<String, Object> body) {
    Map<String, Object> result = new LinkedHashMap<>();
    body.forEach((key, value) -> result.put(key, freeze(value)));
    return Collections.unmodifiableMap(result);
  }

  private static Object freeze(Object value) {
    if (value instanceof Map<?, ?> values) {
      Map<String, Object> copied = new LinkedHashMap<>();
      values.forEach((key, item) -> copied.put(key.toString(), freeze(item)));
      return Collections.unmodifiableMap(copied);
    }
    if (value instanceof List<?> values) {
      List<Object> copied = new ArrayList<>();
      values.forEach(item -> copied.add(freeze(item)));
      return Collections.unmodifiableList(copied);
    }
    return value;
  }

  protected final UUID uuid(String key) {
    Object value = original.get(key);
    if (value instanceof UUID id) return id; // Typed in-process callers use the same DTO.
    if (value instanceof String text) {
      try {
        return UUID.fromString(text);
      } catch (IllegalArgumentException ignored) {
      }
    }
    throw new ApiException(400, "INVALID_REQUEST", "请提供有效的资源编号");
  }

  protected final OffsetDateTime timestamp(String key) {
    if (original.get(key) instanceof String value) {
      try {
        return OffsetDateTime.parse(value);
      } catch (java.time.DateTimeException ignored) {
      }
    }
    throw new ApiException(400, "INVALID_TIME", "时间必须包含时区偏移量");
  }

  protected final String text(String key) {
    Object value = original.get(key);
    if (value == null) return null;
    if (value instanceof String s) return s;
    throw new ApiException(400, "INVALID_REQUEST", "文本字段必须为字符串");
  }

  protected final long version(String key) {
    return versionNumber(original.get(key));
  }

  public static long versionNumber(Object value) {
    if (value instanceof Number n) {
      try {
        long version = new BigDecimal(n.toString()).longValueExact();
        if (version >= 0) return version;
      } catch (ArithmeticException | NumberFormatException ignored) {
      }
    }
    throw new ApiException(400, "INVALID_VERSION", "版本号必须为非负整数");
  }
}
