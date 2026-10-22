package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "system_config", schema = "core")
public class SystemConfigEntity {
  @Id
  @Column(name = "key")
  public String key;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "value_json")
  public Object value;

  @Column(name = "updated_by")
  public UUID updatedBy;

  @Version
  @Column(name = "version")
  public long version;

  @Column(name = "updated_at")
  public Instant updatedAt;
}
