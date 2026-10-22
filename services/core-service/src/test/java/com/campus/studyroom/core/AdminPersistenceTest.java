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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
