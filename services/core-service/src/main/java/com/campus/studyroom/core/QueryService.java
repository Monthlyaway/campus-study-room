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
  final AvailabilityReadRepository availability;

  public QueryService(
      Store d,
      AccessService a,
      ResourceService r,
      BookingService b,
      Clock c,
      AvailabilityReadRepository availability) {
    db = d;
    access = a;
    resource = r;
    bookings = b;
    clock = c;
    this.availability = availability;
  }

  public Object availability(UUID u, Map<String, String> q, boolean rooms, int page, int size) {
    access.require(u, rooms ? "room_application:create" : "seat:search");
    Instant start = DomainPolicy.time(q.get("startAt")), end = DomainPolicy.time(q.get("endAt"));
    DomainPolicy.interval(
        start,
        end,
        clock.instant(),
        bookings.max(rooms ? "M2_MAX_DURATION_HOURS" : "M1_MAX_DURATION_HOURS"));
    return availability.available(u, start, end, rooms, q, page, size);
  }

  public Object roomAvailability(UUID u, UUID room, Map<String, String> q) {
    access.require(u, "seat:search");
    if (!access.admissible(u, room)) throw ApiException.notFound();
    Instant start = DomainPolicy.time(q.get("startAt")), end = DomainPolicy.time(q.get("endAt"));
    DomainPolicy.interval(start, end, clock.instant(), bookings.max("M1_MAX_DURATION_HOURS"));
    var seats = availability.grid(u, room, start, end);
    for (var seat : seats) seat.put("available", seat.get("unavailableReason") == null);
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

  public Object audit(UUID user, Map<String, String> query, int page, int size) {
    access.require(user, "audit:read");
    String sql =
        "select e.* from core.audit_event e left join core.room r on r.id=e.room_id where "
            + ReadQueries.scope("audit:read", "?1", "e.room_id", "r.department_id");
    List<Object> parameters = new ArrayList<>(List.of(user));
    for (String key : List.of("objectId", "roomId", "action", "from", "to")) {
      String value = query.get(key);
      if (value == null || value.isBlank()) continue;
      String predicate =
          switch (key) {
            case "objectId" -> "e.object_id=";
            case "roomId" -> "e.room_id=";
            case "action" -> "e.action=";
            case "from" -> "e.created_at>=";
            default -> "e.created_at<";
          };
      parameters.add(
          switch (key) {
            case "objectId", "roomId" -> Store.id(value);
            case "from", "to" -> DomainPolicy.time(value);
            default -> value;
          });
      sql += " and " + predicate + "?" + parameters.size();
    }
    return db.page(sql + " order by e.created_at desc,e.id", page, size, parameters.toArray());
  }

  public Object claims(UUID user, UUID room, Map<String, String> query, int page, int size) {
    if (!access.roomScope(user, "room:manage", room) && !access.roomScope(user, "audit:read", room))
      throw ApiException.forbidden();
    String sql = "select * from core.resource_claim where room_id=?1";
    List<Object> parameters = new ArrayList<>(List.of(room));
    if (query.containsKey("startAt") && !query.get("startAt").isBlank()) {
      parameters.add(DomainPolicy.time(query.get("startAt")));
      sql += " and (end_at is null or end_at>?" + parameters.size() + ")";
    }
    if (query.containsKey("endAt") && !query.get("endAt").isBlank()) {
      parameters.add(DomainPolicy.time(query.get("endAt")));
      sql += " and start_at<?" + parameters.size();
    }
    if (query.containsKey("sourceType") && !query.get("sourceType").isBlank()) {
      String source = query.get("sourceType");
      if (!Set.of("M1", "M2", "M3").contains(source))
        throw new ApiException(400, "INVALID_SOURCE_TYPE", "占用来源不正确");
      parameters.add(source);
      sql += " and source_type=?" + parameters.size();
    }
    return db.page(sql + " order by start_at desc,id", page, size, parameters.toArray());
  }
}
