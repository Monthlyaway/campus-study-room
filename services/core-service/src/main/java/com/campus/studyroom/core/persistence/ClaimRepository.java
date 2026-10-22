package com.campus.studyroom.core.persistence;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<ClaimEntity, UUID> {
  Optional<ClaimEntity> findBySourceTypeAndSourceId(String sourceType, UUID sourceId);

  @Query(
      "select count(c)>0 from ClaimEntity c where c.roomId=:room and c.releasedAt is null and"
          + " c.startAt<:end and (c.endAt is null or c.endAt>:start) and (:seat is null or c.seatId"
          + " is null or c.seatId=:seat)")
  boolean conflicts(
      @Param("room") UUID room,
      @Param("seat") UUID seat,
      @Param("start") Instant start,
      @Param("end") Instant end);
}
