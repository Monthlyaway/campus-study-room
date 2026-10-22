package com.campus.studyroom.repair;

import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RepairWorker {
    private static final Logger log=LoggerFactory.getLogger(RepairWorker.class);
    private final RepairService service;
    private final UUID instanceId=UUID.randomUUID();
    private final boolean paused;
    private final java.nio.file.Path pauseFile;
    private final java.util.concurrent.atomic.AtomicInteger active=new java.util.concurrent.atomic.AtomicInteger();
    RepairWorker(RepairService service,@Value("${studyroom.jobs-paused:false}")boolean paused,@Value("${studyroom.control-dir:/app/control}")String controlDir){this.service=service;this.paused=paused;this.pauseFile=java.nio.file.Path.of(controlDir,"jobs-paused");}
    boolean isPaused(){return paused||java.nio.file.Files.exists(pauseFile);}
    int activeCount(){return active.get();}
    @Scheduled(fixedDelayString="${studyroom.scan-interval-ms:30000}",initialDelayString="${studyroom.scan-initial-delay-ms:1000}")
    public void scan(){
        active.incrementAndGet();
        try {
            if(isPaused())return;
            for(UUID id:service.deadlines.due(service.clock.instant(),PageRequest.of(0,100))){if(isPaused())break;try{runDeadline(id);}catch(Exception ex){log.error("event=repair_deadline_failure taskId={} errorCode={}",id,errorCode(ex));}}
            for(UUID id:service.outbox.due(service.clock.instant(),PageRequest.of(0,100))){if(isPaused())break;try{deliver(id);}catch(Exception ex){log.error("event=repair_delivery_failure commandId={} errorCode={}",id,errorCode(ex));}}
        } finally { active.decrementAndGet(); }
    }
    record Delivery(UUID id,UUID orderId,String type,String payload,UUID requestId,UUID leaseOwner){}
    void deliver(UUID id){
        Delivery delivery=service.tx.execute(status->{
            OutboxCommand c=service.outbox.lock(id).orElse(null);Instant now=service.clock.instant();
            if(c==null||c.status.equals("DONE")||(c.status.equals("LEASED")&&c.leaseUntil!=null&&c.leaseUntil.isAfter(now))||c.nextAttemptAt.isAfter(now))return null;
            c.status="LEASED";c.leaseOwner=instanceId;c.leaseUntil=now.plusSeconds(120);c.lastAttemptAt=now;c.updatedAt=now;
            return new Delivery(c.commandId,c.orderId,c.commandType,c.payloadJson,c.requestId,instanceId);
        });
        if(delivery==null)return;
        // The lease transaction has committed. Neither database nor business locks span network I/O.
        try{
            Map<String,Object> response=service.core.request("POST",delivery.type.equals("NOTIFY")?"/internal/v1/notifications":"/internal/v1/resource-commands",service.json.read(delivery.payload),delivery.requestId);
            if(!delivery.type.equals("NOTIFY")&&!RepairPolicy.expectedResourceState(delivery.type,Objects.toString(response.get("sourceState"),"")))throw new RepairException(409,"SYNC_STATE_CONFLICT","资源来源状态与等待操作不一致");
            service.tx.executeWithoutResult(status->{
                // Keep the same order -> outbox lock order as retry-sync.
                RepairOrder order=service.lock(delivery.orderId);OutboxCommand c=service.outbox.lock(id).orElseThrow();
                if(!c.status.equals("LEASED")||!delivery.leaseOwner.equals(c.leaseOwner))return;
                Instant now=service.clock.instant();RepairStatus previous=order.status;boolean transitioned=false;
                if(c.commandType.equals("BLOCK")){
                    if(order.status!=RepairStatus.BLOCK_PENDING||order.resourceVersion!=1)throw RepairException.conflict("SYNC_STATE_CONFLICT","工单不再等待封禁确认");
                    order.status=RepairStatus.IN_REPAIR;transitioned=true;
                }else if(c.commandType.equals("RELEASE")){
                    if(order.status!=RepairStatus.RELEASE_PENDING||order.resourceVersion!=2)throw RepairException.conflict("SYNC_STATE_CONFLICT","工单不再等待解除确认");
                    order.status=RepairStatus.CLOSED;order.closedAt=now;transitioned=true;service.notify(order,"CLOSED","0",c.requestId,now);
                }
                if(transitioned){order.updatedAt=now;service.history(order,previous,"资源服务确认"+c.commandType,null,null,null,c.requestId,now);service.audit(order,previous,"RESOURCE_"+c.commandType+"_CONFIRMED",null,null,c.requestId,now,"SERVICE");}
                c.status="DONE";c.leaseOwner=null;c.leaseUntil=null;c.lastErrorCode=null;c.updatedAt=now;
            });
            log.info("event=repair_command_delivered commandId={} requestId={} type={}",id,delivery.requestId,delivery.type);
        }catch(Exception ex){
            service.tx.executeWithoutResult(status->{
                OutboxCommand c=service.outbox.lock(id).orElseThrow();if(!delivery.leaseOwner.equals(c.leaseOwner)||c.status.equals("DONE"))return;
                Instant now=service.clock.instant();c.attempts++;c.status="PENDING";c.leaseOwner=null;c.leaseUntil=null;c.lastErrorCode=errorCode(ex);c.nextAttemptAt=now.plusSeconds(RepairPolicy.retrySeconds(c.attempts));c.updatedAt=now;
                if(c.attempts>=8)log.error("event=repair_sync_attention commandId={} orderId={} attempts={} errorCode={}",c.commandId,c.orderId,c.attempts,c.lastErrorCode);
                else log.warn("event=repair_sync_retry commandId={} requestId={} attempts={} errorCode={}",c.commandId,c.requestId,c.attempts,c.lastErrorCode);
            });
        }
    }
    void runDeadline(UUID taskId){
        service.tx.executeWithoutResult(status->{
            DeadlineTask task=service.deadlines.lock(taskId).orElse(null);Instant now=service.clock.instant();
            if(task==null||task.status.equals("DONE")||task.nextAttemptAt.isAfter(now))return;
            RepairOrder order=service.lock(task.sourceId);
            boolean fire=switch(task.taskType){
                case "M3_ASSIGN_OVERDUE"->order.status==RepairStatus.SUBMITTED&&!now.isBefore(order.assignDueAt);
                case "M3_WORK_OVERDUE"->Set.of(RepairStatus.ASSIGNED,RepairStatus.BLOCK_PENDING,RepairStatus.IN_REPAIR).contains(order.status)&&!now.isBefore(order.workDueAt);
                case "M3_ACCEPT_OVERDUE"->order.status==RepairStatus.AWAITING_ACCEPTANCE&&order.acceptanceRound==task.cycle&&!now.isBefore(order.acceptDueAt);
                default->false;
            };
            if(fire){UUID requestId=UUID.randomUUID();String event=task.taskType.substring(3);service.notify(order,event,Integer.toString(task.cycle),requestId,now);service.audit(order,order.status,task.taskType,"期限已到，已创建升级通知",null,requestId,now,"JOB");}
            if(task.taskType.equals("M3_WORK_OVERDUE")&&!fire&&Set.of(RepairStatus.AWAITING_ACCEPTANCE,RepairStatus.RELEASE_PENDING).contains(order.status)){task.nextAttemptAt=now.plusSeconds(30);return;}
            task.status="DONE";task.leaseOwner=null;task.leaseUntil=null;
        });
    }
    private static String errorCode(Exception ex){return ex instanceof RepairException r?r.code:"DELIVERY_FAILED";}
}
