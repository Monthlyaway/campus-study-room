package com.campus.studyroom.core.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionRepository
    extends JpaRepository<RolePermissionEntity, RolePermissionEntity.Key> {
  void deleteByRoleId(UUID roleId);
}
