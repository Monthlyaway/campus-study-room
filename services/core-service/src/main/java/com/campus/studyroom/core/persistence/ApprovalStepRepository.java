package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalStepRepository extends JpaRepository<ApprovalStepEntity, UUID> {
  Optional<ApprovalStepEntity> findByApplicationIdAndStepNo(UUID applicationId, short stepNo);

  List<ApprovalStepEntity> findByApplicationIdOrderByStepNo(UUID applicationId);
}
