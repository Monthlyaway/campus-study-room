package com.campus.studyroom.core;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskExecutor {
  final Store db;
  final BookingService bookings;
  final ApplicationService applications;
  final Clock clock;

  public TaskExecutor(Store d, BookingService b, ApplicationService a, Clock c) {
    db = d;
    bookings = b;
    applications = a;
    clock = c;
  }

  @Transactional
  public List<UUID> due() {
    return db
        .list(
            "select id from core.scheduled_task where status<>'DONE' and next_attempt_at<=?1 order"
                + " by case when task_type='M1_NO_SHOW' then 0 else 1 end,due_at limit 200",
            clock.instant())
        .stream()
        .map(t -> Store.id(t.get("id")))
        .toList();
  }

  @Transactional
  public void run(UUID id) {
    var t =
        db.one(
            "select * from core.scheduled_task where id=?1 and status<>'DONE' for update skip"
                + " locked",
            id);
    if (t == null) return;
    if (t.get("sourceType").equals("M1")) bookings.task(t);
    else applications.task(t);
    db.update(
        "update core.scheduled_task set status='DONE',lease_owner=null,lease_until=null where"
            + " id=?1",
        id);
  }
}
