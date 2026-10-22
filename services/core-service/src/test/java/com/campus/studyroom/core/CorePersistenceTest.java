package com.campus.studyroom.core;

import static org.junit.jupiter.api.Assertions.*;

import com.campus.studyroom.api.*;
import com.campus.studyroom.core.persistence.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CorePersistenceTest.TimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = "STUDYROOM_CORE_DB_TESTS", matches = "true")
class CorePersistenceTest {
  @Autowired BookingService bookings;
  @Autowired ApplicationService applications;
  @Autowired ResourceService resources;
  @Autowired AdminService admin;
  @Autowired CheckinCodes codes;
  @Autowired Store db;
  @Autowired QueryService queries;
  @Autowired MutableClock time;
  @Autowired ReservationRepository reservationRepository;
  @Autowired ClaimRepository claimRepository;
  @Autowired ViolationRepository violationRepository;
  UUID room, seat, user;
  Instant start;

  static UUID seeded(String name) {
    return UUID.nameUUIDFromBytes(("studyroom:" + name).getBytes(StandardCharsets.UTF_8));
  }

  static UUID adminId() {
    return seeded("user:admin");
  }

  static UUID roomAdmin() {
    return seeded("user:room-admin");
  }

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    private Instant now = Instant.parse("2030-01-01T00:00:00Z");

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

  @BeforeEach
  @SuppressWarnings("unchecked")
  void fixture() {
    time.set(Instant.parse("2030-01-01T00:00:00Z"));
    start = time.instant().plusSeconds(86400);
    String code = UUID.randomUUID().toString();
    var r =
        (Map<String, Object>)
            admin.createMaster(
                roomAdmin(),
                "rooms",
                Map.of(
                    "buildingId",
                    seeded("building:LIBRARY"),
                    "code",
                    code.substring(0, 20),
                    "name",
                    "隔离数据库验收教室"));
    room = Store.id(r.get("id"));
    List<Map<String, Object>> rules = new ArrayList<>();
    for (int day = 1; day <= 7; day++)
      rules.add(Map.of("weekday", day, "startMinute", 0, "endMinute", 1440));
    admin.opening(roomAdmin(), room, Map.of("expectedVersion", 0, "rules", rules));
    seat =
        Store.id(
            ((Map<String, Object>)
                    admin.createMaster(
                        roomAdmin(),
                        "seats",
                        Map.of("roomId", room, "code", "01", "hasPower", true)))
                .get("id"));
    var u =
        (Map<String, Object>)
            admin.createUser(
                adminId(),
                Map.of(
                    "username",
                    "test-" + code,
                    "displayName",
                    "隔离验收用户",
                    "password",
                    "TestFixture123!",
                    "email",
                    code + "@example.invalid",
                    "schoolNo",
                    code.substring(0, 24),
                    "departmentId",
                    seeded("department:CS")));
    user = Store.id(u.get("id"));
    admin.roleBindings(
        adminId(),
        user,
        Map.of(
            "expectedVersion",
            0,
            "bindings",
            List.of(Map.of("roleId", seeded("role:STUDENT"), "scopes", List.of()))));
  }

  Map<String, Object> bookingBody() {
    return Map.of(
        "seatId", seat, "startAt", start.toString(), "endAt", start.plusSeconds(3600).toString());
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> create() {
    return (Map<String, Object>) bookings.create(user, bookingBody());
  }

  Map<String, Object> task(UUID id) {
    return Map.of("sourceId", id, "taskType", "M1_NO_SHOW");
  }

  Map<String, Object> command(UUID source, String operation) {
    return Map.of(
        "commandId",
        UUID.randomUUID(),
        "sourceType",
        "M3",
        "sourceId",
        source,
        "sourceVersion",
        operation.equals("BLOCK") ? 1 : 2,
        "operation",
        operation,
        "roomId",
        room,
        "actorId",
        roomAdmin(),
        "occurredAt",
        time.instant().toString());
  }

  @Test
  void checkinBoundaryAndPersistedNoShowAreExclusive() {
    var b = create();
    UUID id = Store.id(b.get("id"));
    time.set(start.plusSeconds(899));
    String code = codes.get(room).get("code").toString();
    var checked =
        (Map<?, ?>) bookings.checkin(user, id, Map.of("expectedVersion", 0, "roomCode", code));
    assertEquals("CHECKED_IN", checked.get("status"));
    time.set(start.plusSeconds(3600));
    bookings.task(Map.of("sourceId", id, "taskType", "M1_COMPLETE"));
    assertEquals("COMPLETED", reservationRepository.findById(id).orElseThrow().status);
    assertFalse(violationRepository.existsByReservationId(id));
    time.set(start.minusSeconds(3600));
    start = start.plusSeconds(7200);
    b = create();
    id = Store.id(b.get("id"));
    time.set(start.plusSeconds(900));
    UUID late = id;
    ApiException rejected =
        assertThrows(
            ApiException.class,
            () -> bookings.checkin(user, late, Map.of("expectedVersion", 0, "roomCode", code)));
    assertEquals("CHECKIN_WINDOW_CLOSED", rejected.code);
    bookings.task(task(id));
    bookings.task(task(id));
    assertEquals("NO_SHOW", reservationRepository.findById(id).orElseThrow().status);
    assertTrue(violationRepository.existsByReservationId(id));
    assertNotNull(claimRepository.findBySourceTypeAndSourceId("M1", id).orElseThrow().releasedAt);
  }

  @Test
  void maintenanceBeforeNoShowDoesNotPenalizeStudent() {
    var b = create();
    UUID id = Store.id(b.get("id"));
    time.set(start.plusSeconds(901));
    UUID source = UUID.randomUUID();
    resources.command(command(source, "BLOCK"));
    bookings.task(task(id));
    var r = reservationRepository.findById(id).orElseThrow();
    assertEquals("CANCELLED", r.status);
    assertEquals("MAINTENANCE", r.cancelReason);
    assertFalse(violationRepository.existsByReservationId(id));
    resources.command(command(source, "RELEASE"));
    assertEquals("CANCELLED", reservationRepository.findById(id).orElseThrow().status);
  }

  @Test
  void committedNoShowRemainsHistoricalDuringMaintenance() {
    var b = create();
    UUID id = Store.id(b.get("id"));
    time.set(start.plusSeconds(901));
    bookings.task(task(id));
    UUID source = UUID.randomUUID();
    resources.command(command(source, "BLOCK"));
    assertEquals("NO_SHOW", reservationRepository.findById(id).orElseThrow().status);
    assertTrue(violationRepository.existsByReservationId(id));
    resources.command(command(source, "RELEASE"));
  }

  @Test
  void sourceTombstoneAndMultipleMaintenanceClaimsAreIndependent() {
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    var block = command(first, "BLOCK");
    var firstResult = resources.command(block);
    assertEquals(Idempotency.hash(firstResult), Idempotency.hash(resources.command(block)));
    resources.command(command(second, "BLOCK"));
    resources.command(command(first, "RELEASE"));
    assertTrue(resources.conflict(room, seat, start, start.plusSeconds(3600)));
    resources.command(command(second, "RELEASE"));
    assertFalse(resources.conflict(room, seat, start, start.plusSeconds(3600)));
    UUID tombstone = UUID.randomUUID();
    resources.command(command(tombstone, "RELEASE"));
    var stale = (Map<?, ?>) resources.command(command(tombstone, "BLOCK"));
    assertEquals("STALE_IGNORED", stale.get("result"));
    assertEquals("RELEASED", stale.get("sourceState"));
    assertFalse(resources.conflict(room, seat, start, start.plusSeconds(3600)));
    var changed = new LinkedHashMap<>(block);
    changed.put("actorId", user);
    assertEquals(
        "COMMAND_PAYLOAD_MISMATCH",
        assertThrows(ApiException.class, () -> resources.command(changed)).code);
  }

  @Test
  void concurrentDeadlineScannersOnlyCommitOneViolation() throws Exception {
    var b = create();
    UUID id = Store.id(b.get("id"));
    time.set(start.plusSeconds(900));
    CyclicBarrier barrier = new CyclicBarrier(8);
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(
                () -> {
                  barrier.await();
                  bookings.task(task(id));
                  return null;
                }));
      for (var f : futures) f.get(20, TimeUnit.SECONDS);
    }
    assertEquals("NO_SHOW", reservationRepository.findById(id).orElseThrow().status);
    assertEquals(1, db.count("select count(*) from core.violation where reservation_id=?1", id));
    assertEquals(
        1,
        db.count(
            "select count(*) from core.notification where source_id=?1 and event_type='NO_SHOW'",
            id));
  }

  @Test
  void availabilityAndStatisticsUseDatabaseProjection() {
    var query =
        Map.of(
            "startAt",
            start.toString(),
            "endAt",
            start.plusSeconds(3600).toString(),
            "roomId",
            room.toString(),
            "hasPower",
            "true");
    var before = (PageData<?>) queries.availability(user, query, false, 1, 1);
    assertEquals(1, before.total());
    assertEquals(1, before.items().size());
    var b = create();
    assertEquals(0, ((PageData<?>) queries.availability(user, query, false, 1, 1)).total());
    var stats =
        (Map<?, ?>)
            queries.statistics(
                seeded("user:operator"),
                Map.of(
                    "fromDate", "2030-01-02", "toDate", "2030-01-02", "roomId", room.toString()));
    assertEquals(1, ((Number) stats.get("reservationCount")).intValue());
    assertEquals(1.0, ((Number) stats.get("reservedSeatHours")).doubleValue());
    bookings.cancel(user, Store.id(b.get("id")), Map.of("expectedVersion", 0));
  }

  @SuppressWarnings("unchecked")
  UUID anotherStudent(String department) {
    String unique = UUID.randomUUID().toString();
    var u =
        (Map<String, Object>)
            admin.createUser(
                adminId(),
                Map.of(
                    "username",
                    "isolation-" + unique,
                    "displayName",
                    "边界验收用户",
                    "password",
                    "TestFixture123!",
                    "email",
                    unique + "@example.invalid",
                    "schoolNo",
                    unique.substring(0, 24),
                    "departmentId",
                    seeded("department:" + department)));
    UUID id = Store.id(u.get("id"));
    admin.roleBindings(
        adminId(),
        id,
        Map.of(
            "expectedVersion",
            0,
            "bindings",
            List.of(Map.of("roleId", seeded("role:STUDENT"), "scopes", List.of()))));
    return id;
  }

  @Test
  void sameSeatConflictCannotCommitSecondClaim() {
    create();
    UUID second = anotherStudent("CS");
    ApiException error =
        assertThrows(ApiException.class, () -> bookings.create(second, bookingBody()));
    assertEquals("RESERVATION_CONFLICT", error.code);
    assertEquals(
        1,
        db.count(
            "select count(*) from core.resource_claim where room_id=?1 and source_type='M1' and"
                + " released_at is null",
            room));
  }

  @Test
  void configurationChangeOnlyAffectsNewOrders() {
    var before = (Map<?, ?>) admin.config(adminId());
    long revision = ((Number) before.get("version")).longValue();
    var items = (List<?>) before.get("items");
    int previous =
        items.stream()
            .map(x -> (Map<?, ?>) x)
            .filter(x -> "M1_MAX_DURATION_HOURS".equals(x.get("key")))
            .mapToInt(x -> ((Number) x.get("value")).intValue())
            .findFirst()
            .orElseThrow();
    var old = create();
    UUID oldId = Store.id(old.get("id"));
    var changed =
        (Map<?, ?>)
            admin.configUpdate(
                adminId(),
                Map.of(
                    "expectedVersion",
                    revision,
                    "changes",
                    List.of(Map.of("key", "M1_MAX_DURATION_HOURS", "value", 2))));
    long next = ((Number) changed.get("version")).longValue();
    try {
      var persisted = reservationRepository.findById(oldId).orElseThrow();
      assertEquals(revision, persisted.configVersion);
      assertEquals(previous, persisted.maxDurationHours);
      assertEquals("RESERVED", persisted.status);
      start = start.plusSeconds(86400);
      var tooLong = new LinkedHashMap<>(bookingBody());
      tooLong.put("endAt", start.plusSeconds(3 * 3600).toString());
      assertEquals(
          422, assertThrows(ApiException.class, () -> bookings.create(user, tooLong)).status);
      var latest = create();
      persisted = reservationRepository.findById(Store.id(latest.get("id"))).orElseThrow();
      assertEquals(next, persisted.configVersion);
      assertEquals(2, persisted.maxDurationHours);
    } finally {
      admin.configUpdate(
          adminId(),
          Map.of(
              "expectedVersion",
              next,
              "changes",
              List.of(Map.of("key", "M1_MAX_DURATION_HOURS", "value", previous))));
    }
  }

  @Test
  void auditAndClaimFiltersApplyBeforeDatabasePagination() {
    var booking = create();
    UUID id = Store.id(booking.get("id"));
    var query =
        Map.of(
            "objectId", id.toString(), "action", "RESERVATION_CREATED", "roomId", room.toString());
    var events = (PageData<?>) queries.audit(adminId(), query, 1, 1);
    assertEquals(1, events.total());
    assertEquals(1, events.items().size());
    assertEquals(id.toString(), ((Map<?, ?>) events.items().getFirst()).get("objectId").toString());
    assertEquals(
        0,
        ((PageData<?>)
                queries.audit(adminId(), Map.of("objectId", UUID.randomUUID().toString()), 1, 1))
            .total());
    var claims =
        (PageData<?>)
            queries.claims(
                roomAdmin(),
                room,
                Map.of(
                    "sourceType",
                    "M1",
                    "startAt",
                    start.toString(),
                    "endAt",
                    start.plusSeconds(3600).toString()),
                1,
                1);
    assertEquals(1, claims.total());
    assertEquals(
        0,
        ((PageData<?>) queries.claims(roomAdmin(), room, Map.of("sourceType", "M2"), 1, 1))
            .total());
    assertEquals(
        0,
        ((PageData<?>)
                queries.claims(
                    roomAdmin(), room, Map.of("startAt", start.plusSeconds(3600).toString()), 1, 1))
            .total());
    assertEquals(
        403, assertThrows(ApiException.class, () -> queries.audit(user, query, 1, 20)).status);
  }

  @Test
  @SuppressWarnings("unchecked")
  void foreignDepartmentCannotCreateReservation() {
    UUID foreign = anotherStudent("ART");
    var r =
        (Map<String, Object>)
            admin.createMaster(
                roomAdmin(),
                "rooms",
                Map.of(
                    "buildingId",
                    seeded("building:LIBRARY"),
                    "code",
                    UUID.randomUUID().toString().substring(0, 20),
                    "name",
                    "院系限制验收",
                    "departmentId",
                    seeded("department:CS")));
    UUID restricted = Store.id(r.get("id"));
    List<Map<String, Object>> rules = new ArrayList<>();
    for (int day = 1; day <= 7; day++)
      rules.add(Map.of("weekday", day, "startMinute", 0, "endMinute", 1440));
    admin.opening(roomAdmin(), restricted, Map.of("expectedVersion", 0, "rules", rules));
    UUID restrictedSeat =
        Store.id(
            ((Map<String, Object>)
                    admin.createMaster(
                        roomAdmin(),
                        "seats",
                        Map.of("roomId", restricted, "code", "01", "hasPower", true)))
                .get("id"));
    var body = new LinkedHashMap<>(bookingBody());
    body.put("seatId", restrictedSeat);
    assertEquals(
        404, assertThrows(ApiException.class, () -> bookings.create(foreign, body)).status);
    assertEquals(0, db.count("select count(*) from core.reservation where room_id=?1", restricted));
  }
}
