package com.campus.studyroom.repair;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="audit_event", schema="repair")
class RepairAudit {
    @Id UUID id;
    @Column(length=16) String actorType;
    UUID actorId;
    @Column(length=100) String action;
    @Column(length=32) String objectType;
    UUID objectId;
    UUID roomId;
    @Column(length=8) String sourceType;
    UUID sourceId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") String beforeJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") String afterJson;
    @Column(length=2000) String reason;
    UUID requestId;
    Instant createdAt;
    protected RepairAudit() {}
}
