package com.campus.studyroom.repair;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="outbox_command", schema="repair")
class OutboxCommand {
    @Id UUID commandId;
    UUID orderId;
    @Column(length=16) String commandType;
    long sourceVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") String payloadJson;
    @Column(length=64) String payloadHash;
    @Column(length=16) String status;
    int attempts;
    Instant nextAttemptAt;
    UUID leaseOwner;
    Instant leaseUntil;
    Instant lastAttemptAt;
    @Column(length=100) String lastErrorCode;
    UUID requestId;
    Instant createdAt;
    Instant updatedAt;
    @Version long version;
    protected OutboxCommand() {}
}
