package com.campus.studyroom.core;

import static org.junit.jupiter.api.Assertions.*;

import com.campus.studyroom.api.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DomainPolicyTest {
  final Instant now = Instant.parse("2026-10-22T00:00:00Z");

  @Test
  void acceptsAdjacentHourAndMaximumBoundary() {
    assertDoesNotThrow(
        () -> DomainPolicy.interval(now.plusSeconds(3600), now.plusSeconds(5 * 3600), now, 4));
  }

  @Test
  void rejectsNaiveTimeAndNonHourlyOrPastInterval() {
    assertThrows(ApiException.class, () -> DomainPolicy.time("2026-10-22T10:00:00"));
    assertThrows(
        ApiException.class,
        () -> DomainPolicy.interval(now.plusSeconds(3601), now.plusSeconds(7200), now, 4));
    assertThrows(
        ApiException.class, () -> DomainPolicy.interval(now, now.plusSeconds(3600), now, 4));
    assertThrows(
        ApiException.class,
        () -> DomainPolicy.interval(now.plusSeconds(3600), now.plusSeconds(6 * 3600), now, 4));
  }

  @Test
  void endMustBeWithinFourteenDays() {
    assertThrows(
        ApiException.class,
        () ->
            DomainPolicy.interval(
                now.plusSeconds(14 * 86400), now.plusSeconds(14 * 86400 + 3600), now, 4));
  }

  @Test
  void checkinLeftInclusiveRightExclusive() {
    assertThrows(ApiException.class, () -> DomainPolicy.checkIn(now, now.minusNanos(1)));
    assertDoesNotThrow(() -> DomainPolicy.checkIn(now, now));
    assertDoesNotThrow(() -> DomainPolicy.checkIn(now, now.plusSeconds(900).minusNanos(1)));
    assertThrows(ApiException.class, () -> DomainPolicy.checkIn(now, now.plusSeconds(900)));
  }

  @Test
  void expectedVersionAndTerminalStateAreEnforced() {
    assertThrows(ApiException.class, () -> DomainPolicy.version(Map.of("version", 2), 1));
    assertThrows(ApiException.class, () -> DomainPolicy.version(Map.of("version", 2), null));
    assertDoesNotThrow(() -> DomainPolicy.version(Map.of("version", 2), 2));
    assertThrows(
        ApiException.class,
        () -> DomainPolicy.requireState(Map.of("status", "COMPLETED"), "RESERVED", "CHECKED_IN"));
  }

  @Test
  void canonicalIdempotencyIgnoresObjectKeyOrderButNotValues() {
    var a = new LinkedHashMap<String, Object>();
    a.put("seat", 1);
    a.put("start", 2);
    var b = new LinkedHashMap<String, Object>();
    b.put("start", 2);
    b.put("seat", 1);
    assertEquals(Idempotency.hash(a), Idempotency.hash(b));
    b.put("seat", 3);
    assertNotEquals(Idempotency.hash(a), Idempotency.hash(b));
  }

  @Test
  void textUsesUnicodeCodepointsAndRejectsWhitespace() {
    assertEquals("自习", DomainPolicy.text(" 自习 ", 2));
    assertThrows(ApiException.class, () -> DomainPolicy.text("   ", 10));
    assertThrows(ApiException.class, () -> DomainPolicy.text("三字长", 2));
  }
}
