package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface CampusRepository
    extends JpaRepository<CampusEntity, UUID>, JpaSpecificationExecutor<CampusEntity> {}
