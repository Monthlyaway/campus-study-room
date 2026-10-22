package com.campus.studyroom.core;

import com.campus.studyroom.api.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceService {
  final Store db;
  final RoomRepository rooms;
  final Clock clock;
  final Events events;

  public ResourceService(Store d, RoomRepository r, Clock c, Events e) {
    db = d;
    rooms = r;
    clock = c;
    events = e;
  }

  public Room lock(UUID room) {
    return rooms.lock(room).orElseThrow(ApiException::notFound);
  }

  public void guard(UUID user) {
    db.lockGuard(user);
  }

  public boolean conflict(UUID room, UUID seat, Instant start, Instant end) {
    return db.count(
            "select count(*) from core.resource_claim where room_id=?1 and released_at is null and"
                + " start_at<?2 and (end_at is null or end_at>?3) and (cast(?4 as uuid) is null or"
                + " seat_id is null or seat_id=?4)",
            room,
            end,
            start,
            seat)
        > 0;
  }

  public void ensureAvailable(UUID room, UUID seat, Instant start, Instant end) {
    if (conflict(room, seat, start, end))
      throw ApiException.conflict("RESERVATION_CONFLICT", "该时段资源已被占用，请刷新后重选");
  }

  public void opening(UUID room, Instant start, Instant end) {
    for (Instant t = start; t.isBefore(end); t = t.plusSeconds(3600)) {
      var z = t.atZone(DomainPolicy.ZONE);
      int minute = z.getHour() * 60;
      if (db.count(
              "select count(*) from core.opening_rule where room_id=?1 and weekday=?2 and"
                  + " start_minute<=?3 and end_minute>=?4",
              room,
              z.getDayOfWeek().getValue(),
              minute,
              minute + 60)
          == 0) throw new ApiException(422, "OUTSIDE_OPENING_HOURS", "预约时段不在教室开放时间内");
    }
  }

  public UUID claim(
      UUID room,
      UUID seat,
      Instant start,
      Instant end,
      String source,
      UUID id,
      long version,
      UUID actor) {
    UUID claim = UUID.randomUUID();
    db.update(
        "insert into"
            + " core.resource_claim(id,room_id,seat_id,start_at,end_at,source_type,source_id,source_version,created_by,created_request_id)"
            + " values(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
        claim,
        room,
        seat,
        start,
        end,
        source,
        id,
        version,
        actor,
        com.campus.studyroom.api.RequestIds.current());
    events.audit(actor, "RESOURCE_CLAIMED", source, id, room, null, Map.of("claimId", claim));
    return claim;
  }

  public void release(String source, UUID id, UUID actor, UUID room) {
    int n =
        db.update(
            "update core.resource_claim set released_at=?1,release_request_id=?2 where"
                + " source_type=?3 and source_id=?4 and released_at is null",
            clock.instant(),
            com.campus.studyroom.api.RequestIds.current(),
            source,
            id);
    if (n > 0)
      events.audit(actor, "RESOURCE_RELEASED", source, id, room, null, Map.of("released", true));
  }

  @Transactional
  public Object command(Map<String, Object> b) {
    UUID command = Store.id(b.get("commandId")),
        source = Store.id(b.get("sourceId")),
        room = Store.id(b.get("roomId")),
        actor = Store.id(b.get("actorId"));
    String op = Objects.toString(b.get("operation"));
    long ver = ((Number) b.get("sourceVersion")).longValue();
    if (!"M3".equals(b.get("sourceType"))
        || !(op.equals("BLOCK") && ver == 1 || op.equals("RELEASE") && ver == 2))
      throw new ApiException(422, "INVALID_COMMAND", "维修命令不合法");
    DomainPolicy.time(b.get("occurredAt"));
    db.advisory("command:" + command);
    String hash = Idempotency.hash(b);
    var old = db.one("select * from core.processed_command where command_id=?1", command);
    if (old != null) {
      if (!hash.equals(old.get("payloadHash")))
        throw ApiException.conflict("COMMAND_PAYLOAD_MISMATCH", "命令内容不一致");
      return old.get("resultJson");
    }
    lock(room);
    db.advisory("source:M3:" + source);
    var existingClaim =
        db.one(
            "select room_id from core.resource_claim where source_type='M3' and source_id=?1",
            source);
    if (existingClaim != null && !room.equals(Store.id(existingClaim.get("roomId"))))
      throw ApiException.conflict("SOURCE_ROOM_MISMATCH", "来源工单不能更换教室");
    var state =
        db.one(
            "select * from core.resource_source_state where source_type='M3' and source_id=?1",
            source);
    String target = op.equals("BLOCK") ? "ACTIVE" : "RELEASED";
    String result = "APPLIED";
    UUID claimId = null;
    if (state != null && ver < Store.number(state, "maxVersion")) {
      target = state.get("state").toString();
      ver = Store.number(state, "maxVersion");
      result = "STALE_IGNORED";
    } else if (state != null && ver == Store.number(state, "maxVersion")) {
      if (!target.equals(state.get("state")))
        throw ApiException.conflict("SOURCE_VERSION_CONFLICT", "来源版本冲突");
      result = "ALREADY_APPLIED";
    } else {
      if (op.equals("BLOCK")) {
        Instant now = clock.instant();
        claimId = claim(room, null, now, null, "M3", source, ver, actor);
        for (var r :
            db.list(
                "select * from core.reservation where room_id=?1 and end_at>?2 and status"
                    + " in('RESERVED','CHECKED_IN') order by id for update",
                room,
                now)) {
          UUID rid = Store.id(r.get("id"));
          String status = "CHECKED_IN".equals(r.get("status")) ? "INTERRUPTED" : "CANCELLED";
          db.update(
              "update core.reservation set"
                  + " status=?1,cancel_reason='MAINTENANCE',ended_at=?2,version=version+1,updated_at=?2"
                  + " where id=?3",
              status,
              now,
              rid);
          release("M1", rid, actor, room);
          events.audit(actor, "MAINTENANCE_CANCEL", "M1", rid, room, r.get("status"), status);
          events.notify(
              Store.id(r.get("userId")),
              room,
              "MAINTENANCE",
              "M1",
              rid,
              "0",
              Map.of("message", "教室维修影响了您的预约", "reservationId", rid));
        }
        for (var a :
            db.list(
                "select * from core.room_application where room_id=?1 and end_at>?2 and"
                    + " status='APPROVED' order by id for update",
                room,
                now)) {
          UUID aid = Store.id(a.get("id"));
          db.update(
              "update core.room_application set"
                  + " status='CANCELLED',cancel_reason='MAINTENANCE',version=version+1,updated_at=?1"
                  + " where id=?2",
              now,
              aid);
          release("M2", aid, actor, room);
          events.audit(actor, "MAINTENANCE_CANCEL", "M2", aid, room, "APPROVED", "CANCELLED");
          events.notify(
              Store.id(a.get("applicantId")),
              room,
              "MAINTENANCE",
              "M2",
              aid,
              "0",
              Map.of("message", "教室申请因维修取消"));
        }
      } else release("M3", source, actor, room);
      db.update(
          "insert into"
              + " core.resource_source_state(source_type,source_id,max_version,state,last_command_id)"
              + " values('M3',?1,?2,?3,?4) on conflict(source_type,source_id) do update set"
              + " max_version=excluded.max_version,state=excluded.state,last_command_id=excluded.last_command_id",
          source,
          ver,
          target,
          command);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("result", result);
    response.put("sourceVersion", ver);
    response.put("sourceState", target);
    if (claimId != null) response.put("claimId", claimId);
    db.update(
        "insert into core.processed_command(command_id,payload_hash,http_status,result_json)"
            + " values(?1,?2,200,cast(?3 as jsonb))",
        command,
        hash,
        Store.json(response));
    return response;
  }
}
