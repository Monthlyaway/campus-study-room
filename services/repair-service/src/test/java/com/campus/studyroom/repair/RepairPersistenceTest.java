package com.campus.studyroom.repair;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** Genuine PostgreSQL tests. The opt-in variable is required because they need migrated runtime credentials. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"studyroom.jobs-paused=true"})
@Import(RepairPersistenceTest.TimeConfiguration.class)
@EnabledIfEnvironmentVariable(named="STUDYROOM_REPAIR_DB_TESTS",matches="true")
class RepairPersistenceTest {
    @Autowired RepairService service;
    @Autowired RepairWorker worker;
    @Autowired MutableClock testClock;
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock testClock(){return new MutableClock(Instant.now().plus(Duration.ofDays(30)));}
    }
    static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now){this.now=now;}
        synchronized void advance(Duration duration){now=now.plus(duration);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public synchronized Instant instant(){return now;}
    }
    @Test void tcM307DeadlineRoundIsPersistentAndDoesNotAutoClose() {
        UUID[] ids=service.tx.execute(tx->{
            Instant now=testClock.instant();RepairOrder order=new RepairOrder(UUID.randomUUID(),UUID.randomUUID(),"期限验收","持久化期限验证",now);
            order.status=RepairStatus.AWAITING_ACCEPTANCE;order.assignedTo=UUID.randomUUID();order.acceptanceRound=2;order.workCompletedAt=now;order.acceptDueAt=RepairPolicy.acceptanceDeadline(now);service.orders.saveAndFlush(order);
            service.deadline(order,"ACCEPT_OVERDUE",2,order.acceptDueAt,now);
            DeadlineTask task=service.deadlines.findAll().stream().filter(t->t.sourceId.equals(order.id)).findFirst().orElseThrow();return new UUID[]{order.id,task.id};
        });
        testClock.advance(Duration.ofHours(49));worker.runDeadline(ids[1]);worker.runDeadline(ids[1]);worker.runDeadline(ids[1]);
        assertEquals(RepairStatus.AWAITING_ACCEPTANCE,service.find(ids[0]).status);
        assertEquals("DONE",service.deadlines.findById(ids[1]).orElseThrow().status);
        var commands=service.outbox.findByOrderIdOrderByCreatedAtAsc(ids[0]);assertEquals(1,commands.size());
        assertEquals("ACCEPT_OVERDUE",service.json.read(commands.getFirst().payloadJson).get("eventType"));
        assertEquals("2",service.json.read(commands.getFirst().payloadJson).get("eventCycle"));
    }
    @Test void tcM307RejectedWorkRetainsOriginalOverdueDeadline() {
        UUID[] ids=service.tx.execute(tx->{
            Instant now=testClock.instant();RepairOrder order=new RepairOrder(UUID.randomUUID(),UUID.randomUUID(),"返修期限","验证返修仍沿用首次派单截止",now);
            order.status=RepairStatus.AWAITING_ACCEPTANCE;order.assignedTo=UUID.randomUUID();order.firstAssignedAt=now;order.workDueAt=RepairPolicy.workDeadline(now);service.orders.saveAndFlush(order);
            service.deadline(order,"WORK_OVERDUE",0,order.workDueAt,now);
            DeadlineTask task=service.deadlines.findAll().stream().filter(t->t.sourceId.equals(order.id)).findFirst().orElseThrow();return new UUID[]{order.id,task.id};
        });
        testClock.advance(Duration.ofHours(49));worker.runDeadline(ids[1]);
        assertEquals("PENDING",service.deadlines.findById(ids[1]).orElseThrow().status);
        assertEquals(0,service.outbox.findByOrderIdOrderByCreatedAtAsc(ids[0]).size());
        service.tx.executeWithoutResult(tx->{RepairOrder r=service.lock(ids[0]);r.status=RepairStatus.IN_REPAIR;});
        testClock.advance(Duration.ofSeconds(31));worker.runDeadline(ids[1]);worker.runDeadline(ids[1]);
        assertEquals(RepairStatus.IN_REPAIR,service.find(ids[0]).status);assertEquals(1,service.outbox.findByOrderIdOrderByCreatedAtAsc(ids[0]).size());
    }
    @Test void tcM307ConcurrentScannersCreateOneNotification() throws Exception {
        UUID[] ids=service.tx.execute(tx->{
            Instant now=testClock.instant();RepairOrder order=new RepairOrder(UUID.randomUUID(),UUID.randomUUID(),"并发超时","真实 PostgreSQL 并发扫描",now);service.orders.saveAndFlush(order);
            service.deadline(order,"ASSIGN_OVERDUE",0,order.assignDueAt,now);
            DeadlineTask task=service.deadlines.findAll().stream().filter(t->t.sourceId.equals(order.id)).findFirst().orElseThrow();return new UUID[]{order.id,task.id};
        });testClock.advance(Duration.ofHours(25));
        CyclicBarrier barrier=new CyclicBarrier(8);
        try(ExecutorService pool=Executors.newFixedThreadPool(8)){
            List<Future<?>> futures=new ArrayList<>();for(int i=0;i<8;i++)futures.add(pool.submit(()->{barrier.await();worker.runDeadline(ids[1]);return null;}));
            for(Future<?> f:futures)f.get(20,TimeUnit.SECONDS);
        }
        assertEquals(1,service.outbox.findByOrderIdOrderByCreatedAtAsc(ids[0]).size());assertEquals(RepairStatus.SUBMITTED,service.find(ids[0]).status);
    }
    @Test void tcX03BusinessRollbackCannotLeakOutbox() {
        UUID id=UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->service.tx.executeWithoutResult(tx->{
            Instant now=testClock.instant();RepairOrder order=new RepairOrder(UUID.randomUUID(),UUID.randomUUID(),"事务回退","回退资源命令",now);order.id=id;order.assignedTo=UUID.randomUUID();order.status=RepairStatus.BLOCK_PENDING;order.resourceVersion=1;service.orders.saveAndFlush(order);service.resourceCommand(order,"BLOCK",order.assignedTo,UUID.randomUUID(),now);service.entityManager.flush();throw new IllegalStateException("intentional test rollback");
        }));
        assertFalse(service.orders.existsById(id));assertTrue(service.outbox.findByOrderIdOrderByCreatedAtAsc(id).isEmpty());
    }
}
