package com.campus.studyroom.repair;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="repair_rating", schema="repair")
class RepairRating {
    @Id UUID id;
    UUID orderId;
    UUID reporterId;
    int score;
    @Column(length=500) String comment;
    Instant createdAt;
    protected RepairRating() {}
}
