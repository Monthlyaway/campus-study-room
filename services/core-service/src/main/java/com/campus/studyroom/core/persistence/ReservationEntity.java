package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "reservation", schema = "core")
public class ReservationEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "user_id")
  public UUID userId;

  @Column(name = "room_id")
  public UUID roomId;

  @Column(name = "seat_id")
  public UUID seatId;

  @Column(name = "start_at")
  public Instant startAt;

  @Column(name = "end_at")
  public Instant endAt;

  @Column(name = "status")
  public String status;

  @Column(name = "checked_in_at")
  public Instant checkedInAt;

  @Column(name = "cancel_reason")
  public String cancelReason;

  @Column(name = "ended_at")
  public Instant endedAt;

  @Column(name = "config_version")
  public long configVersion;

  @Column(name = "max_duration_hours")
  public int maxDurationHours;

  @Version
  @Column(name = "version")
  public long version;

  @Column(name = "created_at")
  public Instant createdAt;

  @Column(name = "updated_at")
  public Instant updatedAt;
}
