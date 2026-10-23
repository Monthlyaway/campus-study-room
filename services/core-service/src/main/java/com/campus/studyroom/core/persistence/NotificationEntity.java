package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notification", schema = "core")
public class NotificationEntity {
  @Id public UUID id;
  public UUID recipientId;
  public UUID roomId;
  public String eventType;
  public String sourceType;
  public UUID sourceId;
  public String dedupKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public String payloadJson;

  public String channel;
  public Instant dueAt;
  public String status;
  public int attempts;
  public Instant nextAttemptAt;
  public String leaseOwner;
  public Instant leaseUntil;
  public Instant sentAt;
  public Instant readAt;
  public String lastErrorCode;
  @Version public long version;
  public Instant createdAt;
}
