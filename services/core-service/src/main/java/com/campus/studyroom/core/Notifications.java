package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import com.campus.studyroom.core.persistence.*;
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
  final NotificationRepository records;
  final ProcessedCommandRepository commands;

  public Notifications(
      Store d,
      AccessService a,
      Events e,
      Clock c,
      NotificationRepository records,
      ProcessedCommandRepository commands) {
    db = d;
    access = a;
    events = e;
    clock = c;
    this.records = records;
    this.commands = commands;
  }

  @Transactional(readOnly = true)
  public Object list(UUID u, boolean management, int page, int size) {
    return list(u, management, Map.of(), page, size);
  }

  @Transactional(readOnly = true)
  public Object list(UUID u, boolean management, Map<String, String> filters, int page, int size) {
    if (management) access.require(u, "notification:manage");
    List<Object> args = new ArrayList<>();
    args.add(u);
    String sql =
        "select n.* from core.notification n left join core.room r on r.id=n.room_id where ";
    sql +=
        management
            ? "((n.room_id is null and "
                + allScope("?1")
                + ") or (n.room_id is not null and "
                + ReadQueries.scope("notification:manage", "?1", "n.room_id", "r.department_id")
                + "))"
            : "n.recipient_id=?1";
    for (String filter : List.of("status", "sourceType", "roomId")) {
      String value = filters.get(filter);
      if (value == null || value.isBlank()) continue;
      if (filter.equals("status")
          && !Set.of("PENDING", "LEASED", "RETRYING", "SENT").contains(value))
        throw new ApiException(400, "INVALID_STATUS", "通知状态无效");
      Object arg = value;
      if (filter.equals("roomId")) {
        try {
          arg = UUID.fromString(value);
        } catch (IllegalArgumentException e) {
          throw new ApiException(400, "INVALID_ROOM", "房间编号无效");
        }
      }
      args.add(arg);
      sql +=
          " and n."
              + Map.of("status", "status", "sourceType", "source_type", "roomId", "room_id")
                  .get(filter)
              + "=?"
              + args.size();
    }
    return db.page(sql + " order by n.created_at desc,n.id", page, size, args.toArray());
  }

  static String allScope(String actor) {
    return "exists(select 1 from core.user_role ur join core.role role on role.id=ur.role_id and"
               + " role.enabled join core.role_permission rp on rp.role_id=role.id join"
               + " core.permission p on p.id=rp.permission_id join core.user_role_scope sc on"
               + " sc.user_role_id=ur.id where ur.user_id="
        + actor
        + " and p.code='notification:manage' and sc.scope_type='ALL')";
  }

  boolean canManage(UUID u, NotificationEntity n) {
    return n.roomId == null
        ? db.count("select count(*) where " + allScope("?1"), u) > 0
        : access.roomScope(u, "notification:manage", n.roomId);
  }

  Map<String, Object> result(NotificationEntity n) {
    records.flush();
    return db.need("select * from core.notification where id=?1", n.id);
  }

  @Transactional
  public Object read(UUID u, UUID id, Map<String, Object> b) {
    var n = records.lock(id).orElseThrow(ApiException::notFound);
    if (!u.equals(n.recipientId)) throw ApiException.notFound();
    DomainPolicy.version(n.version, b.get("expectedVersion"));
    if (n.readAt == null) n.readAt = clock.instant();
    return result(n);
  }

  @Transactional
  public Object retry(UUID u, UUID id, Map<String, Object> b) {
    var n = records.lock(id).orElseThrow(ApiException::notFound);
    if (!canManage(u, n)) throw ApiException.forbidden();
    DomainPolicy.version(n.version, b.get("expectedVersion"));
    if (!"SENT".equals(n.status)) n.nextAttemptAt = clock.instant();
    events.audit(u, "NOTIFICATION_RETRY", "notification", id, n.roomId, null, Map.of());
    return result(n);
  }

  @Transactional
  public Object internal(Map<String, Object> b) {
    UUID command = Store.id(b.get("commandId"));
    db.advisory("command:" + command);
    String hash = Idempotency.hash(b);
    var old = commands.findById(command).orElse(null);
    if (old != null) {
      if (!hash.equals(old.payloadHash))
        throw ApiException.conflict("COMMAND_PAYLOAD_MISMATCH", "命令内容不一致");
      return old.resultJson;
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
    for (UUID direct : List.copyOf(recipients))
      if (db.count("select count(*) from core.app_user where id=?1 and enabled", direct) == 0
          && (direct.equals(reporter) || direct.equals(assigned)))
        throw new ApiException(422, "NO_RECIPIENTS", "必须的收件账号不可用");
    if (Set.of("ASSIGNED", "WORK_OVERDUE").contains(event) && assigned == null)
      throw new ApiException(422, "NO_RECIPIENTS", "必须的收件账号不可用");
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
    var saved = new ProcessedCommandEntity();
    saved.commandId = command;
    saved.payloadHash = hash;
    saved.httpStatus = 200;
    saved.resultJson = new LinkedHashMap<>(result);
    saved.processedAt = clock.instant();
    commands.save(saved);
    return result;
  }

  @Transactional
  public List<Map<String, Object>> claim() {
    var due = records.due(clock.instant());
    if (due.isEmpty()) return List.of();
    var users =
        db.list(
            "select id,email from core.app_user where id in (?1)",
            due.stream().map(n -> n.recipientId).distinct().toList());
    Map<UUID, String> emails = new HashMap<>();
    for (var user : users)
      emails.put(Store.id(user.get("id")), Objects.toString(user.get("email"), ""));
    List<Map<String, Object>> result = new ArrayList<>();
    for (var n : due) {
      n.status = "LEASED";
      n.leaseOwner = UUID.randomUUID().toString();
      n.leaseUntil = clock.instant().plusSeconds(120);
      var row = new LinkedHashMap<String, Object>();
      row.put("id", n.id);
      row.put("leaseOwner", n.leaseOwner);
      row.put("email", emails.getOrDefault(n.recipientId, ""));
      row.put("eventType", n.eventType);
      row.put("payloadJson", Store.JSON.readValue(n.payloadJson, Object.class));
      result.add(row);
    }
    return result;
  }

  @Transactional
  public void delivered(UUID id, String leaseOwner, boolean success) {
    var n = records.lock(id).orElseThrow(ApiException::notFound);
    // A worker whose lease expired cannot acknowledge a replacement worker's delivery.
    if (!"LEASED".equals(n.status) || !Objects.equals(n.leaseOwner, leaseOwner)) return;
    n.attempts++;
    n.status = success ? "SENT" : "RETRYING";
    n.nextAttemptAt =
        clock.instant().plusSeconds(n.attempts >= 8 ? 300 : Math.min(1L << n.attempts, 300));
    n.sentAt = success ? clock.instant() : null;
    n.lastErrorCode = success ? null : "SMTP_DELIVERY_FAILED";
    n.leaseOwner = null;
    n.leaseUntil = null;
    if (!success && n.attempts >= 8)
      org.slf4j.LoggerFactory.getLogger(Notifications.class)
          .error("Mail delivery needs attention notification={} attempts={}", id, n.attempts);
  }
}
