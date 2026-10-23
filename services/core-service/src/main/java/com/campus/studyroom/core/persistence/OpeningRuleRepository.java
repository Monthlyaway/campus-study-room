package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface OpeningRuleRepository
    extends JpaRepository<OpeningRuleEntity, UUID>, JpaSpecificationExecutor<OpeningRuleEntity> {
  List<OpeningRuleEntity> findByRoomIdOrderByWeekdayAscStartMinuteAsc(UUID roomId);

  List<OpeningRuleEntity> findByRoomId(UUID roomId);

  void deleteByRoomId(UUID roomId);
}
