package com.campus.studyroom.repair;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="repair_history", schema="repair")
class RepairHistory {
    @Id UUID id;
    UUID orderId;
    @Enumerated(EnumType.STRING) RepairStatus fromStatus;
    @Enumerated(EnumType.STRING) RepairStatus toStatus;
    UUID actorId;
    @Column(length=2000) String reason;
    UUID oldAssignee;
    UUID newAssignee;
    UUID requestId;
    Instant createdAt;
    protected RepairHistory() {}
}
