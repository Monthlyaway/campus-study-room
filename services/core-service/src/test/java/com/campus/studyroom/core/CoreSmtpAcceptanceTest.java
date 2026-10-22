package com.campus.studyroom.core;

import static org.junit.jupiter.api.Assertions.*;

import com.campus.studyroom.core.persistence.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Actual SMTP sockets and actual Mailpit; only the clock and unavailable SMTP peer are fixtures.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CoreSmtpAcceptanceTest.TimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = "STUDYROOM_SMTP_DB_TESTS", matches = "true")
class CoreSmtpAcceptanceTest {
  @Autowired AdminService admin;
  @Autowired BookingService bookings;
  @Autowired TaskExecutor tasks;
  @Autowired Notifications notifications;
  @Autowired NotificationRepository records;
  @Autowired ReservationRepository reservations;
  @Autowired Store db;
  @Autowired MutableClock clock;
  UUID user, room, reservation;
  String recipient;
  Instant start;
  JavaMailSenderImpl sender;
  Jobs worker;
  Path control;

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    MutableClock smtpClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    volatile Instant now = Instant.parse("2037-01-01T00:50:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }

    void set(Instant value) {
      now = value;
    }

    void advance(long seconds) {
      now = now.plusSeconds(seconds);
    }
  }

  static UUID seeded(String value) {
    return UUID.nameUUIDFromBytes(("studyroom:" + value).getBytes(StandardCharsets.UTF_8));
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  static UUID id(Object value) {
    return Store.id(map(value).get("id"));
  }

  @BeforeEach
  void setup() throws Exception {
    clock.set(Instant.parse("2037-01-01T00:50:00Z"));
    start = Instant.parse("2037-01-01T01:00:00Z");
    String unique = UUID.randomUUID().toString();
    recipient = "smtp-" + unique + "@example.invalid";
    room =
        id(
            admin.createMaster(
                seeded("user:room-admin"),
                "rooms",
                Map.of(
                    "buildingId",
                    seeded("building:LIBRARY"),
                    "code",
                    unique.substring(0, 20),
                    "name",
                    "真实 SMTP 隔离验收教室")));
    List<Map<String, Object>> rules = new ArrayList<>();
    for (int d = 1; d <= 7; d++)
      rules.add(Map.of("weekday", d, "startMinute", 0, "endMinute", 1440));
    admin.opening(seeded("user:room-admin"), room, Map.of("expectedVersion", 0, "rules", rules));
    UUID seat =
        id(
            admin.createMaster(
                seeded("user:room-admin"),
                "seats",
                Map.of("roomId", room, "code", "01", "hasPower", true)));
    user =
        id(
            admin.createUser(
                seeded("user:admin"),
                Map.of(
                    "username",
                    "smtp-" + unique,
                    "displayName",
                    "SMTP 验收用户",
                    "schoolNo",
                    unique.substring(0, 24),
                    "email",
                    recipient,
                    "password",
                    "SmtpFixture123!",
                    "departmentId",
                    seeded("department:CS"))));
    admin.roleBindings(
        seeded("user:admin"),
        user,
        Map.of(
            "expectedVersion",
            0,
            "bindings",
            List.of(Map.of("roleId", seeded("role:STUDENT"), "scopes", List.of()))));
    reservation =
        id(
            bookings.create(
                user,
                Map.of(
                    "seatId",
                    seat,
                    "startAt",
                    start.toString(),
                    "endAt",
                    start.plusSeconds(3600).toString())));
    tasks.due().forEach(tasks::run);
    sender = new JavaMailSenderImpl();
    sender.setHost(System.getenv().getOrDefault("STUDYROOM_SMTP_HOST", "127.0.0.1"));
    sender.setPort(Integer.parseInt(System.getenv().getOrDefault("STUDYROOM_SMTP_PORT", "11025")));
    sender.setDefaultEncoding("UTF-8");
    sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "2000");
    sender.getJavaMailProperties().put("mail.smtp.timeout", "2000");
    sender.getJavaMailProperties().put("mail.smtp.writetimeout", "2000");
    // Spring's scheduled worker remains paused via CONTROL_DIR. This explicit
    // worker runs the exact production sendMail/claim/delivered code on demand.
    worker = new Jobs(tasks, notifications, sender);
    worker.mode = "smtp";
    worker.from = "acceptance@example.invalid";
    control = Files.createTempDirectory("studyroom-smtp-control-");
    worker.control = control.toString();
  }

  @AfterEach
  void cleanup() throws Exception {
    Files.deleteIfExists(control);
  }

  NotificationEntity notice(String event) {
    UUID n =
        Store.id(
            db.need(
                    "select id from core.notification where source_id=?1 and event_type=?2 and"
                        + " recipient_id=?3",
                    reservation,
                    event,
                    user)
                .get("id"));
    return records.findById(n).orElseThrow();
  }

  void unique(String event) {
    assertEquals(
        1,
        db.count(
            "select count(*) from core.notification where source_id=?1 and event_type=?2 and"
                + " recipient_id=?3",
            reservation,
            event,
            user));
  }

  void reservationUnaffected() {
    assertEquals("RESERVED", reservations.findById(reservation).orElseThrow().status);
    assertEquals(
        1,
        db.count(
            "select count(*) from core.resource_claim where source_id=?1 and released_at IS NULL",
            reservation));
  }

  @Test
  void realSmtpEightFailuresContinueAndManualRetryDeliversOriginalReadNotification()
      throws Exception {
    NotificationEntity original = notice("M1_PRE_START");
    int mailpitPort = sender.getPort();
    String mailpitHost = sender.getHost();
    try (UnavailableSmtp unavailable = new UnavailableSmtp()) {
      sender.setHost(InetAddress.getLoopbackAddress().getHostAddress());
      sender.setPort(unavailable.port());
      for (int attempt = 1; attempt <= 8; attempt++) {
        worker.sendMail();
        var current = notice("M1_PRE_START");
        assertEquals(original.id, current.id);
        assertEquals(attempt, current.attempts);
        assertEquals("RETRYING", current.status);
        assertEquals("SMTP_DELIVERY_FAILED", current.lastErrorCode);
        long delay = attempt >= 8 ? 300 : 1L << attempt;
        assertEquals(clock.instant().plusSeconds(delay), current.nextAttemptAt);
        assertNull(current.leaseOwner);
        assertNull(current.sentAt);
        unique("M1_PRE_START");
        reservationUnaffected();
        if (attempt < 8) clock.advance(delay);
      }
      assertEquals(8, unavailable.connections.get());
      var failed = notice("M1_PRE_START");
      var managed =
          (com.campus.studyroom.api.PageData<?>)
              notifications.list(
                  seeded("user:admin"), true, Map.of("roomId", room.toString()), 1, 100);
      var items = managed.items();
      assertTrue(
          items.stream()
              .map(CoreSmtpAcceptanceTest::map)
              .anyMatch(
                  n ->
                      original.id.equals(Store.id(n.get("id")))
                          && ((Number) n.get("attempts")).intValue() == 8));
      notifications.read(user, original.id, Map.of("expectedVersion", failed.version));
      clock.advance(299);
      worker.sendMail();
      assertEquals(8, notice("M1_PRE_START").attempts);
      assertEquals(8, unavailable.connections.get());
      clock.advance(1);
      worker.sendMail();
      var ninth = notice("M1_PRE_START");
      assertEquals(9, ninth.attempts);
      assertEquals(clock.instant().plusSeconds(300), ninth.nextAttemptAt);
      assertNotNull(ninth.readAt);
      notifications.retry(
          seeded("user:admin"), original.id, Map.of("expectedVersion", ninth.version));
      assertEquals(clock.instant(), notice("M1_PRE_START").nextAttemptAt);
      assertEquals(9, notice("M1_PRE_START").attempts);
    }
    sender.setHost(mailpitHost);
    sender.setPort(mailpitPort);
    worker.sendMail();
    var delivered = notice("M1_PRE_START");
    assertEquals(original.id, delivered.id);
    assertEquals("SENT", delivered.status);
    assertEquals(10, delivered.attempts);
    assertNotNull(delivered.readAt);
    assertNotNull(delivered.sentAt);
    assertNull(delivered.lastErrorCode);
    unique("M1_PRE_START");
    reservationUnaffected();
    received("M1_PRE_START");
    worker.sendMail();
    assertEquals(10, notice("M1_PRE_START").attempts);
  }

  @Test
  void allThreeM1ReminderKindsReachRealMailpitWithoutDuplicateBusinessRecords() throws Exception {
    worker.sendMail();
    received("M1_PRE_START");
    assertEquals("SENT", notice("M1_PRE_START").status);
    clock.set(start.plusSeconds(600));
    tasks.due().forEach(tasks::run);
    worker.sendMail();
    received("M1_LATE");
    assertEquals("RESERVED", reservations.findById(reservation).orElseThrow().status);
    clock.set(start.plusSeconds(900));
    tasks.due().forEach(tasks::run);
    worker.sendMail();
    received("NO_SHOW");
    tasks.due().forEach(tasks::run);
    worker.sendMail();
    for (String event : List.of("M1_PRE_START", "M1_LATE", "NO_SHOW")) {
      unique(event);
      assertEquals("SENT", notice(event).status);
      assertEquals(1, notice(event).attempts);
    }
    assertEquals("NO_SHOW", reservations.findById(reservation).orElseThrow().status);
    assertEquals(
        1, db.count("select count(*) from core.violation where reservation_id=?1", reservation));
  }

  void received(String event) throws Exception {
    String base = System.getenv().getOrDefault("STUDYROOM_MAILPIT_URL", "http://127.0.0.1:18025");
    String query = URLEncoder.encode("to:" + recipient, StandardCharsets.UTF_8);
    try (HttpClient http = HttpClient.newHttpClient()) {
      for (int attempt = 0; attempt < 40; attempt++) {
        var response =
            http.send(
                HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/search?query=" + query + "&limit=100"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var result = Store.JSON.readValue(response.body(), Map.class);
        for (Object raw : (List<?>) result.get("messages")) {
          var message = map(raw);
          if (!message.get("Subject").toString().endsWith(event)) continue;
          var content =
              http.send(
                  HttpRequest.newBuilder(URI.create(base + "/api/v1/message/" + message.get("ID")))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, content.statusCode());
          var email = Store.JSON.readValue(content.body(), Map.class);
          if (email.get("Text").toString().contains(reservation.toString())) {
            assertTrue(
                ((List<?>) email.get("To"))
                    .stream()
                        .map(CoreSmtpAcceptanceTest::map)
                        .anyMatch(to -> recipient.equals(to.get("Address"))));
            return;
          }
        }
        Thread.sleep(100);
      }
    }
    fail("Real Mailpit has no matching " + event + " email for " + reservation);
  }

  static class UnavailableSmtp implements AutoCloseable {
    final ServerSocket server;
    final Thread thread;
    final AtomicInteger connections = new AtomicInteger();

    UnavailableSmtp() throws Exception {
      server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
      thread =
          Thread.ofPlatform()
              .daemon()
              .start(
                  () -> {
                    while (!server.isClosed()) {
                      try (var client = server.accept()) {
                        connections.incrementAndGet();
                        client
                            .getOutputStream()
                            .write(
                                "421 SMTP temporarily unavailable\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                        client.getOutputStream().flush();
                      } catch (java.io.IOException e) {
                        if (!server.isClosed()) throw new RuntimeException(e);
                      }
                    }
                  });
    }

    int port() {
      return server.getLocalPort();
    }

    public void close() throws Exception {
      server.close();
      thread.join(2000);
    }
  }
}
