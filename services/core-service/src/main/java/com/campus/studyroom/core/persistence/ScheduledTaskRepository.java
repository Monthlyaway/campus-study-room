package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, UUID> {
  boolean existsByDedupKey(String key);

  @Query(
      value =
          "select id from core.scheduled_task where status<>'DONE' and next_attempt_at<=:now order"
              + " by case when task_type='M1_NO_SHOW' then 0 else 1 end,due_at,id limit 200",
      nativeQuery = true)
  List<UUID> due(@Param("now") java.time.Instant now);

  @Query(
      value =
          "select * from core.scheduled_task where id=:id and status<>'DONE' for update skip"
              + " locked",
      nativeQuery = true)
  Optional<ScheduledTaskEntity> lock(@Param("id") UUID id);
}
