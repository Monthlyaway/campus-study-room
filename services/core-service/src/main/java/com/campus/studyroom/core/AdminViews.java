package com.campus.studyroom.core;

import com.campus.studyroom.core.persistence.*;
import java.util.*;

/** Explicit public DTOs: never serialize a persistence entity or its password hash by accident. */
public final class AdminViews {
  private AdminViews() {}

  static Map<String, Object> map(Object... pairs) {
    var m = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2)
      m.put(pairs[i].toString(), pairs[i + 1] instanceof UUID u ? u.toString() : pairs[i + 1]);
    return m;
  }

  public static Map<String, Object> user(AccountEntity a, boolean withPassword) {
    var m =
        map(
            "id",
            a.id,
            "username",
            a.username,
            "schoolNo",
            a.schoolNo,
            "displayName",
            a.displayName,
            "email",
            a.email,
            "departmentId",
            a.departmentId,
            "enabled",
            a.enabled,
            "authVersion",
            a.authVersion,
            "version",
            a.version,
            "createdAt",
            a.createdAt,
            "updatedAt",
            a.updatedAt);
    if (withPassword) m.put("passwordHash", a.passwordHash);
    return m;
  }

  static Map<String, Object> master(Object x) {
    if (x instanceof DepartmentEntity a)
      return map(
          "id",
          a.id,
          "code",
          a.code,
          "name",
          a.name,
          "enabled",
          a.enabled,
          "version",
          a.version,
          "createdAt",
          a.createdAt,
          "updatedAt",
          a.updatedAt);
    if (x instanceof CampusEntity a)
      return map(
          "id", a.id, "code", a.code, "name", a.name, "enabled", a.enabled, "version", a.version);
    if (x instanceof BuildingEntity a)
      return map(
          "id",
          a.id,
          "campusId",
          a.campusId,
          "code",
          a.code,
          "name",
          a.name,
          "enabled",
          a.enabled,
          "version",
          a.version);
    if (x instanceof Room a)
      return map(
          "id",
          a.id,
          "buildingId",
          a.buildingId,
          "departmentId",
          a.departmentId,
          "code",
          a.code,
          "name",
          a.name,
          "enabled",
          a.enabled,
          "version",
          a.version);
    if (x instanceof SeatEntity a)
      return map(
          "id",
          a.id,
          "roomId",
          a.roomId,
          "code",
          a.code,
          "hasPower",
          a.hasPower,
          "enabled",
          a.enabled,
          "version",
          a.version);
    throw new IllegalArgumentException("Unknown master entity");
  }

  static Map<String, Object> role(RoleEntity r) {
    return map(
        "id", r.id, "code", r.code, "name", r.name, "enabled", r.enabled, "version", r.version);
  }

  static Map<String, Object> scope(RoleScopeEntity s) {
    return map("scopeType", s.scopeType, "departmentId", s.departmentId, "roomId", s.roomId);
  }

  static Map<String, Object> opening(OpeningRuleEntity r) {
    return map("weekday", r.weekday, "startMinute", r.startMinute, "endMinute", r.endMinute);
  }
}
