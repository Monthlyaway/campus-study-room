package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PermissionRepository
    extends JpaRepository<PermissionEntity, UUID>, JpaSpecificationExecutor<PermissionEntity> {
  Optional<PermissionEntity> findByCode(String code);

  interface RoleCode {
    UUID getRoleId();

    String getCode();
  }

  @Query(
      "select rp.roleId as roleId,p.code as code from RolePermissionEntity rp join PermissionEntity"
          + " p on p.id=rp.permissionId where rp.roleId in :roles order by p.code")
  List<RoleCode> codesForRoles(@Param("roles") Collection<UUID> roles);

  @Query(
      "select p.code from PermissionEntity p join RolePermissionEntity rp on rp.permissionId=p.id"
          + " where rp.roleId=:role order by p.code")
  List<String> codesForRole(@Param("role") UUID role);
}
