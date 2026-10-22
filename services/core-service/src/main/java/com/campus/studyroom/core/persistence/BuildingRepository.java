package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface BuildingRepository
    extends JpaRepository<BuildingEntity, UUID>, JpaSpecificationExecutor<BuildingEntity> {
  boolean existsByCampusIdAndEnabledTrue(UUID campusId);
}
