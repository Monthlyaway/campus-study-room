package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user", schema = "core")
public class AccountEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "username")
  public String username;

  @Column(name = "school_no")
  public String schoolNo;

  @Column(name = "display_name")
  public String displayName;

  @Column(name = "password_hash")
  public String passwordHash;

  @Column(name = "email")
  public String email;

  @Column(name = "department_id")
  public UUID departmentId;

  @Column(name = "enabled")
  public boolean enabled = true;

  @Column(name = "auth_version")
  public long authVersion;

  @Version
  @Column(name = "version")
  public long version;

  @Column(name = "created_at")
  public Instant createdAt;

  @Column(name = "updated_at")
  public Instant updatedAt;
}
