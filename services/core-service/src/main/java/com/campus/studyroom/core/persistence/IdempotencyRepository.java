package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface IdempotencyRepository extends JpaRepository<IdempotencyEntity, UUID> {
  Optional<IdempotencyEntity> findByActorTypeAndActorIdAndMethodAndCanonicalPathAndKey(
      String actorType, UUID actorId, String method, String path, UUID key);
}
