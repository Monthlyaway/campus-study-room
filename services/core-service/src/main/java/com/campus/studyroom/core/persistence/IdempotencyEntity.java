package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "idempotency_record", schema = "core")
public class IdempotencyEntity {
  @Id public UUID id;
  public String actorType;
  public UUID actorId;
  public String method;
  public String canonicalPath;
  public UUID key;
  public String requestHash;
  public int httpStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public String responseDataJson;

  public Instant createdAt;
  public Instant expiresAt;
}
