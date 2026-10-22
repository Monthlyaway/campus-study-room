package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface CheckinCodeRepository extends JpaRepository<CheckinCodeEntity, UUID> {
  Optional<CheckinCodeEntity> findByRoomIdAndLocalDate(UUID roomId, java.time.LocalDate date);
}
