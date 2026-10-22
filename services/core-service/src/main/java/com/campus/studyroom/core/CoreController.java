package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CoreController {
  final BookingService booking;
  final ApplicationService applications;
  final AdminService admin;
  final QueryService queries;
  final AccessService access;
  final Idempotency idem;
  final RateLimits limits;
  final Store db;
  final CheckinCodes codes;
  final Notifications notifications;

  public CoreController(
      BookingService b,
      ApplicationService a,
      AdminService ad,
      QueryService q,
      AccessService ac,
      Idempotency i,
      RateLimits l,
      Store d,
      CheckinCodes c,
      Notifications n) {
    booking = b;
    applications = a;
    admin = ad;
    queries = q;
    access = ac;
    idem = i;
    limits = l;
    db = d;
    codes = c;
    notifications = n;
  }

  UUID user(Authentication a) {
    return AuthController.principal(a).userId();
  }

  Object ok(Object data) {
    return ApiResponse.of(data);
  }

  Object write(
      Authentication a,
      String method,
      String path,
      String key,
      Map<String, Object> b,
      int status,
      Supplier<Object> action) {
    var r = idem.execute(user(a), method, path, key, b, status, action);
    if (r.status() == 204) return ResponseEntity.noContent().build();
    return ResponseEntity.status(r.status()).body(ApiResponse.of(r.data()));
  }

  @GetMapping("/version")
  Object version(
      @Value("${studyroom.release-id}") String release,
      @Value("${studyroom.commit}") String commit) {
    return ok(Map.of("releaseId", release, "commit", commit, "service", "core-service"));
  }

  @GetMapping("/availability/{kind:seats|rooms}")
  Object search(
      Authentication a,
      @PathVariable String kind,
      @RequestParam Map<String, String> q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ok(queries.availability(user(a), q, kind.equals("rooms"), page, size));
  }

  @GetMapping("/rooms/{id}/availability")
  Object roomAvailability(
      Authentication a, @PathVariable UUID id, @RequestParam Map<String, String> q) {
    return ok(queries.roomAvailability(user(a), id, q));
  }

  @GetMapping("/reservations")
  Object bookings(
      Authentication a,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String startFrom,
      @RequestParam(required = false) String startTo,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ok(booking.list(user(a), status, startFrom, startTo, page, size));
  }

  @GetMapping("/reservations/{id}")
  Object booking(Authentication a, @PathVariable UUID id) {
    return ok(booking.detail(user(a), id));
  }

  @PostMapping("/reservations")
  Object createBooking(
      Authentication a,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(a, "POST", "/reservations", key, b, 201, () -> booking.create(user(a), b));
  }

  @PostMapping("/reservations/{id}/{action:cancel|check-in}")
  Object bookingAction(
      Authentication a,
      @PathVariable UUID id,
      @PathVariable String action,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    if (action.equals("check-in")) limits.checkIn(user(a));
    return write(
        a,
        "POST",
        "/reservations/" + id + "/" + action,
        key,
        b,
        200,
        () ->
            action.equals("cancel")
                ? booking.cancel(user(a), id, b)
                : booking.checkin(user(a), id, b));
  }

  @GetMapping("/violations")
  Object violations(
      Authentication a,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ok(
        Pages.of(
            db.list(
                "select * from core.violation where user_id=?1 order by occurred_at desc", user(a)),
            page,
            size));
  }

  @GetMapping("/rooms/{id}/display-code")
  Object code(Authentication a, @PathVariable UUID id) {
    if (!access.roomScope(user(a), "room:display_code", id)) throw ApiException.forbidden();
    return ok(codes.get(id));
  }

  @GetMapping("/room-applications")
  Object applications(
      Authentication a,
      @RequestParam(defaultValue = "mine") String view,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ok(applications.list(user(a), view, status, page, size));
  }

  @GetMapping("/room-applications/{id}")
  Object application(Authentication a, @PathVariable UUID id) {
    return ok(applications.detail(user(a), id));
  }

  @PostMapping("/room-applications")
  Object createApplication(
      Authentication a,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(
        a, "POST", "/room-applications", key, b, 201, () -> applications.create(user(a), b));
  }

  @PostMapping("/room-applications/{id}/{action:decisions|cancel}")
  Object applicationAction(
      Authentication a,
      @PathVariable UUID id,
      @PathVariable String action,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(
        a,
        "POST",
        "/room-applications/" + id + "/" + action,
        key,
        b,
        200,
        () ->
            action.equals("cancel")
                ? applications.cancel(user(a), id, b)
                : applications.decision(user(a), id, b));
  }

  @GetMapping("/{kind:departments|campuses|buildings|rooms}")
  Object masters(
      Authentication a,
      @PathVariable String kind,
      @RequestParam Map<String, String> q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int size) {
    return ok(admin.masters(user(a), kind, q, page, size));
  }

  @PostMapping("/{kind:departments|campuses|buildings|rooms}")
  Object createMaster(
      Authentication a,
      @PathVariable String kind,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(a, "POST", "/" + kind, key, b, 201, () -> admin.createMaster(user(a), kind, b));
  }

  @GetMapping("/rooms/{id}")
  Object room(Authentication a, @PathVariable UUID id) {
    return ok(admin.room(user(a), id));
  }

  @PatchMapping("/{kind:departments|campuses|buildings|rooms|seats}/{id}")
  Object updateMaster(
      Authentication a,
      @PathVariable String kind,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(
        a,
        "PATCH",
        "/" + kind + "/" + id,
        key,
        b,
        200,
        () -> admin.updateMaster(user(a), kind, id, b));
  }

  @GetMapping("/rooms/{id}/seats")
  Object seats(
      Authentication a,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int size) {
    if (!admin.visibleRoom(user(a), id)) throw ApiException.notFound();
    return ok(admin.masters(user(a), "seats", Map.of("roomId", id.toString()), page, size));
  }

  @PostMapping("/rooms/{id}/seats")
  Object createSeat(
      Authentication a,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    Map<String, Object> body = new LinkedHashMap<>(b);
    body.put("roomId", id.toString());
    return write(
        a,
        "POST",
        "/rooms/" + id + "/seats",
        key,
        body,
        201,
        () -> admin.createMaster(user(a), "seats", body));
  }

  @PutMapping("/rooms/{id}/opening-rules")
  Object opening(
      Authentication a,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(
        a,
        "PUT",
        "/rooms/" + id + "/opening-rules",
        key,
        b,
        200,
        () -> admin.opening(user(a), id, b));
  }

  @GetMapping("/users")
  Object users(
      Authentication a,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ok(admin.users(user(a), q, page, size));
  }

  @PostMapping("/users")
  Object createUser(
      Authentication a,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(a, "POST", "/users", key, b, 201, () -> admin.createUser(user(a), b));
  }

  @PatchMapping("/users/{id}")
  Object updateUser(
      Authentication a,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(a, "PATCH", "/users/" + id, key, b, 200, () -> admin.updateUser(user(a), id, b));
  }

  @PostMapping("/users/{id}/reset-password")
  Object resetPassword(
      Authentication a,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(
        a,
        "POST",
        "/users/" + id + "/reset-password",
        key,
        b,
        204,
        () -> admin.resetPassword(user(a), id, b));
  }

  @PutMapping("/users/{id}/role-bindings")
  Object bindings(
      Authentication a,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(
        a,
        "PUT",
        "/users/" + id + "/role-bindings",
        key,
        b,
        200,
        () -> admin.roleBindings(user(a), id, b));
  }

  @GetMapping("/roles")
  Object roles(Authentication a) {
    return ok(admin.roles(user(a)));
  }

  @PostMapping("/roles")
  Object createRole(
      Authentication a,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(a, "POST", "/roles", key, b, 201, () -> admin.createRole(user(a), b));
  }

  @PutMapping("/roles/{id}/permissions")
  Object permissions(
      Authentication a,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(
        a,
        "PUT",
        "/roles/" + id + "/permissions",
        key,
        b,
        200,
        () -> admin.rolePermissions(user(a), id, b));
  }

  @DeleteMapping("/roles/{id}")
  Object deleteRole(
      Authentication a,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(a, "DELETE", "/roles/" + id, key, b, 200, () -> admin.deleteRole(user(a), id, b));
  }

  @GetMapping("/permissions")
  Object permissions(Authentication a) {
    access.require(user(a), "role:manage");
    return ok(Pages.of(db.list("select * from core.permission order by code"), 1, 100));
  }

  @GetMapping("/system-config")
  Object config(Authentication a) {
    return ok(admin.config(user(a)));
  }

  @PatchMapping("/system-config")
  Object configUpdate(
      Authentication a,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(a, "PATCH", "/system-config", key, b, 200, () -> admin.configUpdate(user(a), b));
  }

  @GetMapping("/statistics/usage")
  Object statistics(Authentication a, @RequestParam Map<String, String> q) {
    return ok(queries.statistics(user(a), q));
  }

  @GetMapping("/notifications")
  Object notifications(
      Authentication a,
      @RequestParam Map<String, String> q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ok(notifications.list(user(a), false, q, page, size));
  }

  @GetMapping("/notification-deliveries")
  Object deliveries(
      Authentication a,
      @RequestParam Map<String, String> q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ok(notifications.list(user(a), true, q, page, size));
  }

  @PostMapping("/notifications/{id}/read")
  Object readNotification(
      Authentication a,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(
        a,
        "POST",
        "/notifications/" + id + "/read",
        key,
        b,
        200,
        () -> notifications.read(user(a), id, b));
  }

  @PostMapping("/notification-deliveries/{id}/retry")
  Object retryNotification(
      Authentication a,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> b) {
    return write(
        a,
        "POST",
        "/notification-deliveries/" + id + "/retry",
        key,
        b,
        202,
        () -> notifications.retry(user(a), id, b));
  }

  @GetMapping("/audit-events")
  Object audit(
      Authentication a,
      @RequestParam Map<String, String> q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ok(queries.audit(user(a), q, page, size));
  }

  @GetMapping("/rooms/{id}/resource-claims")
  Object claims(
      Authentication a,
      @PathVariable UUID id,
      @RequestParam Map<String, String> q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ok(queries.claims(user(a), id, q, page, size));
  }
}
