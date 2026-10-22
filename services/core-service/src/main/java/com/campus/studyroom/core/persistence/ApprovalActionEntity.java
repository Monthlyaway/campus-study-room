package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "approval_action", schema = "core")
public class ApprovalActionEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "application_id")
  public UUID applicationId;

  @Column(name = "step_no")
  public short stepNo;

  @Column(name = "actor_id")
  public UUID actorId;

  @Column(name = "decision")
  public String decision;

  @Column(name = "comment")
  public String comment;

  @Column(name = "occurred_at")
  public Instant occurredAt;
}
