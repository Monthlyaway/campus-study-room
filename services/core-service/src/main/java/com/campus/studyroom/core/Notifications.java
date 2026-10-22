package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Notifications {
  final Store db;
  final AccessService access;
  final Events events;
  final Clock clock;

  public Notifications(Store d, AccessService a, Events e, Clock c) {
    db = d;
    access = a;
    events = e;
    clock = c;
  }

  @Transactional(readOnly = true)
  public Object list(UUID u, boolean management, int page, int size) {
    if (management) access.require(u, "notification:manage");
    var rows =
        db.list(
            management
                ? "select * from core.notification order by created_at desc"
                : "select * from core.notification where recipient_id=?1 order by created_at desc",
            management ? new Object[] {} : new Object[] {u});
    if (management) rows = rows.stream().filter(n -> canManage(u, n)).toList();
    return Pages.of(rows, page, size);
  }

  boolean canManage(UUID u, Map<String, Object> n) {
    return n.get("roomId") == null
        ? access.scope(u, "notification:manage", null, null)
        : access.roomScope(u, "notification:manage", Store.id(n.get("roomId")));
  }

  @Transactional
  public Object read(UUID u, UUID id, Map<String, Object> b) {
    var n = db.need("select * from core.notification where id=?1 for update", id);
    if (!u.equals(Store.id(n.get("recipientId")))) throw ApiException.notFound();
    DomainPolicy.version(n, b.get("expectedVersion"));
    if (n.get("readAt") == null)
      db.update(
          "update core.notification set read_at=?1,version=version+1 where id=?2",
          clock.instant(),
          id);
    return db.need("select * from core.notification where id=?1", id);
  }

  @Transactional
  public Object retry(UUID u, UUID id, Map<String, Object> b) {
    var n = db.need("select * from core.notification where id=?1 for update", id);
    if (!canManage(u, n)) throw ApiException.forbidden();
    DomainPolicy.version(n, b.get("expectedVersion"));
    if (!"SENT".equals(n.get("status")))
      db.update(
          "update core.notification set next_attempt_at=?1,version=version+1 where id=?2",
          clock.instant(),
          id);
    events.audit(
        u,
        "NOTIFICATION_RETRY",
        "notification",
        id,
        n.get("roomId") == null ? null : Store.id(n.get("roomId")),
        null,
        Map.of());
    return db.need("select * from core.notification where id=?1", id);
  }

  @Transactional
  public Object internal(Map<String, Object> b) {
    UUID command = Store.id(b.get("commandId"));
    db.advisory("command:" + command);
    String hash = Idempotency.hash(b);
    var old = db.one("select * from core.processed_command where command_id=?1", command);
    if (old != null) {
      if (!hash.equals(old.get("payloadHash")))
        throw ApiException.conflict("COMMAND_PAYLOAD_MISMATCH", "命令内容不一致");
      return old.get("resultJson");
    }
    String event = Objects.toString(b.get("eventType"));
    if (!Set.of(
            "ASSIGNED",
            "AWAITING_ACCEPTANCE",
            "ASSIGN_OVERDUE",
            "WORK_OVERDUE",
            "ACCEPT_OVERDUE",
            "CLOSED",
            "CANCELLED")
        .contains(event)) throw new ApiException(422, "INVALID_EVENT", "事件类型不合法");
    UUID room = Store.id(b.get("roomId")),
        order = Store.id(b.get("orderId")),
        reporter = Store.id(b.get("reporterId"));
    UUID assigned = b.get("assignedTo") == null ? null : Store.id(b.get("assignedTo"));
    Set<UUID> recipients = new HashSet<>();
    if (Set.of("AWAITING_ACCEPTANCE", "ACCEPT_OVERDUE", "CLOSED", "CANCELLED").contains(event))
      recipients.add(reporter);
    if (Set.of("ASSIGNED", "WORK_OVERDUE").contains(event) && assigned != null)
      recipients.add(assigned);
    if (event.endsWith("OVERDUE")) {
      for (var u :
          db.list(
              "select distinct u.id from core.app_user u join core.user_role ur on ur.user_id=u.id"
                  + " join core.role r on r.id=ur.role_id where u.enabled and r.enabled and"
                  + " (r.code='PROPERTY_ADMIN' or (r.code='PROPERTY_DISPATCHER' and"
                  + " ?1='ASSIGN_OVERDUE'))",
              event)) {
        UUID uid = Store.id(u.get("id"));
        if (access.roomScope(uid, "repair:read_scope", room)) recipients.add(uid);
      }
    }
    recipients.removeIf(
        u -> db.count("select count(*) from core.app_user where id=?1 and enabled", u) == 0);
    if (recipients.isEmpty()) throw new ApiException(422, "NO_RECIPIENTS", "没有可接收此通知的有效账号");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("orderId", order);
    payload.put("eventType", event);
    if (b.get("payload") instanceof Map<?, ?> p)
      for (String k :
          List.of("title", "status", "dueAt", "assignDueAt", "workDueAt", "acceptDueAt"))
        if (p.containsKey(k)) payload.put(k, p.get(k));
    for (UUID recipient : recipients)
      events.notify(
          recipient, room, event, "M3", order, Objects.toString(b.get("eventCycle"), "0"), payload);
    var result = Map.of("result", "APPLIED", "recipientCount", recipients.size());
    db.update(
        "insert into core.processed_command(command_id,payload_hash,http_status,result_json)"
            + " values(?1,?2,200,cast(?3 as jsonb))",
        command,
        hash,
        Store.json(result));
    return result;
  }

  @Transactional
  public List<Map<String, Object>> claim() {
    var due =
        db.list(
            "select n.*,u.email from core.notification n join core.app_user u on"
                + " u.id=n.recipient_id where ((n.status in('PENDING','RETRYING') and"
                + " n.next_attempt_at<=?1) or (n.status='LEASED' and n.lease_until<=?1)) order by"
                + " n.next_attempt_at limit 50 for update of n skip locked",
            clock.instant());
    for (var n : due)
      db.update(
          "update core.notification set"
              + " status='LEASED',lease_owner='core',lease_until=?1,version=version+1 where id=?2",
          clock.instant().plusSeconds(120),
          Store.id(n.get("id")));
    return due;
  }

  @Transactional
  public void delivered(UUID id, boolean success) {
    var n = db.need("select * from core.notification where id=?1 for update", id);
    int attempts = ((Number) n.get("attempts")).intValue() + 1;
    db.update(
        "update core.notification set"
            + " status=?1,attempts=?2,next_attempt_at=?3,lease_owner=null,lease_until=null,sent_at=?4,last_error_code=?5,version=version+1"
            + " where id=?6",
        success ? "SENT" : "RETRYING",
        attempts,
        clock.instant().plusSeconds(attempts >= 8 ? 300 : Math.min(1L << attempts, 300)),
        success ? clock.instant() : null,
        success ? null : "SMTP_DELIVERY_FAILED",
        id);
  }
}
