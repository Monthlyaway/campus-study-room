package com.campus.studyroom.core.persistence;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.*;

@Entity
@Table(name = "role_permission", schema = "core")
@IdClass(RolePermissionEntity.Key.class)
public class RolePermissionEntity {
  @Id
  @Column(name = "role_id")
  public UUID roleId;

  @Id
  @Column(name = "permission_id")
  public UUID permissionId;

  public static class Key implements Serializable {
    public UUID roleId;
    public UUID permissionId;

    public Key() {}

    public Key(UUID roleId, UUID permissionId) {
      this.roleId = roleId;
      this.permissionId = permissionId;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key k
          && Objects.equals(roleId, k.roleId)
          && Objects.equals(permissionId, k.permissionId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(roleId, permissionId);
    }
  }
}
