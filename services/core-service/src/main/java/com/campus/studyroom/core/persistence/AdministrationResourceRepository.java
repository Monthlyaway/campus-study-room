package com.campus.studyroom.core.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AdministrationResourceRepository extends JpaRepository<ClaimEntity, UUID> {
  @Query(
      "select count(c)>0 from ClaimEntity c where c.roomId=:room and c.releasedAt is null and"
          + " (c.endAt is null or c.endAt>:now) and (:seat is null or c.seatId is null or"
          + " c.seatId=:seat)")
  boolean inUse(@Param("room") UUID room, @Param("seat") UUID seat, @Param("now") Instant now);
}
