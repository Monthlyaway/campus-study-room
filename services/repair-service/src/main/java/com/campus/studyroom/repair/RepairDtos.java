package com.campus.studyroom.repair;

import java.util.*;

final class RepairDtos {
    private RepairDtos(){}
    static Map<String,Object> fields(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    static Map<String,Object> order(RepairOrder r,List<String> actions){return fields(
        "id",r.id,"reporterId",r.reporterId,"roomId",r.roomId,"title",r.title,"description",r.description,"status",r.status,
        "assignedTo",r.assignedTo,"assignedBy",r.assignedBy,"submittedAt",r.submittedAt,"firstAssignedAt",r.firstAssignedAt,
        "assignedAt",r.assignedAt,"workCompletedAt",r.workCompletedAt,"acceptedAt",r.acceptedAt,"closedAt",r.closedAt,
        "assignDueAt",r.assignDueAt,"workDueAt",r.workDueAt,"acceptDueAt",r.acceptDueAt,"acceptanceRound",r.acceptanceRound,
        "lastWorkNote",r.lastWorkNote,"resourceVersion",r.resourceVersion,"version",r.version,"createdAt",r.createdAt,"updatedAt",r.updatedAt,"allowedActions",actions);}
    static Map<String,Object> history(RepairHistory h){return fields("id",h.id,"fromStatus",h.fromStatus,"toStatus",h.toStatus,"actorId",h.actorId,"reason",h.reason,"oldAssignee",h.oldAssignee,"newAssignee",h.newAssignee,"requestId",h.requestId,"createdAt",h.createdAt);}
    static Map<String,Object> acceptance(RepairAcceptance a){return fields("id",a.id,"roundNo",a.roundNo,"actorId",a.actorId,"decision",a.decision,"comment",a.comment,"createdAt",a.createdAt);}
    static Map<String,Object> rating(RepairRating r){return fields("score",r.score,"comment",r.comment,"createdAt",r.createdAt);}
    static Map<String,Object> audit(RepairAudit a){return fields("id",a.id,"actorType",a.actorType,"actorId",a.actorId,"action",a.action,"objectId",a.objectId,"roomId",a.roomId,"sourceType",a.sourceType,"sourceId",a.sourceId,"before",a.beforeJson,"after",a.afterJson,"reason",a.reason,"requestId",a.requestId,"createdAt",a.createdAt);}
}
