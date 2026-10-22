package com.campus.studyroom.repair;

import com.campus.studyroom.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class RepairController {
    private final RepairService service;
    private final String release;
    private final String commit;
    RepairController(RepairService service,@Value("${studyroom.release-id}")String release,@Value("${studyroom.commit}")String commit){this.service=service;this.release=release;this.commit=commit;}
    private static UUID requestId(HttpServletRequest req){Object value=req.getAttribute("repairRequestId");return value instanceof UUID u?u:UUID.randomUUID();}
    private RepairAccess access(SessionPrincipal principal,HttpServletRequest req){if(principal==null)throw new RepairException(401,"UNAUTHENTICATED","请先登录");return service.access(principal,requestId(req));}
    private static Map<String,Object> envelope(Object data,HttpServletRequest req){return Map.of("data",data,"requestId",requestId(req));}
    @GetMapping({"/version","/repairs/version"}) Map<String,Object> version(HttpServletRequest req){return envelope(Map.of("releaseId",release,"commit",commit,"service","repair-service"),req);}
    @GetMapping("/repairs") Map<String,Object> list(@RequestParam(defaultValue="mine")String view,@RequestParam(required=false)String status,@RequestParam(required=false)UUID roomId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest req){return envelope(service.list(view,status,roomId,page,size,access(principal,req)),req);}
    @GetMapping("/repairs/assignable-workers") Map<String,Object> workers(@RequestParam UUID roomId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest req){return envelope(service.workers(roomId,page,size,access(principal,req)),req);}
    @GetMapping("/repairs/audit-events") Map<String,Object> audits(@RequestParam(required=false)String action,@RequestParam(required=false)UUID objectId,@RequestParam(required=false)UUID roomId,@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest req){return envelope(service.auditList(action,objectId,roomId,from,to,page,size,access(principal,req)),req);}
    @GetMapping("/repairs/{id}") Map<String,Object> detail(@PathVariable UUID id,@AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest req){return envelope(service.detail(id,access(principal,req)),req);}
    @PostMapping("/repairs") ResponseEntity<?> create(@RequestHeader("Idempotency-Key")UUID key,@RequestBody Map<String,Object> body,@AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest req){RepairService.Result result=service.create(RepairService.uuid(body,"roomId"),RepairService.string(body,"title"),RepairService.string(body,"description"),key,body,access(principal,req));return ResponseEntity.status(result.status()).body(envelope(result.data(),req));}
    @PostMapping("/repairs/{id}/{action:assign|start|complete|acceptance|cancel|rating|retry-sync}") ResponseEntity<?> action(@PathVariable UUID id,@PathVariable String action,@RequestHeader("Idempotency-Key")UUID key,@RequestBody Map<String,Object> body,@AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest req){RepairService.Result result=service.action(id,action,key,body,access(principal,req));return ResponseEntity.status(result.status()).body(envelope(result.data(),req));}
}
