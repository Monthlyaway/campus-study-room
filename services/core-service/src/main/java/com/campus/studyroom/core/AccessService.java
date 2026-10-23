package com.campus.studyroom.core;

import com.campus.studyroom.api.ApiException;
import com.campus.studyroom.core.persistence.*;
import com.campus.studyroom.security.SessionPrincipal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccessService {
  final AccountRepository accounts;
  final UserRoleRepository bindings;
  final RoleScopeRepository scopes;
  final RoomRepository rooms;
  final SystemConfigRepository configs;
  final Clock clock;

  public AccessService(
      AccountRepository accounts,
      UserRoleRepository bindings,
      RoleScopeRepository scopes,
      RoomRepository rooms,
      SystemConfigRepository configs,
      Clock clock) {
    this.accounts = accounts;
    this.bindings = bindings;
    this.scopes = scopes;
    this.rooms = rooms;
    this.configs = configs;
    this.clock = clock;
  }

  public Map<String, Object> user(UUID id) {
    return AdminViews.user(accounts.findById(id).orElseThrow(ApiException::notFound), true);
  }

  public boolean valid(SessionPrincipal p) {
    if (p == null || !p.authenticatedAt().plus(Duration.ofHours(8)).isAfter(clock.instant()))
      return false;
    return accounts
        .findById(p.userId())
        .filter(a -> a.enabled && a.authVersion == p.authVersion())
        .isPresent();
  }

  public Set<String> permissions(UUID user) {
    return new LinkedHashSet<>(bindings.permissionCodes(user));
  }

  public boolean has(UUID user, String permission) {
    return permissions(user).contains(permission);
  }

  public void require(UUID user, String permission) {
    if (!has(user, permission)) throw ApiException.forbidden();
  }

  public boolean scope(UUID user, String permission, UUID room, UUID department) {
    return bindings.grantsScope(
        user, permission, room, department, room != null && department == null);
  }

  public boolean createRoomScope(UUID user, UUID department) {
    return bindings.grantsScope(user, "room:manage", null, department, department == null);
  }

  public boolean roomScope(UUID user, String permission, UUID room) {
    return rooms
        .findById(room)
        .map(r -> scope(user, permission, room, r.departmentId))
        .orElse(false);
  }

  public boolean admissible(UUID user, UUID room) {
    var a = accounts.findById(user);
    var r = rooms.findById(room);
    return a.isPresent()
        && r.isPresent()
        && (r.get().departmentId == null
            || Objects.equals(r.get().departmentId, a.get().departmentId));
  }

  public Map<String, Object> me(UUID user) {
    var m = AdminViews.user(accounts.findById(user).orElseThrow(ApiException::notFound), false);
    m.put("userId", m.get("id"));
    m.put("permissions", permissions(user));
    m.put("roles", bindings.roleCodes(user));
    m.put("scopes", scopes.forUser(user).stream().map(AdminViews::scope).toList());
    m.put(
        "bookingLimits",
        Map.of(
            "reservationMaxHours",
            maxHours("M1_MAX_DURATION_HOURS"),
            "applicationMaxHours",
            maxHours("M2_MAX_DURATION_HOURS")));
    return m;
  }

  int maxHours(String key) {
    return ((Number) configs.findById(key).orElseThrow(ApiException::notFound).value).intValue();
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
