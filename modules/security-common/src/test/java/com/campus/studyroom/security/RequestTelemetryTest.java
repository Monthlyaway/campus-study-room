package com.campus.studyroom.security;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.mock.env.MockEnvironment;

class RequestTelemetryTest {
  @AfterEach
  void cleanup() {
    MDC.clear();
  }

  @Test
  void jsonContainsOperationalFieldsAndNeverArbitraryPayloads() {
    var formatter =
        new StudyroomLogFormatter(
            new MockEnvironment()
                .withProperty("spring.application.name", "core-service")
                .withProperty("studyroom.release-id", "r-test"));
    String actor = UUID.randomUUID().toString();
    String request = UUID.randomUUID().toString();
    var input =
        new LoggingEvent(
            getClass().getName(),
            (Logger) LoggerFactory.getLogger(getClass()),
            Level.ERROR,
            "password=raw-secret cookie=session-secret csrf=csrf-secret checkin=123456"
                + " email=person@example.invalid",
            new IllegalStateException("private-message"),
            null);
    input.setMDCPropertyMap(
        Map.of(
            "requestId",
            request,
            "actorId",
            actor,
            "event",
            "notification_retry",
            "errorCode",
            "SMTP_UNAVAILABLE",
            "durationMs",
            "23",
            "password",
            "mdc-secret"));
    String json = formatter.format(input);
    var result = JsonParserFactory.getJsonParser().parseMap(json);
    assertEquals("core-service", result.get("service"));
    assertEquals("r-test", result.get("releaseId"));
    assertEquals(request, result.get("requestId"));
    assertEquals(actor, result.get("actorId"));
    assertEquals("SMTP_UNAVAILABLE", result.get("errorCode"));
    assertEquals(23, ((Number) result.get("durationMs")).intValue());
    assertEquals("java.lang.IllegalStateException", result.get("exceptionType"));
    for (String value :
        new String[] {
          "raw-secret",
          "session-secret",
          "csrf-secret",
          "123456",
          "person@example.invalid",
          "private-message",
          "mdc-secret"
        }) assertFalse(json.contains(value));
    assertTrue(json.endsWith("\n"));
  }

  @Test
  void correlationIsValidatedAndEveryOutcomeRestoresPreviousContext() {
    MDC.put("jobId", "parent-context");
    var invalid = new RequestTelemetry("not-a-uuid\nforged-log");
    assertNotNull(invalid.id());
    assertEquals(invalid.id().toString(), MDC.get("requestId"));
    assertNull(MDC.get("jobId"));
    MDC.put("actorId", UUID.randomUUID().toString());
    invalid.finish(200, false, new RuntimeException("secret"));
    assertEquals(Map.of("jobId", "parent-context"), MDC.getCopyOfContextMap());
    String id = UUID.randomUUID().toString();
    var valid = new RequestTelemetry(id);
    assertEquals(id, valid.id().toString());
    valid.finish(403, true, null);
    assertEquals(Map.of("jobId", "parent-context"), MDC.getCopyOfContextMap());
  }

  @Test
  void legacyWorkerMetadataRemainsQueryableWithoutLeakingErrorText() {
    var formatter = new StudyroomLogFormatter(new MockEnvironment());
    String request = UUID.randomUUID().toString();
    String command = UUID.randomUUID().toString();
    var input =
        new LoggingEvent(
            getClass().getName(),
            (Logger) LoggerFactory.getLogger(getClass()),
            Level.WARN,
            "event=repair_sync_retry commandId={} requestId={} attempts={} errorCode={} detail={}",
            null,
            new Object[] {command, request, 8, "CORE_UNAVAILABLE", "password=never-export"});
    input.setMDCPropertyMap(Map.of("actorId", "person@example.invalid", "durationMs", "bad"));
    String json = formatter.format(input);
    var result = JsonParserFactory.getJsonParser().parseMap(json);
    assertEquals(command, result.get("commandId"));
    assertEquals(request, result.get("requestId"));
    assertEquals("repair_sync_retry", result.get("event"));
    assertEquals("", result.get("actorId"));
    assertFalse(json.contains("never-export"));
    assertFalse(json.contains("person@example.invalid"));
  }
}
