package com.campus.studyroom.core;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Private operational endpoint; the public proxy rejects every /actuator path. */
@RestController
class CoreJobStatus {
  final Jobs jobs;
  final com.campus.studyroom.security.BusinessRequestTracker requests;

  CoreJobStatus(Jobs jobs, com.campus.studyroom.security.BusinessRequestTracker requests) {
    this.jobs = jobs;
    this.requests = requests;
  }

  @GetMapping("/actuator/jobs")
  Map<String, Object> jobs() {
    return Map.of(
        "paused",
        jobs.paused(),
        "active",
        jobs.activeCount(),
        "activeRequests",
        requests.activeCount());
  }
}
