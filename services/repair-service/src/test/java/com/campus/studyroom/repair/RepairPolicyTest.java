package com.campus.studyroom.repair;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RepairPolicyTest {
    private final UUID reporter=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID worker=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID admin=UUID.fromString("00000000-0000-0000-0000-000000000003");
    RepairOrder order(RepairStatus state){RepairOrder r=new RepairOrder(reporter,UUID.randomUUID(),"插座故障","靠窗座位插座无电",Instant.parse("2026-10-22T10:00:00Z"));r.status=state;r.assignedTo=worker;return r;}
    @Test void tcM301UnicodeAndTrimmedTextBoundaries(){
        assertEquals("故障",RepairPolicy.text("  故障  ",100,true,"标题"));
        assertThrows(RepairException.class,()->RepairPolicy.text(" \t\n ",100,true,"标题"));
        assertThrows(RepairException.class,()->RepairPolicy.text("字".repeat(101),100,true,"标题"));
        assertDoesNotThrow(()->RepairPolicy.text("🙂".repeat(100),100,true,"标题"));
    }
    @Test void tcM302DeadlinesAreBasedOnOriginalAssignment(){
        Instant now=Instant.parse("2026-10-22T00:00:00Z");RepairOrder r=order(RepairStatus.ASSIGNED);r.firstAssignedAt=now;r.workDueAt=RepairPolicy.workDeadline(now);
        r.assignedAt=now.plusSeconds(3600);r.assignedTo=UUID.randomUUID();
        assertEquals(Instant.parse("2026-10-24T00:00:00Z"),r.workDueAt);
        assertEquals(now.plusSeconds(86400),RepairPolicy.assignmentDeadline(now));
        assertEquals(now.plusSeconds(172800),RepairPolicy.acceptanceDeadline(now));
    }
    @Test void tcM303OnlyAssignedWorkerCanStartAndPendingIsNotRepairing(){
        RepairOrder r=order(RepairStatus.ASSIGNED);Set<String> rights=Set.of("repair:work");
        assertTrue(RepairAccess.actions(r,worker,rights,false).contains("start"));
        assertFalse(RepairAccess.actions(r,admin,rights,false).contains("start"));
        r.status=RepairStatus.BLOCK_PENDING;assertFalse(RepairAccess.actions(r,worker,rights,false).contains("complete"));
        assertFalse(RepairPolicy.expectedResourceState("BLOCK","RELEASED"));
        assertTrue(RepairPolicy.expectedResourceState("BLOCK","ACTIVE"));
    }
    @Test void tcM305SelfRepairCannotBeAcceptedEvenByAdministrator(){
        RepairOrder r=order(RepairStatus.AWAITING_ACCEPTANCE);r.reporterId=worker;
        assertFalse(RepairAccess.actions(r,worker,Set.of("repair:accept_scope"),false).contains("accept"));
        assertThrows(RepairException.class,()->RepairPolicy.independentAcceptor(worker,worker));
        assertTrue(RepairAccess.actions(r,admin,Set.of("repair:accept_scope"),false).contains("accept"));
        assertTrue(RepairPolicy.expectedResourceState("RELEASE","RELEASED"));
        assertFalse(RepairPolicy.expectedResourceState("RELEASE","ACTIVE"));
    }
    @Test void tcM306OnlyClosedUnratedReporterCanRate(){
        RepairOrder r=order(RepairStatus.IN_REPAIR);assertFalse(RepairAccess.actions(r,reporter,Set.of(),false).contains("rate"));
        r.status=RepairStatus.CLOSED;assertTrue(RepairAccess.actions(r,reporter,Set.of(),false).contains("rate"));
        assertFalse(RepairAccess.actions(r,reporter,Set.of(),true).contains("rate"));
        assertFalse(RepairAccess.actions(r,admin,Set.of(),false).contains("rate"));
        assertThrows(RepairException.class,()->RepairPolicy.rating(0));assertThrows(RepairException.class,()->RepairPolicy.rating(6));
    }
    @Test void tcM308CancellationPermissionsFollowExactStates(){
        RepairOrder r=order(RepairStatus.SUBMITTED);assertTrue(RepairAccess.actions(r,reporter,Set.of(),false).contains("cancel"));
        r.status=RepairStatus.ASSIGNED;assertFalse(RepairAccess.actions(r,reporter,Set.of(),false).contains("cancel"));
        assertTrue(RepairAccess.actions(r,admin,Set.of("repair:cancel_scope"),false).contains("cancel"));
        for(RepairStatus state:List.of(RepairStatus.BLOCK_PENDING,RepairStatus.IN_REPAIR,RepairStatus.AWAITING_ACCEPTANCE,RepairStatus.RELEASE_PENDING,RepairStatus.CLOSED,RepairStatus.CANCELLED)){
            r.status=state;assertFalse(RepairAccess.actions(r,admin,Set.of("repair:cancel_scope"),false).contains("cancel"));
        }
    }
    @Test void ownerFinishingActionsSurviveRemovedCreateRole(){
        RepairOrder r=order(RepairStatus.AWAITING_ACCEPTANCE);assertTrue(RepairAccess.actions(r,reporter,Set.of(),false).contains("accept"));
        r.status=RepairStatus.SUBMITTED;assertTrue(RepairAccess.actions(r,reporter,Set.of(),false).contains("cancel"));
    }
    @Test void expectedVersionsCannotBeSilentlyOverwritten(){
        assertThrows(RepairException.class,()->RepairPolicy.requireVersion(2,1L));
        assertThrows(RepairException.class,()->RepairPolicy.requireVersion(2,null));
        assertDoesNotThrow(()->RepairPolicy.requireVersion(2,2L));
    }
    @Test void retriesContinueAfterEightFailuresWithoutChangingCommand(){
        assertEquals(2,RepairPolicy.retrySeconds(1));assertEquals(128,RepairPolicy.retrySeconds(7));
        for(int attempts:List.of(8,9,31,1000))assertEquals(300,RepairPolicy.retrySeconds(attempts));
    }
    @Test void requestCanonicalizationIgnoresJsonKeyOrder(){
        assertEquals(RepairJson.canonical(Map.of("a",1,"b",Map.of("x",2,"y",3))),RepairJson.canonical(Map.of("b",Map.of("y",3,"x",2),"a",1)));
    }
}
