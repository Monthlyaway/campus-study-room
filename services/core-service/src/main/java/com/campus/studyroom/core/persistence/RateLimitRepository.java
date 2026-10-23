package com.campus.studyroom.core.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface RateLimitRepository extends JpaRepository<RateLimitEntity, String> {}
