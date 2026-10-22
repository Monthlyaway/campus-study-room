package com.campus.studyroom.core;

import com.campus.studyroom.api.ApiException;
import com.campus.studyroom.core.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RateLimits {
  final Store db;
  final Clock clock;
  final RateLimitRepository records;

  public RateLimits(Store db, Clock clock, RateLimitRepository records) {
    this.db = db;
    this.clock = clock;
    this.records = records;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void checkLogin(String key) {
    var r = bucket(key);
    if (r.blockedUntil != null && r.blockedUntil.isAfter(clock.instant())) throw limited();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void loginFailure(String key) {
    var r = bucket(key);
    var times = times(r, 300);
    times.add(clock.instant().toString());
    if (times.size() > 5) times = new ArrayList<>(times.subList(times.size() - 5, times.size()));
    r.recentAttempts = times;
    r.blockedUntil = times.size() >= 5 ? clock.instant().plusSeconds(300) : null;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void checkIn(UUID user) {
    var r = bucket("checkin:" + user);
    var times = times(r, 60);
    if (times.size() >= 5) throw limited();
    times.add(clock.instant().toString());
    r.recentAttempts = times;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void clear(String key) {
    db.advisory(key);
    records.findById(key).ifPresent(records::delete);
  }

  RateLimitEntity bucket(String key) {
    db.advisory(key);
    return records
        .findById(key)
        .orElseGet(
            () -> {
              var r = new RateLimitEntity();
              r.bucketKey = key;
              r.recentAttempts = new ArrayList<>();
              return records.save(r);
            });
  }

  List<String> times(RateLimitEntity r, int seconds) {
    var result = new ArrayList<String>();
    for (String t : r.recentAttempts)
      if (Instant.parse(t).isAfter(clock.instant().minusSeconds(seconds))) result.add(t);
    return result;
  }

  ApiException limited() {
    return new ApiException(429, "RATE_LIMITED", "操作过于频繁，请稍后重试", Map.of("retryAfter", 60));
  }
}
