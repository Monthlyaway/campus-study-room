package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "processed_command", schema = "core")
public class ProcessedCommandEntity {
  @Id
  @Column(name = "command_id")
  public UUID commandId;

  @Column(name = "payload_hash")
  public String payloadHash;

  @Column(name = "http_status")
  public int httpStatus;

  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "result_json")
  public Map<String, Object> resultJson;

  @Column(name = "processed_at")
  public Instant processedAt;
}
