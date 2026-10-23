package com.campus.studyroom.core;

import com.campus.studyroom.core.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Events {
  final Store db;
  final Clock clock;
  final AuditEventRepository audits;
  final NotificationRepository notifications;
  final ScheduledTaskRepository tasks;

  public Events(
      Store db,
      Clock clock,
      AuditEventRepository audits,
      NotificationRepository notifications,
      ScheduledTaskRepository tasks) {
    this.db = db;
    this.clock = clock;
    this.audits = audits;
    this.notifications = notifications;
    this.tasks = tasks;
  }

  public void audit(
      UUID actor, String action, String type, UUID id, UUID room, Object before, Object after) {
    saveAudit("USER", actor, action, type, id, room, before, after);
  }

  public void systemAudit(
      UUID actor, String action, String type, UUID id, UUID room, Object before, Object after) {
    saveAudit("JOB", actor, action, type, id, room, before, after);
  }

  public void serviceAudit(
      UUID actor, String action, String type, UUID id, UUID room, Object before, Object after) {
    saveAudit("SERVICE", actor, action, type, id, room, before, after);
  }

  void saveAudit(
      String actorType,
      UUID actor,
      String action,
      String type,
      UUID id,
      UUID room,
      Object before,
      Object after) {
    var a = new AuditEventEntity();
    a.id = UUID.randomUUID();
    a.actorType = actorType;
    a.actorId = actor;
    a.action = action;
    a.objectType = type;
    a.objectId = id;
    a.roomId = room;
    a.sourceType = type;
    a.sourceId = id;
    a.beforeJson = before == null ? null : Store.json(before);
    a.afterJson = after == null ? null : Store.json(after);
    a.requestId = com.campus.studyroom.api.RequestIds.current();
    a.createdAt = clock.instant();
    audits.save(a);
  }

  public void notify(
      UUID recipient,
      UUID room,
      String event,
      String source,
      UUID id,
      String cycle,
      Map<String, Object> payload) {
    String key = source + ":" + id + ":" + event + ":" + cycle + ":" + recipient + ":EMAIL";
    db.advisory("notification:" + key);
    if (notifications.existsByDedupKey(key)) return;
    var n = new NotificationEntity();
    n.id = UUID.randomUUID();
    n.recipientId = recipient;
    n.roomId = room;
    n.eventType = event;
    n.sourceType = source;
    n.sourceId = id;
    n.dedupKey = key;
    n.payloadJson = Store.json(payload);
    n.channel = "EMAIL";
    n.status = "PENDING";
    n.dueAt = clock.instant();
    n.nextAttemptAt = n.dueAt;
    n.createdAt = n.dueAt;
    notifications.save(n);
  }

  public void task(String type, String source, UUID id, Instant due) {
    String key = source + ":" + id + ":" + type + ":0";
    db.advisory("task:" + key);
    if (tasks.existsByDedupKey(key)) return;
    var t = new ScheduledTaskEntity();
    t.id = UUID.randomUUID();
    t.taskType = type;
    t.sourceType = source;
    t.sourceId = id;
    t.dedupKey = key;
    t.dueAt = due;
    t.nextAttemptAt = due;
    t.status = "PENDING";
    tasks.save(t);
  }
}
