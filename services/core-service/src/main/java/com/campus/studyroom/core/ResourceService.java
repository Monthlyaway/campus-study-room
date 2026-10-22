package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import com.campus.studyroom.core.persistence.*;
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
  final ClaimRepository claims;
  final SourceStateRepository sources;
  final ProcessedCommandRepository commands;
  final ReservationRepository reservations;
  final ApplicationRepository applications;
  final OpeningRuleRepository openingRules;

  public ResourceService(
      Store d,
      RoomRepository r,
      Clock c,
      Events e,
      ClaimRepository claims,
      SourceStateRepository sources,
      ProcessedCommandRepository commands,
      ReservationRepository reservations,
      ApplicationRepository applications,
      OpeningRuleRepository openingRules) {
    db = d;
    rooms = r;
    clock = c;
    events = e;
    this.claims = claims;
    this.sources = sources;
    this.commands = commands;
    this.reservations = reservations;
    this.applications = applications;
    this.openingRules = openingRules;
  }

  public Room lock(UUID room) {
    return rooms.lock(room).orElseThrow(ApiException::notFound);
  }

  public void guard(UUID user) {
    db.lockGuard(user);
  }

  public boolean conflict(UUID room, UUID seat, Instant start, Instant end) {
    return claims.conflicts(room, seat, start, end);
  }

  public void ensureAvailable(UUID room, UUID seat, Instant start, Instant end) {
    if (conflict(room, seat, start, end))
      throw ApiException.conflict("RESERVATION_CONFLICT", "该时段资源已被占用，请刷新后重选");
  }

  public void opening(UUID room, Instant start, Instant end) {
    var rules = openingRules.findByRoomId(room);
    for (Instant t = start; t.isBefore(end); t = t.plusSeconds(3600)) {
      var z = t.atZone(DomainPolicy.ZONE);
      int minute = z.getHour() * 60;
      boolean open =
          rules.stream()
              .anyMatch(
                  x ->
                      x.weekday == z.getDayOfWeek().getValue()
                          && x.startMinute <= minute
                          && x.endMinute >= minute + 60);
      if (!open) throw new ApiException(422, "OUTSIDE_OPENING_HOURS", "预约时段不在教室开放时间内");
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
    ClaimEntity c = new ClaimEntity();
    c.id = UUID.randomUUID();
    c.roomId = room;
    c.seatId = seat;
    c.startAt = start;
    c.endAt = end;
    c.sourceType = source;
    c.sourceId = id;
    c.sourceVersion = version;
    c.createdBy = actor;
    c.createdRequestId = RequestIds.current();
    claims.saveAndFlush(c);
    events.audit(actor, "RESOURCE_CLAIMED", source, id, room, null, Map.of("claimId", c.id));
    return c.id;
  }

  public void release(String source, UUID id, UUID actor, UUID room) {
    claims
        .findBySourceTypeAndSourceId(source, id)
        .filter(c -> c.releasedAt == null)
        .ifPresent(
            c -> {
              c.releasedAt = clock.instant();
              c.releaseRequestId = RequestIds.current();
              claims.saveAndFlush(c);
              events.audit(
                  actor, "RESOURCE_RELEASED", source, id, room, null, Map.of("released", true));
            });
  }

  @Transactional
  public Object command(Map<String, Object> b) {
    UUID command = Store.id(b.get("commandId")),
        source = Store.id(b.get("sourceId")),
        room = Store.id(b.get("roomId")),
        actor = Store.id(b.get("actorId"));
    String op = Objects.toString(b.get("operation"));
    long version = ((Number) b.get("sourceVersion")).longValue();
    if (!"M3".equals(b.get("sourceType"))
        || !(op.equals("BLOCK") && version == 1 || op.equals("RELEASE") && version == 2))
      throw new ApiException(422, "INVALID_COMMAND", "维修命令不合法");
    DomainPolicy.time(b.get("occurredAt"));
    db.advisory("command:" + command);
    String hash = Idempotency.hash(b);
    var old = commands.findById(command);
    if (old.isPresent()) {
      if (!hash.equals(old.get().payloadHash))
        throw ApiException.conflict("COMMAND_PAYLOAD_MISMATCH", "命令内容不一致");
      return old.get().resultJson;
    }
    lock(room);
    db.advisory("source:M3:" + source);
    var existingClaim = claims.findBySourceTypeAndSourceId("M3", source);
    if (existingClaim.isPresent() && !room.equals(existingClaim.get().roomId))
      throw ApiException.conflict("SOURCE_ROOM_MISMATCH", "来源工单不能更换教室");
    var state = sources.findById(new SourceStateEntity.Key("M3", source)).orElse(null);
    String target = op.equals("BLOCK") ? "ACTIVE" : "RELEASED", result = "APPLIED";
    UUID claimId = null;
    if (state != null && version < state.maxVersion) {
      target = state.state;
      version = state.maxVersion;
      result = "STALE_IGNORED";
    } else if (state != null && version == state.maxVersion) {
      if (!target.equals(state.state))
        throw ApiException.conflict("SOURCE_VERSION_CONFLICT", "来源版本冲突");
      result = "ALREADY_APPLIED";
    } else {
      if (op.equals("BLOCK")) {
        Instant now = clock.instant();
        claimId = claim(room, null, now, null, "M3", source, version, actor);
        for (var r : reservations.maintenanceAffected(room, now)) {
          String before = r.status;
          r.status = r.status.equals("CHECKED_IN") ? "INTERRUPTED" : "CANCELLED";
          r.cancelReason = "MAINTENANCE";
          r.endedAt = now;
          r.updatedAt = now;
          reservations.saveAndFlush(r);
          release("M1", r.id, actor, room);
          events.serviceAudit(actor, "MAINTENANCE_CANCEL", "M1", r.id, room, before, r.status);
          events.notify(
              r.userId,
              room,
              "MAINTENANCE",
              "M1",
              r.id,
              "0",
              Map.of("message", "教室维修影响了您的预约", "reservationId", r.id));
        }
        for (var a : applications.maintenanceAffected(room, now)) {
          a.status = "CANCELLED";
          a.cancelReason = "MAINTENANCE";
          a.updatedAt = now;
          applications.saveAndFlush(a);
          release("M2", a.id, actor, room);
          events.serviceAudit(
              actor, "MAINTENANCE_CANCEL", "M2", a.id, room, "APPROVED", "CANCELLED");
          events.notify(
              a.applicantId, room, "MAINTENANCE", "M2", a.id, "0", Map.of("message", "教室申请因维修取消"));
        }
      } else release("M3", source, actor, room);
      if (state == null) {
        state = new SourceStateEntity();
        state.sourceType = "M3";
        state.sourceId = source;
      }
      state.maxVersion = version;
      state.state = target;
      state.lastCommandId = command;
      sources.saveAndFlush(state);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("result", result);
    response.put("sourceVersion", version);
    response.put("sourceState", target);
    if (claimId != null) response.put("claimId", claimId);
    ProcessedCommandEntity done = new ProcessedCommandEntity();
    done.commandId = command;
    done.payloadHash = hash;
    done.httpStatus = 200;
    done.resultJson = response;
    done.processedAt = clock.instant();
    commands.saveAndFlush(done);
    return response;
  }
}
