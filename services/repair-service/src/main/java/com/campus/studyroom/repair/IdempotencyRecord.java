package com.campus.studyroom.repair;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="idempotency_record", schema="repair")
class IdempotencyRecord {
    @Id UUID id;
    @Column(length=16) String actorType;
    UUID actorId;
    @Column(length=8) String method;
    @Column(length=255) String canonicalPath;
    @Column(name="idempotency_key") UUID key;
    @Column(length=64) String requestHash;
    int httpStatus;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") String responseDataJson;
    Instant createdAt;
    Instant expiresAt;
    protected IdempotencyRecord() {}
}
