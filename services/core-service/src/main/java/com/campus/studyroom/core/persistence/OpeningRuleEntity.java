package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "opening_rule", schema = "core")
public class OpeningRuleEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "room_id")
  public UUID roomId;

  @Column(name = "weekday")
  public short weekday;

  @Column(name = "start_minute")
  public int startMinute;

  @Column(name = "end_minute")
  public int endMinute;
}
