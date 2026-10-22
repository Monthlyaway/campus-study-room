package com.campus.studyroom.core.persistence;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RoleRepository
    extends JpaRepository<RoleEntity, UUID>, JpaSpecificationExecutor<RoleEntity> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RoleEntity r where r.id=:id")
  Optional<RoleEntity> lock(@Param("id") UUID id);

  List<RoleEntity> findByEnabledTrueOrderByCode();

  Optional<RoleEntity> findByCode(String code);
}
