package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "department", schema = "core")
public class DepartmentEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "code")
  public String code;

  @Column(name = "name")
  public String name;

  @Column(name = "enabled")
  public boolean enabled = true;

  @Version
  @Column(name = "version")
  public long version;

  @Column(name = "created_at")
  public Instant createdAt;

  @Column(name = "updated_at")
  public Instant updatedAt;
}
