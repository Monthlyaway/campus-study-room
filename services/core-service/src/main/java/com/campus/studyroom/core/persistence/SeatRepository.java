package com.campus.studyroom.core.persistence;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SeatRepository
    extends JpaRepository<SeatEntity, UUID>, JpaSpecificationExecutor<SeatEntity> {
  Page<SeatEntity> findByRoomId(UUID roomId, Pageable pageable);

  long countByRoomIdAndEnabledTrue(UUID roomId);

  @Query("select s.roomId from SeatEntity s where s.id=:id")
  Optional<UUID> roomId(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from SeatEntity s where s.id=:id")
  Optional<SeatEntity> lock(@Param("id") UUID id);
}
