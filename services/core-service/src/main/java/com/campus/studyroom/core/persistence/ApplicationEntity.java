package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "room_application", schema = "core")
public class ApplicationEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "applicant_id")
  public UUID applicantId;

  @Column(name = "applicant_department_id")
  public UUID applicantDepartmentId;

  @Column(name = "room_id")
  public UUID roomId;

  @Column(name = "start_at")
  public Instant startAt;

  @Column(name = "end_at")
  public Instant endAt;

  @Column(name = "purpose_title")
  public String purposeTitle;

  @Column(name = "purpose_description")
  public String purposeDescription;

  @Column(name = "status")
  public String status;

  @Column(name = "current_step")
  public short currentStep;

  @Column(name = "expires_at")
  public Instant expiresAt;

  @Column(name = "cancel_reason")
  public String cancelReason;

  @Column(name = "config_version")
  public long configVersion;

  @Column(name = "max_duration_hours")
  public int maxDurationHours;

  @Version
  @Column(name = "version")
  public long version;

  @Column(name = "created_at")
  public Instant createdAt;

  @Column(name = "updated_at")
  public Instant updatedAt;
}
