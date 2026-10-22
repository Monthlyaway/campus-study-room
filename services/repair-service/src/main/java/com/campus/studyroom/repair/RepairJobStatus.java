package com.campus.studyroom.repair;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
/** Private operational endpoint; Nginx must deny /actuator/* on the public listener. */
@RestController
class RepairJobStatus {
    private final RepairWorker worker;
    RepairJobStatus(RepairWorker worker){this.worker=worker;}
    @GetMapping("/actuator/jobs") Map<String,Object> jobs(){return Map.of("paused",worker.isPaused(),"active",worker.activeCount());}
}
