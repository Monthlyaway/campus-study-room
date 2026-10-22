package com.campus.studyroom.core;

import com.campus.studyroom.core.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskExecutor {
  final ScheduledTaskRepository tasks;
  final BookingService bookings;
  final ApplicationService applications;
  final Clock clock;

  public TaskExecutor(
      ScheduledTaskRepository tasks,
      BookingService bookings,
      ApplicationService applications,
      Clock clock) {
    this.tasks = tasks;
    this.bookings = bookings;
    this.applications = applications;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<UUID> due() {
    return tasks.due(clock.instant());
  }

  @Transactional
  public void run(UUID id) {
    var t = tasks.lock(id).orElse(null);
    if (t == null || t.nextAttemptAt.isAfter(clock.instant())) return;
    var values = new LinkedHashMap<String, Object>();
    values.put("id", t.id);
    values.put("taskType", t.taskType);
    values.put("sourceType", t.sourceType);
    values.put("sourceId", t.sourceId);
    values.put("dueAt", t.dueAt);
    if (t.sourceType.equals("M1")) bookings.task(values);
    else applications.task(values);
    t.status = "DONE";
    t.leaseOwner = null;
    t.leaseUntil = null;
    t.lastErrorCode = null;
  }

  @Transactional
  public void failed(UUID id) {
    var t = tasks.lock(id).orElse(null);
    if (t == null) return;
    t.attempts++;
    t.nextAttemptAt =
        clock.instant().plusSeconds(t.attempts >= 8 ? 300 : Math.min(1L << t.attempts, 300));
    t.lastErrorCode = "TASK_EXECUTION_FAILED";
  }
}
