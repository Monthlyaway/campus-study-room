package com.campus.studyroom.core;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "room", schema = "core")
public class Room {
  @Id public UUID id;

  @Column(name = "building_id", nullable = false)
  public UUID buildingId;

  public String code;
  public String name;

  @Column(name = "department_id")
  public UUID departmentId;

  public boolean enabled;
  @Version public long version;
}
