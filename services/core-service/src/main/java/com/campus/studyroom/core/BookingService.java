package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import com.campus.studyroom.core.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {
  final Store db;
  final AccessService access;
  final ResourceService resource;
  final Events events;
  final Clock clock;
  final CheckinCodes codes;
  final ReservationRepository reservations;
  final ViolationRepository violations;
  final SeatRepository seats;
  final SystemConfigRepository configs;
  final ConfigRevisionRepository configRevisions;

  public BookingService(
      Store d,
      AccessService a,
      ResourceService r,
      Events e,
      Clock c,
      CheckinCodes x,
      ReservationRepository reservations,
      ViolationRepository violations,
      SeatRepository seats,
      SystemConfigRepository configs,
      ConfigRevisionRepository configRevisions) {
    db = d;
    access = a;
    resource = r;
    events = e;
    clock = c;
    codes = x;
    this.reservations = reservations;
    this.violations = violations;
    this.seats = seats;
    this.configs = configs;
    this.configRevisions = configRevisions;
  }

  static final String DETAIL =
      "select r.*,s.code seat_code,s.has_power,rm.name room_name,rm.code room_code from"
          + " core.reservation r join core.seat s on s.id=r.seat_id join core.room rm on"
          + " rm.id=r.room_id ";

  int max(String key) {
    return ((Number) configs.findById(key).orElseThrow().value).intValue();
  }

  record ConfigSnapshot(long version, int maxDurationHours) {}

  ConfigSnapshot snapshot(String key) {
    var revision = configRevisions.readLock().orElseThrow();
    return new ConfigSnapshot(revision.version, max(key));
  }

  @Transactional
  public Object create(UUID user, Map<String, Object> b) {
    access.require(user, "reservation:create");
    UUID seat = Store.id(b.get("seatId"));
    UUID room = seats.roomId(seat).orElseThrow(ApiException::notFound);
    resource.guard(user);
    Room r = resource.lock(room);
    var s = seats.findById(seat).orElseThrow(ApiException::notFound);
    if (!access.admissible(user, room)) throw ApiException.notFound();
    if (!r.enabled || !s.enabled) throw new ApiException(422, "RESOURCE_DISABLED", "教室或座位已停用");
    Instant start = DomainPolicy.time(b.get("startAt")),
        end = DomainPolicy.time(b.get("endAt")),
        now = clock.instant();
    var config = snapshot("M1_MAX_DURATION_HOURS");
    int max = config.maxDurationHours();
    DomainPolicy.interval(start, end, now, max);
    resource.opening(room, start, end);
    if (reservations.existsByUserIdAndStatusInAndStartAtLessThanAndEndAtGreaterThan(
        user, List.of("RESERVED", "CHECKED_IN"), end, start))
      throw ApiException.conflict("USER_RESERVATION_CONFLICT", "您已有同一时段的预约");
    resource.ensureAvailable(room, seat, start, end);
    ReservationEntity booking = new ReservationEntity();
    booking.id = UUID.randomUUID();
    booking.userId = user;
    booking.roomId = room;
    booking.seatId = seat;
    booking.startAt = start;
    booking.endAt = end;
    booking.status = "RESERVED";
    booking.configVersion = config.version();
    booking.maxDurationHours = max;
    booking.createdAt = booking.updatedAt = now;
    reservations.saveAndFlush(booking);
    UUID id = booking.id;
    resource.claim(room, seat, start, end, "M1", id, 0, user);
    events.audit(user, "RESERVATION_CREATED", "M1", id, room, null, "RESERVED");
    events.task(
        "M1_PRE_START",
        "M1",
        id,
        start.minusSeconds(900).isBefore(now) ? now : start.minusSeconds(900));
    events.task("M1_LATE", "M1", id, start.plusSeconds(600));
    events.task("M1_NO_SHOW", "M1", id, start.plusSeconds(900));
    events.task("M1_COMPLETE", "M1", id, end);
    return detail(user, id);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> detail(UUID user, UUID id) {
    return present(user, db.need(DETAIL + "where r.id=?1", id));
  }

  Map<String, Object> present(UUID user, Map<String, Object> r) {
    boolean owner = user.equals(Store.id(r.get("userId")));
    if (!owner && !access.roomScope(user, "reservation:read_scope", Store.id(r.get("roomId"))))
      throw ApiException.notFound();
    List<String> actions = new ArrayList<>();
    if (owner && "RESERVED".equals(r.get("status"))) {
      Instant start = DomainPolicy.time(r.get("startAt"));
      if (clock.instant().isBefore(start)) actions.add("cancel");
      if (!clock.instant().isBefore(start) && clock.instant().isBefore(start.plusSeconds(900)))
        actions.add("check-in");
    }
    r.put("allowedActions", actions);
    return r;
  }

  @Transactional(readOnly = true)
  public Object list(UUID u, String status, int page, int size) {
    String where = "where r.user_id=?1";
    List<Object> p = new ArrayList<>(List.of(u));
    if (status != null && !status.isBlank()) {
      p.add(status);
      where += " and r.status=?2";
    }
    var result = db.page(DETAIL + where + " order by r.start_at desc", page, size, p.toArray());
    return new PageData<>(
        result.items().stream().map(r -> present(u, r)).toList(), page, size, result.total());
  }

  ReservationEntity locked(UUID id) {
    UUID room = reservations.roomId(id).orElseThrow(ApiException::notFound);
    resource.lock(room);
    return reservations.lock(id).orElseThrow(ApiException::notFound);
  }

  @Transactional
  public Object cancel(UUID user, UUID id, Map<String, Object> b) {
    var r = locked(id);
    if (!user.equals(r.userId)) throw ApiException.notFound();
    DomainPolicy.version(r.version, b.get("expectedVersion"));
    DomainPolicy.requireState(r.status, "RESERVED");
    if (!clock.instant().isBefore(r.startAt))
      throw new ApiException(422, "CANCELLATION_CLOSED", "预约开始后不能取消");
    r.status = "CANCELLED";
    r.cancelReason = "USER_CANCEL";
    r.endedAt = r.updatedAt = clock.instant();
    reservations.saveAndFlush(r);
    resource.release("M1", id, user, r.roomId);
    events.audit(user, "RESERVATION_CANCELLED", "M1", id, r.roomId, "RESERVED", "CANCELLED");
    return detail(user, id);
  }

  @Transactional
  public Object checkin(UUID user, UUID id, Map<String, Object> b) {
    var r = locked(id);
    if (!user.equals(r.userId)) throw ApiException.notFound();
    DomainPolicy.version(r.version, b.get("expectedVersion"));
    DomainPolicy.requireState(r.status, "RESERVED");
    DomainPolicy.checkIn(r.startAt, clock.instant());
    if (!codes.matches(r.roomId, Objects.toString(b.get("roomCode"), "")))
      throw new ApiException(422, "INVALID_ROOM_CODE", "签到码不正确或已过期");
    r.status = "CHECKED_IN";
    r.checkedInAt = r.updatedAt = clock.instant();
    reservations.saveAndFlush(r);
    events.audit(user, "RESERVATION_CHECKED_IN", "M1", id, r.roomId, "RESERVED", "CHECKED_IN");
    return detail(user, id);
  }

  @Transactional
  public void task(Map<String, Object> t) {
    UUID id = Store.id(t.get("sourceId"));
    if (!reservations.existsById(id)) return;
    var r = locked(id);
    Instant now = clock.instant();
    String type = t.get("taskType").toString();
    if (r.status.equals("RESERVED") && !now.isBefore(r.startAt.plusSeconds(900))) {
      r.status = "NO_SHOW";
      r.endedAt = r.updatedAt = now;
      reservations.saveAndFlush(r);
      resource.release("M1", id, r.userId, r.roomId);
      if (!violations.existsByReservationId(id)) {
        var v = new ViolationEntity();
        v.id = UUID.randomUUID();
        v.reservationId = id;
        v.userId = r.userId;
        v.reason = "NO_SHOW";
        v.occurredAt = now;
        violations.saveAndFlush(v);
      }
      events.notify(
          r.userId,
          r.roomId,
          "NO_SHOW",
          "M1",
          id,
          "0",
          Map.of("message", "未按时签到，预约已取消", "reservationId", id));
      events.systemAudit(
          r.userId, "RESERVATION_NO_SHOW", "M1", id, r.roomId, "RESERVED", "NO_SHOW");
    } else if (type.equals("M1_COMPLETE")
        && r.status.equals("CHECKED_IN")
        && !now.isBefore(r.endAt)) {
      r.status = "COMPLETED";
      r.endedAt = r.endAt;
      r.updatedAt = now;
      reservations.saveAndFlush(r);
      resource.release("M1", id, r.userId, r.roomId);
      events.systemAudit(
          r.userId, "RESERVATION_COMPLETED", "M1", id, r.roomId, "CHECKED_IN", "COMPLETED");
    } else if (r.status.equals("RESERVED")
        && (type.equals("M1_PRE_START") && now.isBefore(r.startAt)
            || type.equals("M1_LATE") && now.isBefore(r.startAt.plusSeconds(900))))
      events.notify(
          r.userId,
          r.roomId,
          type,
          "M1",
          id,
          "0",
          Map.of(
              "message",
              type.equals("M1_LATE") ? "请在开始后15分钟内完成签到" : "您的自习预约即将开始",
              "reservationId",
              id,
              "startAt",
              r.startAt.toString()));
  }
}
