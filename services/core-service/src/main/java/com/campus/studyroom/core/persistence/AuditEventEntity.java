package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_event", schema = "core")
@org.hibernate.annotations.Immutable
public class AuditEventEntity {
  @Id public UUID id;
  public String actorType;
  public UUID actorId;
  public String action;
  public String objectType;
  public UUID objectId;
  public UUID roomId;
  public String sourceType;
  public UUID sourceId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public String beforeJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public String afterJson;

  public String reason;
  public UUID requestId;
  public Instant createdAt;
}
