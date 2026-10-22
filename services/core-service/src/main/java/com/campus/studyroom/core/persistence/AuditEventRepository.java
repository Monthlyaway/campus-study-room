package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {}
