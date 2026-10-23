package com.campus.studyroom.core.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, UUID> {
  @Query("select r.roomId from ApplicationEntity r where r.id=:id")
  Optional<UUID> roomId(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from ApplicationEntity a where a.id=:id")
  Optional<ApplicationEntity> lock(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select a from ApplicationEntity a where a.roomId=:room and a.endAt>:now and"
          + " a.status='APPROVED' order by a.id")
  List<ApplicationEntity> maintenanceAffected(@Param("room") UUID room, @Param("now") Instant now);
}
