package com.campus.studyroom.repair;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="repair_order", schema="repair")
class RepairOrder {
    @Id UUID id;
    UUID reporterId;
    UUID roomId;
    @Column(length=100) String title;
    @Column(length=2000) String description;
    @Enumerated(EnumType.STRING) RepairStatus status;
    UUID assignedTo;
    @Column(length=64) String assignedToName;
    UUID assignedBy;
    Instant submittedAt;
    Instant firstAssignedAt;
    Instant assignedAt;
    Instant workCompletedAt;
    Instant acceptedAt;
    Instant closedAt;
    Instant assignDueAt;
    Instant workDueAt;
    Instant acceptDueAt;
    int acceptanceRound;
    @Column(length=2000) String lastWorkNote;
    long resourceVersion;
    @Version long version;
    Instant createdAt;
    Instant updatedAt;
    protected RepairOrder() {}
    RepairOrder(UUID reporter, UUID room, String title, String description, Instant now) {
        id = UUID.randomUUID(); reporterId = reporter; roomId = room; this.title = title; this.description = description;
        status = RepairStatus.SUBMITTED; submittedAt = createdAt = updatedAt = now;
        assignDueAt = RepairPolicy.assignmentDeadline(now);
    }
}
