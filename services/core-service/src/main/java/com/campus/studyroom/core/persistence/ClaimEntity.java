package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "resource_claim", schema = "core")
public class ClaimEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "room_id")
  public UUID roomId;

  @Column(name = "seat_id")
  public UUID seatId;

  @Column(name = "start_at")
  public Instant startAt;

  @Column(name = "end_at")
  public Instant endAt;

  @Column(name = "source_type")
  public String sourceType;

  @Column(name = "source_id")
  public UUID sourceId;

  @Column(name = "source_version")
  public long sourceVersion;

  @Column(name = "released_at")
  public Instant releasedAt;

  @Column(name = "created_by")
  public UUID createdBy;

  @Column(name = "created_request_id")
  public UUID createdRequestId;

  @Column(name = "release_request_id")
  public UUID releaseRequestId;
}
