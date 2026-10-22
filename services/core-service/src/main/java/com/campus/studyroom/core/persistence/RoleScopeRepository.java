package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RoleScopeRepository
    extends JpaRepository<RoleScopeEntity, UUID>, JpaSpecificationExecutor<RoleScopeEntity> {
  List<RoleScopeEntity> findByUserRoleId(UUID userRoleId);

  @Query(
      "select s from RoleScopeEntity s join UserRoleEntity ur on ur.id=s.userRoleId join RoleEntity"
          + " r on r.id=ur.roleId where ur.userId=:user and r.enabled=true")
  List<RoleScopeEntity> forUser(@Param("user") UUID user);
}
