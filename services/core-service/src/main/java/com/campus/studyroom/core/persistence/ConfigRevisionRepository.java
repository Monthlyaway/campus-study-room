package com.campus.studyroom.core.persistence;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ConfigRevisionRepository
    extends JpaRepository<ConfigRevisionEntity, Integer>,
        JpaSpecificationExecutor<ConfigRevisionEntity> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ConfigRevisionEntity c where c.id=1")
  Optional<ConfigRevisionEntity> lock();

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select c from ConfigRevisionEntity c where c.id=1")
  Optional<ConfigRevisionEntity> readLock();
}
