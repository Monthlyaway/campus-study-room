package com.campus.studyroom.core;

import com.campus.studyroom.api.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RateLimits {
  final Store db;
  final Clock clock;

  public RateLimits(Store d, Clock c) {
    db = d;
    clock = c;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void checkLogin(String key) {
    var m = bucket(key);
    if (m.get("blockedUntil") != null
        && DomainPolicy.time(m.get("blockedUntil")).isAfter(clock.instant())) throw limited();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void loginFailure(String key) {
    var m = bucket(key);
    List<String> times = times(m, 300);
    times.add(clock.instant().toString());
    if (times.size() > 5) times = times.subList(times.size() - 5, times.size());
    db.update(
        "update core.rate_limit_bucket set recent_attempts=cast(?1 as"
            + " jsonb),blocked_until=?2,version=version+1 where bucket_key=?3",
        Store.json(times),
        times.size() >= 5 ? clock.instant().plusSeconds(300) : null,
        key);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void checkIn(UUID user) {
    String key = "checkin:" + user;
    var m = bucket(key);
    List<String> times = times(m, 60);
    if (times.size() >= 5) throw limited();
    times.add(clock.instant().toString());
    db.update(
        "update core.rate_limit_bucket set recent_attempts=cast(?1 as jsonb),version=version+1"
            + " where bucket_key=?2",
        Store.json(times),
        key);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void clear(String key) {
    db.update("delete from core.rate_limit_bucket where bucket_key=?1", key);
  }

  Map<String, Object> bucket(String key) {
    db.advisory(key);
    db.update(
        "insert into core.rate_limit_bucket(bucket_key) values(?1) on conflict do nothing", key);
    return db.need("select * from core.rate_limit_bucket where bucket_key=?1 for update", key);
  }

  List<String> times(Map<String, Object> m, int seconds) {
    List<String> result = new ArrayList<>();
    for (Object x : (List<?>) m.get("recentAttempts"))
      if (Instant.parse(x.toString()).isAfter(clock.instant().minusSeconds(seconds)))
        result.add(x.toString());
    return result;
  }

  ApiException limited() {
    return new ApiException(429, "RATE_LIMITED", "操作过于频繁，请稍后重试", Map.of("retryAfter", 60));
  }
}
