package com.campus.studyroom.repair;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="repair_acceptance", schema="repair")
class RepairAcceptance {
    @Id UUID id;
    UUID orderId;
    int roundNo;
    UUID actorId;
    @Column(length=16) String decision;
    @Column(length=500) String comment;
    Instant createdAt;
    protected RepairAcceptance() {}
}
