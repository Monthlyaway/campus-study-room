package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {
  boolean existsByDedupKey(String key);

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select n from NotificationEntity n where n.id=:id")
  Optional<NotificationEntity> lock(@Param("id") UUID id);

  @Query(
      value =
          "select * from core.notification where ((status in ('PENDING','RETRYING') and"
              + " next_attempt_at<=:now) or (status='LEASED' and lease_until<=:now)) order by"
              + " next_attempt_at,id limit 50 for update skip locked",
      nativeQuery = true)
  List<NotificationEntity> due(@Param("now") java.time.Instant now);
}
