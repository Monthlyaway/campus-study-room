package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "campus", schema = "core")
public class CampusEntity {
  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "code")
  public String code;

  @Column(name = "name")
  public String name;

  @Column(name = "enabled")
  public boolean enabled = true;

  @Version
  @Column(name = "version")
  public long version;
}
