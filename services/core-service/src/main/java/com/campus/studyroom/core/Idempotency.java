package com.campus.studyroom.core;

import com.campus.studyroom.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Idempotency {
  final Store db;
  final Clock clock;
  final com.campus.studyroom.core.persistence.IdempotencyRepository records;

  public Idempotency(
      Store d, Clock c, com.campus.studyroom.core.persistence.IdempotencyRepository records) {
    db = d;
    clock = c;
    this.records = records;
  }

  public record Result(int status, Object data) {}

  @Transactional
  public Result execute(
      UUID actor,
      String method,
      String path,
      String key,
      Object body,
      int status,
      Supplier<Object> action) {
    UUID id;
    try {
      id = UUID.fromString(key);
    } catch (Exception e) {
      throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "请提供有效的 Idempotency-Key");
    }
    String hash = hash(body);
    db.advisory(actor + ":" + method + ":" + path + ":" + id);
    var old =
        records.findByActorTypeAndActorIdAndMethodAndCanonicalPathAndKey(
            "USER", actor, method, path, id);
    if (old.isPresent()) {
      var record = old.get();
      if (!hash.equals(record.requestHash))
        throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "同一请求编号不能用于不同内容");
      return new Result(
          record.httpStatus, Store.JSON.readValue(record.responseDataJson, Object.class));
    }
    Object data = action.get();
    var record = new com.campus.studyroom.core.persistence.IdempotencyEntity();
    record.id = UUID.randomUUID();
    record.actorType = "USER";
    record.actorId = actor;
    record.method = method;
    record.canonicalPath = path;
    record.key = id;
    record.requestHash = hash;
    record.httpStatus = status;
    record.responseDataJson = Store.json(data);
    record.createdAt = clock.instant();
    record.expiresAt = record.createdAt.plusSeconds(86400);
    records.save(record);
    return new Result(status, data);
  }

  static String hash(Object body) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(Store.json(canonical(body)).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static Object canonical(Object v) {
    if (v instanceof Map<?, ?> m) {
      var r = new TreeMap<String, Object>();
      m.forEach((k, x) -> r.put(k.toString(), canonical(x)));
      return r;
    }
    if (v instanceof List<?> l) return l.stream().map(Idempotency::canonical).toList();
    return v;
  }
}
