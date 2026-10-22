package com.campus.studyroom.repair;

import static org.junit.jupiter.api.Assertions.*;

import com.campus.studyroom.security.SessionPrincipal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** Isolated repair PG, controlled clock, and the real running core HTTP service. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "studyroom.jobs-paused=true")
@Import(RepairDeadlineHttpTest.TimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = "STUDYROOM_REPAIR_DEADLINE_TESTS", matches = "true")
class RepairDeadlineHttpTest {
  @Autowired RepairService service;
  @Autowired RepairWorker worker;
  @Autowired MutableClock clock;
  Map<String, Object> fixture;

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    MutableClock deadlineClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    Instant now = Instant.now();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  RepairAccess actor(String name) {
    var account = map(map(fixture.get("users")).get(name));
    return service.access(
        new SessionPrincipal(
            UUID.fromString(account.get("id").toString()),
            ((Number) account.get("authVersion")).longValue(),
            Instant.now()),
        UUID.randomUUID());
  }

  RepairOrder create(String title) {
    UUID room = UUID.fromString(fixture.get("roomId").toString());
    var body =
        Map.<String, Object>of("roomId", room, "title", title, "description", "三种期限跨服务验收，独立测试时钟");
    var response =
        service.create(
            room,
            title,
            body.get("description").toString(),
            UUID.randomUUID(),
            body,
            actor("reporter"));
    assertEquals(201, response.status());
    return service.find(UUID.fromString(response.data().get("id").toString()));
  }

  void action(String who, RepairOrder order, String action, Map<String, Object> values) {
    var body = new LinkedHashMap<String, Object>(values);
    body.put("expectedVersion", service.find(order.id).version);
    service.action(order.id, action, UUID.randomUUID(), body, actor(who));
  }

  UUID workerId() {
    return UUID.fromString(map(map(fixture.get("users")).get("worker_a")).get("id").toString());
  }

  DeadlineTask task(RepairOrder order, String event) {
    return service.deadlines.findAll().stream()
        .filter(t -> t.sourceId.equals(order.id) && t.taskType.equals("M3_" + event))
        .findFirst()
        .orElseThrow();
  }

  List<OutboxCommand> notices(RepairOrder order, String event) {
    return service.outbox.findByOrderIdOrderByCreatedAtAsc(order.id).stream()
        .filter(
            c ->
                c.commandType.equals("NOTIFY")
                    && event.equals(service.json.read(c.payloadJson).get("eventType")))
        .toList();
  }

  void deliver(RepairOrder order) {
    for (var command : service.outbox.findByOrderIdOrderByCreatedAtAsc(order.id)) {
      worker.deliver(command.commandId);
      var after = service.outbox.findById(command.commandId).orElseThrow();
      assertEquals("DONE", after.status, "Real core HTTP failed: " + after.lastErrorCode);
    }
  }

  void exactDeadline(RepairOrder order, String event, RepairStatus status) {
    var task = task(order, event);
    clock.now = task.dueAt.minusNanos(1);
    worker.runDeadline(task.id);
    assertEquals(0, notices(order, event).size());
    clock.now = task.dueAt;
    for (int i = 0; i < 3; i++) worker.runDeadline(task.id);
    assertEquals(status, service.find(order.id).status);
    assertEquals("DONE", service.deadlines.findById(task.id).orElseThrow().status);
    var notices = notices(order, event);
    assertEquals(1, notices.size());
    deliver(order);
    // A real duplicate HTTP delivery must be idempotent at core as well.
    var command = notices.getFirst();
    service.core.request(
        "POST",
        "/internal/v1/notifications",
        service.json.read(command.payloadJson),
        command.requestId);
    assertEquals(status, service.find(order.id).status);
  }

  Map<String, Object> publicRequest(
      HttpClient client, String method, String path, Map<String, Object> body, String csrf)
      throws Exception {
    String base = System.getenv().getOrDefault("CORE_INTERNAL_URL", "http://localhost:8081");
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
    if (csrf != null)
      request.header("X-CSRF-TOKEN", csrf).header("Idempotency-Key", UUID.randomUUID().toString());
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(service.json.write(body)));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), "Public core assertion failed " + response.body());
    return map(service.json.read(response.body()).get("data"));
  }

  void availabilityAfterTimeout(boolean blocked) throws Exception {
    try (var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build()) {
      var csrf =
          publicRequest(client, "GET", "/api/v1/auth/csrf", null, null).get("csrfToken").toString();
      String username = map(map(fixture.get("users")).get("reporter")).get("username").toString();
      publicRequest(
          client,
          "POST",
          "/api/v1/auth/login",
          Map.of("username", username, "password", System.getenv("DEMO_PASSWORD")),
          csrf);
      var start =
          ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
              .plusDays(1)
              .withHour(10)
              .withMinute(0)
              .withSecond(0)
              .withNano(0);
      String query =
          "/api/v1/availability/seats?roomId="
              + fixture.get("roomId")
              + "&startAt="
              + URLEncoder.encode(start.toOffsetDateTime().toString(), StandardCharsets.UTF_8)
              + "&endAt="
              + URLEncoder.encode(
                  start.plusHours(1).toOffsetDateTime().toString(), StandardCharsets.UTF_8);
      var result = publicRequest(client, "GET", query, null, null);
      assertEquals(
          blocked,
          ((List<?>) result.get("items")).isEmpty(),
          "Actual core M1 availability must follow confirmed resource state");
    }
  }

  void cancelDuringSyncIsForbidden(RepairOrder order, RepairStatus pending) {
    long version = service.find(order.id).version;
    var failure =
        assertThrows(
            RepairException.class,
            () -> action("dispatch", order, "cancel", Map.of("reason", "同步中不能取消")));
    assertEquals(403, failure.status);
    assertEquals("FORBIDDEN", failure.code);
    assertEquals(pending, service.find(order.id).status);
    assertEquals(version, service.find(order.id).version);
  }

  @Test
  void threeDeadlineBoundariesPreserveStateAndDeliverExactlyOnceThroughRealCore() throws Exception {
    Path input = Path.of(System.getenv("STUDYROOM_REPAIR_DEADLINE_FIXTURE"));
    fixture = service.json.read(Files.readString(input));
    var submitted = create("24小时未派单升级");
    var assigned = create("48小时未完工升级");
    var acceptance = create("48小时未验收升级");
    action("dispatch", assigned, "assign", Map.of("assignedTo", workerId()));
    action("dispatch", acceptance, "assign", Map.of("assignedTo", workerId()));
    action("worker_a", acceptance, "start", Map.of());
    assertEquals(RepairStatus.BLOCK_PENDING, service.find(acceptance.id).status);
    cancelDuringSyncIsForbidden(acceptance, RepairStatus.BLOCK_PENDING);
    deliver(acceptance);
    assertEquals(RepairStatus.IN_REPAIR, service.find(acceptance.id).status);
    action("worker_a", acceptance, "complete", Map.of("workNote", "维修完成等待验收"));
    exactDeadline(submitted, "ASSIGN_OVERDUE", RepairStatus.SUBMITTED);
    exactDeadline(assigned, "WORK_OVERDUE", RepairStatus.ASSIGNED);
    exactDeadline(acceptance, "ACCEPT_OVERDUE", RepairStatus.AWAITING_ACCEPTANCE);
    availabilityAfterTimeout(true);
    assertEquals(1, service.find(acceptance.id).resourceVersion);
    assertEquals(
        1,
        service.outbox.findByOrderIdOrderByCreatedAtAsc(acceptance.id).stream()
            .filter(c -> !c.commandType.equals("NOTIFY"))
            .count(),
        "Timeout must not create RELEASE");
    var result =
        Map.of(
            "ASSIGN_OVERDUE",
            submitted.id,
            "WORK_OVERDUE",
            assigned.id,
            "ACCEPT_OVERDUE",
            acceptance.id);
    Files.writeString(input.resolveSibling("deadline-results.json"), service.json.write(result));
    // Cleanup uses only valid business actions; no direct resource deletion.
    action("reporter", submitted, "cancel", Map.of());
    action("dispatch", assigned, "cancel", Map.of("reason", "期限验收完成，未开始维修"));
    action(
        "property", acceptance, "acceptance", Map.of("decision", "ACCEPT", "comment", "独立管理员验收通过"));
    assertEquals(RepairStatus.RELEASE_PENDING, service.find(acceptance.id).status);
    cancelDuringSyncIsForbidden(acceptance, RepairStatus.RELEASE_PENDING);
    deliver(submitted);
    deliver(assigned);
    deliver(acceptance);
    deliver(acceptance);
    assertEquals(RepairStatus.CLOSED, service.find(acceptance.id).status);
    availabilityAfterTimeout(false);
  }
}
