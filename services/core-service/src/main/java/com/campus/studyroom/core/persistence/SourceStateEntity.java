package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.*;

@Entity
@Table(name = "resource_source_state", schema = "core")
@IdClass(SourceStateEntity.Key.class)
public class SourceStateEntity {
  @Id
  @Column(name = "source_type")
  public String sourceType;

  @Id
  @Column(name = "source_id")
  public UUID sourceId;

  @Column(name = "max_version")
  public long maxVersion;

  public String state;

  @Column(name = "last_command_id")
  public UUID lastCommandId;

  public static class Key implements Serializable {
    public String sourceType;
    public UUID sourceId;

    public Key() {}

    public Key(String type, UUID id) {
      sourceType = type;
      sourceId = id;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key k
          && Objects.equals(k.sourceType, sourceType)
          && Objects.equals(k.sourceId, sourceId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(sourceType, sourceId);
    }
  }
}
