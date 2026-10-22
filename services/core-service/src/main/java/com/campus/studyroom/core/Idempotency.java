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

  public Idempotency(Store d, Clock c) {
    db = d;
    clock = c;
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
        db.one(
            "select * from core.idempotency_record where actor_id=?1 and method=?2 and"
                + " canonical_path=?3 and key=?4",
            actor,
            method,
            path,
            id);
    if (old != null) {
      if (!hash.equals(old.get("requestHash")))
        throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "同一请求编号不能用于不同内容");
      return new Result(((Number) old.get("httpStatus")).intValue(), old.get("responseDataJson"));
    }
    Object data = action.get();
    db.update(
        "insert into"
            + " core.idempotency_record(id,actor_type,actor_id,method,canonical_path,key,request_hash,http_status,response_data_json,created_at,expires_at)"
            + " values(?1,'USER',?2,?3,?4,?5,?6,?7,cast(?8 as jsonb),?9,?10)",
        UUID.randomUUID(),
        actor,
        method,
        path,
        id,
        hash,
        status,
        Store.json(data),
        clock.instant(),
        clock.instant().plusSeconds(86400));
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
