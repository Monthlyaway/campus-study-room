package com.campus.studyroom.repair;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface RepairOrderRepository extends JpaRepository<RepairOrder, UUID>, JpaSpecificationExecutor<RepairOrder> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from RepairOrder r where r.id=:id")
    Optional<RepairOrder> lock(@Param("id") UUID id);
    @Query("select distinct r.roomId from RepairOrder r") List<UUID> roomIds();
}
interface RepairHistoryRepository extends JpaRepository<RepairHistory, UUID> {
    List<RepairHistory> findByOrderIdOrderByCreatedAtAscIdAsc(UUID orderId);
}
interface RepairAcceptanceRepository extends JpaRepository<RepairAcceptance, UUID> {
    List<RepairAcceptance> findByOrderIdOrderByRoundNoAsc(UUID orderId);
}
interface RepairRatingRepository extends JpaRepository<RepairRating, UUID> {
    Optional<RepairRating> findByOrderId(UUID orderId);
    List<RepairRating> findByOrderIdIn(java.util.Collection<UUID> orderIds);
}
interface OutboxRepository extends JpaRepository<OutboxCommand, UUID> {
    @Query("select c.commandId from OutboxCommand c where (c.status='PENDING' and c.nextAttemptAt<=:now) or (c.status='LEASED' and c.leaseUntil<=:now) order by c.nextAttemptAt")
    List<UUID> due(@Param("now") Instant now, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from OutboxCommand c where c.commandId=:id")
    Optional<OutboxCommand> lock(@Param("id") UUID id);
    List<OutboxCommand> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}
interface DeadlineRepository extends JpaRepository<DeadlineTask, UUID> {
    @Query("select t.id from DeadlineTask t where (t.status='PENDING' and t.nextAttemptAt<=:now) or (t.status='LEASED' and t.leaseUntil<=:now) order by t.nextAttemptAt")
    List<UUID> due(@Param("now") Instant now, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from DeadlineTask t where t.id=:id") Optional<DeadlineTask> lock(@Param("id") UUID id);
    boolean existsByDedupKey(String dedupKey);
}
interface RepairAuditRepository extends JpaRepository<RepairAudit, UUID>, JpaSpecificationExecutor<RepairAudit> {}
interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {
    Optional<IdempotencyRecord> findByActorIdAndMethodAndCanonicalPathAndKey(UUID actorId, String method, String path, UUID key);
}
