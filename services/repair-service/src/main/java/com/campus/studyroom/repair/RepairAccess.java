package com.campus.studyroom.repair;

import com.campus.studyroom.security.SessionPrincipal;
import java.util.*;

/** Request-scoped decisions only; never cached across requests or persisted in sessions. */
final class RepairAccess {
    final SessionPrincipal principal;
    final UUID requestId;
    private final CoreClient core;
    private final Map<String,Boolean> decisions=new HashMap<>();
    RepairAccess(SessionPrincipal principal,UUID requestId,CoreClient core){this.principal=principal;this.requestId=requestId;this.core=core;}
    boolean permission(String permission,UUID room,UUID owner,UUID worker){
        String key=permission+":"+room+":"+owner+":"+worker;
        return decisions.computeIfAbsent(key,k->core.allowed(principal,permission,room,owner,worker,requestId));
    }
    boolean permission(String permission,RepairOrder order){return permission(permission,order.roomId,order.reporterId,order.assignedTo);}
    boolean own(RepairOrder order){return principal.userId().equals(order.reporterId);}
    boolean assigned(RepairOrder order){return principal.userId().equals(order.assignedTo);}
    boolean read(RepairOrder order){return own(order)||(assigned(order)&&permission("repair:read_assigned",order))||permission("repair:read_scope",order);}
    Set<String> rights(RepairOrder order){
        Set<String> result=new HashSet<>();
        if(own(order)){result.add("own");result.add("read");}
        List<String> required=new ArrayList<>();
        if(order.status==RepairStatus.SUBMITTED||order.status==RepairStatus.ASSIGNED)required.add("repair:assign");
        if(assigned(order)&&(order.status==RepairStatus.ASSIGNED||order.status==RepairStatus.IN_REPAIR))required.add("repair:work");
        if(order.status==RepairStatus.AWAITING_ACCEPTANCE&&!own(order)&&!assigned(order))required.add("repair:accept_scope");
        if((order.status==RepairStatus.SUBMITTED&&!own(order))||order.status==RepairStatus.ASSIGNED)required.add("repair:cancel_scope");
        if(order.status==RepairStatus.BLOCK_PENDING||order.status==RepairStatus.RELEASE_PENDING)required.add("repair:retry_sync");
        for(String permission:required)if(permission(permission,order))result.add(permission);
        if(!own(order)&&assigned(order)&&permission("repair:read_assigned",order))result.add("read");
        if(!own(order)&&permission("repair:read_scope",order)){result.add("repair:read_scope");result.add("read");}
        return result;
    }
    static List<String> actions(RepairOrder order,UUID actor,Set<String> rights,boolean rated){
        List<String> result=new ArrayList<>();boolean own=actor.equals(order.reporterId);boolean worker=actor.equals(order.assignedTo);
        if((order.status==RepairStatus.SUBMITTED||order.status==RepairStatus.ASSIGNED)&&rights.contains("repair:assign"))result.add("assign");
        if(order.status==RepairStatus.ASSIGNED&&worker&&rights.contains("repair:work"))result.add("start");
        if(order.status==RepairStatus.IN_REPAIR&&worker&&rights.contains("repair:work"))result.add("complete");
        if(order.status==RepairStatus.AWAITING_ACCEPTANCE&&!worker&&(own||rights.contains("repair:accept_scope"))){result.add("accept");result.add("reject");}
        if((order.status==RepairStatus.SUBMITTED&&own)||((order.status==RepairStatus.SUBMITTED||order.status==RepairStatus.ASSIGNED)&&rights.contains("repair:cancel_scope")))result.add("cancel");
        if(order.status==RepairStatus.CLOSED&&own&&!rated)result.add("rate");
        if((order.status==RepairStatus.BLOCK_PENDING||order.status==RepairStatus.RELEASE_PENDING)&&rights.contains("repair:retry_sync"))result.add("retry-sync");
        return result;
    }
}
