package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "approval_step", schema = "core")
public class ApprovalStepEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "application_id")
  public UUID applicationId;

  @Column(name = "step_no")
  public short stepNo;

  @Column(name = "required_permission")
  public String requiredPermission;

  @Column(name = "status")
  public String status;
}
