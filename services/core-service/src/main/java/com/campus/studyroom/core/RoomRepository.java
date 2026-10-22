package com.campus.studyroom.core;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, UUID>, JpaSpecificationExecutor<Room> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Room r where r.id=:id")
  Optional<Room> lock(@Param("id") UUID id);

  boolean existsByDepartmentIdAndEnabledTrue(UUID departmentId);

  boolean existsByBuildingIdAndEnabledTrue(UUID buildingId);

  @Query(
      "select room from Room room where (:building is null or room.buildingId=:building) and"
          + " (room.departmentId is null or room.departmentId=:department or exists (select s.id"
          + " from UserRoleEntity ur join RoleEntity role on role.id=ur.roleId join"
          + " RolePermissionEntity rp on rp.roleId=role.id join PermissionEntity p on"
          + " p.id=rp.permissionId join RoleScopeEntity s on s.userRoleId=ur.id where"
          + " ur.userId=:user and role.enabled=true and p.code in :permissions and"
          + " (s.scopeType='ALL' or (s.scopeType='ROOM' and s.roomId=room.id) or"
          + " (s.scopeType='DEPARTMENT' and s.departmentId=room.departmentId) or"
          + " (s.scopeType='SHARED' and room.departmentId is null))))")
  Page<Room> visible(
      @Param("user") UUID user,
      @Param("department") UUID department,
      @Param("building") UUID building,
      @Param("permissions") Collection<String> permissions,
      Pageable pageable);
}
