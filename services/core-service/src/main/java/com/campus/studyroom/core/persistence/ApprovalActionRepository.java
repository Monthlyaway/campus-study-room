package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalActionRepository extends JpaRepository<ApprovalActionEntity, UUID> {
  boolean existsByApplicationIdAndActorId(UUID applicationId, UUID actorId);
}
