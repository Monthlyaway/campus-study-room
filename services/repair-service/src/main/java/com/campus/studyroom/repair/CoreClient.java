package com.campus.studyroom.repair;

import com.campus.studyroom.security.SessionPrincipal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Real HTTP only: this service has no core JPA entities or core database credentials. */
@Component
class CoreClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String credentials;
    private final RepairJson json;
    CoreClient(@Value("${studyroom.core-url}") String baseUrl, @Value("${studyroom.internal-username:repair-service}") String username,
               @Value("${studyroom.internal-password:}") String password, @Value("${studyroom.internal-password-file:}") String passwordFile, RepairJson json) {
        this.baseUrl = baseUrl.replaceAll("/$", ""); this.json = json;
        try { if (!passwordFile.isBlank()) password = Files.readString(Path.of(passwordFile)).trim(); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Cannot read internal service secret", ex); }
        if (password.isBlank()) throw new IllegalStateException("INTERNAL_PASSWORD or INTERNAL_PASSWORD_FILE is required");
        credentials = Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> request(String method, String path, Map<String,Object> body, UUID requestId) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic "+credentials).header("X-Request-ID", requestId.toString()).header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(body)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String,Object> envelope = json.read(response.body());
            if (response.statusCode() >= 400) {
                Map<String,Object> error = envelope.get("error") instanceof Map<?,?> e ? (Map<String,Object>)e : Map.of();
                throw new RepairException(response.statusCode(), Objects.toString(error.get("code"), "CORE_HTTP_"+response.statusCode()), "依赖服务拒绝请求");
            }
            return envelope.get("data") instanceof Map<?,?> d ? (Map<String,Object>)d : envelope;
        } catch (RepairException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception ex) { throw unavailable(); }
    }
    boolean allowed(SessionPrincipal principal, String permission, UUID roomId, UUID ownerId, UUID assignedTo, UUID requestId) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("userId",principal.userId()); body.put("authVersion",principal.authVersion()); body.put("permission",permission);
        if(roomId!=null) body.put("roomId",roomId); if(ownerId!=null)body.put("ownerId",ownerId); if(assignedTo!=null)body.put("assignedTo",assignedTo);
        Map<String,Object> result;
        try { result = request("POST","/internal/v1/authorization/check",body,requestId); }
        catch (RepairException ex) { throw unavailable(); }
        if ("INVALID_SESSION".equals(result.get("reason"))) throw new RepairException(401,"UNAUTHENTICATED","会话已失效，请重新登录");
        return Boolean.TRUE.equals(result.get("allowed"));
    }
    void validateSession(SessionPrincipal p, UUID requestId) {
        if (!allowed(p,"notification:read_own",null,p.userId(),null,requestId)) throw new RepairException(401,"UNAUTHENTICATED","会话已失效，请重新登录");
    }
    Map<String,Object> room(UUID id, UUID requestId) { return request("GET","/internal/v1/rooms/"+id+"/reference",null,requestId); }
    Map<String,Object> workers(SessionPrincipal p, UUID room, int page, int size, UUID requestId) {
        return request("GET","/internal/v1/repair-workers?actorId="+p.userId()+"&authVersion="+p.authVersion()+"&roomId="+room+"&page="+page+"&size="+size,null,requestId);
    }
    @SuppressWarnings("unchecked")
    void requireWorker(SessionPrincipal p, UUID room, UUID target, UUID requestId) {
        int page=1;
        while(true) {
            Map<String,Object> result = workers(p,room,page,100,requestId);
            List<Map<String,Object>> items=(List<Map<String,Object>>)result.getOrDefault("items",List.of());
            if(items.stream().anyMatch(x->target.toString().equals(Objects.toString(x.get("userId"), ""))))return;
            long total=((Number)result.getOrDefault("total",items.size())).longValue();
            if((long)page*100>=total||items.isEmpty())break; page++;
        }
        throw new RepairException(422,"INVALID_REPAIRER","请选择仍启用且具有维修资格的人员");
    }
    static RepairException unavailable() { return new RepairException(503,"AUTHORIZATION_UNAVAILABLE","授权服务暂时不可用，请稍后重试"); }
}
