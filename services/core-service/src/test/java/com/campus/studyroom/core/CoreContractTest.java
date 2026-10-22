package com.campus.studyroom.core;

import static org.junit.jupiter.api.Assertions.*;

import com.campus.studyroom.api.*;
import com.campus.studyroom.core.persistence.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CoreContractTest.TimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = "STUDYROOM_CONTRACT_DB_TESTS", matches = "true")
class CoreContractTest {
  static final Instant BASE = Instant.parse("2031-01-01T00:00:00Z");
  static final String FIXTURE_PASSWORD = "MatrixFixture123!";
  @Autowired AdminService admin;
  @Autowired BookingService bookings;
  @Autowired ApplicationService applications;
  @Autowired ResourceService resources;
  @Autowired QueryService queries;
  @Autowired CheckinCodes codes;
  @Autowired Store db;
  @Autowired TaskExecutor taskExecutor;
  @Autowired AccountRepository accounts;
  @Autowired ReservationRepository reservations;
  @Autowired ApplicationRepository applicationRows;
  @Autowired ClaimRepository claims;
  @Autowired MutableClock time;
  @Autowired Environment environment;
  UUID room, seat, user;
  String username;
  Instant start;
  final ContractAssertions contract = new ContractAssertions("core.yaml");

  CoreContractTest() throws Exception {}

  final List<HttpClient> clients = new ArrayList<>();

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    private Instant now = BASE;

    synchronized void set(Instant value) {
      now = value;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public synchronized Instant instant() {
      return now;
    }
  }

  static UUID seeded(String name) {
    return UUID.nameUUIDFromBytes(("studyroom:" + name).getBytes(StandardCharsets.UTF_8));
  }

  static UUID adminId() {
    return seeded("user:admin");
  }

  static UUID roomAdmin() {
    return seeded("user:room-admin");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  static UUID id(Map<String, Object> value) {
    return Store.id(value.get("id"));
  }

  static long version(Map<String, Object> value) {
    return ((Number) value.get("version")).longValue();
  }

  @BeforeEach
  void fixture() {
    time.set(BASE);
    start = BASE.plusSeconds(3600);
    room = newRoom(null);
    seat = newSeat(room, "01", true);
    var account = newUser("CS");
    user = id(account);
    username = account.get("username").toString();
    bind(user, List.of(binding("STUDENT", Map.of("scopeType", "SELF"))));
  }

  @AfterEach
  void closeClients() {
    clients.forEach(HttpClient::close);
  }

  UUID newRoom(UUID department) {
    var body = new LinkedHashMap<String, Object>();
    body.put("buildingId", seeded("building:LIBRARY"));
    body.put("code", "MX" + UUID.randomUUID().toString().substring(0, 18));
    body.put("name", "边界矩阵教室");
    if (department != null) body.put("departmentId", department);
    UUID id = id(map(admin.createMaster(roomAdmin(), "rooms", body)));
    List<Map<String, Object>> rules = new ArrayList<>();
    for (int day = 1; day <= 7; day++)
      rules.add(Map.of("weekday", day, "startMinute", 0, "endMinute", 1440));
    admin.opening(roomAdmin(), id, Map.of("expectedVersion", 0, "rules", rules));
    return id;
  }

  UUID newSeat(UUID targetRoom, String code, boolean power) {
    return id(
        map(
            admin.createMaster(
                roomAdmin(),
                "seats",
                Map.of("roomId", targetRoom, "code", code, "hasPower", power))));
  }

  Map<String, Object> newUser(String department) {
    String unique = "matrix-" + UUID.randomUUID();
    return map(
        admin.createUser(
            adminId(),
            Map.of(
                "username",
                unique,
                "displayName",
                "矩阵验收用户",
                "schoolNo",
                unique.substring(0, 24),
                "departmentId",
                seeded("department:" + department),
                "password",
                FIXTURE_PASSWORD,
                "email",
                unique + "@example.invalid")));
  }

  Map<String, Object> binding(String role, Map<String, Object> scope) {
    return Map.of("roleId", seeded("role:" + role), "scopes", List.of(scope));
  }

  void bind(UUID target, List<Map<String, Object>> bindings) {
    admin.roleBindings(
        adminId(),
        target,
        Map.of(
            "expectedVersion",
            accounts.findById(target).orElseThrow().version,
            "bindings",
            bindings));
  }

  Map<String, Object> booking(UUID targetSeat, Instant at, int hours) {
    return Map.of(
        "seatId",
        targetSeat,
        "startAt",
        at.toString(),
        "endAt",
        at.plusSeconds(hours * 3600L).toString());
  }

  Map<String, Object> reserve(UUID who, UUID targetSeat, Instant at, int hours) {
    return map(bookings.create(who, booking(targetSeat, at, hours)));
  }

  Map<String, Object> application(UUID targetRoom, Instant at, int hours) {
    return Map.of(
        "roomId",
        targetRoom,
        "startAt",
        at.toString(),
        "endAt",
        at.plusSeconds(hours * 3600L).toString(),
        "purposeTitle",
        "矩阵验收申请",
        "purposeDescription",
        "边界与权限的真实接口验收");
  }

  Map<String, Object> submit(Instant at, int hours) {
    return map(applications.create(seeded("user:applicant"), application(room, at, hours)));
  }

  Map<String, Object> approve(UUID target, String actor, long expected) {
    return map(
        applications.decision(
            seeded("user:" + actor),
            target,
            Map.of("expectedVersion", expected, "decision", "APPROVE")));
  }

  Map<String, Object> approveBoth(Instant at, int hours) {
    var a = submit(at, hours);
    approve(id(a), "dept-approver", version(a));
    return approve(id(a), "space-approver", 1);
  }

  record Reply(int status, Map<String, Object> body) {
    Map<String, Object> data() {
      return map(body.get("data"));
    }

    String code() {
      return map(body.get("error")).get("code").toString();
    }
  }

  class Api {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client;
    String csrf;

    Api() throws Exception {
      client =
          HttpClient.newBuilder()
              .cookieHandler(cookies)
              .connectTimeout(Duration.ofSeconds(5))
              .build();
      clients.add(client);
      csrf = call("GET", "/auth/csrf", null, null).data().get("csrfToken").toString();
    }

    Api(String login, String password) throws Exception {
      this();
      assertEquals(
          200,
          call("POST", "/auth/login", Map.of("username", login, "password", password), null)
              .status());
      csrf = call("GET", "/auth/csrf", null, null).data().get("csrfToken").toString();
    }

    String cookie() {
      return cookies.getCookieStore().getCookies().stream()
          .map(c -> c.getName() + "=" + c.getValue())
          .collect(Collectors.joining("; "));
    }

    Reply call(String method, String path, Object body, String key) throws Exception {
      return send(client, method, path, body, key, null);
    }

    Reply send(
        HttpClient transport, String method, String path, Object body, String key, String cookie)
        throws Exception {
      var builder =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://localhost:"
                          + environment.getProperty("local.server.port")
                          + "/api/v1"
                          + path))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", key == null ? UUID.randomUUID().toString() : key);
      if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
      if (cookie != null) builder.header("Cookie", cookie);
      builder.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(Store.json(body)));
      var response = transport.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      var reply =
          new Reply(
              response.statusCode(),
              response.body().isBlank()
                  ? Map.of()
                  : map(Store.JSON.readValue(response.body(), Object.class)));
      contract.response(method, "/api/v1" + path, reply.status(), reply.body());
      return reply;
    }

    Map<String, Object> good(String method, String path, Object body, int status) throws Exception {
      var result = call(method, path, body, null);
      assertEquals(status, result.status(), method + " " + path + " " + result.body());
      return result.data();
    }
  }

  Api student() throws Exception {
    return new Api(username, FIXTURE_PASSWORD);
  }

  Api seededApi(String login) throws Exception {
    return new Api(login, Objects.requireNonNull(System.getenv("DEMO_PASSWORD")));
  }

  void expect(Reply reply, int status, String code) {
    assertEquals(status, reply.status());
    assertEquals(code, reply.code());
  }

  @Test
  void roomDepartmentFilterAppliesBeforePageAndTotal() throws Exception {
    UUID cs = seeded("department:CS");
    UUID expected = newRoom(cs);
    UUID foreign = newRoom(seeded("department:ART"));
    Api actor = seededApi("room-admin");
    var result = actor.good("GET", "/rooms?departmentId=" + cs + "&size=1", null, 200);
    assertEquals(
        db.count("select count(*) from core.room where department_id=?1", cs),
        ((Number) result.get("total")).longValue());
    for (Object item : (List<?>) result.get("items"))
      assertEquals(cs, Store.id(map(item).get("departmentId")));
    var combined =
        actor.good(
            "GET",
            "/rooms?departmentId=" + cs + "&buildingId=" + seeded("building:LIBRARY") + "&size=100",
            null,
            200);
    var entries = ((List<?>) combined.get("items")).stream().map(CoreContractTest::map).toList();
    assertTrue(entries.stream().anyMatch(x -> id(x).equals(expected)));
    assertFalse(entries.stream().anyMatch(x -> id(x).equals(foreign) || id(x).equals(room)));
  }

  @Test
  void fractionalVersionCannotMutateReservation() throws Exception {
    var original = reserve(user, seat, start, 1);
    var before = db.need("select * from core.reservation where id=?1", id(original));
    Reply response =
        student()
            .call(
                "POST",
                "/reservations/" + id(original) + "/cancel",
                Map.of("expectedVersion", 0.9),
                null);
    assertEquals(400, response.status());
    assertEquals("INVALID_VERSION", response.code());
    assertEquals(before, db.need("select * from core.reservation where id=?1", id(original)));
    assertNull(claims.findBySourceTypeAndSourceId("M1", id(original)).orElseThrow().releasedAt);
  }

  @Test
  void passwordResetHasNoContentAndReplayDoesNotInvalidateFreshSession() throws Exception {
    Api target = student();
    Api actor = seededApi("admin");
    String key = UUID.randomUUID().toString();
    var body =
        Map.of(
            "expectedVersion",
            accounts.findById(user).orElseThrow().version,
            "newPassword",
            "ContractFixture456!");
    var first = actor.call("POST", "/users/" + user + "/reset-password", body, key);
    assertEquals(204, first.status());
    assertTrue(first.body().isEmpty());
    assertEquals(401, target.call("GET", "/auth/me", null, null).status());
    Api fresh = new Api(username, "ContractFixture456!");
    long version = accounts.findById(user).orElseThrow().authVersion;
    var replay = actor.call("POST", "/users/" + user + "/reset-password", body, key);
    assertEquals(204, replay.status());
    assertTrue(replay.body().isEmpty());
    assertEquals(version, accounts.findById(user).orElseThrow().authVersion);
    assertEquals(200, fresh.call("GET", "/auth/me", null, null).status());
  }

  @Test
  void publicReadAndBusinessResponseShapesMatchTheContract() throws Exception {
    Api system = seededApi("admin");
    for (String route :
        List.of(
            "/users?q=" + username,
            "/roles",
            "/permissions",
            "/system-config",
            "/audit-events?objectId=" + user)) system.good("GET", route, null, 200);
    Api manager = seededApi("room-admin");
    for (String route :
        List.of(
            "/departments",
            "/campuses",
            "/buildings",
            "/rooms",
            "/rooms/" + room,
            "/rooms/" + room + "/seats",
            "/rooms/" + room + "/display-code")) manager.good("GET", route, null, 200);
    Api student = student();
    String period = "startAt=" + start + "&endAt=" + start.plusSeconds(3600);
    student.good("GET", "/availability/seats?" + period + "&roomId=" + room, null, 200);
    student.good("GET", "/rooms/" + room + "/availability?" + period, null, 200);
    var order = student.good("POST", "/reservations", booking(seat, start, 1), 201);
    student.good("GET", "/reservations/" + id(order), null, 200);
    student.good("GET", "/reservations", null, 200);
    student.good(
        "POST",
        "/reservations/" + id(order) + "/cancel",
        Map.of("expectedVersion", version(order)),
        200);
    student.good("GET", "/violations", null, 200);
    Api applicant = seededApi("applicant");
    applicant.good("GET", "/availability/rooms?" + period + "&roomId=" + room, null, 200);
    var app = applicant.good("POST", "/room-applications", application(room, start, 1), 201);
    applicant.good("GET", "/room-applications", null, 200);
    applicant.good("GET", "/room-applications/" + id(app), null, 200);
    var first =
        seededApi("dept-approver")
            .good(
                "POST",
                "/room-applications/" + id(app) + "/decisions",
                Map.of("expectedVersion", 0, "decision", "APPROVE"),
                200);
    var approved =
        seededApi("space-approver")
            .good(
                "POST",
                "/room-applications/" + id(app) + "/decisions",
                Map.of("expectedVersion", version(first), "decision", "APPROVE"),
                200);
    manager.good("GET", "/rooms/" + room + "/resource-claims", null, 200);
    var notes = applicant.good("GET", "/notifications?roomId=" + room, null, 200);
    var note = map(((List<?>) notes.get("items")).getFirst());
    applicant.good(
        "POST",
        "/notifications/" + id(note) + "/read",
        Map.of("expectedVersion", version(note)),
        200);
    system.good("GET", "/notification-deliveries?roomId=" + room, null, 200);
    var latest = db.need("select * from core.notification where id=?1", id(note));
    system.good(
        "POST",
        "/notification-deliveries/" + id(note) + "/retry",
        Map.of("expectedVersion", version(latest)),
        202);
    applicant.good(
        "POST",
        "/room-applications/" + id(app) + "/cancel",
        Map.of("expectedVersion", version(approved)),
        200);
    seededApi("operator")
        .good(
            "GET",
            "/statistics/usage?fromDate=2031-01-01&toDate=2031-01-01&roomId=" + room,
            null,
            200);
    system.good("GET", "/audit-events?roomId=" + room, null, 200);
    assertEquals(204, student.call("POST", "/auth/logout", null, null).status());
  }

  Reply internal(String method, String path, Object body) throws Exception {
    String credentials =
        environment.getProperty("studyroom.internal-user")
            + ":"
            + environment.getProperty("studyroom.internal-password");
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://localhost:"
                          + environment.getProperty("local.server.port")
                          + "/internal/v1"
                          + path))
              .header(
                  "Authorization",
                  "Basic "
                      + Base64.getEncoder()
                          .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
              .header("Content-Type", "application/json")
              .header("X-Request-ID", UUID.randomUUID().toString())
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(Store.json(body)))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      var result =
          new Reply(
              response.statusCode(), map(Store.JSON.readValue(response.body(), Object.class)));
      new ContractAssertions("internal-core.yaml")
          .response(method, "/internal/v1" + path, result.status(), result.body());
      return result;
    }
  }

  @Test
  void privateFiveOperationsMatchTheirOwnContractAndDeduplicateCommands() throws Exception {
    // Internal authorization reconstructs its caller timestamp at the real service boundary.
    // Use wall time here; the remaining tests retain deterministic business-time boundaries.
    time.set(Instant.now());
    long authVersion = accounts.findById(user).orElseThrow().authVersion;
    var auth =
        internal(
            "POST",
            "/authorization/check",
            Map.of(
                "userId",
                user,
                "authVersion",
                authVersion,
                "permission",
                "repair:create",
                "roomId",
                room));
    assertEquals(200, auth.status());
    assertEquals(true, auth.data().get("allowed"));
    var invalid =
        internal(
            "POST",
            "/authorization/check",
            Map.of(
                "userId",
                user,
                "authVersion",
                authVersion + 1,
                "permission",
                "repair:create",
                "roomId",
                room));
    assertEquals("INVALID_SESSION", invalid.data().get("reason"));
    assertEquals(200, internal("GET", "/rooms/" + room + "/reference", null).status());
    UUID dispatcher = seeded("user:dispatcher");
    assertEquals(
        200,
        internal(
                "GET",
                "/repair-workers?actorId="
                    + dispatcher
                    + "&authVersion="
                    + accounts.findById(dispatcher).orElseThrow().authVersion
                    + "&roomId="
                    + room,
                null)
            .status());
    UUID source = UUID.randomUUID();
    var command = new LinkedHashMap<String, Object>();
    command.put("commandId", UUID.randomUUID());
    command.put("sourceId", source);
    command.put("sourceType", "M3");
    command.put("sourceVersion", 1);
    command.put("roomId", room);
    command.put("actorId", dispatcher);
    command.put("operation", "BLOCK");
    command.put("occurredAt", time.instant().toString());
    var applied = internal("POST", "/resource-commands", command);
    assertEquals("ACTIVE", applied.data().get("sourceState"));
    assertEquals(applied.data(), internal("POST", "/resource-commands", command).data());
    command.put("commandId", UUID.randomUUID());
    command.put("sourceVersion", 2);
    command.put("operation", "RELEASE");
    assertEquals(
        "RELEASED", internal("POST", "/resource-commands", command).data().get("sourceState"));
    var notification = new LinkedHashMap<String, Object>();
    notification.put("commandId", UUID.randomUUID());
    notification.put("eventType", "ASSIGNED");
    notification.put("eventCycle", UUID.randomUUID().toString());
    notification.put("orderId", source);
    notification.put("roomId", room);
    notification.put("reporterId", user);
    notification.put("assignedTo", seeded("user:repairer"));
    notification.put("acceptanceRound", 0);
    notification.put("payload", Map.of("title", "内部契约验收", "status", "ASSIGNED"));
    var sent = internal("POST", "/notifications", notification);
    assertEquals(200, sent.status());
    assertEquals(sent.data(), internal("POST", "/notifications", notification).data());
    assertEquals(1, db.count("select count(*) from core.notification where source_id=?1", source));
    notification.put("eventCycle", "changed");
    assertEquals(409, internal("POST", "/notifications", notification).status());
  }
}
