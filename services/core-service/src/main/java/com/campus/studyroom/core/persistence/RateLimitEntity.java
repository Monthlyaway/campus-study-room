package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "rate_limit_bucket", schema = "core")
public class RateLimitEntity {
  @Id public String bucketKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public List<String> recentAttempts;

  public Instant blockedUntil;
  @Version public long version;
}
