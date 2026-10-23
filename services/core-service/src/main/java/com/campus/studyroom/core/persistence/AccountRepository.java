package com.campus.studyroom.core.persistence;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AccountRepository
    extends JpaRepository<AccountEntity, UUID>, JpaSpecificationExecutor<AccountEntity> {
  Optional<AccountEntity> findByUsername(String username);

  boolean existsByDepartmentIdAndEnabledTrue(UUID departmentId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from AccountEntity a where a.id=:id")
  Optional<AccountEntity> lock(@Param("id") UUID id);

  @Query(
      "select count(distinct a.id) from AccountEntity a join UserRoleEntity ur on ur.userId=a.id"
          + " join RoleEntity r on r.id=ur.roleId where a.enabled=true and r.enabled=true and"
          + " r.code='SYSTEM_ADMIN' and a.id<>:excluded")
  long otherEnabledAdministrators(@Param("excluded") UUID excluded);

  @Query(
      "select a from AccountEntity a where a.id in (select ur.userId from UserRoleEntity ur where"
          + " ur.roleId=:role) order by a.id")
  List<AccountEntity> assignedToRole(@Param("role") UUID role);
}
