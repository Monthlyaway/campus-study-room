package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import com.campus.studyroom.security.SessionPrincipal;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
public class InternalController {
  final AccessService access;
  final Store db;
  final ResourceService resource;
  final Notifications notifications;

  public InternalController(AccessService a, Store d, ResourceService r, Notifications n) {
    access = a;
    db = d;
    resource = r;
    notifications = n;
  }

  UUID nullable(Object o) {
    return o == null ? null : Store.id(o);
  }

  @PostMapping("/authorization/check")
  Object check(@RequestBody Map<String, Object> b) {
    UUID u = Store.id(b.get("userId"));
    long ver = ((Number) b.get("authVersion")).longValue();
    boolean valid = access.valid(new SessionPrincipal(u, ver, Instant.now()));
    if (!valid) return ApiResponse.of(Map.of("allowed", false, "reason", "INVALID_SESSION"));
    boolean allowed =
        access.allowed(
            u,
            Objects.toString(b.get("permission")),
            nullable(b.get("roomId")),
            nullable(b.get("ownerId")),
            nullable(b.get("assignedTo")));
    return ApiResponse.of(Map.of("allowed", allowed, "reason", allowed ? "ALLOWED" : "FORBIDDEN"));
  }

  @GetMapping("/rooms/{id}/reference")
  Object room(@PathVariable UUID id) {
    return ApiResponse.of(
        db.need("select id,name,enabled,department_id from core.room where id=?1", id));
  }

  @PostMapping("/resource-commands")
  Object command(@RequestBody Map<String, Object> b) {
    return ApiResponse.of(resource.command(b));
  }

  @PostMapping("/notifications")
  Object notification(@RequestBody Map<String, Object> b) {
    return ApiResponse.of(notifications.internal(b));
  }

  @GetMapping("/repair-workers")
  Object workers(
      @RequestParam UUID actorId,
      @RequestParam long authVersion,
      @RequestParam UUID roomId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (!access.valid(new SessionPrincipal(actorId, authVersion, Instant.now())))
      throw new ApiException(401, "INVALID_SESSION", "会话已失效");
    if (!access.roomScope(actorId, "repair:assign", roomId)) throw ApiException.forbidden();
    return ApiResponse.of(
        Pages.of(
            db.list(
                "select distinct u.id user_id,u.display_name from core.app_user u join"
                    + " core.user_role ur on ur.user_id=u.id join core.role r on r.id=ur.role_id"
                    + " where u.enabled and r.enabled and r.code='REPAIRER' order by"
                    + " u.display_name"),
            page,
            size));
  }
}
