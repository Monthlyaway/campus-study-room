package com.campus.studyroom.core.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {
  @Query("select r.roomId from ReservationEntity r where r.id=:id")
  Optional<UUID> roomId(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from ReservationEntity r where r.id=:id")
  Optional<ReservationEntity> lock(@Param("id") UUID id);

  boolean existsByUserIdAndStatusInAndStartAtLessThanAndEndAtGreaterThan(
      UUID user, List<String> status, Instant end, Instant start);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select r from ReservationEntity r where r.roomId=:room and r.endAt>:now and r.status"
          + " in('RESERVED','CHECKED_IN') order by r.id")
  List<ReservationEntity> maintenanceAffected(@Param("room") UUID room, @Param("now") Instant now);
}
