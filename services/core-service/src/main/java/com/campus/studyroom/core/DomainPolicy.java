package com.campus.studyroom.core;

import com.campus.studyroom.api.ApiException;
import java.time.*;
import java.util.*;

public final class DomainPolicy {
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private DomainPolicy() {}

  public static Instant time(Object value) {
    try {
      return OffsetDateTime.parse(value.toString()).toInstant();
    } catch (Exception e) {
      throw new ApiException(400, "INVALID_TIME", "时间必须包含时区偏移量");
    }
  }

  public static void interval(Instant start, Instant end, Instant now, int max) {
    var s = start.atZone(ZONE);
    var e = end.atZone(ZONE);
    if (s.getMinute() != 0
        || s.getSecond() != 0
        || s.getNano() != 0
        || e.getMinute() != 0
        || e.getSecond() != 0
        || e.getNano() != 0) throw new ApiException(422, "HOUR_ALIGNMENT_REQUIRED", "开始和结束时间须为整点");
    if (!start.isAfter(now)
        || !end.isAfter(start)
        || end.isAfter(now.plus(Duration.ofDays(14)))
        || Duration.between(start, end).toHours() > max)
      throw new ApiException(422, "INVALID_BOOKING_WINDOW", "请选择未来14天内的有效时段，且不超过最长时长");
  }

  public static void checkIn(Instant start, Instant now) {
    if (now.isBefore(start) || !now.isBefore(start.plusSeconds(900)))
      throw new ApiException(422, "CHECKIN_WINDOW_CLOSED", "签到仅在预约开始后的15分钟内开放");
  }

  public static String text(Object value, int max) {
    String s = value == null ? "" : value.toString().trim();
    if (s.isEmpty() || s.codePointCount(0, s.length()) > max)
      throw new ApiException(422, "INVALID_TEXT", "请输入1至" + max + "字的内容");
    return s;
  }

  public static void version(long actual, Object expected) {
    if (expectedVersion(expected) != actual)
      throw ApiException.conflict("VERSION_CONFLICT", "数据已变化，请刷新后重试");
  }

  public static void requireState(String actual, String... allowed) {
    if (!Arrays.asList(allowed).contains(actual))
      throw ApiException.conflict("INVALID_STATE", "当前状态不允许此操作");
  }

  public static void version(Map<String, Object> m, Object expected) {
    version(Store.number(m, "version"), expected);
  }

  static long expectedVersion(Object value) {
    return com.campus.studyroom.api.command.JsonCommand.versionNumber(value);
  }

  public static void requireState(Map<String, Object> m, String... allowed) {
    if (!Arrays.asList(allowed).contains(m.get("status")))
      throw ApiException.conflict("INVALID_STATE", "当前状态不允许此操作");
  }
}
