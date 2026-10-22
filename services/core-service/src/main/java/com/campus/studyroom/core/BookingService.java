package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
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

  public BookingService(
      Store d, AccessService a, ResourceService r, Events e, Clock c, CheckinCodes x) {
    db = d;
    access = a;
    resource = r;
    events = e;
    clock = c;
    codes = x;
  }

  static final String DETAIL =
      "select r.*,s.code seat_code,s.has_power,rm.name room_name,rm.code room_code from"
          + " core.reservation r join core.seat s on s.id=r.seat_id join core.room rm on"
          + " rm.id=r.room_id ";

  int max(String key) {
    return ((Number)
            db.need("select value_json from core.system_config where key=?1", key).get("valueJson"))
        .intValue();
  }

  long configVersion() {
    return db.count("select version from core.config_revision where id=1");
  }

  @Transactional
  public Object create(UUID user, Map<String, Object> b) {
    access.require(user, "reservation:create");
    UUID seat = Store.id(b.get("seatId"));
    var s = db.need("select * from core.seat where id=?1", seat);
    UUID room = Store.id(s.get("roomId"));
    resource.guard(user);
    Room r = resource.lock(room);
    if (!access.admissible(user, room)) throw ApiException.notFound();
    if (!r.enabled || !Boolean.TRUE.equals(s.get("enabled")))
      throw new ApiException(422, "RESOURCE_DISABLED", "教室或座位已停用");
    Instant start = DomainPolicy.time(b.get("startAt")), end = DomainPolicy.time(b.get("endAt"));
    int max = max("M1_MAX_DURATION_HOURS");
    DomainPolicy.interval(start, end, clock.instant(), max);
    resource.opening(room, start, end);
    if (db.count(
            "select count(*) from core.reservation where user_id=?1 and status"
                + " in('RESERVED','CHECKED_IN') and start_at<?2 and end_at>?3",
            user,
            end,
            start)
        > 0) throw ApiException.conflict("USER_RESERVATION_CONFLICT", "您已有同一时段的预约");
    resource.ensureAvailable(room, seat, start, end);
    UUID id = UUID.randomUUID();
    db.update(
        "insert into"
            + " core.reservation(id,user_id,room_id,seat_id,start_at,end_at,status,config_version,max_duration_hours)"
            + " values(?1,?2,?3,?4,?5,?6,'RESERVED',?7,?8)",
        id,
        user,
        room,
        seat,
        start,
        end,
        configVersion(),
        max);
    resource.claim(room, seat, start, end, "M1", id, 0, user);
    events.audit(user, "RESERVATION_CREATED", "M1", id, room, null, "RESERVED");
    events.task(
        "M1_PRE_START",
        "M1",
        id,
        start.minusSeconds(900).isBefore(clock.instant())
            ? clock.instant()
            : start.minusSeconds(900));
    events.task("M1_LATE", "M1", id, start.plusSeconds(600));
    events.task("M1_NO_SHOW", "M1", id, start.plusSeconds(900));
    events.task("M1_COMPLETE", "M1", id, end);
    return detail(user, id);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> detail(UUID user, UUID id) {
    var r = db.need(DETAIL + "where r.id=?1", id);
    return present(user, r);
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

  @Transactional
  public Object cancel(UUID user, UUID id, Map<String, Object> b) {
    var old = db.need("select room_id from core.reservation where id=?1", id);
    UUID room = Store.id(old.get("roomId"));
    resource.lock(room);
    var r = db.need("select * from core.reservation where id=?1 for update", id);
    if (!user.equals(Store.id(r.get("userId")))) throw ApiException.notFound();
    DomainPolicy.version(r, b.get("expectedVersion"));
    DomainPolicy.requireState(r, "RESERVED");
    if (!clock.instant().isBefore(DomainPolicy.time(r.get("startAt"))))
      throw new ApiException(422, "CANCELLATION_CLOSED", "预约开始后不能取消");
    db.update(
        "update core.reservation set"
            + " status='CANCELLED',cancel_reason='USER_CANCEL',ended_at=?1,updated_at=?1,version=version+1"
            + " where id=?2",
        clock.instant(),
        id);
    resource.release("M1", id, user, room);
    events.audit(user, "RESERVATION_CANCELLED", "M1", id, room, "RESERVED", "CANCELLED");
    return detail(user, id);
  }

  @Transactional
  public Object checkin(UUID user, UUID id, Map<String, Object> b) {
    var old = db.need("select room_id from core.reservation where id=?1", id);
    UUID room = Store.id(old.get("roomId"));
    resource.lock(room);
    var r = db.need("select * from core.reservation where id=?1 for update", id);
    if (!user.equals(Store.id(r.get("userId")))) throw ApiException.notFound();
    DomainPolicy.version(r, b.get("expectedVersion"));
    DomainPolicy.requireState(r, "RESERVED");
    DomainPolicy.checkIn(DomainPolicy.time(r.get("startAt")), clock.instant());
    if (!codes.matches(room, Objects.toString(b.get("roomCode"), "")))
      throw new ApiException(422, "INVALID_ROOM_CODE", "签到码不正确或已过期");
    db.update(
        "update core.reservation set"
            + " status='CHECKED_IN',checked_in_at=?1,updated_at=?1,version=version+1 where id=?2",
        clock.instant(),
        id);
    events.audit(user, "RESERVATION_CHECKED_IN", "M1", id, room, "RESERVED", "CHECKED_IN");
    return detail(user, id);
  }

  @Transactional
  public void task(Map<String, Object> t) {
    UUID id = Store.id(t.get("sourceId"));
    var r = db.one("select * from core.reservation where id=?1", id);
    if (r == null) return;
    UUID room = Store.id(r.get("roomId"));
    resource.lock(room);
    r = db.need("select * from core.reservation where id=?1 for update", id);
    Instant start = DomainPolicy.time(r.get("startAt"));
    UUID user = Store.id(r.get("userId"));
    String type = t.get("taskType").toString();
    String state = r.get("status").toString();
    Instant now = clock.instant();
    if (state.equals("RESERVED") && !now.isBefore(start.plusSeconds(900))) {
      db.update(
          "update core.reservation set status='NO_SHOW',ended_at=?1,updated_at=?1,version=version+1"
              + " where id=?2",
          now,
          id);
      resource.release("M1", id, user, room);
      db.update(
          "insert into core.violation(id,reservation_id,user_id,reason,occurred_at)"
              + " values(?1,?2,?3,'NO_SHOW',?4) on conflict(reservation_id) do nothing",
          UUID.randomUUID(),
          id,
          user,
          now);
      events.notify(
          user,
          room,
          "NO_SHOW",
          "M1",
          id,
          "0",
          Map.of("message", "未按时签到，预约已取消", "reservationId", id));
      events.audit(user, "RESERVATION_NO_SHOW", "M1", id, room, "RESERVED", "NO_SHOW");
    } else if (type.equals("M1_COMPLETE")
        && state.equals("CHECKED_IN")
        && !now.isBefore(DomainPolicy.time(r.get("endAt")))) {
      db.update(
          "update core.reservation set"
              + " status='COMPLETED',ended_at=end_at,updated_at=?1,version=version+1 where id=?2",
          now,
          id);
      resource.release("M1", id, user, room);
      events.audit(user, "RESERVATION_COMPLETED", "M1", id, room, state, "COMPLETED");
    } else if (state.equals("RESERVED")
        && (type.equals("M1_PRE_START") && now.isBefore(start)
            || type.equals("M1_LATE") && now.isBefore(start.plusSeconds(900))))
      events.notify(
          user,
          room,
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
              start.toString()));
  }
}
