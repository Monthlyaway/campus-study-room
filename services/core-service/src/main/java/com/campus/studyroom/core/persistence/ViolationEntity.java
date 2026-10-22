package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "violation", schema = "core")
public class ViolationEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "reservation_id")
  public UUID reservationId;

  @Column(name = "user_id")
  public UUID userId;

  @Column(name = "reason")
  public String reason;

  @Column(name = "occurred_at")
  public Instant occurredAt;
}
