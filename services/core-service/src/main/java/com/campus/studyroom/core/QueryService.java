package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QueryService {
  final Store db;
  final AccessService access;
  final ResourceService resource;
  final BookingService bookings;
  final Clock clock;

  public QueryService(Store d, AccessService a, ResourceService r, BookingService b, Clock c) {
    db = d;
    access = a;
    resource = r;
    bookings = b;
    clock = c;
  }

  public Object availability(UUID u, Map<String, String> q, boolean rooms, int page, int size) {
    access.require(u, rooms ? "room_application:create" : "seat:search");
    Instant start = DomainPolicy.time(q.get("startAt")), end = DomainPolicy.time(q.get("endAt"));
    DomainPolicy.interval(
        start,
        end,
        clock.instant(),
        bookings.max(rooms ? "M2_MAX_DURATION_HOURS" : "M1_MAX_DURATION_HOURS"));
    if (!rooms
        && db.count(
                "select count(*) from core.reservation where user_id=?1 and status"
                    + " in('RESERVED','CHECKED_IN') and start_at<?2 and end_at>?3",
                u,
                end,
                start)
            > 0) return Pages.of(List.of(), page, size);
    String sql =
        rooms
            ? "select r.*,r.name room_name,b.name building_name,c.name campus_name from core.room r"
                  + " join core.building b on b.id=r.building_id join core.campus c on"
                  + " c.id=b.campus_id join core.app_user u on u.id=?1 where r.enabled and"
                  + " b.enabled and c.enabled and (r.department_id is null or"
                  + " r.department_id=u.department_id) and not exists(select 1 from"
                  + " core.resource_claim rc where rc.room_id=r.id and rc.released_at is null and"
                  + " rc.start_at<?2 and (rc.end_at is null or rc.end_at>?3))"
            : "select s.id,s.id seat_id,s.room_id,s.code,s.has_power,r.name room_name,r.code"
                  + " room_code,b.name building_name,c.name campus_name,r.building_id,b.campus_id"
                  + " from core.seat s join core.room r on r.id=s.room_id join core.building b on"
                  + " b.id=r.building_id join core.campus c on c.id=b.campus_id join core.app_user"
                  + " u on u.id=?1 where s.enabled and r.enabled and b.enabled and c.enabled and"
                  + " (r.department_id is null or r.department_id=u.department_id) and not"
                  + " exists(select 1 from core.resource_claim rc where rc.room_id=r.id and"
                  + " (rc.seat_id is null or rc.seat_id=s.id) and rc.released_at is null and"
                  + " rc.start_at<?2 and (rc.end_at is null or rc.end_at>?3))";
    var all = db.list(sql, u, end, start);
    List<Map<String, Object>> result = new ArrayList<>();
    for (var s : all) {
      UUID room = Store.id(s.get(rooms ? "id" : "roomId"));
      if (q.containsKey("roomId") && !q.get("roomId").equals(room.toString())
          || q.containsKey("buildingId") && !q.get("buildingId").equals(s.get("buildingId"))
          || q.containsKey("campusId") && !q.get("campusId").equals(s.get("campusId"))
          || !rooms && "true".equals(q.get("hasPower")) && !Boolean.TRUE.equals(s.get("hasPower")))
        continue;
      try {
        resource.opening(room, start, end);
      } catch (ApiException e) {
        continue;
      }
      s.put("available", true);
      result.add(s);
    }
    return Pages.of(result, page, size);
  }

  public Object roomAvailability(UUID u, UUID room, Map<String, String> q) {
    if (!access.admissible(u, room)) throw ApiException.notFound();
    Instant start = DomainPolicy.time(q.get("startAt")), end = DomainPolicy.time(q.get("endAt"));
    DomainPolicy.interval(start, end, clock.instant(), bookings.max("M1_MAX_DURATION_HOURS"));
    var r = db.need("select * from core.room where id=?1", room);
    boolean opening = true;
    try {
      resource.opening(room, start, end);
    } catch (ApiException e) {
      opening = false;
    }
    boolean userBusy =
        db.count(
                "select count(*) from core.reservation where user_id=?1 and status"
                    + " in('RESERVED','CHECKED_IN') and start_at<?2 and end_at>?3",
                u,
                end,
                start)
            > 0;
    var seats =
        db.list(
            "select id,id seat_id,room_id,code,has_power,enabled,version from core.seat where"
                + " room_id=?1 order by code",
            room);
    for (var s : seats) {
      String reason =
          !Boolean.TRUE.equals(r.get("enabled")) || !Boolean.TRUE.equals(s.get("enabled"))
              ? "DISABLED"
              : !opening
                  ? "OUTSIDE_OPENING_HOURS"
                  : userBusy
                      ? "USER_CONFLICT"
                      : resource.conflict(room, Store.id(s.get("id")), start, end)
                          ? "OCCUPIED"
                          : null;
      s.put("available", reason == null);
      s.put("unavailableReason", reason);
    }
    return Map.of("roomId", room, "seats", seats, "startAt", start, "endAt", end);
  }

  public Object statistics(UUID u, Map<String, String> q) {
    access.require(u, "statistics:read");
    LocalDate from = LocalDate.parse(q.get("fromDate")), to = LocalDate.parse(q.get("toDate"));
    if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 30)
      throw new ApiException(422, "INVALID_DATE_RANGE", "统计范围最多31天");
    Instant start = from.atStartOfDay(DomainPolicy.ZONE).toInstant(),
        end = to.plusDays(1).atStartOfDay(DomainPolicy.ZONE).toInstant();
    String scope =
        "exists(select 1 from core.user_role ur join core.role role on role.id=ur.role_id and"
            + " role.enabled join core.role_permission rp on rp.role_id=role.id join"
            + " core.permission p on p.id=rp.permission_id join core.user_role_scope sc on"
            + " sc.user_role_id=ur.id where ur.user_id=?4 and p.code='statistics:read' and"
            + " (sc.scope_type='ALL' or (sc.scope_type='ROOM' and sc.room_id=r.room_id) or"
            + " (sc.scope_type='DEPARTMENT' and sc.department_id=room.department_id) or"
            + " (sc.scope_type='SHARED' and room.department_id is null)))";
    String sql =
        "select count(*) filter(where r.start_at>=?1 and r.start_at<?2) reservation_count, count(*)"
            + " filter(where r.start_at>=?1 and r.start_at<?2 and r.status='CANCELLED')"
            + " cancelled_count, count(*) filter(where r.start_at>=?1 and r.start_at<?2 and"
            + " r.status='NO_SHOW') no_show_count, count(*) filter(where r.start_at>=?1 and"
            + " r.start_at<?2 and r.start_at+interval '15 minutes'<=?3 and r.status<>'CANCELLED')"
            + " checkin_denominator, count(*) filter(where r.start_at>=?1 and r.start_at<?2 and"
            + " r.start_at+interval '15 minutes'<=?3 and r.status<>'CANCELLED' and r.checked_in_at"
            + " is not null) checkin_numerator, round(coalesce(sum(case when r.status"
            + " in('RESERVED','CHECKED_IN','COMPLETED','INTERRUPTED') then greatest(0,extract(epoch"
            + " from least(case when r.status='INTERRUPTED' then r.ended_at else r.end_at"
            + " end,cast(?2 as timestamptz))-greatest(r.start_at,cast(?1 as timestamptz)))) else 0"
            + " end),0)/3600,2) reserved_seat_hours from core.reservation r join core.room room on"
            + " room.id=r.room_id join core.app_user u on u.id=r.user_id where r.start_at<?2 and"
            + " r.end_at>?1 and "
            + scope;
    List<Object> p = new ArrayList<>(List.of(start, end, clock.instant(), u));
    if (q.containsKey("roomId")) {
      p.add(Store.id(q.get("roomId")));
      sql += " and r.room_id=?" + p.size();
    }
    if (q.containsKey("departmentId")) {
      p.add(Store.id(q.get("departmentId")));
      sql += " and u.department_id=?" + p.size();
    }
    Map<String, Object> out = db.need(sql, p.toArray());
    out.put("fromDate", from);
    out.put("toDate", to);
    out.put("timezone", "Asia/Shanghai");
    long num = Store.number(out, "checkinNumerator"), den = Store.number(out, "checkinDenominator");
    out.put("checkinRate", den == 0 ? null : (double) num / den);
    return out;
  }
}
