package com.campus.studyroom.repair;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="scheduled_task", schema="repair")
class DeadlineTask {
    @Id UUID id;
    @Column(length=64) String taskType;
    @Column(length=8) String sourceType;
    UUID sourceId;
    @Column(length=255) String dedupKey;
    int cycle;
    Instant dueAt;
    @Column(length=16) String status;
    int attempts;
    Instant nextAttemptAt;
    UUID leaseOwner;
    Instant leaseUntil;
    @Column(length=100) String lastErrorCode;
    Instant createdAt;
    @Version long version;
    protected DeadlineTask() {}
}
