package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "user_role_scope", schema = "core")
public class RoleScopeEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "user_role_id")
  public UUID userRoleId;

  @Column(name = "scope_type")
  public String scopeType;

  @Column(name = "department_id")
  public UUID departmentId;

  @Column(name = "room_id")
  public UUID roomId;
}
