package com.campus.studyroom.core;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Events {
  final Store db;
  final Clock clock;

  public Events(Store d, Clock c) {
    db = d;
    clock = c;
  }

  public void audit(
      UUID actor, String action, String type, UUID id, UUID room, Object before, Object after) {
    db.update(
        "insert into"
            + " core.audit_event(id,actor_type,actor_id,action,object_type,object_id,room_id,source_type,source_id,before_json,after_json,request_id,created_at)"
            + " values(?1,'USER',?2,?3,?4,?5,?6,?4,?5,cast(?7 as jsonb),cast(?8 as jsonb),?9,?10)",
        UUID.randomUUID(),
        actor,
        action,
        type,
        id,
        room,
        Store.json(before),
        Store.json(after),
        com.campus.studyroom.api.RequestIds.current(),
        clock.instant());
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
    db.update(
        "insert into"
            + " core.notification(id,recipient_id,room_id,event_type,source_type,source_id,dedup_key,payload_json,due_at,next_attempt_at)"
            + " values(?1,?2,?3,?4,?5,?6,?7,cast(?8 as jsonb),?9,?9) on conflict(dedup_key) do"
            + " nothing",
        UUID.randomUUID(),
        recipient,
        room,
        event,
        source,
        id,
        key,
        Store.json(payload),
        clock.instant());
  }

  public void task(String type, String source, UUID id, Instant due) {
    db.update(
        "insert into"
            + " core.scheduled_task(id,task_type,source_type,source_id,dedup_key,due_at,next_attempt_at)"
            + " values(?1,?2,?3,?4,?5,?6,?6) on conflict(dedup_key) do nothing",
        UUID.randomUUID(),
        type,
        source,
        id,
        source + ":" + id + ":" + type + ":0",
        due);
  }
}
