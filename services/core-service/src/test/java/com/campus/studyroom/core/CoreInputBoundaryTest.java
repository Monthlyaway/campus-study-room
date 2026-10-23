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
@Import(CoreInputBoundaryTest.TimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = "STUDYROOM_INPUT_DB_TESTS", matches = "true")
class CoreInputBoundaryTest {
  static final Instant BASE = Instant.parse("2031-01-01T00:00:00Z");
  static final String FIXTURE_PASSWORD = "MatrixFixture123!";
  @Autowired AdminService admin;
  @Autowired Idempotency idempotency;
  @Autowired Events events;
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

  CoreInputBoundaryTest() throws Exception {}

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
  void oldMapIdempotencyRecordsReplayWithUnknownNullAndOffsetFieldsUntouched() throws Exception {
    var original = new LinkedHashMap<String, Object>();
    original.put("seatId", seat.toString().toUpperCase(Locale.ROOT));
    original.put("startAt", "2031-01-01T09:00:00+08:00");
    original.put("endAt", "2031-01-01T10:00:00+08:00");
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("note", null);
    metadata.put("list", Arrays.asList(null, "stable"));
    original.put("extension", metadata);
    String key = UUID.randomUUID().toString();
    var stored =
        idempotency.execute(
            user,
            "POST",
            "/reservations",
            key,
            original,
            201,
            () -> bookings.create(user, original));
    var before = db.need("select * from core.reservation where id=?1", id(map(stored.data())));
    Api caller = student();
    var replay = caller.call("POST", "/reservations", original, key);
    assertEquals(201, replay.status());
    assertEquals(id(map(stored.data())), id(replay.data()));
    assertEquals(before, db.need("select * from core.reservation where id=?1", id(replay.data())));
    assertEquals(
        Idempotency.hash(original),
        db.need(
                "select request_hash from core.idempotency_record where key=?1",
                UUID.fromString(key))
            .get("requestHash"));
    var changed = new LinkedHashMap<>(original);
    changed.remove("extension");
    expect(caller.call("POST", "/reservations", changed, key), 409, "IDEMPOTENCY_KEY_REUSED");
    changed.put("extension", null);
    expect(caller.call("POST", "/reservations", changed, key), 409, "IDEMPOTENCY_KEY_REUSED");
    changed = new LinkedHashMap<>(original);
    changed.put("startAt", start.toString());
    expect(caller.call("POST", "/reservations", changed, key), 409, "IDEMPOTENCY_KEY_REUSED");
    assertEquals(1, db.count("select count(*) from core.reservation where user_id=?1", user));

    var app = submit(start.plusSeconds(7200), 1);
    String decisionKey = UUID.randomUUID().toString();
    var noComment = Map.<String, Object>of("expectedVersion", 0, "decision", "APPROVE");
    var decision =
        idempotency.execute(
            seeded("user:dept-approver"),
            "POST",
            "/room-applications/" + id(app) + "/decisions",
            decisionKey,
            noComment,
            200,
            () -> applications.decision(seeded("user:dept-approver"), id(app), noComment));
    Api approver = seededApi("dept-approver");
    var same =
        approver.call(
            "POST", "/room-applications/" + id(app) + "/decisions", noComment, decisionKey);
    assertEquals(200, same.status());
    assertEquals(version(map(decision.data())), version(same.data()));
    var explicitNull = new LinkedHashMap<>(noComment);
    explicitNull.put("comment", null);
    expect(
        approver.call(
            "POST", "/room-applications/" + id(app) + "/decisions", explicitNull, decisionKey),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    assertEquals(
        1, db.count("select count(*) from core.approval_action where application_id=?1", id(app)));
  }

  @Test
  void m1AndM2RequireOffsetsWholeHoursAndInclusiveFourteenDayEnd() throws Exception {
    bind(
        user,
        List.of(
            binding("STUDENT", Map.of("scopeType", "SELF")),
            binding("ROOM_APPLICANT", Map.of("scopeType", "SELF"))));
    Api caller = student();
    List<Object[]> invalid =
        List.of(
            new Object[] {
              "2031-01-01T09:00:00", start.plusSeconds(3600).toString(), 400, "INVALID_TIME"
            },
            new Object[] {start.toString(), "2031-01-01T10:00:00", 400, "INVALID_TIME"},
            new Object[] {
              start.plusSeconds(1800).toString(),
              start.plusSeconds(5400).toString(),
              422,
              "HOUR_ALIGNMENT_REQUIRED"
            },
            new Object[] {
              start.plusNanos(1).toString(),
              start.plusSeconds(3600).toString(),
              422,
              "HOUR_ALIGNMENT_REQUIRED"
            },
            new Object[] {BASE.toString(), start.toString(), 422, "INVALID_BOOKING_WINDOW"},
            new Object[] {start.toString(), start.toString(), 422, "INVALID_BOOKING_WINDOW"},
            new Object[] {
              BASE.plusSeconds(14 * 86400L).toString(),
              BASE.plusSeconds(14 * 86400L + 3600).toString(),
              422,
              "INVALID_BOOKING_WINDOW"
            });
    for (String route : List.of("/reservations", "/room-applications")) {
      for (Object[] scenario : invalid) {
        var body =
            new LinkedHashMap<>(
                route.equals("/reservations")
                    ? booking(seat, start, 1)
                    : application(room, start, 1));
        body.put("startAt", scenario[0]);
        body.put("endAt", scenario[1]);
        expect(caller.call("POST", route, body, null), (Integer) scenario[2], (String) scenario[3]);
      }
    }
    assertEquals(0, db.count("select count(*) from core.reservation where user_id=?1", user));
    assertEquals(
        0, db.count("select count(*) from core.room_application where applicant_id=?1", user));
    assertEquals(0, db.count("select count(*) from core.resource_claim where room_id=?1", room));
    Instant edge = BASE.plusSeconds(14 * 86400L - 3600);
    var reservation = caller.good("POST", "/reservations", booking(seat, edge, 1), 201);
    assertEquals(BASE.plusSeconds(14 * 86400L), DomainPolicy.time(reservation.get("endAt")));
    caller.good(
        "POST", "/reservations/" + id(reservation) + "/cancel", Map.of("expectedVersion", 0), 200);
    var application = caller.good("POST", "/room-applications", application(room, edge, 1), 201);
    assertEquals(BASE.plusSeconds(14 * 86400L), DomainPolicy.time(application.get("endAt")));
  }

  @Test
  void malformedVersionsStillConsumeCheckinLimitAndNeverMutateState() throws Exception {
    var original = reserve(user, seat, start, 1);
    time.set(start);
    Api caller = student();
    for (Object invalid : List.of(0.5, "0", -1, new java.math.BigInteger("9223372036854775808")))
      expect(
          caller.call(
              "POST",
              "/reservations/" + id(original) + "/check-in",
              Map.of("expectedVersion", invalid, "roomCode", "ignored"),
              null),
          400,
          "INVALID_VERSION");
    expect(
        caller.call(
            "POST",
            "/reservations/" + id(original) + "/check-in",
            Map.of("roomCode", "ignored"),
            null),
        400,
        "INVALID_VERSION");
    expect(
        caller.call(
            "POST",
            "/reservations/" + id(original) + "/check-in",
            Map.of("expectedVersion", 0, "roomCode", codes.get(room).get("code")),
            null),
        429,
        "RATE_LIMITED");
    assertEquals("RESERVED", reservations.findById(id(original)).orElseThrow().status);
    assertEquals(0, reservations.findById(id(original)).orElseThrow().version);
  }

  @Test
  void studentCannotUseAdministrativeReadsOrWritesAndRowsStayUnchanged() throws Exception {
    Api caller = student();
    for (String route :
        List.of(
            "/users",
            "/roles",
            "/permissions",
            "/system-config",
            "/statistics/usage?fromDate=2031-01-01&toDate=2031-01-01",
            "/audit-events",
            "/notification-deliveries",
            "/rooms/" + room + "/resource-claims",
            "/rooms/" + room + "/display-code"))
      expect(caller.call("GET", route, null, null), 403, "FORBIDDEN");
    var userBefore = db.need("select * from core.app_user where id=?1", user);
    var roomBefore = db.need("select * from core.room where id=?1", room);
    var seatBefore = db.need("select * from core.seat where id=?1", seat);
    List<Object[]> denied =
        List.of(
            new Object[] {
              "PATCH",
              "/users/" + user,
              Map.of("expectedVersion", version(userBefore), "enabled", false)
            },
            new Object[] {
              "POST",
              "/users/" + user + "/reset-password",
              Map.of("expectedVersion", version(userBefore), "newPassword", "NeverApplied123!")
            },
            new Object[] {
              "PUT",
              "/users/" + user + "/role-bindings",
              Map.of("expectedVersion", version(userBefore), "bindings", List.of())
            },
            new Object[] {"POST", "/users", Map.of()},
            new Object[] {"POST", "/roles", Map.of()},
            new Object[] {"POST", "/departments", Map.of()},
            new Object[] {"POST", "/campuses", Map.of()},
            new Object[] {"POST", "/buildings", Map.of()},
            new Object[] {"POST", "/rooms", Map.of()},
            new Object[] {
              "PATCH", "/rooms/" + room, Map.of("expectedVersion", 1, "enabled", false)
            },
            new Object[] {"POST", "/rooms/" + room + "/seats", Map.of("code", "DENIED")},
            new Object[] {
              "PATCH", "/seats/" + seat, Map.of("expectedVersion", 0, "enabled", false)
            },
            new Object[] {
              "PUT",
              "/rooms/" + room + "/opening-rules",
              Map.of("expectedVersion", 1, "rules", List.of())
            },
            new Object[] {
              "PATCH", "/system-config", Map.of("expectedVersion", 0, "changes", List.of())
            });
    for (Object[] request : denied)
      expect(
          caller.call((String) request[0], (String) request[1], request[2], null),
          403,
          "FORBIDDEN");
    var extraRole =
        map(
            admin.createRole(
                adminId(), Map.of("code", "DENY_" + UUID.randomUUID(), "name", "拒绝权限矩阵")));
    var roleBefore = db.need("select * from core.role where id=?1", id(extraRole));
    expect(
        caller.call(
            "PUT",
            "/roles/" + id(extraRole) + "/permissions",
            Map.of("expectedVersion", 0, "permissionCodes", List.of()),
            null),
        403,
        "FORBIDDEN");
    expect(
        caller.call("DELETE", "/roles/" + id(extraRole), Map.of("expectedVersion", 0), null),
        403,
        "FORBIDDEN");
    assertEquals(roleBefore, db.need("select * from core.role where id=?1", id(extraRole)));
    for (String[] resource :
        List.of(
            new String[] {"departments", "department", "department:CS"},
            new String[] {"campuses", "campus", "campus:MAIN"},
            new String[] {"buildings", "building", "building:LIBRARY"})) {
      UUID resourceId = seeded(resource[2]);
      var before = db.need("select * from core." + resource[1] + " where id=?1", resourceId);
      expect(
          caller.call(
              "PATCH",
              "/" + resource[0] + "/" + resourceId,
              Map.of("expectedVersion", version(before), "name", "DENIED"),
              null),
          403,
          "FORBIDDEN");
      assertEquals(
          before, db.need("select * from core." + resource[1] + " where id=?1", resourceId));
    }
    assertEquals(userBefore, db.need("select * from core.app_user where id=?1", user));
    assertEquals(roomBefore, db.need("select * from core.room where id=?1", room));
    assertEquals(seatBefore, db.need("select * from core.seat where id=?1", seat));
    assertEquals(
        0, db.count("select count(*) from core.seat where room_id=?1 and code='DENIED'", room));
  }

  @Test
  void roomScopedManagerCannotWriteOrInspectPrivateObjectsInAnotherRoom() throws Exception {
    UUID other = newRoom(null), otherSeat = newSeat(other, "FOREIGN", true);
    var role =
        map(
            admin.createRole(
                adminId(), Map.of("code", "SCOPE_" + UUID.randomUUID(), "name", "范围权限矩阵")));
    admin.rolePermissions(
        adminId(),
        id(role),
        Map.of(
            "expectedVersion",
            0,
            "permissionCodes",
            List.of(
                "room:manage",
                "room:display_code",
                "audit:read",
                "notification:manage",
                "statistics:read")));
    bind(
        user,
        List.of(
            Map.of(
                "roleId",
                id(role),
                "scopes",
                List.of(Map.of("scopeType", "ROOM", "roomId", room)))));
    UUID source = UUID.randomUUID();
    events.notify(user, other, "TEST_SCOPE", "M1", source, "0", Map.of("message", "范围外通知"));
    var note = db.need("select * from core.notification where source_id=?1", source);
    var roomBefore = db.need("select * from core.room where id=?1", other);
    var seatBefore = db.need("select * from core.seat where id=?1", otherSeat);
    Api manager = student();
    for (Object[] command :
        List.of(
            new Object[] {
              "PATCH", "/rooms/" + other, Map.of("expectedVersion", 1, "name", "DENIED")
            },
            new Object[] {"POST", "/rooms/" + other + "/seats", Map.of("code", "DENIED")},
            new Object[] {
              "PATCH", "/seats/" + otherSeat, Map.of("expectedVersion", 0, "enabled", false)
            },
            new Object[] {
              "PUT",
              "/rooms/" + other + "/opening-rules",
              Map.of("expectedVersion", 1, "rules", List.of())
            },
            new Object[] {
              "POST",
              "/notification-deliveries/" + id(note) + "/retry",
              Map.of("expectedVersion", version(note))
            }))
      expect(
          manager.call((String) command[0], (String) command[1], command[2], null),
          403,
          "FORBIDDEN");
    expect(
        manager.call("GET", "/rooms/" + other + "/resource-claims", null, null), 403, "FORBIDDEN");
    expect(manager.call("GET", "/rooms/" + other + "/display-code", null, null), 403, "FORBIDDEN");
    assertEquals(
        0,
        ((Number) manager.good("GET", "/audit-events?roomId=" + other, null, 200).get("total"))
            .longValue());
    assertEquals(
        0,
        ((Number)
                manager
                    .good("GET", "/notification-deliveries?roomId=" + other, null, 200)
                    .get("total"))
            .longValue());
    assertEquals(
        0,
        ((Number)
                manager
                    .good(
                        "GET",
                        "/statistics/usage?fromDate=2031-01-01&toDate=2031-01-01&roomId=" + other,
                        null,
                        200)
                    .get("reservationCount"))
            .longValue());
    assertEquals(roomBefore, db.need("select * from core.room where id=?1", other));
    assertEquals(seatBefore, db.need("select * from core.seat where id=?1", otherSeat));
    assertEquals(note, db.need("select * from core.notification where id=?1", id(note)));
    assertEquals(
        0, db.count("select count(*) from core.seat where room_id=?1 and code='DENIED'", other));
    manager.good("POST", "/rooms/" + room + "/seats", Map.of("code", "ALLOWED"), 201);
  }

  @Test
  void disabledRoomBlocksPendingFinalApprovalAndRepairReleaseNeverEnablesIt() throws Exception {
    var pending = submit(start, 1);
    var first = approve(id(pending), "dept-approver", 0);
    var disabled =
        map(
            admin.updateMaster(
                roomAdmin(), "rooms", room, Map.of("expectedVersion", 1, "enabled", false)));
    var before = db.need("select * from core.room_application where id=?1", id(pending));
    expect(
        seededApi("space-approver")
            .call(
                "POST",
                "/room-applications/" + id(pending) + "/decisions",
                Map.of("expectedVersion", version(first), "decision", "APPROVE"),
                null),
        422,
        "RESOURCE_DISABLED");
    assertEquals(before, db.need("select * from core.room_application where id=?1", id(pending)));
    assertEquals(
        1,
        db.count("select count(*) from core.approval_action where application_id=?1", id(pending)));
    assertEquals(
        0,
        db.count(
            "select count(*) from core.resource_claim where source_type='M2' and source_id=?1",
            id(pending)));
    UUID source = UUID.randomUUID();
    for (int version : List.of(1, 2))
      resources.command(
          Map.of(
              "commandId",
              UUID.randomUUID(),
              "sourceType",
              "M3",
              "sourceId",
              source,
              "sourceVersion",
              version,
              "roomId",
              room,
              "actorId",
              seeded("user:dispatcher"),
              "operation",
              version == 1 ? "BLOCK" : "RELEASE",
              "occurredAt",
              time.instant().toString()));
    var after = db.need("select * from core.room where id=?1", room);
    assertEquals(false, after.get("enabled"));
    assertEquals(version(disabled), version(after));
    var results =
        student()
            .good(
                "GET",
                "/availability/seats?roomId="
                    + room
                    + "&startAt="
                    + start
                    + "&endAt="
                    + start.plusSeconds(3600),
                null,
                200);
    assertEquals(0, ((Number) results.get("total")).longValue());
    assertEquals(before, db.need("select * from core.room_application where id=?1", id(pending)));
  }
}
