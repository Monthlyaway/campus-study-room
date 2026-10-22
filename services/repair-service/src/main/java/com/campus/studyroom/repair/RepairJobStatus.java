package com.campus.studyroom.repair;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Private operational endpoint; Nginx must deny /actuator/* on the public listener. */
@RestController
class RepairJobStatus {
  private final RepairWorker worker;
  private final com.campus.studyroom.security.BusinessRequestTracker requests;

  RepairJobStatus(
      RepairWorker worker, com.campus.studyroom.security.BusinessRequestTracker requests) {
    this.worker = worker;
    this.requests = requests;
  }

  @GetMapping("/actuator/jobs")
  Map<String, Object> jobs() {
    return Map.of(
        "paused",
        worker.isPaused(),
        "active",
        worker.activeCount(),
        "activeRequests",
        requests.activeCount());
  }
}
