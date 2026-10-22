package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedCommandRepository extends JpaRepository<ProcessedCommandEntity, UUID> {}
