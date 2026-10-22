package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "permission", schema = "core")
public class PermissionEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "code")
  public String code;

  @Column(name = "description")
  public String description;
}
