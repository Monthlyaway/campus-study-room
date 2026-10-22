package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "config_revision", schema = "core")
public class ConfigRevisionEntity {
  @Id
  @Column(name = "id")
  public int id;

  @Version
  @Column(name = "version")
  public long version;
}
