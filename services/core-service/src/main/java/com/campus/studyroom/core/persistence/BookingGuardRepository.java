package com.campus.studyroom.core.persistence;

import com.campus.studyroom.core.BookingGuard;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingGuardRepository extends JpaRepository<BookingGuard, UUID> {}
