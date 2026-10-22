package com.campus.studyroom.core;

import com.campus.studyroom.api.ApiException;
import com.campus.studyroom.security.SessionPrincipal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccessService {
  final Store db;
  final Clock clock;

  public AccessService(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public Map<String, Object> user(UUID id) {
    return db.need("select * from core.app_user where id=?1", id);
  }

  public boolean valid(SessionPrincipal p) {
    if (p == null || p.authenticatedAt().plus(Duration.ofHours(8)).isBefore(clock.instant()))
      return false;
    var u = db.one("select enabled,auth_version from core.app_user where id=?1", p.userId());
    return u != null
        && Boolean.TRUE.equals(u.get("enabled"))
        && Store.number(u, "authVersion") == p.authVersion();
  }

  public Set<String> permissions(UUID user) {
    return new LinkedHashSet<>(
        db
            .list(
                "select distinct p.code from core.user_role ur join core.role r on r.id=ur.role_id"
                    + " and r.enabled join core.role_permission rp on rp.role_id=r.id join"
                    + " core.permission p on p.id=rp.permission_id where ur.user_id=?1",
                user)
            .stream()
            .map(m -> m.get("code").toString())
            .toList());
  }

  public boolean has(UUID u, String permission) {
    return permissions(u).contains(permission);
  }

  public void require(UUID u, String p) {
    if (!has(u, p)) throw ApiException.forbidden();
  }

  public boolean scope(UUID u, String permission, UUID room, UUID department) {
    return db.count(
            "select count(*) from core.user_role ur join core.role r on r.id=ur.role_id and"
                + " r.enabled join core.role_permission rp on rp.role_id=r.id join core.permission"
                + " p on p.id=rp.permission_id join core.user_role_scope s on s.user_role_id=ur.id"
                + " where ur.user_id=?1 and p.code=?2 and (s.scope_type='ALL' or"
                + " (s.scope_type='ROOM' and s.room_id=?3) or (s.scope_type='DEPARTMENT' and"
                + " s.department_id=?4) or (s.scope_type='SHARED' and cast(?4 as uuid) is null and"
                + " cast(?3 as uuid) is not null))",
            u,
            permission,
            room,
            department)
        > 0;
  }

  public boolean roomScope(UUID u, String permission, UUID room) {
    var r = db.one("select department_id from core.room where id=?1", room);
    return r != null
        && scope(
            u,
            permission,
            room,
            r.get("departmentId") == null ? null : Store.id(r.get("departmentId")));
  }

  public boolean admissible(UUID u, UUID room) {
    return db.count(
            "select count(*) from core.room r join core.app_user u on u.id=?1 where r.id=?2 and"
                + " (r.department_id is null or r.department_id=u.department_id)",
            u,
            room)
        > 0;
  }

  public Map<String, Object> me(UUID u) {
    var m = user(u);
    m.remove("passwordHash");
    m.put("userId", m.get("id"));
    m.put("permissions", permissions(u));
    m.put(
        "roles",
        db
            .list(
                "select r.code from core.user_role ur join core.role r on r.id=ur.role_id and"
                    + " r.enabled where ur.user_id=?1",
                u)
            .stream()
            .map(x -> x.get("code"))
            .toList());
    m.put(
        "scopes",
        db.list(
            "select s.scope_type,s.department_id,s.room_id from core.user_role ur join"
                + " core.user_role_scope s on s.user_role_id=ur.id where ur.user_id=?1",
            u));
    return m;
  }

  public boolean allowed(UUID u, String p, UUID room, UUID owner, UUID assigned) {
    if (p.equals("notification:read_own") || p.equals("notification:mark_read_own")) return true;
    if (p.equals("repair:read_own")
        || p.equals("repair:cancel_own")
        || p.equals("repair:rate_own")
        || p.equals("repair:accept_own"))
      return u.equals(owner) && (!p.equals("repair:accept_own") || !u.equals(assigned));
    if (p.equals("repair:create")) return has(u, p) && admissible(u, room);
    if (p.equals("repair:work") || p.equals("repair:read_assigned"))
      return has(u, p) && u.equals(assigned);
    if (p.equals("repair:accept_scope") && u.equals(assigned)) return false;
    return room != null ? roomScope(u, p, room) : scope(u, p, null, null);
  }
}
