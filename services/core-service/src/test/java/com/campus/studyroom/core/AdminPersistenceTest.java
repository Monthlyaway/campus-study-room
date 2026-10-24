package com.campus.studyroom.core;

import static org.junit.jupiter.api.Assertions.*;

import com.campus.studyroom.api.*;
import com.campus.studyroom.core.persistence.*;
import com.campus.studyroom.security.SessionPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.campus.studyroom.core.AdminPersistenceTest$QueryCounter")
@EnabledIfEnvironmentVariable(named = "STUDYROOM_ADMIN_DB_TESTS", matches = "true")
class AdminPersistenceTest {
  @Autowired AdminService admin;
  @Autowired AccessService access;
  @Autowired AccountRepository accounts;
  @Autowired SystemConfigRepository configs;
  @Autowired ConfigRevisionRepository revisions;
  @Autowired RoleRepository roles;
  @Autowired RoomRepository rooms;
  @Autowired CampusRepository campuses;
  @Autowired BuildingRepository buildings;
  @Autowired Clock clock;

  public static class QueryCounter implements org.hibernate.resource.jdbc.spi.StatementInspector {
    static final ThreadLocal<List<String>> statements = new ThreadLocal<>();

    public String inspect(String sql) {
      var current = statements.get();
      if (current != null) current.add(sql);
      return sql;
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void roomPageUsesBoundedQueriesAndBatchedSeatCounts() {
    var campus =
        map(
            admin.createMaster(
                actor(),
                "campuses",
                Map.of(
                    "code", "C" + UUID.randomUUID().toString().substring(0, 15), "name", "批量校区")));
    var building =
        map(
            admin.createMaster(
                actor(),
                "buildings",
                Map.of(
                    "campusId",
                    campus.get("id"),
                    "code",
                    "B" + UUID.randomUUID().toString().substring(0, 15),
                    "name",
                    "批量教学楼")));
    String roomCodePrefix = "Q" + UUID.randomUUID().toString().substring(0, 8);
    for (int n = 0; n < 21; n++) {
      var room =
          map(
              admin.createMaster(
                  roomAdmin(),
                  "rooms",
                  Map.of(
                      "buildingId",
                      building.get("id"),
                      "code",
                      roomCodePrefix + String.format("%02d", n),
                      "name",
                      "批量教室" + n)));
      if (n % 2 == 0)
        admin.createMaster(
            roomAdmin(), "seats", Map.of("roomId", room.get("id"), "code", "01", "hasPower", true));
    }
    Map<String, String> filter = Map.of("buildingId", building.get("id").toString());
    try {
      QueryCounter.statements.set(new ArrayList<>());
      var first = (PageData<Map<String, Object>>) admin.masters(actor(), "rooms", filter, 1, 1);
      int one = QueryCounter.statements.get().size();
      QueryCounter.statements.set(new ArrayList<>());
      var page = (PageData<Map<String, Object>>) admin.masters(actor(), "rooms", filter, 1, 20);
      int twenty = QueryCounter.statements.get().size();
      assertEquals(21, first.total());
      assertEquals(21, page.total());
      assertEquals(20, page.items().size());
      assertTrue(
          twenty <= one + 1,
          "SQL count grew with rows: "
              + one
              + " -> "
              + twenty
              + " "
              + QueryCounter.statements.get());
      assertEquals(
          1,
          QueryCounter.statements.get().stream()
              .filter(q -> q.contains("count(") && q.contains("core.seat"))
              .count());
      for (int n = 0; n < 20; n++) {
        var room = page.items().get(n);
        assertEquals("批量教学楼", room.get("buildingName"));
        assertEquals("批量校区", room.get("campusName"));
        assertEquals(n % 2 == 0 ? 1L : 0L, ((Number) room.get("seatCount")).longValue());
      }
    } finally {
      QueryCounter.statements.remove();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void roleListBatchesPermissionQueries() {
    int baseline;
    try {
      QueryCounter.statements.set(new ArrayList<>());
      admin.roles(actor());
      baseline = QueryCounter.statements.get().size();
    } finally {
      QueryCounter.statements.remove();
    }
    for (int n = 0; n < 20; n++)
      admin.createRole(actor(), Map.of("code", "BATCH_" + UUID.randomUUID(), "name", "批量角色"));
    try {
      QueryCounter.statements.set(new ArrayList<>());
      var result = (PageData<Map<String, Object>>) admin.roles(actor());
      assertTrue(
          QueryCounter.statements.get().size() <= baseline + 1,
          "Role queries grew with rows: " + baseline + " -> " + QueryCounter.statements.get());
      assertTrue(result.total() >= 20);
      assertTrue(
          result.items().stream().allMatch(r -> r.get("permissionCodes") instanceof List<?>));
      assertTrue(
          QueryCounter.statements.get().stream()
              .anyMatch(q -> q.contains("role_permission") && q.contains(" in ")));
    } finally {
      QueryCounter.statements.remove();
    }
  }

  static UUID seeded(String name) {
    return UUID.nameUUIDFromBytes(("studyroom:" + name).getBytes(StandardCharsets.UTF_8));
  }

  static UUID actor() {
    return seeded("user:admin");
  }

  static UUID roomAdmin() {
    return seeded("user:room-admin");
  }

  static UUID department() {
    return seeded("department:CS");
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> map(Object o) {
    return (Map<String, Object>) o;
  }

  Map<String, Object> user(String username, String display) {
    return map(
        admin.createUser(
            actor(),
            Map.of(
                "username",
                username,
                "displayName",
                display,
                "schoolNo",
                UUID.randomUUID().toString().substring(0, 24),
                "password",
                "IsolatedTestPass123!",
                "email",
                username + "@example.invalid",
                "departmentId",
                department())));
  }

  UUID user() {
    return Store.id(user("admin-test-" + UUID.randomUUID(), "隔离管理验收").get("id"));
  }

  Map<String, Object> role(String permission) {
    var r =
        map(admin.createRole(actor(), Map.of("code", "TEST_" + UUID.randomUUID(), "name", "隔离角色")));
    return map(
        admin.rolePermissions(
            actor(),
            Store.id(r.get("id")),
            Map.of(
                "expectedVersion",
                0,
                "permissionCodes",
                permission == null ? List.of() : List.of(permission))));
  }

  Map<String, Object> binding(UUID role, Map<String, Object> scope) {
    return Map.of("roleId", role, "scopes", List.of(scope));
  }

  long userVersion(UUID id) {
    return accounts.findById(id).orElseThrow().version;
  }

  Map<String, Object> room(UUID owner, UUID department) {
    var b = new LinkedHashMap<String, Object>();
    b.put("buildingId", seeded("building:LIBRARY"));
    b.put("code", UUID.randomUUID().toString().substring(0, 20));
    b.put("name", "管理测试教室");
    if (department != null) b.put("departmentId", department);
    return map(admin.createMaster(owner, "rooms", b));
  }

  @Test
  @SuppressWarnings("unchecked")
  void authorizationDirectoryNamesAllDepartmentsWithoutOpeningBusinessAccess() {
    String prefix = "AUTH" + UUID.randomUUID().toString().substring(0, 8);
    var created = new ArrayList<Room>();
    for (int n = 0; n < 101; n++) {
      Room r = new Room();
      r.id = UUID.randomUUID();
      r.buildingId = seeded("building:LIBRARY");
      r.departmentId = seeded("department:ART");
      r.code = prefix + String.format("%03d", n);
      r.name = "授权目录测试教室 " + n;
      r.enabled = n != 100;
      created.add(r);
    }
    rooms.saveAllAndFlush(created);
    var first = (PageData<Map<String, Object>>) admin.roleScopeRooms(actor(), prefix, 1, 100);
    var second = (PageData<Map<String, Object>>) admin.roleScopeRooms(actor(), prefix, 2, 100);
    assertEquals(101, first.total());
    assertEquals(100, first.items().size());
    assertEquals(1, second.items().size());
    assertEquals("授权目录测试教室 100", second.items().getFirst().get("name"));
    assertEquals(false, second.items().getFirst().get("enabled"));
    assertEquals(Set.of("id", "name", "code", "enabled", "buildingName", "departmentName"),
        second.items().getFirst().keySet());
    assertFalse(admin.visibleRoom(actor(), created.getFirst().id));
    assertThrows(ApiException.class, () -> admin.room(actor(), created.getFirst().id));
    assertEquals(403, assertThrows(ApiException.class,
        () -> admin.roleScopeRooms(seeded("user:student"), "", 1, 20)).status);
    assertEquals(400, assertThrows(ApiException.class,
        () -> admin.roleScopeRooms(actor(), "", 0, 20)).status);
    UUID target = user();
    var scoped = role("room:manage");
    var result = map(admin.roleBindings(actor(), target, Map.of("expectedVersion", userVersion(target),
        "bindings", List.of(binding(Store.id(scoped.get("id")), Map.of("scopeType", "ROOM", "roomId", created.getLast().id))))));
    var bindings = (List<Map<String, Object>>) result.get("roleBindings");
    var projectedScopes = (List<Map<String, Object>>) bindings.getFirst().get("scopes");
    assertEquals(created.getLast().name, projectedScopes.getFirst().get("roomName"));
    assertEquals(created.getLast().id.toString(), projectedScopes.getFirst().get("roomId"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void notificationRoomDirectoryUsesOnlySameBindingManagementScope() {
    UUID target = user();
    var ownRole = role("notification:manage");
    var otherRole = role("audit:read");
    UUID roomId = Store.id(room(roomAdmin(), seeded("department:ART")).get("id"));
    admin.roleBindings(actor(), target, Map.of("expectedVersion", userVersion(target),
        "bindings", List.of(binding(Store.id(ownRole.get("id")), Map.of("scopeType", "SELF")),
            binding(Store.id(otherRole.get("id")), Map.of("scopeType", "ALL")))));
    assertEquals(0, ((PageData<?>) admin.notificationScopeRooms(target, "", 1, 100)).total());
    admin.roleBindings(actor(), target, Map.of("expectedVersion", userVersion(target),
        "bindings", List.of(binding(Store.id(ownRole.get("id")), Map.of("scopeType", "ROOM", "roomId", roomId)),
            binding(Store.id(otherRole.get("id")), Map.of("scopeType", "ALL")))));
    var result = (PageData<Map<String, Object>>) admin.notificationScopeRooms(target, "", 1, 100);
    assertEquals(1, result.total());
    assertEquals(roomId.toString(), result.items().getFirst().get("id"));
    assertFalse(admin.visibleRoom(target, roomId));
    assertThrows(ApiException.class, () -> admin.room(target, roomId));
    assertEquals(0, ((PageData<?>) admin.notificationScopeRooms(target, "no-match-" + UUID.randomUUID(), 1, 100)).total());
    assertEquals(403, assertThrows(ApiException.class,
        () -> admin.notificationScopeRooms(seeded("user:student"), "", 1, 100)).status);
  }

  @Test
  void selfScopePersistsWithoutGrantingRoomManagementAndInvalidatesSession() {
    UUID id = user();
    var before = accounts.findById(id).orElseThrow();
    var session = new SessionPrincipal(id, before.authVersion, clock.instant());
    assertTrue(access.valid(session));
    var r = role("room:manage");
    var result =
        map(
            admin.roleBindings(
                actor(),
                id,
                Map.of(
                    "expectedVersion",
                    before.version,
                    "bindings",
                    List.of(binding(Store.id(r.get("id")), Map.of("scopeType", "SELF"))))));
    assertEquals(before.version + 1, ((Number) result.get("version")).longValue());
    assertFalse(access.valid(session));
    assertTrue(access.has(id, "room:manage"));
    assertFalse(access.createRoomScope(id, null));
    assertFalse(access.scope(id, "room:manage", null, department()));
    var scopes = (List<?>) access.me(id).get("scopes");
    assertEquals("SELF", ((Map<?, ?>) scopes.getFirst()).get("scopeType"));
  }

  @Test
  void permissionsAndScopeMustComeFromSameBinding() {
    UUID id = user();
    var a = role("room:manage");
    var b = role(null);
    admin.roleBindings(
        actor(),
        id,
        Map.of(
            "expectedVersion",
            0,
            "bindings",
            List.of(
                binding(
                    Store.id(a.get("id")),
                    Map.of("scopeType", "DEPARTMENT", "departmentId", department())),
                binding(Store.id(b.get("id")), Map.of("scopeType", "ALL")))));
    assertTrue(access.scope(id, "room:manage", null, department()));
    assertFalse(access.scope(id, "room:manage", null, seeded("department:ART")));
    assertFalse(access.createRoomScope(id, null));
    var shared = role("room:manage");
    admin.roleBindings(
        actor(),
        id,
        Map.of(
            "expectedVersion",
            userVersion(id),
            "bindings",
            List.of(binding(Store.id(shared.get("id")), Map.of("scopeType", "SHARED")))));
    assertTrue(access.createRoomScope(id, null));
    assertNotNull(room(id, null).get("id"));
    assertFalse(access.createRoomScope(id, department()));
  }

  @Test
  void userSearchFiltersRowsAndCountInDatabase() {
    String token = "search-" + UUID.randomUUID().toString().substring(0, 12);
    user(token + "-a", "第一条");
    user("different-" + UUID.randomUUID(), token.toUpperCase(Locale.ROOT));
    var p = (PageData<?>) admin.users(actor(), token, 1, 1);
    assertEquals(2, p.total());
    assertEquals(1, p.items().size());
    var second = (PageData<?>) admin.users(actor(), token, 2, 1);
    assertEquals(2, second.total());
    assertNotEquals(
        ((Map<?, ?>) p.items().getFirst()).get("id"),
        ((Map<?, ?>) second.items().getFirst()).get("id"));
    assertEquals(0, ((PageData<?>) admin.users(actor(), token + "%", 1, 20)).total());
  }

  @Test
  void configRevisionIsAtomicVersionedAndVisibleInSession() {
    long before = revisions.findById(1).orElseThrow().version;
    var result =
        map(
            admin.configUpdate(
                actor(),
                Map.of(
                    "expectedVersion",
                    before,
                    "changes",
                    List.of(
                        Map.of("key", "M1_MAX_DURATION_HOURS", "value", 3),
                        Map.of("key", "M2_MAX_DURATION_HOURS", "value", 5)))));
    assertEquals(before + 1, ((Number) result.get("version")).longValue());
    assertEquals(
        Map.of("reservationMaxHours", 3, "applicationMaxHours", 5),
        access.me(actor()).get("bookingLimits"));
    assertEquals(
        "VERSION_CONFLICT",
        assertThrows(
                ApiException.class,
                () ->
                    admin.configUpdate(
                        actor(),
                        Map.of(
                            "expectedVersion",
                            before,
                            "changes",
                            List.of(Map.of("key", "M1_MAX_DURATION_HOURS", "value", 4)))))
            .code);
    assertEquals(
        "INVALID_CONFIG",
        assertThrows(
                ApiException.class,
                () ->
                    admin.configUpdate(
                        actor(),
                        Map.of(
                            "expectedVersion",
                            before + 1,
                            "changes",
                            List.of(
                                Map.of("key", "M1_MAX_DURATION_HOURS", "value", 4),
                                Map.of("key", "M2_MAX_DURATION_HOURS", "value", 13)))))
            .code);
    assertEquals(before + 1, revisions.findById(1).orElseThrow().version);
    assertEquals(
        3, ((Number) configs.findById("M1_MAX_DURATION_HOURS").orElseThrow().value).intValue());
  }

  @Test
  void lastAdministratorAndRolePermissionsAreProtected() {
    long version = userVersion(actor());
    assertEquals(
        "LAST_SYSTEM_ADMIN",
        assertThrows(
                ApiException.class,
                () ->
                    admin.updateUser(
                        actor(), actor(), Map.of("expectedVersion", version, "enabled", false)))
            .code);
    assertTrue(accounts.findById(actor()).orElseThrow().enabled);
    var adminRole = roles.findByCode("SYSTEM_ADMIN").orElseThrow();
    assertEquals(
        "SYSTEM_ROLE_PROTECTED",
        assertThrows(
                ApiException.class,
                () ->
                    admin.rolePermissions(
                        actor(),
                        adminRole.id,
                        Map.of(
                            "expectedVersion",
                            adminRole.version,
                            "permissionCodes",
                            List.of("role:manage"))))
            .code);
    UUID id = user();
    var r = role("reservation:create");
    UUID roleId = Store.id(r.get("id"));
    admin.roleBindings(
        actor(),
        id,
        Map.of(
            "expectedVersion",
            0,
            "bindings",
            List.of(binding(roleId, Map.of("scopeType", "SELF")))));
    long auth = accounts.findById(id).orElseThrow().authVersion;
    var changed =
        map(
            admin.rolePermissions(
                actor(),
                roleId,
                Map.of("expectedVersion", r.get("version"), "permissionCodes", List.of())));
    assertEquals(
        ((Number) r.get("version")).longValue() + 1, ((Number) changed.get("version")).longValue());
    assertFalse(access.has(id, "reservation:create"));
    assertEquals(auth + 1, accounts.findById(id).orElseThrow().authVersion);
  }

  @Test
  void openingRulesIncrementParentVersionAndRejectStaleUpdate() {
    var r = room(roomAdmin(), null);
    UUID id = Store.id(r.get("id"));
    var changed =
        map(
            admin.opening(
                roomAdmin(),
                id,
                Map.of(
                    "expectedVersion",
                    0,
                    "rules",
                    List.of(Map.of("weekday", 1, "startMinute", 480, "endMinute", 1200)))));
    assertEquals(1, ((Number) changed.get("version")).longValue());
    assertEquals(
        "VERSION_CONFLICT",
        assertThrows(
                ApiException.class,
                () ->
                    admin.opening(
                        roomAdmin(), id, Map.of("expectedVersion", 0, "rules", List.of())))
            .code);
    assertEquals(
        "IMMUTABLE_RELATION",
        assertThrows(
                ApiException.class,
                () ->
                    admin.updateMaster(
                        roomAdmin(),
                        "rooms",
                        id,
                        Map.of("expectedVersion", 1, "departmentId", department())))
            .code);
    assertNull(rooms.findById(id).orElseThrow().departmentId);
  }

  @Test
  void creatingChildAndDisablingParentCannotBothCommit() throws Exception {
    var campus =
        map(
            admin.createMaster(
                actor(),
                "campuses",
                Map.of("code", UUID.randomUUID().toString().substring(0, 20), "name", "并发校区")));
    UUID id = Store.id(campus.get("id"));
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var disable =
          pool.submit(
              () -> {
                barrier.await();
                try {
                  admin.updateMaster(
                      actor(), "campuses", id, Map.of("expectedVersion", 0, "enabled", false));
                  return true;
                } catch (ApiException ex) {
                  assertEquals("RESOURCE_IN_USE", ex.code);
                  return false;
                }
              });
      var create =
          pool.submit(
              () -> {
                barrier.await();
                try {
                  admin.createMaster(
                      actor(),
                      "buildings",
                      Map.of(
                          "campusId",
                          id,
                          "code",
                          UUID.randomUUID().toString().substring(0, 20),
                          "name",
                          "并发楼栋"));
                  return true;
                } catch (ApiException ex) {
                  assertEquals("RESOURCE_DISABLED", ex.code);
                  return false;
                }
              });
      assertNotEquals(disable.get(10, TimeUnit.SECONDS), create.get(10, TimeUnit.SECONDS));
    }
    assertEquals(
        campuses.findById(id).orElseThrow().enabled, buildings.existsByCampusIdAndEnabledTrue(id));
  }
}
