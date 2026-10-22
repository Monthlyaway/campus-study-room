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
  void reservationHistoryUsesHalfOpenDateBoundsBeforePagination() {
    Instant from = Instant.parse("2030-01-02T16:00:00Z");
    Instant to = from.plusSeconds(86400);
    List<UUID> ids = new ArrayList<>();
    for (long hours : List.of(-1L, 0L, 23L, 24L)) {
      start = from.plusSeconds(hours * 3600);
      ids.add(Store.id(create().get("id")));
    }
    bookings.cancel(user, ids.get(1), Map.of("expectedVersion", 0));
    UUID otherSeat =
        Store.id(
            ((Map<String, Object>)
                    admin.createMaster(
                        roomAdmin(),
                        "seats",
                        Map.of("roomId", room, "code", "02", "hasPower", true)))
                .get("id"));
    UUID other = anotherStudent("CS");
    bookings.create(
        other,
        Map.of(
            "seatId",
            otherSeat,
            "startAt",
            from.toString(),
            "endAt",
            from.plusSeconds(3600).toString()));
    String localFrom = "2030-01-03T00:00:00+08:00", localTo = "2030-01-04T00:00:00+08:00";
    var first = bookings.list(user, null, localFrom, localTo, 1, 1);
    var second = bookings.list(user, null, from.toString(), to.toString(), 2, 1);
    assertEquals(2, first.total());
    assertEquals(2, second.total());
    assertEquals(ids.get(2), Store.id(first.items().getFirst().get("id")));
    assertEquals(ids.get(1), Store.id(second.items().getFirst().get("id")));
    assertEquals(0, bookings.list(user, null, localFrom, localTo, 3, 1).items().size());
    var cancelled = bookings.list(user, "CANCELLED", localFrom, localTo, 1, 20);
    assertEquals(1, cancelled.total());
    assertEquals(ids.get(1), Store.id(cancelled.items().getFirst().get("id")));
    assertEquals(3, bookings.list(user, null, localFrom, null, 1, 20).total());
    assertEquals(3, bookings.list(user, null, null, localTo, 1, 20).total());
    assertEquals(4, bookings.list(user, null, "", " ", 1, 20).total());
    for (String invalid : List.of("2030-01-03T00:00:00", "not-a-date"))
      assertEquals(
          "INVALID_TIME",
          assertThrows(ApiException.class, () -> bookings.list(user, null, invalid, localTo, 1, 20))
              .code);
    for (String invalidEnd : List.of(localFrom, "2030-01-02T00:00:00+08:00"))
      assertEquals(
          "INVALID_DATE_RANGE",
          assertThrows(
                  ApiException.class, () -> bookings.list(user, null, localFrom, invalidEnd, 1, 20))
              .code);
    assertEquals(4, db.count("select count(*) from core.reservation where user_id=?1", user));
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

  @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
  @Autowired ApplicationRepository applicationRepository;
  @Autowired ApprovalActionRepository approvalActionRepository;

  UUID applicationAt(Instant at) {
    return Store.id(
        ((Map<?, ?>)
                applications.create(
                    seeded("user:applicant"),
                    Map.of(
                        "roomId",
                        room,
                        "startAt",
                        at.toString(),
                        "endAt",
                        at.plusSeconds(3600).toString(),
                        "purposeTitle",
                        "事务边界验收",
                        "purposeDescription",
                        "真实数据库审批与维护组合验收")))
            .get("id"));
  }

  Object approve(UUID application, String actor, long version) {
    return applications.decision(
        seeded("user:" + actor),
        application,
        Map.of("expectedVersion", version, "decision", "APPROVE"));
  }

  List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Object>> futures = new ArrayList<>();
      for (Callable<?> action : List.of(first, second))
        futures.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  try {
                    return action.call();
                  } catch (ApiException expected) {
                    return expected;
                  }
                }));
      List<Object> outcomes = new ArrayList<>();
      for (var future : futures) outcomes.add(future.get(20, TimeUnit.SECONDS));
      return outcomes;
    }
  }

  @Test
  void reservationAndFinalApprovalCompeteAtTheSameRoomBarrier() throws Exception {
    UUID application = applicationAt(start);
    approve(application, "dept-approver", 0);
    var outcomes = race(this::create, () -> approve(application, "space-approver", 1));
    assertEquals(1, outcomes.stream().filter(ApiException.class::isInstance).count());
    var conflict =
        (ApiException)
            outcomes.stream().filter(ApiException.class::isInstance).findFirst().orElseThrow();
    assertEquals(409, conflict.status);
    assertEquals("RESERVATION_CONFLICT", conflict.code);
    assertEquals(
        1,
        db.count(
            "select count(*) from core.resource_claim where room_id=?1 and released_at is null",
            room));
    var a = applicationRepository.findById(application).orElseThrow();
    if (outcomes.getFirst() instanceof ApiException) {
      assertEquals("APPROVED", a.status);
      assertEquals(2, a.version);
      assertEquals(
          2,
          db.count(
              "select count(*) from core.approval_action where application_id=?1", application));
      assertEquals(0, db.count("select count(*) from core.reservation where room_id=?1", room));
      assertEquals(
          1,
          db.count(
              "select count(*) from core.notification where source_id=?1 and event_type='APPROVED'",
              application));
    } else {
      assertEquals("PENDING", a.status);
      assertEquals(1, a.version);
      assertEquals(2, a.currentStep);
      assertEquals(
          1,
          db.count(
              "select count(*) from core.approval_action where application_id=?1", application));
      assertEquals(
          "PENDING",
          db.need(
                  "select status from core.approval_step where application_id=?1 and step_no=2",
                  application)
              .get("status"));
      assertTrue(claimRepository.findBySourceTypeAndSourceId("M2", application).isEmpty());
      assertEquals(
          0, db.count("select count(*) from core.notification where source_id=?1", application));
      assertEquals(
          0,
          db.count(
              "select count(*) from core.audit_event where object_id=?1 and"
                  + " action='RESOURCE_CLAIMED'",
              application));
    }
  }

  @Test
  void failureAfterClaimInsertRollsBackApplicationClaimAuditAndNotification() {
    UUID application = applicationAt(start);
    approve(application, "dept-approver", 0);
    // A committed fixture row forces a real PostgreSQL uniqueness failure after the claim insert.
    // Its actor differs from the final approver so the service does not reject it in canReview.
    var duplicate = new ApprovalActionEntity();
    duplicate.id = UUID.randomUUID();
    duplicate.applicationId = application;
    duplicate.stepNo = 2;
    duplicate.actorId = roomAdmin();
    duplicate.decision = "APPROVE";
    duplicate.comment = "test-only constraint fault fixture";
    duplicate.occurredAt = time.instant();
    approvalActionRepository.saveAndFlush(duplicate);
    var before = db.need("select * from core.room_application where id=?1", application);
    var beforeSteps =
        db.list(
            "select * from core.approval_step where application_id=?1 order by step_no",
            application);
    var beforeActions =
        db.list(
            "select * from core.approval_action where application_id=?1 order by step_no",
            application);
    var beforeAudit =
        db.list("select * from core.audit_event where object_id=?1 order by id", application);
    var beforeNotifications =
        db.list("select * from core.notification where source_id=?1 order by id", application);
    var registry =
        entityManagerFactory
            .unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class)
            .getEventListenerRegistry();
    var originals = new ArrayList<org.hibernate.event.spi.PostInsertEventListener>();
    registry
        .getEventListenerGroup(org.hibernate.event.spi.EventType.POST_INSERT)
        .listeners()
        .forEach(originals::add);
    var inserted = new java.util.concurrent.atomic.AtomicBoolean();
    org.hibernate.event.spi.PostInsertEventListener observer =
        event -> {
          if (event.getEntity() instanceof ClaimEntity claim
              && "M2".equals(claim.sourceType)
              && application.equals(claim.sourceId)) inserted.set(true);
        };
    registry.appendListeners(org.hibernate.event.spi.EventType.POST_INSERT, observer);
    try {
      var error =
          assertThrows(
              org.springframework.dao.DataIntegrityViolationException.class,
              () -> approve(application, "space-approver", 1));
      assertTrue(
          error
              .getMostSpecificCause()
              .getMessage()
              .contains("approval_action_application_id_step_no_key"));
      assertTrue(
          inserted.get(),
          "The target claim INSERT must succeed before the later constraint failure; an early check"
              + " is not rollback evidence");
      assertEquals(before, db.need("select * from core.room_application where id=?1", application));
      assertEquals(
          beforeSteps,
          db.list(
              "select * from core.approval_step where application_id=?1 order by step_no",
              application));
      assertEquals(
          beforeActions,
          db.list(
              "select * from core.approval_action where application_id=?1 order by step_no",
              application));
      assertTrue(claimRepository.findBySourceTypeAndSourceId("M2", application).isEmpty());
      assertEquals(
          beforeAudit,
          db.list("select * from core.audit_event where object_id=?1 order by id", application));
      assertEquals(
          beforeNotifications,
          db.list("select * from core.notification where source_id=?1 order by id", application));
    } finally {
      registry.setListeners(
          org.hibernate.event.spi.EventType.POST_INSERT,
          originals.toArray(org.hibernate.event.spi.PostInsertEventListener[]::new));
      approvalActionRepository.deleteById(duplicate.id);
    }
  }

  @Test
  void simultaneousDecisionsCommitOnlyOneStepAction() throws Exception {
    UUID application = applicationAt(start);
    time.set(start.minusSeconds(1));
    var outcomes =
        race(
            () -> approve(application, "dept-approver", 0),
            () -> approve(application, "dept-approver", 0));
    assertEquals(1, outcomes.stream().filter(ApiException.class::isInstance).count());
    var conflict =
        (ApiException)
            outcomes.stream().filter(ApiException.class::isInstance).findFirst().orElseThrow();
    assertEquals("VERSION_CONFLICT", conflict.code);
    var a = applicationRepository.findById(application).orElseThrow();
    assertEquals("PENDING", a.status);
    assertEquals(1, a.version);
    assertEquals(2, a.currentStep);
    assertEquals(
        1,
        db.count("select count(*) from core.approval_action where application_id=?1", application));
    assertEquals(
        List.of("APPROVED", "PENDING"),
        db
            .list(
                "select status from core.approval_step where application_id=?1 order by step_no",
                application)
            .stream()
            .map(x -> x.get("status"))
            .toList());
    assertEquals(
        1,
        db.count(
            "select count(*) from core.audit_event where object_id=?1 and"
                + " action='APPLICATION_APPROVE'",
            application));
    assertTrue(claimRepository.findBySourceTypeAndSourceId("M2", application).isEmpty());
  }

  @Test
  void expiryAndFinalApprovalCannotLeaveExpiredApplicationClaimed() throws Exception {
    UUID earlier = applicationAt(start.plusSeconds(86400));
    UUID racing = applicationAt(start.plusSeconds(90000));
    approve(earlier, "dept-approver", 0);
    approve(racing, "dept-approver", 0);
    Instant expiry = applicationRepository.findById(racing).orElseThrow().expiresAt;
    time.set(expiry.minusSeconds(1));
    approve(earlier, "space-approver", 1);
    time.set(expiry);
    var outcomes =
        race(
            () -> approve(racing, "space-approver", 1),
            () -> {
              applications.task(Map.of("sourceId", racing, "taskType", "M2_EXPIRE"));
              return "EXPIRE_PROCESSED";
            });
    assertInstanceOf(ApiException.class, outcomes.getFirst());
    var rejected = (ApiException) outcomes.getFirst();
    assertTrue(Set.of("VERSION_CONFLICT", "APPLICATION_EXPIRED").contains(rejected.code));
    var a = applicationRepository.findById(racing).orElseThrow();
    assertEquals("EXPIRED", a.status);
    assertEquals(2, a.version);
    assertEquals(
        1, db.count("select count(*) from core.approval_action where application_id=?1", racing));
    assertTrue(claimRepository.findBySourceTypeAndSourceId("M2", racing).isEmpty());
    assertEquals(
        0,
        db.count(
            "select count(*) from core.notification where source_id=?1 and event_type='APPROVED'",
            racing));
    applications.task(Map.of("sourceId", earlier, "taskType", "M2_EXPIRE"));
    assertEquals("APPROVED", applicationRepository.findById(earlier).orElseThrow().status);
    assertEquals(2, applicationRepository.findById(earlier).orElseThrow().version);
    var earlierClaim = claimRepository.findBySourceTypeAndSourceId("M2", earlier).orElseThrow();
    assertNull(earlierClaim.releasedAt);
    assertEquals(2, earlierClaim.sourceVersion);
  }

  @Test
  void maintenanceCascadesFiveStatesWithoutChangingHistoryOrRevivingOrders() {
    Instant base = time.instant();
    start = base.plusSeconds(3600);
    UUID historical = Store.id(create().get("id"));
    start = base.plusSeconds(10800);
    UUID current = Store.id(create().get("id"));
    start = base.plusSeconds(18000);
    UUID future = Store.id(create().get("id"));
    UUID approved = applicationAt(base.plusSeconds(25200));
    approve(approved, "dept-approver", 0);
    approve(approved, "space-approver", 1);
    UUID pending = applicationAt(base.plusSeconds(32400));
    time.set(base.plusSeconds(3600));
    bookings.checkin(
        user, historical, Map.of("expectedVersion", 0, "roomCode", codes.get(room).get("code")));
    time.set(base.plusSeconds(7200));
    bookings.task(Map.of("sourceId", historical, "taskType", "M1_COMPLETE"));
    var historyBefore = db.need("select * from core.reservation where id=?1", historical);
    var pendingBefore = db.need("select * from core.room_application where id=?1", pending);
    time.set(base.plusSeconds(10800));
    bookings.checkin(
        user, current, Map.of("expectedVersion", 0, "roomCode", codes.get(room).get("code")));
    time.set(base.plusSeconds(11100));
    UUID repair = UUID.randomUUID();
    resources.command(command(repair, "BLOCK"));
    var active = reservationRepository.findById(current).orElseThrow();
    assertEquals("INTERRUPTED", active.status);
    assertEquals(time.instant(), active.endedAt);
    assertEquals("MAINTENANCE", active.cancelReason);
    assertEquals("CANCELLED", reservationRepository.findById(future).orElseThrow().status);
    assertEquals("MAINTENANCE", reservationRepository.findById(future).orElseThrow().cancelReason);
    assertEquals("CANCELLED", applicationRepository.findById(approved).orElseThrow().status);
    assertEquals(
        "MAINTENANCE", applicationRepository.findById(approved).orElseThrow().cancelReason);
    assertEquals(historyBefore, db.need("select * from core.reservation where id=?1", historical));
    assertEquals(
        pendingBefore, db.need("select * from core.room_application where id=?1", pending));
    for (UUID id : List.of(current, future))
      assertNotNull(claimRepository.findBySourceTypeAndSourceId("M1", id).orElseThrow().releasedAt);
    assertNotNull(
        claimRepository.findBySourceTypeAndSourceId("M2", approved).orElseThrow().releasedAt);
    assertTrue(claimRepository.findBySourceTypeAndSourceId("M2", pending).isEmpty());
    assertEquals(0, db.count("select count(*) from core.violation where user_id=?1", user));
    assertEquals(
        3,
        db.count(
            "select count(*) from core.notification where room_id=?1 and event_type='MAINTENANCE'",
            room));
    for (UUID id : List.of(current, future, approved))
      assertEquals(
          1,
          db.count(
              "select count(*) from core.notification where source_id=?1 and"
                  + " event_type='MAINTENANCE'",
              id));
    assertEquals(
        3,
        db.count(
            "select count(*) from core.audit_event where room_id=?1 and action='MAINTENANCE_CANCEL'"
                + " and actor_type='SERVICE'",
            room));
    assertEquals(
        1,
        db.count(
            "select count(*) from core.resource_claim where room_id=?1 and released_at is null and"
                + " source_type='M3'",
            room));
    var reservationsAfter =
        db.list("select * from core.reservation where room_id=?1 order by id", room);
    var applicationsAfter =
        db.list("select * from core.room_application where room_id=?1 order by id", room);
    resources.command(command(repair, "RELEASE"));
    assertEquals(
        reservationsAfter,
        db.list("select * from core.reservation where room_id=?1 order by id", room));
    assertEquals(
        applicationsAfter,
        db.list("select * from core.room_application where room_id=?1 order by id", room));
    assertEquals(
        0,
        db.count(
            "select count(*) from core.resource_claim where room_id=?1 and released_at is null",
            room));
    assertFalse(resources.conflict(room, seat, start, start.plusSeconds(3600)));
  }
}
