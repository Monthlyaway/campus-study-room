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
@Import(CoreAcceptanceMatrixTest.TimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = "STUDYROOM_MATRIX_DB_TESTS", matches = "true")
class CoreAcceptanceMatrixTest {
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
      return new Reply(
          response.statusCode(),
          response.body().isBlank()
              ? Map.of()
              : map(Store.JSON.readValue(response.body(), Object.class)));
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
  void logoutAndDisableRejectReplayedCookiesWithoutCreatingOrders() throws Exception {
    Api original = student();
    String cookie = original.cookie();
    assertEquals(204, original.call("POST", "/auth/logout", null, null).status());
    var bare = HttpClient.newHttpClient();
    clients.add(bare);
    var replay =
        original.send(bare, "POST", "/reservations", booking(seat, start, 1), null, cookie);
    assertTrue(Set.of(401, 403).contains(replay.status()));
    Api active = student();
    admin.updateUser(
        adminId(),
        user,
        Map.of("expectedVersion", accounts.findById(user).orElseThrow().version, "enabled", false));
    assertEquals(401, active.call("POST", "/reservations", booking(seat, start, 1), null).status());
    Api disabled = new Api();
    expect(
        disabled.call(
            "POST",
            "/auth/login",
            Map.of("username", username, "password", FIXTURE_PASSWORD),
            null),
        401,
        "INVALID_CREDENTIALS");
    assertEquals(0, db.count("select count(*) from core.reservation where user_id=?1", user));
  }

  @Test
  void cancellationChecksOwnerAndExactStartAndReplaysOneTransition() throws Exception {
    var first = reserve(user, seat, start, 1);
    var later = reserve(user, seat, start.plusSeconds(3600), 1);
    Api stranger = seededApi("student2");
    expect(
        stranger.call(
            "POST", "/reservations/" + id(first) + "/cancel", Map.of("expectedVersion", 0), null),
        404,
        "NOT_FOUND");
    assertEquals("RESERVED", reservations.findById(id(first)).orElseThrow().status);
    time.set(start.minusSeconds(1));
    Api owner = student();
    String key = UUID.randomUUID().toString();
    var body = Map.of("expectedVersion", 0);
    var cancelled = owner.call("POST", "/reservations/" + id(first) + "/cancel", body, key);
    assertEquals(200, cancelled.status());
    assertEquals("CANCELLED", cancelled.data().get("status"));
    assertEquals(
        cancelled.data(),
        owner.call("POST", "/reservations/" + id(first) + "/cancel", body, key).data());
    assertEquals(
        1,
        db.count(
            "select count(*) from core.audit_event where object_id=?1 and"
                + " action='RESERVATION_CANCELLED'",
            id(first)));
    assertEquals(
        0, db.count("select count(*) from core.violation where reservation_id=?1", id(first)));
    assertNotNull(claims.findBySourceTypeAndSourceId("M1", id(first)).orElseThrow().releasedAt);
    time.set(start.plusSeconds(3600));
    expect(
        owner.call("POST", "/reservations/" + id(later) + "/cancel", body, null),
        422,
        "CANCELLATION_CLOSED");
    assertEquals("RESERVED", reservations.findById(id(later)).orElseThrow().status);
    assertEquals(0, reservations.findById(id(later)).orElseThrow().version);
  }

  @Test
  void dailyCodesRespectTimeRoomNormalizationPrivacyAndSuccessfulReplay() throws Exception {
    time.set(BASE.minusSeconds(86400));
    String previous = codes.get(room).get("code").toString();
    time.set(BASE);
    UUID otherRoom = newRoom(null);
    String foreign = codes.get(otherRoom).get("code").toString();
    var b = reserve(user, seat, start, 1);
    String current =
        seededApi("room-admin")
            .good("GET", "/rooms/" + room + "/display-code", null, 200)
            .get("code")
            .toString();
    var stored =
        db.need(
            "select code_hash,code_ciphertext from core.room_checkin_code where room_id=?1 and"
                + " local_date=?2",
            room,
            LocalDate.of(2031, 1, 1));
    assertTrue(stored.get("codeHash").toString().startsWith("{bcrypt}$2"));
    assertTrue(stored.get("codeCiphertext").toString().startsWith("v1:"));
    assertFalse(Store.json(stored).contains(current));
    time.set(start.minusSeconds(1));
    Api owner = student();
    expect(
        owner.call(
            "POST",
            "/reservations/" + id(b) + "/check-in",
            Map.of("expectedVersion", 0, "roomCode", current),
            null),
        422,
        "CHECKIN_WINDOW_CLOSED");
    time.set(start);
    for (String bad : List.of(previous, foreign))
      expect(
          owner.call(
              "POST",
              "/reservations/" + id(b) + "/check-in",
              Map.of("expectedVersion", 0, "roomCode", bad),
              null),
          422,
          "INVALID_ROOM_CODE");
    var body =
        Map.of("expectedVersion", 0, "roomCode", "  " + current.toLowerCase(Locale.ROOT) + "  ");
    String key = UUID.randomUUID().toString();
    var checked = owner.call("POST", "/reservations/" + id(b) + "/check-in", body, key);
    assertEquals(200, checked.status());
    assertEquals("CHECKED_IN", checked.data().get("status"));
    assertEquals(
        checked.data(),
        owner.call("POST", "/reservations/" + id(b) + "/check-in", body, key).data());
    assertEquals(
        1,
        db.count(
            "select count(*) from core.audit_event where object_id=?1 and"
                + " action='RESERVATION_CHECKED_IN'",
            id(b)));
    assertEquals(403, owner.call("GET", "/rooms/" + room + "/display-code", null, null).status());
    for (String path :
        List.of(
            "/auth/me",
            "/rooms/" + room,
            "/rooms/" + room + "/seats",
            "/reservations/" + id(b),
            "/notifications")) {
      var reply = owner.call("GET", path, null, null);
      assertEquals(200, reply.status());
      assertFalse(
          Store.json(reply.body()).contains(current),
          "Ordinary response leaked room code: " + path);
      assertFalse(Store.json(reply.body()).contains("codeCiphertext"));
      assertFalse(Store.json(reply.body()).contains("codeHash"));
    }
  }

  @Test
  void sixthCheckinAttemptIs429AndWindowReopensAfterSixtySeconds() throws Exception {
    var b = reserve(user, seat, start, 1);
    codes.get(room);
    time.set(start);
    Api owner = student();
    for (int n = 0; n < 5; n++)
      expect(
          owner.call(
              "POST",
              "/reservations/" + id(b) + "/check-in",
              Map.of("expectedVersion", 0, "roomCode", "INVALID"),
              null),
          422,
          "INVALID_ROOM_CODE");
    expect(
        owner.call(
            "POST",
            "/reservations/" + id(b) + "/check-in",
            Map.of("expectedVersion", 0, "roomCode", "INVALID"),
            null),
        429,
        "RATE_LIMITED");
    assertEquals("RESERVED", reservations.findById(id(b)).orElseThrow().status);
    assertEquals(
        0,
        db.count(
            "select count(*) from core.audit_event where object_id=?1 and"
                + " action='RESERVATION_CHECKED_IN'",
            id(b)));
    time.set(start.plusSeconds(60));
    owner.good(
        "POST",
        "/reservations/" + id(b) + "/check-in",
        Map.of("expectedVersion", 0, "roomCode", codes.get(room).get("code")),
        200);
    var later = reserve(user, seat, start.plusSeconds(3600), 1);
    time.set(start.plusSeconds(4500));
    expect(
        owner.call(
            "POST",
            "/reservations/" + id(later) + "/check-in",
            Map.of("expectedVersion", 0, "roomCode", codes.get(room).get("code")),
            null),
        422,
        "CHECKIN_WINDOW_CLOSED");
    assertEquals("RESERVED", reservations.findById(id(later)).orElseThrow().status);
  }

  @Test
  void disabledHistoricalSeatAndWholeRoomOccupancyAreRecheckedOnRebooking() throws Exception {
    Api owner = student();
    Api manager = seededApi("room-admin");
    var old = owner.good("POST", "/reservations", booking(seat, start, 1), 201);
    expect(
        manager.call(
            "PATCH", "/seats/" + seat, Map.of("expectedVersion", 0, "enabled", false), null),
        409,
        "RESOURCE_IN_USE");
    var cancelled =
        owner.good(
            "POST", "/reservations/" + id(old) + "/cancel", Map.of("expectedVersion", 0), 200);
    var snapshot = db.need("select * from core.reservation where id=?1", id(old));
    var disabled =
        manager.good(
            "PATCH", "/seats/" + seat, Map.of("expectedVersion", 0, "enabled", false), 200);
    expect(
        owner.call("POST", "/reservations", booking(seat, start.plusSeconds(3600), 1), null),
        422,
        "RESOURCE_DISABLED");
    assertEquals("01", owner.good("GET", "/reservations/" + id(old), null, 200).get("seatCode"));
    manager.good(
        "PATCH",
        "/seats/" + seat,
        Map.of("expectedVersion", version(disabled), "enabled", true),
        200);
    var approved = approveBoth(start.plusSeconds(3600), 1);
    expect(
        owner.call("POST", "/reservations", booking(seat, start.plusSeconds(3600), 1), null),
        409,
        "RESERVATION_CONFLICT");
    var renewed =
        owner.good("POST", "/reservations", booking(seat, start.plusSeconds(7200), 1), 201);
    assertNotEquals(id(cancelled), id(renewed));
    assertEquals(snapshot, db.need("select * from core.reservation where id=?1", id(old)));
    assertEquals("APPROVED", applicationRows.findById(id(approved)).orElseThrow().status);
  }

  @Test
  void mixedSeatAvailabilityAndWholeRoomBoundariesCoverNewSeats() throws Exception {
    UUID plain = newSeat(room, "02", false), occupied = newSeat(room, "03", true);
    var neighbor = newUser("CS");
    bind(id(neighbor), List.of(binding("STUDENT", Map.of("scopeType", "SELF"))));
    reserve(id(neighbor), occupied, start, 1);
    var q =
        Map.of(
            "startAt",
            start.toString(),
            "endAt",
            start.plusSeconds(3600).toString(),
            "roomId",
            room.toString(),
            "hasPower",
            "true");
    var available = (PageData<?>) queries.availability(user, q, false, 1, 20);
    assertEquals(1, available.total());
    assertEquals(seat, Store.id(map(available.items().getFirst()).get("id")));
    var grid = map(queries.roomAvailability(user, room, q));
    var rows = (List<?>) grid.get("seats");
    assertEquals(3, rows.size());
    assertFalse(Store.json(grid).contains("userId"));
    assertFalse(Store.json(grid).contains("sourceId"));
    assertEquals(
        "OCCUPIED",
        rows.stream()
            .map(CoreAcceptanceMatrixTest::map)
            .filter(x -> occupied.equals(Store.id(x.get("id"))))
            .findFirst()
            .orElseThrow()
            .get("unavailableReason"));
    var endQ = new LinkedHashMap<>(q);
    endQ.put("startAt", start.plusSeconds(3600).toString());
    endQ.put("endAt", start.plusSeconds(7200).toString());
    assertEquals(2, ((PageData<?>) queries.availability(user, endQ, false, 1, 20)).total());
    var a = approveBoth(start.plusSeconds(3600), 2);
    UUID added = newSeat(room, "04", true);
    Api owner = student();
    for (UUID s : List.of(seat, plain, occupied, added))
      for (int hour : List.of(1, 2))
        expect(
            owner.call(
                "POST", "/reservations", booking(s, start.plusSeconds(hour * 3600L), 1), null),
            409,
            "RESERVATION_CONFLICT");
    var cross = new LinkedHashMap<>(q);
    cross.put("endAt", start.plusSeconds(7200).toString());
    assertEquals(0, ((PageData<?>) queries.availability(user, cross, false, 1, 20)).total());
    owner.good("POST", "/reservations", booking(added, start.plusSeconds(10800), 1), 201);
    applications.cancel(seeded("user:applicant"), id(a), Map.of("expectedVersion", version(a)));
    assertEquals(3, ((PageData<?>) queries.availability(user, endQ, false, 1, 20)).total());
    assertNotNull(claims.findBySourceTypeAndSourceId("M2", id(a)).orElseThrow().releasedAt);
  }

  @Test
  void fourHourExistingOrderSurvivesTwoHourLimitAndAuditKeepsBothValues() throws Exception {
    Api operator = seededApi("admin");
    var config = operator.good("GET", "/system-config", null, 200);
    int original =
        ((List<?>) config.get("items"))
            .stream()
                .map(CoreAcceptanceMatrixTest::map)
                .filter(x -> "M1_MAX_DURATION_HOURS".equals(x.get("key")))
                .mapToInt(x -> ((Number) x.get("value")).intValue())
                .findFirst()
                .orElseThrow();
    long revision = version(config);
    if (original != 4)
      revision =
          version(
              operator.good(
                  "PATCH",
                  "/system-config",
                  Map.of(
                      "expectedVersion",
                      revision,
                      "changes",
                      List.of(Map.of("key", "M1_MAX_DURATION_HOURS", "value", 4))),
                  200));
    try {
      var old = reserve(user, seat, start, 4);
      var originalRow = db.need("select * from core.reservation where id=?1", id(old));
      var previousAuditIds =
          db.list("select id from core.audit_event where action='CONFIG_UPDATED'").stream()
              .map(x -> x.get("id"))
              .collect(Collectors.toSet());
      var changed =
          operator.good(
              "PATCH",
              "/system-config",
              Map.of(
                  "expectedVersion",
                  revision,
                  "changes",
                  List.of(Map.of("key", "M1_MAX_DURATION_HOURS", "value", 2))),
              200);
      revision = version(changed);
      Api owner = student();
      assertEquals(
          422,
          owner
              .call("POST", "/reservations", booking(seat, start.plusSeconds(18000), 3), null)
              .status());
      owner.good("POST", "/reservations", booking(seat, start.plusSeconds(18000), 2), 201);
      assertEquals(originalRow, db.need("select * from core.reservation where id=?1", id(old)));
      for (Object invalid : List.of(0, 13, 1.5))
        expect(
            operator.call(
                "PATCH",
                "/system-config",
                Map.of(
                    "expectedVersion",
                    revision,
                    "changes",
                    List.of(Map.of("key", "M1_MAX_DURATION_HOURS", "value", invalid))),
                null),
            422,
            "INVALID_CONFIG");
      assertEquals(revision, version(operator.good("GET", "/system-config", null, 200)));
      assertEquals(
          403,
          owner
              .call(
                  "PATCH",
                  "/system-config",
                  Map.of(
                      "expectedVersion",
                      revision,
                      "changes",
                      List.of(Map.of("key", "M1_MAX_DURATION_HOURS", "value", 4))),
                  null)
              .status());
      var audits =
          db
              .list(
                  "select id,before_json,after_json,actor_id,created_at from core.audit_event where"
                      + " action='CONFIG_UPDATED' and actor_id=?1 order by created_at desc,id",
                  adminId())
              .stream()
              .filter(x -> !previousAuditIds.contains(x.get("id")))
              .toList();
      assertEquals(1, audits.size());
      var audit = audits.getFirst();
      assertEquals(
          List.of(Map.of("key", "M1_MAX_DURATION_HOURS", "value", 4)),
          audit.get("beforeJson"),
          "Configuration audit must preserve old value");
      assertEquals(
          List.of(Map.of("key", "M1_MAX_DURATION_HOURS", "value", 2)), audit.get("afterJson"));
      assertEquals(adminId(), Store.id(audit.get("actorId")));
      assertEquals(time.instant(), DomainPolicy.time(audit.get("createdAt")));
    } finally {
      var current = operator.good("GET", "/system-config", null, 200);
      operator.good(
          "PATCH",
          "/system-config",
          Map.of(
              "expectedVersion",
              version(current),
              "changes",
              List.of(Map.of("key", "M1_MAX_DURATION_HOURS", "value", original))),
          200);
    }
  }

  void historyRow(
      UUID targetRoom,
      UUID targetSeat,
      Instant at,
      int hours,
      String status,
      Instant checked,
      Instant ended) {
    var r = new ReservationEntity();
    r.id = UUID.randomUUID();
    r.userId = user;
    r.roomId = targetRoom;
    r.seatId = targetSeat;
    r.startAt = at;
    r.endAt = at.plusSeconds(hours * 3600L);
    r.status = status;
    r.checkedInAt = checked;
    r.endedAt = ended;
    r.maxDurationHours = 4;
    r.configVersion = 0;
    r.createdAt = BASE;
    r.updatedAt = BASE;
    if ("CANCELLED".equals(status) || "INTERRUPTED".equals(status)) r.cancelReason = "MAINTENANCE";
    reservations.saveAndFlush(r);
  }

  @Test
  void complexStatisticsMatchHandCalculatedHoursRateAndRoomScope() throws Exception {
    Instant day = LocalDate.of(2031, 1, 2).atStartOfDay(DomainPolicy.ZONE).toInstant();
    historyRow(
        room,
        seat,
        day.plusSeconds(9 * 3600),
        2,
        "COMPLETED",
        day.plusSeconds(9 * 3600),
        day.plusSeconds(11 * 3600));
    historyRow(
        room, seat, day.plusSeconds(10 * 3600), 1, "CANCELLED", null, day.plusSeconds(9 * 3600));
    historyRow(
        room,
        seat,
        day.plusSeconds(11 * 3600),
        1,
        "NO_SHOW",
        null,
        day.plusSeconds(11 * 3600 + 900));
    historyRow(
        room,
        seat,
        day.plusSeconds(12 * 3600),
        2,
        "INTERRUPTED",
        day.plusSeconds(12 * 3600),
        day.plusSeconds(12 * 3600 + 1800));
    historyRow(room, seat, day.plusSeconds(16 * 3600), 2, "RESERVED", null, null);
    historyRow(
        room, seat, day.plusSeconds(14 * 3600), 2, "CHECKED_IN", day.plusSeconds(14 * 3600), null);
    historyRow(
        room,
        seat,
        day.minusSeconds(3600),
        2,
        "COMPLETED",
        day.minusSeconds(3600),
        day.plusSeconds(3600));
    historyRow(
        room, seat, day.plusSeconds(13 * 3600), 1, "CANCELLED", null, day.plusSeconds(12 * 3600));
    UUID outside = newRoom(null), outsideSeat = newSeat(outside, "01", true);
    historyRow(
        outside,
        outsideSeat,
        day.plusSeconds(9 * 3600),
        1,
        "COMPLETED",
        day.plusSeconds(9 * 3600),
        day.plusSeconds(10 * 3600));
    var analyst = newUser("CS");
    bind(id(analyst), List.of(binding("OPERATOR", Map.of("scopeType", "ROOM", "roomId", room))));
    time.set(day.plusSeconds(15 * 3600));
    var api = new Api(analyst.get("username").toString(), FIXTURE_PASSWORD);
    var result =
        api.good("GET", "/statistics/usage?fromDate=2031-01-02&toDate=2031-01-02", null, 200);
    assertEquals(7, ((Number) result.get("reservationCount")).intValue());
    assertEquals(2, ((Number) result.get("cancelledCount")).intValue());
    assertEquals(1, ((Number) result.get("noShowCount")).intValue());
    assertEquals(4, ((Number) result.get("checkinDenominator")).intValue());
    assertEquals(3, ((Number) result.get("checkinNumerator")).intValue());
    assertEquals(.75, ((Number) result.get("checkinRate")).doubleValue(), .00001);
    assertEquals(7.5, ((Number) result.get("reservedSeatHours")).doubleValue(), .00001);
    for (String query :
        List.of(
            "fromDate=2031-01-04&toDate=2031-01-04",
            "fromDate=2031-01-02&toDate=2031-01-02&roomId=" + outside)) {
      var zero = api.good("GET", "/statistics/usage?" + query, null, 200);
      assertEquals(0, ((Number) zero.get("reservationCount")).intValue());
      assertNull(zero.get("checkinRate"));
      assertEquals(0.0, ((Number) zero.get("reservedSeatHours")).doubleValue());
    }
  }

  @Test
  void applicantRoleOverlayEnablesSubmissionReplayAndFreePendingSpace() throws Exception {
    Api before = student();
    var body = application(room, start, 1);
    assertEquals(403, before.call("POST", "/room-applications", body, null).status());
    bind(
        user,
        List.of(
            binding("STUDENT", Map.of("scopeType", "SELF")),
            binding("ROOM_APPLICANT", Map.of("scopeType", "SELF"))));
    assertEquals(401, before.call("GET", "/auth/me", null, null).status());
    Api applicant = student();
    String key = UUID.randomUUID().toString();
    var first = applicant.call("POST", "/room-applications", body, key);
    assertEquals(201, first.status());
    assertEquals(first.data(), applicant.call("POST", "/room-applications", body, key).data());
    assertEquals("PENDING", first.data().get("status"));
    assertEquals(start, DomainPolicy.time(first.data().get("expiresAt")));
    assertEquals(
        1, db.count("select count(*) from core.room_application where applicant_id=?1", user));
    assertTrue(claims.findBySourceTypeAndSourceId("M2", id(first.data())).isEmpty());
    assertEquals(
        1,
        ((PageData<?>)
                queries.availability(
                    user,
                    Map.of(
                        "roomId",
                        room.toString(),
                        "startAt",
                        start.toString(),
                        "endAt",
                        start.plusSeconds(3600).toString()),
                    false,
                    1,
                    20))
            .total());
    var later =
        applicant.good(
            "POST", "/room-applications", application(room, BASE.plusSeconds(2 * 86400), 1), 201);
    assertEquals(BASE.plusSeconds(86400), DomainPolicy.time(later.get("expiresAt")));
  }

  @Test
  void selfApprovalAndSameActorBothStepsFailWhileHistoryStaysPrivate() throws Exception {
    bind(
        user,
        List.of(
            binding("STUDENT", Map.of("scopeType", "SELF")),
            binding("ROOM_APPLICANT", Map.of("scopeType", "SELF")),
            binding("DEPT_APPROVER", Map.of("scopeType", "ALL")),
            binding("SPACE_APPROVER", Map.of("scopeType", "ALL"))));
    Api self = student();
    var own = self.good("POST", "/room-applications", application(room, start, 1), 201);
    expect(
        self.call(
            "POST",
            "/room-applications/" + id(own) + "/decisions",
            Map.of("expectedVersion", 0, "decision", "APPROVE"),
            null),
        403,
        "FORBIDDEN");
    assertEquals(
        0, db.count("select count(*) from core.approval_action where application_id=?1", id(own)));
    var others = submit(start.plusSeconds(3600), 1);
    var first =
        self.good(
            "POST",
            "/room-applications/" + id(others) + "/decisions",
            Map.of("expectedVersion", 0, "decision", "APPROVE"),
            200);
    expect(
        self.call(
            "POST",
            "/room-applications/" + id(others) + "/decisions",
            Map.of("expectedVersion", version(first), "decision", "APPROVE"),
            null),
        403,
        "FORBIDDEN");
    assertEquals(
        1,
        db.count("select count(*) from core.approval_action where application_id=?1", id(others)));
    assertEquals("PENDING", applicationRows.findById(id(others)).orElseThrow().status);
    var rejected =
        seededApi("space-approver")
            .good(
                "POST",
                "/room-applications/" + id(others) + "/decisions",
                Map.of(
                    "expectedVersion",
                    version(first),
                    "decision",
                    "REJECT",
                    "comment",
                    "场地用途需要进一步说明"),
                200);
    assertEquals("REJECTED", rejected.get("status"));
    var history = seededApi("applicant").good("GET", "/room-applications/" + id(others), null, 200);
    assertTrue(Store.json(history.get("actions")).contains("场地用途需要进一步说明"));
    var denied = seededApi("student2").call("GET", "/room-applications/" + id(others), null, null);
    expect(denied, 404, "NOT_FOUND");
    assertFalse(denied.body().containsKey("data"));
    assertFalse(Store.json(denied.body()).contains("purposeDescription"));
    bind(user, List.of());
    Api noRoles = student();
    assertEquals(200, noRoles.call("GET", "/room-applications/" + id(own), null, null).status());
    assertEquals(
        403,
        noRoles
            .call("POST", "/room-applications", application(room, start.plusSeconds(7200), 1), null)
            .status());
  }

  @Test
  void approvedApplicationCannotBeCancelledByOwnerAtStartButScopedManagerCan() throws Exception {
    var a = approveBoth(start, 1);
    time.set(start);
    var owner = seededApi("applicant");
    expect(
        owner.call(
            "POST",
            "/room-applications/" + id(a) + "/cancel",
            Map.of("expectedVersion", version(a)),
            null),
        403,
        "FORBIDDEN");
    assertEquals("APPROVED", applicationRows.findById(id(a)).orElseThrow().status);
    assertNull(claims.findBySourceTypeAndSourceId("M2", id(a)).orElseThrow().releasedAt);
    Api manager = seededApi("space-approver");
    assertEquals(
        422,
        manager
            .call(
                "POST",
                "/room-applications/" + id(a) + "/cancel",
                Map.of("expectedVersion", version(a), "reason", " "),
                null)
            .status());
    var cancelled =
        manager.good(
            "POST",
            "/room-applications/" + id(a) + "/cancel",
            Map.of("expectedVersion", version(a), "reason", "教学活动临时调整"),
            200);
    assertEquals("CANCELLED", cancelled.get("status"));
    assertEquals("教学活动临时调整", cancelled.get("cancelReason"));
    assertNotNull(claims.findBySourceTypeAndSourceId("M2", id(a)).orElseThrow().releasedAt);
  }

  @Test
  void scopedManagerCannotMutateAnotherRoomAndAttachedRoleCannotBeDeleted() throws Exception {
    UUID other = newRoom(null);
    var before = db.need("select * from core.room where id=?1", other);
    var role =
        map(
            admin.createRole(
                adminId(), Map.of("code", "MX_" + UUID.randomUUID(), "name", "单室管理测试")));
    var configured =
        map(
            admin.rolePermissions(
                adminId(),
                id(role),
                Map.of(
                    "expectedVersion", version(role), "permissionCodes", List.of("room:manage"))));
    bind(
        user,
        List.of(
            Map.of(
                "roleId",
                id(role),
                "scopes",
                List.of(Map.of("scopeType", "ROOM", "roomId", room)))));
    Api manager = student();
    expect(
        manager.call(
            "PATCH", "/rooms/" + other, Map.of("expectedVersion", 1, "name", "不应写入"), null),
        403,
        "FORBIDDEN");
    assertEquals(before, db.need("select * from core.room where id=?1", other));
    manager.good("PATCH", "/rooms/" + room, Map.of("expectedVersion", 1, "name", "授权内更名"), 200);
    expect(
        seededApi("admin")
            .call(
                "DELETE",
                "/roles/" + id(role),
                Map.of("expectedVersion", version(configured)),
                null),
        409,
        "ROLE_IN_USE");
  }

  UUID persistedTask(UUID reservation, String type) {
    return Store.id(
        db.need(
                "select id from core.scheduled_task where source_id=?1 and task_type=?2",
                reservation,
                type)
            .get("id"));
  }

  @Test
  void threeReminderStagesArePersistentDeduplicatedAndPrivate() throws Exception {
    var booking = reserve(user, seat, start, 1);
    UUID reservation = id(booking);
    for (var stage :
        List.of(
            Map.entry("M1_PRE_START", start.minusSeconds(900)),
            Map.entry("M1_LATE", start.plusSeconds(600)),
            Map.entry("M1_NO_SHOW", start.plusSeconds(900)))) {
      time.set(stage.getValue());
      for (int n = 0; n < 3; n++) taskExecutor.run(persistedTask(reservation, stage.getKey()));
    }
    assertEquals("NO_SHOW", reservations.findById(reservation).orElseThrow().status);
    assertEquals(
        1, db.count("select count(*) from core.violation where reservation_id=?1", reservation));
    assertNotNull(claims.findBySourceTypeAndSourceId("M1", reservation).orElseThrow().releasedAt);
    var notificationRows =
        db.list(
            "select * from core.notification where source_id=?1 order by event_type", reservation);
    assertEquals(
        List.of("M1_LATE", "M1_PRE_START", "NO_SHOW"),
        notificationRows.stream().map(x -> x.get("eventType")).toList());
    for (var notification : notificationRows) {
      assertEquals(user, Store.id(notification.get("recipientId")));
      assertEquals("EMAIL", notification.get("channel"));
    }
    var target = notificationRows.getFirst();
    Api stranger = seededApi("student2");
    expect(
        stranger.call(
            "POST",
            "/notifications/" + id(target) + "/read",
            Map.of("expectedVersion", version(target)),
            null),
        404,
        "NOT_FOUND");
    assertNull(
        db.need("select read_at from core.notification where id=?1", id(target)).get("readAt"));
    Api owner = student();
    String key = UUID.randomUUID().toString();
    var body = Map.of("expectedVersion", version(target));
    var read = owner.call("POST", "/notifications/" + id(target) + "/read", body, key);
    assertEquals(200, read.status());
    assertNotNull(read.data().get("readAt"));
    assertEquals(
        read.data(),
        owner.call("POST", "/notifications/" + id(target) + "/read", body, key).data());
    var page = owner.good("GET", "/notifications", null, 200);
    assertEquals(3, ((Number) page.get("total")).intValue());
    assertTrue(
        ((List<?>) page.get("items"))
            .stream()
                .map(CoreAcceptanceMatrixTest::map)
                .filter(x -> id(x).equals(id(target)))
                .allMatch(x -> x.get("readAt") != null));
  }

  @Test
  void checkinAndNoShowRaceRetainOneLegalOutcomeAtEachBoundary() throws Exception {
    var first = reserve(user, seat, start, 1);
    var second = reserve(user, seat, start.plusSeconds(3600), 1);
    String code = codes.get(room).get("code").toString();
    for (var target : List.of(first, second)) {
      boolean before = id(target).equals(id(first));
      Instant at = before ? start.plusSeconds(899) : start.plusSeconds(4500);
      time.set(at);
      var barrier = new CyclicBarrier(2);
      try (var pool = Executors.newFixedThreadPool(2)) {
        var check =
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  try {
                    return bookings.checkin(
                        user, id(target), Map.of("expectedVersion", 0, "roomCode", code));
                  } catch (ApiException e) {
                    return e;
                  }
                });
        var expire =
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  bookings.task(Map.of("sourceId", id(target), "taskType", "M1_NO_SHOW"));
                  return true;
                });
        Object result = check.get(20, TimeUnit.SECONDS);
        expire.get(20, TimeUnit.SECONDS);
        if (before) {
          assertInstanceOf(Map.class, result);
          assertEquals("CHECKED_IN", reservations.findById(id(target)).orElseThrow().status);
          assertEquals(
              0,
              db.count("select count(*) from core.violation where reservation_id=?1", id(target)));
        } else {
          assertInstanceOf(ApiException.class, result);
          assertTrue(
              Set.of("CHECKIN_WINDOW_CLOSED", "VERSION_CONFLICT")
                  .contains(((ApiException) result).code));
          assertEquals("NO_SHOW", reservations.findById(id(target)).orElseThrow().status);
          assertEquals(
              1,
              db.count("select count(*) from core.violation where reservation_id=?1", id(target)));
        }
      }
    }
  }

  @Test
  void frameworkMethodAndRouteErrorsRemainSafeClientErrors() throws Exception {
    Api client = student();
    expect(
        client.call("PUT", "/system-config", Map.of("probe", "MATRIX_PRIVATE_PROBE"), null),
        405,
        "METHOD_NOT_ALLOWED");
    var missing = client.call("GET", "/route-that-does-not-exist", null, null);
    expect(missing, 404, "NOT_FOUND");
    assertFalse(Store.json(missing.body()).contains("route-that-does-not-exist"));
    assertEquals(0, db.count("select count(*) from core.reservation where user_id=?1", user));
  }
}
