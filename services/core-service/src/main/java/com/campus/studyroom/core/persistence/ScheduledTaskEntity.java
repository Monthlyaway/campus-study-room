package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "scheduled_task", schema = "core")
public class ScheduledTaskEntity {
  @Id public UUID id;
  public String taskType;
  public String sourceType;
  public UUID sourceId;
  public String dedupKey;
  public Instant dueAt;
  public String status;
  public int attempts;
  public Instant nextAttemptAt;
  public String leaseOwner;
  public Instant leaseUntil;
  public String lastErrorCode;
}
