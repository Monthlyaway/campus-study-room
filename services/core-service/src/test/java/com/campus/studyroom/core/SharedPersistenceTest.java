package com.campus.studyroom.core;

import static org.junit.jupiter.api.Assertions.*;

import com.campus.studyroom.api.*;
import com.campus.studyroom.core.persistence.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SharedPersistenceTest.TimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = "STUDYROOM_CORE_DB_TESTS", matches = "true")
class SharedPersistenceTest {
  @Autowired Events events;
  @Autowired Notifications notifications;
  @Autowired NotificationRepository records;
  @Autowired Idempotency idem;
  @Autowired RateLimits limits;
  @Autowired Store db;
  @Autowired SharedClock clock;
  @Autowired PlatformTransactionManager transactions;
  TransactionTemplate tx;
  UUID user = seed("user:student"), admin = seed("user:admin");

  static UUID seed(String name) {
    return UUID.nameUUIDFromBytes(("studyroom:" + name).getBytes(StandardCharsets.UTF_8));
  }

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    SharedClock testClock() {
      return new SharedClock();
    }
  }

  static class SharedClock extends Clock {
    Instant now = Instant.parse("2035-01-01T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public synchronized Instant instant() {
      return now;
    }

    synchronized void advance(long seconds) {
      now = now.plusSeconds(seconds);
    }
  }

  @BeforeEach
  void setup() {
    tx = new TransactionTemplate(transactions);
  }

  UUID notice() {
    UUID source = UUID.randomUUID();
    events.notify(user, null, "TEST", "M1", source, "0", Map.of("title", "独立通知验收"));
    return Store.id(
        db.need("select id from core.notification where source_id=?1", source).get("id"));
  }

  Map<String, Object> claim(UUID id) {
    return notifications.claim().stream()
        .filter(n -> id.equals(n.get("id")))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void dedupAndTransactionRollback() {
    UUID source = UUID.randomUUID();
    events.notify(user, null, "TEST", "M1", source, "0", Map.of("title", "test"));
    events.notify(user, null, "TEST", "M1", source, "0", Map.of("title", "test"));
    assertEquals(1, db.count("select count(*) from core.notification where source_id=?1", source));
    UUID rolled = UUID.randomUUID();
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.execute(
                status -> {
                  events.notify(user, null, "TEST", "M1", rolled, "0", Map.of());
                  events.audit(user, "ROLLBACK_TEST", "M1", rolled, null, null, "created");
                  throw new IllegalStateException("test rollback");
                }));
    assertEquals(0, db.count("select count(*) from core.notification where source_id=?1", rolled));
    assertEquals(0, db.count("select count(*) from core.audit_event where object_id=?1", rolled));
  }

  @Test
  void expiredLeaseCannotAcknowledgeNewOwnerAndReadDoesNotCancelDelivery() {
    UUID id = notice();
    var first = claim(id);
    var before = records.findById(id).orElseThrow();
    notifications.read(user, id, Map.of("expectedVersion", before.version));
    assertEquals("LEASED", records.findById(id).orElseThrow().status);
    clock.advance(121);
    var second = claim(id);
    assertNotEquals(first.get("leaseOwner"), second.get("leaseOwner"));
    notifications.delivered(id, first.get("leaseOwner").toString(), true);
    assertEquals("LEASED", records.findById(id).orElseThrow().status);
    notifications.delivered(id, second.get("leaseOwner").toString(), true);
    var after = records.findById(id).orElseThrow();
    assertEquals("SENT", after.status);
    assertNotNull(after.readAt);
    assertEquals(1, after.attempts);
  }

  @Test
  void failureBackoffPersistsAndManualRetryReusesRecord() {
    UUID id = notice();
    for (int attempt = 1; attempt <= 8; attempt++) {
      var lease = claim(id);
      notifications.delivered(id, lease.get("leaseOwner").toString(), false);
      var n = records.findById(id).orElseThrow();
      assertEquals(attempt, n.attempts);
      assertEquals("RETRYING", n.status);
      long delay = attempt >= 8 ? 300 : Math.min(1L << attempt, 300);
      assertEquals(clock.instant().plusSeconds(delay), n.nextAttemptAt);
      clock.advance(delay + 1);
    }
    var n = records.findById(id).orElseThrow();
    notifications.retry(admin, id, Map.of("expectedVersion", n.version));
    var after = records.findById(id).orElseThrow();
    assertEquals(clock.instant(), after.nextAttemptAt);
    assertEquals(8, after.attempts);
    assertEquals(1, db.count("select count(*) from core.notification where id=?1", id));
  }

  @Test
  void idempotencyConcurrentReplayAndFailedActionRollback() throws Exception {
    String key = UUID.randomUUID().toString();
    AtomicInteger effects = new AtomicInteger();
    var barrier = new CyclicBarrier(8);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var calls = new ArrayList<Future<Idempotency.Result>>();
      for (int i = 0; i < 8; i++)
        calls.add(
            pool.submit(
                () -> {
                  barrier.await();
                  return idem.execute(
                      user,
                      "POST",
                      "/shared-test",
                      key,
                      Map.of("x", 1),
                      201,
                      () -> {
                        effects.incrementAndGet();
                        return Map.of("id", "same");
                      });
                }));
      for (var call : calls) {
        var result = call.get(20, TimeUnit.SECONDS);
        assertEquals(201, result.status());
        assertEquals(Map.of("id", "same"), result.data());
      }
    }
    assertEquals(1, effects.get());
    assertThrows(
        ApiException.class,
        () -> idem.execute(user, "POST", "/shared-test", key, Map.of("x", 2), 201, Map::of));
    String retry = UUID.randomUUID().toString();
    assertThrows(
        IllegalStateException.class,
        () ->
            idem.execute(
                user,
                "POST",
                "/shared-test",
                retry,
                Map.of(),
                201,
                () -> {
                  throw new IllegalStateException("failed");
                }));
    assertEquals(
        201,
        idem.execute(user, "POST", "/shared-test", retry, Map.of(), 201, () -> Map.of("ok", true))
            .status());
  }

  @Test
  void auditIsAppendOnlyAndJobActorIsDistinguishable() {
    UUID id = UUID.randomUUID();
    events.systemAudit(user, "JOB_TEST", "M1", id, null, null, "done");
    var row = db.need("select * from core.audit_event where object_id=?1", id);
    assertEquals("JOB", row.get("actorType"));
    assertThrows(
        RuntimeException.class,
        () ->
            tx.execute(status -> db.update("delete from core.audit_event where object_id=?1", id)));
    assertEquals(1, db.count("select count(*) from core.audit_event where object_id=?1", id));
  }

  @Test
  void notificationsAreScopedFilteredAndPagedInDatabase() {
    UUID id = notice();
    var page =
        (PageData<?>)
            notifications.list(user, false, Map.of("sourceType", "M1", "status", "PENDING"), 1, 1);
    assertEquals(1, page.items().size());
    assertTrue(page.total() >= 1);
    var denied =
        assertThrows(ApiException.class, () -> notifications.list(user, true, Map.of(), 1, 20));
    assertEquals(403, denied.status);
    var old = records.findById(id).orElseThrow();
    assertThrows(
        ApiException.class,
        () -> notifications.read(admin, id, Map.of("expectedVersion", old.version)));
  }

  @Test
  void rateLimitSurvivesIndependentTransactionsAndExpires() {
    String key = "login:test:" + UUID.randomUUID();
    for (int i = 0; i < 5; i++) limits.loginFailure(key);
    assertEquals(429, assertThrows(ApiException.class, () -> limits.checkLogin(key)).status);
    clock.advance(301);
    assertDoesNotThrow(() -> limits.checkLogin(key));
    limits.clear(key);
    UUID actor = UUID.randomUUID();
    for (int i = 0; i < 5; i++) limits.checkIn(actor);
    assertEquals(429, assertThrows(ApiException.class, () -> limits.checkIn(actor)).status);
    clock.advance(61);
    assertDoesNotThrow(() -> limits.checkIn(actor));
  }
}
