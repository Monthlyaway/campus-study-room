package com.campus.studyroom.repair;

import com.campus.studyroom.security.SessionPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class RepairService {
    record Result(int status,Map<String,Object> data){}
    final RepairOrderRepository orders;
    final RepairHistoryRepository histories;
    final RepairAcceptanceRepository acceptances;
    final RepairRatingRepository ratings;
    final OutboxRepository outbox;
    final DeadlineRepository deadlines;
    final RepairAuditRepository audits;
    final IdempotencyRepository idempotency;
    final CoreClient core;
    final RepairJson json;
    final Clock clock;
    final TransactionTemplate tx;
    final EntityManager entityManager;
    RepairService(RepairOrderRepository orders,RepairHistoryRepository histories,RepairAcceptanceRepository acceptances,RepairRatingRepository ratings,
                  OutboxRepository outbox,DeadlineRepository deadlines,RepairAuditRepository audits,IdempotencyRepository idempotency,CoreClient core,RepairJson json,Clock clock,
                  org.springframework.transaction.PlatformTransactionManager manager,EntityManager entityManager){
        this.orders=orders;this.histories=histories;this.acceptances=acceptances;this.ratings=ratings;this.outbox=outbox;this.deadlines=deadlines;this.audits=audits;
        this.idempotency=idempotency;this.core=core;this.json=json;this.clock=clock;this.tx=new TransactionTemplate(manager);this.entityManager=entityManager;
    }
    RepairAccess access(SessionPrincipal principal,UUID requestId){return new RepairAccess(principal,requestId,core);}
    RepairOrder find(UUID id){return orders.findById(id).orElseThrow(RepairException::missing);}
    RepairOrder lock(UUID id){return orders.lock(id).orElseThrow(RepairException::missing);}
    Map<String,Object> detail(UUID id,RepairAccess access){
        RepairOrder order=find(id);if(!access.read(order))throw RepairException.missing();Set<String> rights=access.rights(order);
        return tx.execute(s->detailData(find(id),access.principal.userId(),rights));
    }
    Map<String,Object> detailData(RepairOrder r,UUID actor,Set<String> rights){
        Optional<RepairRating> rating=ratings.findByOrderId(r.id);
        Map<String,Object> result=RepairDtos.order(r,RepairAccess.actions(r,actor,rights,rating.isPresent()));
        result.put("history",histories.findByOrderIdOrderByCreatedAtAscIdAsc(r.id).stream().map(RepairDtos::history).toList());
        result.put("acceptances",acceptances.findByOrderIdOrderByRoundNoAsc(r.id).stream().map(RepairDtos::acceptance).toList());
        result.put("rating",rating.map(RepairDtos::rating).orElse(null));
        List<OutboxCommand> pending=outbox.findByOrderIdOrderByCreatedAtAsc(r.id).stream().filter(c->!c.commandType.equals("NOTIFY")&&!c.status.equals("DONE")).toList();
        OutboxCommand latest=pending.isEmpty()?null:pending.getLast();
        result.put("sync",RepairDtos.fields("pending",latest!=null,"attempts",latest==null?0:latest.attempts,"lastErrorCode",latest==null?null:latest.lastErrorCode,
            "lastAttemptAt",latest==null?null:latest.lastAttemptAt,"nextAttemptAt",latest==null?null:latest.nextAttemptAt,"needsAttention",latest!=null&&latest.attempts>=8));
        return result;
    }
    Map<String,Object> list(String view,String status,UUID roomId,int page,int size,RepairAccess access){
        checkPage(page,size);if(!Set.of("mine","assigned","manage").contains(view))throw new RepairException(400,"INVALID_ARGUMENT","无效的工单视图");
        RepairStatus filter=parseStatus(status);Set<UUID> allowedRooms=new HashSet<>();
        if(view.equals("manage"))for(UUID room:orders.roomIds())if(access.permission("repair:read_scope",room,null,null))allowedRooms.add(room);
        if(view.equals("assigned")&&!access.permission("repair:read_assigned",null,null,access.principal.userId()))throw RepairException.forbidden();
        Page<RepairOrder> found=orders.findAll((root,q,cb)->{
            List<Predicate> ps=new ArrayList<>();
            switch(view){case "mine"->ps.add(cb.equal(root.get("reporterId"),access.principal.userId()));case "assigned"->ps.add(cb.equal(root.get("assignedTo"),access.principal.userId()));default->ps.add(root.get("roomId").in(allowedRooms));}
            if(filter!=null)ps.add(cb.equal(root.get("status"),filter));if(roomId!=null)ps.add(cb.equal(root.get("roomId"),roomId));return cb.and(ps.toArray(Predicate[]::new));
        },PageRequest.of(page-1,size,Sort.by(Sort.Direction.DESC,"submittedAt").and(Sort.by("id"))));
        Set<UUID> ratedIds=new HashSet<>();if(!found.isEmpty())ratings.findByOrderIdIn(found.stream().map(r->r.id).toList()).forEach(r->ratedIds.add(r.orderId));
        List<Map<String,Object>> items=new ArrayList<>();
        for(RepairOrder r:found){Set<String> rights=access.rights(r);items.add(RepairDtos.order(r,RepairAccess.actions(r,access.principal.userId(),rights,ratedIds.contains(r.id))));}
        return RepairDtos.fields("items",items,"page",page,"size",size,"total",found.getTotalElements());
    }
    Map<String,Object> workers(UUID room,int page,int size,RepairAccess access){checkPage(page,size);return core.workers(access.principal,room,page,size,access.requestId);}
    Result create(UUID roomId,String title,String description,UUID key,Map<String,Object> body,RepairAccess access){
        Result replay=cached(key,"/api/v1/repairs",body,access);if(replay!=null)return replay;
        if(!access.permission("repair:create",roomId,null,null))throw RepairException.forbidden();
        Map<String,Object> room=core.room(roomId,access.requestId);if(!Boolean.TRUE.equals(room.get("enabled")))throw new RepairException(422,"ROOM_DISABLED","教室已停用，无法新建报修");
        String cleanTitle=RepairPolicy.text(title,100,true,"标题"),cleanDescription=RepairPolicy.text(description,2000,true,"故障说明");
        return execute(key,"/api/v1/repairs",body,201,access,()->{
            Instant now=clock.instant();RepairOrder order=new RepairOrder(access.principal.userId(),roomId,cleanTitle,cleanDescription,now);orders.saveAndFlush(order);
            history(order,null,"上报故障",null,null,access.principal.userId(),access.requestId,now);
            audit(order,null,"REPAIR_SUBMITTED","上报故障",access.principal.userId(),access.requestId,now,"USER");
            deadline(order,"ASSIGN_OVERDUE",0,order.assignDueAt,now);
            return detailData(order,access.principal.userId(),Set.of("own","read"));
        });
    }
    Result action(UUID id,String action,UUID key,Map<String,Object> body,RepairAccess access){
        Result replay=cached(key,"/api/v1/repairs/"+id+"/"+action,body,access);if(replay!=null)return replay;
        RepairOrder before=find(id);if(!access.read(before))throw RepairException.missing();
        Set<String> rights=access.rights(before);Long expected=expected(body);
        UUID assignee=action.equals("assign")?uuid(body,"assignedTo"):null;
        String assigneeName;
        if(action.equals("assign")){if(!rights.contains("repair:assign"))throw RepairException.forbidden();assigneeName=core.requireWorker(access.principal,before.roomId,assignee,access.requestId);}else assigneeName=null;
        String decision=Objects.toString(body.get("decision"),"");
        UUID currentActor=access.principal.userId();
        switch(action){
            case "start","complete"->{if(!currentActor.equals(before.assignedTo)||!rights.contains("repair:work"))throw RepairException.forbidden();}
            case "acceptance"->{RepairPolicy.independentAcceptor(currentActor,before.assignedTo);if(!currentActor.equals(before.reporterId)&&!rights.contains("repair:accept_scope"))throw RepairException.forbidden();if(!Set.of("ACCEPT","REJECT").contains(decision))throw new RepairException(422,"INVALID_ARGUMENT","验收决定必须为 ACCEPT 或 REJECT");}
            case "cancel"->{if(!currentActor.equals(before.reporterId)&&!rights.contains("repair:cancel_scope"))throw RepairException.forbidden();}
            case "rating"->{if(!currentActor.equals(before.reporterId))throw RepairException.forbidden();}
            case "retry-sync"->{if(!rights.contains("repair:retry_sync"))throw RepairException.forbidden();}
            default->{}
        }
        int status=action.equals("start")||action.equals("retry-sync")||(action.equals("acceptance")&&decision.equals("ACCEPT"))?202:action.equals("rating")?201:200;
        return execute(key,"/api/v1/repairs/"+id+"/"+action,body,status,access,()->{
            RepairOrder r=lock(id);RepairPolicy.requireVersion(r.version,expected);
            // Authorization is fetched before the transaction; reject changed aggregate rather than use stale assignment.
            if(!Objects.equals(r.assignedTo,before.assignedTo)||!Objects.equals(r.reporterId,before.reporterId))throw RepairException.conflict("VERSION_CONFLICT","工单已更新，请刷新");
            RepairStatus original=r.status;Instant now=clock.instant();UUID actor=access.principal.userId();String note=null;UUID previousAssignee=r.assignedTo;UUID historyId;
            List<String> actions=RepairAccess.actions(r,actor,rights,ratings.findByOrderId(r.id).isPresent());
            String needed=action.equals("acceptance")?(decision.equals("ACCEPT")?"accept":"reject"):action.equals("rating")?"rate":action;
            if(!actions.contains(needed)){
                if((action.equals("rating")&&r.status==RepairStatus.CLOSED&&actor.equals(r.reporterId))||Set.of("start","complete","cancel","acceptance","assign","retry-sync").contains(action))
                    throw RepairException.conflict("STATE_CONFLICT","当前状态或权限不允许此操作，请刷新后重试");
                throw RepairException.forbidden();
            }
            switch(action){
                case "assign"->{
                    RepairPolicy.requireState(r.status,RepairStatus.SUBMITTED,RepairStatus.ASSIGNED);r.status=RepairStatus.ASSIGNED;r.assignedTo=assignee;r.assignedToName=assigneeName;r.assignedBy=actor;r.assignedAt=now;
                    if(r.firstAssignedAt==null){r.firstAssignedAt=now;r.workDueAt=RepairPolicy.workDeadline(now);deadline(r,"WORK_OVERDUE",0,r.workDueAt,now);}
                    note="派发维修任务";
                }
                case "start"->{RepairPolicy.requireState(r.status,RepairStatus.ASSIGNED);r.status=RepairStatus.BLOCK_PENDING;r.resourceVersion=1;resourceCommand(r,"BLOCK",actor,access.requestId,now);note="请求封禁教室，等待资源服务确认";}
                case "complete"->{RepairPolicy.requireState(r.status,RepairStatus.IN_REPAIR);note=RepairPolicy.text(string(body,"workNote"),2000,true,"完工说明");r.lastWorkNote=note;r.status=RepairStatus.AWAITING_ACCEPTANCE;r.workCompletedAt=now;r.acceptDueAt=RepairPolicy.acceptanceDeadline(now);r.acceptanceRound++;deadline(r,"ACCEPT_OVERDUE",r.acceptanceRound,r.acceptDueAt,now);notify(r,"AWAITING_ACCEPTANCE",Integer.toString(r.acceptanceRound),access.requestId,now);}
                case "acceptance"->{
                    RepairPolicy.requireState(r.status,RepairStatus.AWAITING_ACCEPTANCE);RepairPolicy.independentAcceptor(actor,r.assignedTo);
                    if(!Set.of("ACCEPT","REJECT").contains(decision))throw new RepairException(422,"INVALID_ARGUMENT","验收决定必须为 ACCEPT 或 REJECT");
                    note=RepairPolicy.text(string(body,"comment"),500,decision.equals("REJECT"),"验收说明");
                    RepairAcceptance a=new RepairAcceptance();a.id=UUID.randomUUID();a.orderId=r.id;a.roundNo=r.acceptanceRound;a.actorId=actor;a.decision=decision;a.comment=note;a.createdAt=now;acceptances.save(a);
                    if(decision.equals("ACCEPT")){r.status=RepairStatus.RELEASE_PENDING;r.acceptedAt=now;r.resourceVersion=2;resourceCommand(r,"RELEASE",actor,access.requestId,now);}else r.status=RepairStatus.IN_REPAIR;
                }
                case "cancel"->{RepairPolicy.requireState(r.status,RepairStatus.SUBMITTED,RepairStatus.ASSIGNED);boolean own=actor.equals(r.reporterId)&&r.status==RepairStatus.SUBMITTED;note=RepairPolicy.text(string(body,"reason"),500,!own,"取消原因");r.status=RepairStatus.CANCELLED;notify(r,"CANCELLED","0",access.requestId,now);}
                case "rating"->{
                    RepairPolicy.requireState(r.status,RepairStatus.CLOSED);Integer score=integer(body,"score");RepairPolicy.rating(score);note=RepairPolicy.text(string(body,"comment"),500,false,"评价说明");
                    if(ratings.findByOrderId(r.id).isPresent())throw RepairException.conflict("ALREADY_RATED","每张工单只能评价一次");
                    RepairRating rating=new RepairRating();rating.id=UUID.randomUUID();rating.orderId=r.id;rating.reporterId=actor;rating.score=score;rating.comment=note;rating.createdAt=now;ratings.save(rating);
                }
                case "retry-sync"->{
                    for(OutboxCommand c:outbox.findByOrderIdOrderByCreatedAtAsc(r.id))if(!c.commandType.equals("NOTIFY")&&!c.status.equals("DONE")){
                        // Do not steal an active lease: the sender may already be awaiting an HTTP response.
                        OutboxCommand locked=outbox.lock(c.commandId).orElseThrow();locked.nextAttemptAt=now;if(!locked.status.equals("LEASED")||locked.leaseUntil==null||!locked.leaseUntil.isAfter(now)){locked.status="PENDING";locked.leaseOwner=null;locked.leaseUntil=null;}locked.updatedAt=now;
                    }note="手动重试原同步命令";
                }
                default->throw new RepairException(400,"INVALID_ARGUMENT","不支持的工单操作");
            }
            r.updatedAt=now;historyId=history(r,original,note,previousAssignee,r.assignedTo,actor,access.requestId,now);
            if(action.equals("assign"))notify(r,"ASSIGNED",historyId.toString(),access.requestId,now);
            audit(r,original,"REPAIR_"+action.toUpperCase(Locale.ROOT).replace('-','_'),note,actor,access.requestId,now,"USER");
            orders.saveAndFlush(r);return detailData(r,actor,rights);
        });
    }
    Result cached(UUID key,String path,Map<String,Object> body,RepairAccess access){
        if(key==null)throw new RepairException(400,"IDEMPOTENCY_KEY_REQUIRED","需要 Idempotency-Key 请求编号");
        return idempotency.findByActorIdAndMethodAndCanonicalPathAndKey(access.principal.userId(),"POST",path,key).map(r->replay(r,json.hash(body))).orElse(null);
    }
    Result execute(UUID key,String path,Map<String,Object> body,int status,RepairAccess access,Supplier<Map<String,Object>> action){
        if(key==null)throw new RepairException(400,"IDEMPOTENCY_KEY_REQUIRED","需要 Idempotency-Key 请求编号");
        String hash=json.hash(body);UUID actor=access.principal.userId();
        Optional<IdempotencyRecord> old=idempotency.findByActorIdAndMethodAndCanonicalPathAndKey(actor,"POST",path,key);
        if(old.isPresent())return replay(old.get(),hash);
        try{return tx.execute(s->{
            IdempotencyRecord record=new IdempotencyRecord();record.id=UUID.randomUUID();record.actorType="USER";record.actorId=actor;record.method="POST";record.canonicalPath=path;record.key=key;
            record.requestHash=hash;record.httpStatus=0;record.responseDataJson="{}";record.createdAt=clock.instant();record.expiresAt=record.createdAt.plus(Duration.ofHours(24));
            entityManager.persist(record);entityManager.flush();
            Map<String,Object> result=action.get();record.httpStatus=status;record.responseDataJson=json.write(result);entityManager.flush();return new Result(status,result);
        });}catch(DataIntegrityViolationException ex){
            // The failed transaction has ended. Read the winning committed response in a new transaction.
            Optional<IdempotencyRecord> committed=idempotency.findByActorIdAndMethodAndCanonicalPathAndKey(actor,"POST",path,key);
            if(committed.isPresent())return replay(committed.get(),hash);throw ex;
        }
    }
    Result replay(IdempotencyRecord record,String hash){if(!record.requestHash.equals(hash))throw RepairException.conflict("IDEMPOTENCY_KEY_REUSED","该请求编号已用于不同内容");return new Result(record.httpStatus,json.read(record.responseDataJson));}
    UUID history(RepairOrder r,RepairStatus from,String reason,UUID oldAssignee,UUID newAssignee,UUID actor,UUID requestId,Instant now){
        RepairHistory h=new RepairHistory();h.id=UUID.randomUUID();h.orderId=r.id;h.fromStatus=from;h.toStatus=r.status;h.actorId=actor;h.reason=reason;h.oldAssignee=oldAssignee;h.newAssignee=newAssignee;h.requestId=requestId;h.createdAt=now;histories.save(h);return h.id;
    }
    void audit(RepairOrder r,RepairStatus from,String action,String reason,UUID actor,UUID requestId,Instant now,String actorType){
        RepairAudit a=new RepairAudit();a.id=UUID.randomUUID();a.actorType=actorType;a.actorId=actor;a.action=action;a.objectType="REPAIR";a.objectId=r.id;a.roomId=r.roomId;a.sourceType="M3";a.sourceId=r.id;
        a.beforeJson=from==null?null:json.write(Map.of("status",from));a.afterJson=json.write(Map.of("status",r.status,"resourceVersion",r.resourceVersion));a.reason=reason;a.requestId=requestId;a.createdAt=now;audits.save(a);
    }
    void deadline(RepairOrder r,String type,int cycle,Instant due,Instant now){
        String dedup="M3:"+r.id+":"+type+":"+cycle;if(deadlines.existsByDedupKey(dedup))return;
        DeadlineTask t=new DeadlineTask();t.id=UUID.randomUUID();t.taskType="M3_"+type;t.sourceType="M3";t.sourceId=r.id;t.dedupKey=dedup;t.cycle=cycle;t.dueAt=due;t.nextAttemptAt=due;t.status="PENDING";t.createdAt=now;deadlines.save(t);
    }
    void resourceCommand(RepairOrder r,String operation,UUID actor,UUID requestId,Instant now){
        UUID command=UUID.randomUUID();Map<String,Object> payload=RepairDtos.fields("commandId",command,"sourceType","M3","sourceId",r.id,"sourceVersion",r.resourceVersion,"operation",operation,"roomId",r.roomId,"actorId",actor,"occurredAt",now);
        queue(r,command,operation,r.resourceVersion,payload,requestId,now);
    }
    void notify(RepairOrder r,String event,String cycle,UUID requestId,Instant now){
        UUID command=UUID.randomUUID();Map<String,Object> payload=RepairDtos.fields("commandId",command,"eventType",event,"eventCycle",cycle,"orderId",r.id,"roomId",r.roomId,"reporterId",r.reporterId,"assignedTo",r.assignedTo,"acceptanceRound",r.acceptanceRound,
            "payload",RepairDtos.fields("orderId",r.id,"title",r.title,"status",r.status,"assignDueAt",r.assignDueAt,"workDueAt",r.workDueAt,"acceptDueAt",r.acceptDueAt));
        queue(r,command,"NOTIFY",0,payload,requestId,now);
    }
    void queue(RepairOrder r,UUID command,String type,long sourceVersion,Map<String,Object> payload,UUID requestId,Instant now){
        OutboxCommand c=new OutboxCommand();c.commandId=command;c.orderId=r.id;c.commandType=type;c.sourceVersion=sourceVersion;c.payloadJson=json.write(payload);c.payloadHash=json.hash(payload);c.status="PENDING";c.nextAttemptAt=now;c.requestId=requestId;c.createdAt=c.updatedAt=now;outbox.save(c);
    }
    Map<String,Object> auditList(String action,UUID objectId,UUID roomId,Instant from,Instant to,int page,int size,RepairAccess access){
        checkPage(page,size);Set<UUID> rooms=new HashSet<>();for(UUID room:orders.roomIds())if(access.permission("audit:read",room,null,null))rooms.add(room);
        if(rooms.isEmpty()&&!access.permission("audit:read",null,null,null))throw RepairException.forbidden();
        Page<RepairAudit> result=audits.findAll((root,q,cb)->{List<Predicate> p=new ArrayList<>();p.add(root.get("roomId").in(rooms));if(action!=null)p.add(cb.equal(root.get("action"),action));if(objectId!=null)p.add(cb.equal(root.get("objectId"),objectId));if(roomId!=null)p.add(cb.equal(root.get("roomId"),roomId));if(from!=null)p.add(cb.greaterThanOrEqualTo(root.get("createdAt"),from));if(to!=null)p.add(cb.lessThan(root.get("createdAt"),to));return cb.and(p.toArray(Predicate[]::new));},PageRequest.of(page-1,size,Sort.by(Sort.Direction.DESC,"createdAt")));
        return RepairDtos.fields("items",result.stream().map(RepairDtos::audit).toList(),"page",page,"size",size,"total",result.getTotalElements());
    }
    static void checkPage(int page,int size){if(page<1||size<1||size>100)throw new RepairException(400,"INVALID_ARGUMENT","page 从 1 开始，size 必须为 1–100");}
    static RepairStatus parseStatus(String status){try{return status==null||status.isBlank()?null:RepairStatus.valueOf(status);}catch(Exception ex){throw new RepairException(400,"INVALID_ARGUMENT","无效的工单状态");}}
    static String string(Map<String,Object> body,String key){Object v=body.get(key);if(v==null)return null;if(!(v instanceof String))throw new RepairException(400,"INVALID_ARGUMENT",key+" 必须为文字");return (String)v;}
    static UUID uuid(Map<String,Object> body,String field){try{return UUID.fromString(Objects.toString(body.get(field),""));}catch(Exception e){throw new RepairException(400,"INVALID_ARGUMENT",field+" 必须是 UUID");}}
    static Long expected(Map<String,Object> body){Object v=body.get("expectedVersion");if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<0)throw new RepairException(400,"INVALID_ARGUMENT","expectedVersion 必须是非负整数");return ((Number)v).longValue();}
    static Integer integer(Map<String,Object> body,String field){Object v=body.get(field);if(!(v instanceof Integer))throw new RepairException(422,"INVALID_ARGUMENT",field+" 必须是整数");return (Integer)v;}
}
