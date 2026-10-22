package com.campus.studyroom.core;

import com.campus.studyroom.api.PageData;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;

/** Database-side projections: visibility, full-period opening, conflicts and pagination in SQL. */
@Repository
public class AvailabilityReadRepository {
  final Store db;

  public AvailabilityReadRepository(Store db) {
    this.db = db;
  }

  static final String OPEN =
      "not exists(select 1 from generate_series(cast(?2 as timestamptz),cast(?3 as"
          + " timestamptz)-interval '1 hour',interval '1 hour') requested_hour where not"
          + " exists(select 1 from core.opening_rule opening where opening.room_id=r.id and"
          + " opening.weekday=extract(isodow from requested_hour at time zone 'Asia/Shanghai') and"
          + " opening.start_minute<=extract(hour from requested_hour at time zone"
          + " 'Asia/Shanghai')*60 and opening.end_minute>=(extract(hour from requested_hour at time"
          + " zone 'Asia/Shanghai')+1)*60))";
  static final String USER_BUSY =
      "exists(select 1 from core.reservation own where own.user_id=?1 and own.status"
          + " in('RESERVED','CHECKED_IN') and own.start_at<?3 and own.end_at>?2)";

  public PageData<Map<String, Object>> available(
      UUID user,
      Instant start,
      Instant end,
      boolean rooms,
      Map<String, String> filters,
      int page,
      int size) {
    String fields =
        rooms
            ? "r.*,r.name room_name,b.name building_name,c.name campus_name,b.campus_id,(select"
                  + " count(*) from core.seat rs where rs.room_id=r.id and rs.enabled)"
                  + " seat_count,true available"
            : "s.id,s.id seat_id,s.room_id,s.code,s.has_power,r.name room_name,r.code"
                  + " room_code,b.name building_name,c.name"
                  + " campus_name,r.building_id,b.campus_id,true available";
    String sql =
        "select "
            + fields
            + " from core.room r "
            + (rooms ? "" : "join core.seat s on s.room_id=r.id and s.enabled ")
            + "join core.building b on b.id=r.building_id and b.enabled join core.campus c on"
            + " c.id=b.campus_id and c.enabled join core.app_user u on u.id=?1 where r.enabled and"
            + " (r.department_id is null or r.department_id=u.department_id) and "
            + OPEN
            + " and not exists(select 1 from core.resource_claim claim where claim.room_id=r.id "
            + (rooms ? "" : "and (claim.seat_id is null or claim.seat_id=s.id) ")
            + "and claim.released_at is null and claim.start_at<?3 and (claim.end_at is null or"
            + " claim.end_at>?2))";
    if (!rooms) sql += " and not " + USER_BUSY;
    List<Object> params = new ArrayList<>(List.of(user, start, end));
    for (String key : List.of("roomId", "buildingId", "campusId")) {
      if (filters.containsKey(key) && !filters.get(key).isBlank()) {
        params.add(Store.id(filters.get(key)));
        sql +=
            " and "
                + switch (key) {
                  case "roomId" -> "r.id";
                  case "buildingId" -> "b.id";
                  default -> "c.id";
                }
                + "=?"
                + params.size();
      }
    }
    if (!rooms && filters.containsKey("hasPower")) {
      params.add(Boolean.valueOf(filters.get("hasPower")));
      sql += " and s.has_power=?" + params.size();
    }
    sql += " order by r.code" + (rooms ? "" : ",s.code");
    return db.page(sql, page, size, params.toArray());
  }

  public List<Map<String, Object>> grid(UUID user, UUID room, Instant start, Instant end) {
    String conflict =
        "exists(select 1 from core.resource_claim claim where claim.room_id=r.id and (claim.seat_id"
            + " is null or claim.seat_id=s.id) and claim.released_at is null and claim.start_at<?3"
            + " and (claim.end_at is null or claim.end_at>?2))";
    return db.list(
        "select s.id,s.id seat_id,s.room_id,s.code,s.has_power,s.enabled,s.version,case when"
            + " not(r.enabled and s.enabled and b.enabled and c.enabled) then 'DISABLED' when not("
            + OPEN
            + ") then 'OUTSIDE_OPENING_HOURS' when "
            + USER_BUSY
            + " then 'USER_CONFLICT' when "
            + conflict
            + " then 'OCCUPIED' else null end unavailable_reason from core.seat s join core.room r"
            + " on r.id=s.room_id join core.building b on b.id=r.building_id join core.campus c on"
            + " c.id=b.campus_id where r.id=?4 order by s.code",
        user,
        start,
        end,
        room);
  }
}
