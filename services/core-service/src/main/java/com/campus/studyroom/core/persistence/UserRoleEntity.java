package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "user_role", schema = "core")
public class UserRoleEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "user_id")
  public UUID userId;

  @Column(name = "role_id")
  public UUID roleId;
}
