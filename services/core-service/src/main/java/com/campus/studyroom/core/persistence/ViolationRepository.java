package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ViolationRepository extends JpaRepository<ViolationEntity, UUID> {
  boolean existsByReservationId(UUID id);
}
