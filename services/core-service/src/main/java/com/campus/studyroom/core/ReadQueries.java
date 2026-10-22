package com.campus.studyroom.core;

/**
 * Shared SQL fragments for parameterized read projections. All arguments are code-owned
 * identifiers.
 */
final class ReadQueries {
  private ReadQueries() {}

  static String scope(String permission, String actor, String room, String department) {
    return "exists(select 1 from core.user_role ur join core.role role on role.id=ur.role_id and"
        + " role.enabled join core.role_permission rp on rp.role_id=role.id join"
        + " core.permission p on p.id=rp.permission_id join core.user_role_scope sc on"
        + " sc.user_role_id=ur.id where ur.user_id="
        + actor
        + " and p.code='"
        + permission
        + "' and (sc.scope_type='ALL' or (sc.scope_type='ROOM' and sc.room_id="
        + room
        + ") or (sc.scope_type='DEPARTMENT' and sc.department_id="
        + department
        + ") or (sc.scope_type='SHARED' and "
        + room
        + " is not null and "
        + department
        + " is null)))";
  }
}
