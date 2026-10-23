package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "seat", schema = "core")
public class SeatEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "room_id")
  public UUID roomId;

  @Column(name = "code")
  public String code;

  @Column(name = "has_power")
  public boolean hasPower;

  @Column(name = "enabled")
  public boolean enabled = true;

  @Version
  @Column(name = "version")
  public long version;
}
