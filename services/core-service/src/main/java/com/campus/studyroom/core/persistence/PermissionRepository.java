package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PermissionRepository
    extends JpaRepository<PermissionEntity, UUID>, JpaSpecificationExecutor<PermissionEntity> {
  Optional<PermissionEntity> findByCode(String code);

  @Query(
      "select p.code from PermissionEntity p join RolePermissionEntity rp on rp.permissionId=p.id"
          + " where rp.roleId=:role order by p.code")
  List<String> codesForRole(@Param("role") UUID role);
}
