package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UserRoleRepository
    extends JpaRepository<UserRoleEntity, UUID>, JpaSpecificationExecutor<UserRoleEntity> {
  List<UserRoleEntity> findByUserId(UUID userId);

  boolean existsByRoleId(UUID roleId);

  void deleteByUserId(UUID userId);

  @Query(
      "select count(ur)>0 from UserRoleEntity ur join RoleEntity r on r.id=ur.roleId where"
          + " ur.userId=:user and r.code=:code and r.enabled=true")
  boolean hasRole(@Param("user") UUID user, @Param("code") String code);

  @Query(
      "select distinct p.code from UserRoleEntity ur join RoleEntity r on r.id=ur.roleId join"
          + " RolePermissionEntity rp on rp.roleId=r.id join PermissionEntity p on"
          + " p.id=rp.permissionId where ur.userId=:user and r.enabled=true order by p.code")
  List<String> permissionCodes(@Param("user") UUID user);

  @Query(
      "select r.code from UserRoleEntity ur join RoleEntity r on r.id=ur.roleId where"
          + " ur.userId=:user and r.enabled=true order by r.code")
  List<String> roleCodes(@Param("user") UUID user);

  @Query(
      "select count(s)>0 from UserRoleEntity ur join RoleEntity r on r.id=ur.roleId join"
          + " RolePermissionEntity rp on rp.roleId=r.id join PermissionEntity p on"
          + " p.id=rp.permissionId join RoleScopeEntity s on s.userRoleId=ur.id where"
          + " ur.userId=:user and r.enabled=true and p.code=:permission and (s.scopeType='ALL' or"
          + " (s.scopeType='ROOM' and s.roomId=:room) or (s.scopeType='DEPARTMENT' and"
          + " s.departmentId=:department) or (s.scopeType='SHARED' and :shared=true))")
  boolean grantsScope(
      @Param("user") UUID user,
      @Param("permission") String permission,
      @Param("room") UUID room,
      @Param("department") UUID department,
      @Param("shared") boolean shared);
}
