package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
  final Store db;
  final AccessService access;
  final ResourceService resource;
  final Events events;
  final PasswordEncoder encoder;
  final Clock clock;

  public AdminService(
      Store d, AccessService a, ResourceService r, Events e, PasswordEncoder p, Clock c) {
    db = d;
    access = a;
    resource = r;
    events = e;
    encoder = p;
    clock = c;
  }

  static final Map<String, String> TABLE =
      Map.of(
          "departments",
          "department",
          "campuses",
          "campus",
          "buildings",
          "building",
          "rooms",
          "room",
          "seats",
          "seat");

  @Transactional(readOnly = true)
  public Object masters(UUID u, String kind, Map<String, String> q, int page, int size) {
    String table = TABLE.get(kind);
    if (table == null) throw ApiException.notFound();
    if (kind.equals("seats"))
      return Pages.of(
          db.list(
              "select * from core.seat where room_id=?1 order by code", Store.id(q.get("roomId"))),
          page,
          size);
    String sql =
        kind.equals("rooms")
            ? "select r.*,b.name building_name,c.name campus_name,(select count(*) from core.seat s"
                  + " where s.room_id=r.id and s.enabled) seat_count from core.room r join"
                  + " core.building b on b.id=r.building_id join core.campus c on c.id=b.campus_id"
                  + " order by r.code"
            : "select * from core." + table + " order by code";
    var rows = db.list(sql);
    if (kind.equals("rooms"))
      rows =
          rows.stream()
              .filter(r -> visibleRoom(u, Store.id(r.get("id"))))
              .filter(
                  r ->
                      !q.containsKey("buildingId")
                          || q.get("buildingId").equals(r.get("buildingId")))
              .toList();
    if (kind.equals("buildings") && q.containsKey("campusId"))
      rows = rows.stream().filter(r -> q.get("campusId").equals(r.get("campusId"))).toList();
    return Pages.of(rows, page, size);
  }

  boolean visibleRoom(UUID u, UUID room) {
    return access.admissible(u, room)
        || access.roomScope(u, "room:manage", room)
        || access.roomScope(u, "repair:read_scope", room)
        || access.roomScope(u, "room_application:review_space", room)
        || access.roomScope(u, "statistics:read", room);
  }

  @Transactional(readOnly = true)
  public Object room(UUID u, UUID id) {
    if (!visibleRoom(u, id)) throw ApiException.notFound();
    var r =
        db.need(
            "select r.*,b.name building_name,c.name campus_name from core.room r join core.building"
                + " b on b.id=r.building_id join core.campus c on c.id=b.campus_id where r.id=?1",
            id);
    r.put(
        "openingRules",
        db.list(
            "select weekday,start_minute,end_minute from core.opening_rule where room_id=?1 order"
                + " by weekday,start_minute",
            id));
    return r;
  }

  void manage(UUID u, String kind, UUID id, Map<String, Object> b) {
    if (kind.equals("rooms") || kind.equals("seats")) {
      UUID room =
          kind.equals("rooms")
              ? id
              : id == null
                  ? Store.id(b.get("roomId"))
                  : Store.id(
                      db.need("select room_id from core.seat where id=?1", id).get("roomId"));
      if (room == null) {
        UUID department = b.get("departmentId") == null ? null : Store.id(b.get("departmentId"));
        if (!access.scope(u, "room:manage", null, department)) throw ApiException.forbidden();
      } else if (!access.roomScope(u, "room:manage", room)) throw ApiException.forbidden();
    } else access.require(u, "user:manage");
  }

  @Transactional
  public Object createMaster(UUID u, String kind, Map<String, Object> b) {
    manage(u, kind, null, b);
    String table = TABLE.get(kind);
    UUID id = UUID.randomUUID();
    String code = DomainPolicy.text(b.get("code"), 32);
    String name = kind.equals("seats") ? null : DomainPolicy.text(b.get("name"), 100);
    switch (kind) {
      case "departments", "campuses" ->
          db.update(
              "insert into core." + table + "(id,code,name) values(?1,?2,?3)", id, code, name);
      case "buildings" ->
          db.update(
              "insert into core.building(id,campus_id,code,name) values(?1,?2,?3,?4)",
              id,
              Store.id(b.get("campusId")),
              code,
              name);
      case "rooms" ->
          db.update(
              "insert into core.room(id,building_id,code,name,department_id)"
                  + " values(?1,?2,?3,?4,?5)",
              id,
              Store.id(b.get("buildingId")),
              code,
              name,
              b.get("departmentId") == null ? null : Store.id(b.get("departmentId")));
      case "seats" -> {
        UUID room = Store.id(b.get("roomId"));
        resource.lock(room);
        db.update(
            "insert into core.seat(id,room_id,code,has_power) values(?1,?2,?3,?4)",
            id,
            room,
            code,
            Boolean.TRUE.equals(b.get("hasPower")));
      }
      default -> throw ApiException.notFound();
    }
    events.audit(
        u,
        "MASTER_CREATED",
        table,
        id,
        kind.equals("rooms") ? id : null,
        null,
        Map.of("code", code));
    return db.need("select * from core." + table + " where id=?1", id);
  }

  @Transactional
  public Object updateMaster(UUID u, String kind, UUID id, Map<String, Object> b) {
    manage(u, kind, id, b);
    String table = TABLE.get(kind);
    UUID room =
        kind.equals("rooms")
            ? id
            : kind.equals("seats")
                ? Store.id(db.need("select room_id from core.seat where id=?1", id).get("roomId"))
                : null;
    if (room != null) resource.lock(room);
    var old = db.need("select * from core." + table + " where id=?1 for update", id);
    DomainPolicy.version(old, b.get("expectedVersion"));
    if (b.containsKey("departmentId")
        || b.containsKey("roomId")
        || b.containsKey("buildingId")
        || b.containsKey("campusId"))
      throw new ApiException(422, "IMMUTABLE_RELATION", "资源归属不能直接修改");
    if (Boolean.FALSE.equals(b.get("enabled"))) {
      if (room != null
          && db.count(
                  "select count(*) from core.resource_claim where room_id=?1 and released_at is"
                      + " null and (end_at is null or end_at>?2) and (cast(?3 as uuid) is null or"
                      + " seat_id is null or seat_id=?3)",
                  room,
                  clock.instant(),
                  kind.equals("seats") ? id : null)
              > 0) throw ApiException.conflict("RESOURCE_IN_USE", "存在有效占用，不能停用");
      String referenced =
          switch (kind) {
            case "departments" ->
                "select (select count(*) from core.app_user where department_id=?1 and"
                    + " enabled)+(select count(*) from core.room where department_id=?1 and"
                    + " enabled)";
            case "campuses" -> "select count(*) from core.building where campus_id=?1 and enabled";
            case "buildings" -> "select count(*) from core.room where building_id=?1 and enabled";
            default -> null;
          };
      if (referenced != null && db.count(referenced, id) > 0)
        throw ApiException.conflict("RESOURCE_IN_USE", "仍有启用数据引用此资源");
    }
    if (b.containsKey("name") && !kind.equals("seats"))
      db.update(
          "update core." + table + " set name=?1 where id=?2",
          DomainPolicy.text(b.get("name"), 100),
          id);
    if (b.containsKey("enabled"))
      db.update("update core." + table + " set enabled=?1 where id=?2", b.get("enabled"), id);
    if (kind.equals("seats") && b.containsKey("hasPower"))
      db.update("update core.seat set has_power=?1 where id=?2", b.get("hasPower"), id);
    db.update("update core." + table + " set version=version+1 where id=?1", id);
    events.audit(
        u,
        "MASTER_UPDATED",
        table,
        id,
        room,
        null,
        Map.of("version", Store.number(old, "version") + 1));
    db.clear();
    return db.need("select * from core." + table + " where id=?1", id);
  }

  @Transactional
  public Object opening(UUID u, UUID room, Map<String, Object> b) {
    manage(u, "rooms", room, b);
    resource.lock(room);
    var r = db.need("select * from core.room where id=?1", room);
    DomainPolicy.version(r, b.get("expectedVersion"));
    if (db.count(
            "select count(*) from core.resource_claim where room_id=?1 and released_at is null and"
                + " (end_at is null or end_at>?2)",
            room,
            clock.instant())
        > 0) throw ApiException.conflict("RESOURCE_IN_USE", "存在有效占用，不能修改开放时间");
    if (!(b.get("rules") instanceof List<?> rules))
      throw new ApiException(422, "INVALID_RULES", "请提供一周开放时间");
    List<int[]> ranges = new ArrayList<>();
    for (Object x : rules) {
      var rule = (Map<?, ?>) x;
      int day = ((Number) rule.get("weekday")).intValue(),
          start = ((Number) rule.get("startMinute")).intValue(),
          end = ((Number) rule.get("endMinute")).intValue();
      if (day < 1
          || day > 7
          || start < 0
          || end > 1440
          || start >= end
          || start % 60 != 0
          || end % 60 != 0
          || ranges.stream().anyMatch(a -> a[0] == day && a[1] < end && a[2] > start))
        throw new ApiException(422, "INVALID_RULES", "开放区间必须为整小时且不能重叠");
      ranges.add(new int[] {day, start, end});
    }
    db.update("delete from core.opening_rule where room_id=?1", room);
    for (int[] x : ranges)
      db.update(
          "insert into core.opening_rule(id,room_id,weekday,start_minute,end_minute)"
              + " values(?1,?2,?3,?4,?5)",
          UUID.randomUUID(),
          room,
          x[0],
          x[1],
          x[2]);
    db.update("update core.room set version=version+1 where id=?1", room);
    events.audit(u, "OPENING_RULES_UPDATED", "room", room, room, null, b.get("rules"));
    db.clear();
    return room(u, room);
  }

  @Transactional(readOnly = true)
  public Object users(UUID u, int page, int size) {
    access.require(u, "user:manage");
    var rows =
        db.list(
            "select"
                + " id,username,school_no,display_name,email,department_id,enabled,auth_version,version"
                + " from core.app_user order by username");
    for (var x : rows) x.put("roleBindings", bindings(Store.id(x.get("id"))));
    return Pages.of(rows, page, size);
  }

  List<Map<String, Object>> bindings(UUID u) {
    var bindings = db.list("select id,role_id from core.user_role where user_id=?1", u);
    for (var b : bindings)
      b.put(
          "scopes",
          db.list(
              "select scope_type,department_id,room_id from core.user_role_scope where"
                  + " user_role_id=?1",
              Store.id(b.get("id"))));
    return bindings;
  }

  void password(String p) {
    if (p.length() < 12
        || p.length() > 64
        || p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new ApiException(422, "INVALID_PASSWORD", "密码须12至64字符，UTF-8不超过72字节");
  }

  @Transactional
  public Object createUser(UUID u, Map<String, Object> b) {
    access.require(u, "user:manage");
    String pw = Objects.toString(b.get("password"), "");
    password(pw);
    String email = email(b.get("email")),
        username = DomainPolicy.text(b.get("username"), 64).toLowerCase(Locale.ROOT);
    UUID id = UUID.randomUUID();
    db.update(
        "insert into"
            + " core.app_user(id,username,school_no,display_name,password_hash,email,department_id)"
            + " values(?1,?2,?3,?4,?5,?6,?7)",
        id,
        username,
        b.get("schoolNo"),
        DomainPolicy.text(b.get("displayName"), 64),
        encoder.encode(pw),
        email,
        Store.id(b.get("departmentId")));
    db.update("insert into core.booking_guard(user_id) values(?1)", id);
    events.audit(u, "USER_CREATED", "user", id, null, null, Map.of("username", username));
    var r = access.user(id);
    r.remove("passwordHash");
    return r;
  }

  String email(Object v) {
    String e = DomainPolicy.text(v, 254);
    if (!e.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
      throw new ApiException(422, "INVALID_EMAIL", "邮箱格式不正确");
    return e;
  }

  @Transactional
  public Object updateUser(UUID u, UUID id, Map<String, Object> b) {
    access.require(u, "user:manage");
    db.advisory("admin-membership");
    var old = db.need("select * from core.app_user where id=?1 for update", id);
    DomainPolicy.version(old, b.get("expectedVersion"));
    if (Boolean.FALSE.equals(b.get("enabled"))) lastAdmin(id);
    if (b.containsKey("email"))
      db.update("update core.app_user set email=?1 where id=?2", email(b.get("email")), id);
    if (b.containsKey("displayName"))
      db.update(
          "update core.app_user set display_name=?1 where id=?2",
          DomainPolicy.text(b.get("displayName"), 64),
          id);
    if (b.containsKey("departmentId"))
      db.update(
          "update core.app_user set department_id=?1 where id=?2",
          Store.id(b.get("departmentId")),
          id);
    if (b.containsKey("enabled"))
      db.update("update core.app_user set enabled=?1 where id=?2", b.get("enabled"), id);
    db.update(
        "update core.app_user set auth_version=auth_version+1,version=version+1,updated_at=?1 where"
            + " id=?2",
        clock.instant(),
        id);
    events.audit(u, "USER_UPDATED", "user", id, null, null, Map.of("changedFields", b.keySet()));
    var r = access.user(id);
    r.remove("passwordHash");
    return r;
  }

  void lastAdmin(UUID id) {
    if (db.count(
                "select count(*) from core.user_role ur join core.role r on r.id=ur.role_id where"
                    + " ur.user_id=?1 and r.code='SYSTEM_ADMIN'",
                id)
            > 0
        && db.count(
                "select count(distinct u.id) from core.app_user u join core.user_role ur on"
                    + " ur.user_id=u.id join core.role r on r.id=ur.role_id where u.enabled and"
                    + " r.code='SYSTEM_ADMIN' and u.id<>?1",
                id)
            == 0) throw ApiException.conflict("LAST_SYSTEM_ADMIN", "不能移除最后一个启用的系统管理员");
  }

  @Transactional
  public Object resetPassword(UUID u, UUID id, Map<String, Object> b) {
    access.require(u, "user:manage");
    var old = db.need("select * from core.app_user where id=?1 for update", id);
    DomainPolicy.version(old, b.get("expectedVersion"));
    String pw = Objects.toString(b.get("newPassword"), "");
    password(pw);
    db.update(
        "update core.app_user set password_hash=?1,auth_version=auth_version+1,version=version+1"
            + " where id=?2",
        encoder.encode(pw),
        id);
    events.audit(u, "PASSWORD_RESET", "user", id, null, null, Map.of());
    return Map.of("updated", true);
  }

  @Transactional(readOnly = true)
  public Object roles(UUID u) {
    access.require(u, "role:manage");
    var rows = db.list("select * from core.role where enabled order by code");
    for (var r : rows)
      r.put(
          "permissionCodes",
          db
              .list(
                  "select p.code from core.role_permission rp join core.permission p on"
                      + " p.id=rp.permission_id where rp.role_id=?1",
                  Store.id(r.get("id")))
              .stream()
              .map(p -> p.get("code"))
              .toList());
    return Pages.of(rows, 1, 100);
  }

  @Transactional
  public Object createRole(UUID u, Map<String, Object> b) {
    access.require(u, "role:manage");
    UUID id = UUID.randomUUID();
    db.update(
        "insert into core.role(id,code,name) values(?1,?2,?3)",
        id,
        DomainPolicy.text(b.get("code"), 64),
        DomainPolicy.text(b.get("name"), 100));
    events.audit(u, "ROLE_CREATED", "role", id, null, null, Map.of());
    return db.need("select * from core.role where id=?1", id);
  }

  @Transactional
  public Object rolePermissions(UUID u, UUID id, Map<String, Object> b) {
    access.require(u, "role:manage");
    var role = db.need("select * from core.role where id=?1 for update", id);
    DomainPolicy.version(role, b.get("expectedVersion"));
    List<?> codes = (List<?>) b.get("permissionCodes");
    if (codes == null) throw new ApiException(422, "INVALID_PERMISSIONS", "权限列表不能为空");
    if (role.get("code").equals("SYSTEM_ADMIN")
        && !codes.containsAll(List.of("user:manage", "role:manage")))
      throw ApiException.conflict("SYSTEM_ROLE_PROTECTED", "系统管理员必须保留账号和权限管理能力");
    Set<UUID> ids = new HashSet<>();
    for (Object code : codes) {
      var p = db.one("select id from core.permission where code=?1", code.toString());
      if (p == null) throw new ApiException(422, "UNKNOWN_PERMISSION", "只能分配已注册的权限");
      ids.add(Store.id(p.get("id")));
    }
    db.update("delete from core.role_permission where role_id=?1", id);
    for (UUID p : ids)
      db.update("insert into core.role_permission(role_id,permission_id) values(?1,?2)", id, p);
    db.update("update core.role set version=version+1 where id=?1", id);
    db.update(
        "update core.app_user set auth_version=auth_version+1 where id in(select user_id from"
            + " core.user_role where role_id=?1)",
        id);
    events.audit(
        u, "ROLE_PERMISSIONS_CHANGED", "role", id, null, null, Map.of("permissionCodes", codes));
    return db.need("select * from core.role where id=?1", id);
  }

  @Transactional
  public Object deleteRole(UUID u, UUID id, Map<String, Object> b) {
    access.require(u, "role:manage");
    var r = db.need("select * from core.role where id=?1 for update", id);
    DomainPolicy.version(r, b.get("expectedVersion"));
    if (r.get("code").equals("SYSTEM_ADMIN")
        || db.count("select count(*) from core.user_role where role_id=?1", id) > 0)
      throw ApiException.conflict("ROLE_IN_USE", "此角色仍被关联或为关键角色，不能停用");
    db.update("update core.role set enabled=false,version=version+1 where id=?1", id);
    events.audit(u, "ROLE_DISABLED", "role", id, null, null, Map.of());
    return Map.of("disabled", true);
  }

  @Transactional
  public Object roleBindings(UUID u, UUID id, Map<String, Object> b) {
    access.require(u, "role:manage");
    db.advisory("admin-membership");
    var old = db.need("select * from core.app_user where id=?1 for update", id);
    DomainPolicy.version(old, b.get("expectedVersion"));
    var bs = (List<?>) b.get("bindings");
    if (bs == null) throw new ApiException(422, "INVALID_BINDINGS", "请提供角色绑定");
    Set<UUID> roleIds = new HashSet<>();
    boolean keepsAdmin = false;
    for (Object x : bs) {
      var bind = (Map<?, ?>) x;
      UUID rid = Store.id(bind.get("roleId"));
      if (!roleIds.add(rid)) throw new ApiException(422, "DUPLICATE_ROLE", "角色不能重复");
      var role = db.need("select * from core.role where id=?1 and enabled", rid);
      if (role.get("code").equals("STUDENT") && old.get("schoolNo") == null)
        throw new ApiException(422, "SCHOOL_NUMBER_REQUIRED", "学生账号必须有学号");
      if (role.get("code").equals("SYSTEM_ADMIN")) keepsAdmin = true;
    }
    if (!keepsAdmin) lastAdmin(id);
    db.update("delete from core.user_role where user_id=?1", id);
    for (Object x : bs) {
      var bind = (Map<?, ?>) x;
      UUID bid = UUID.randomUUID();
      db.update(
          "insert into core.user_role(id,user_id,role_id) values(?1,?2,?3)",
          bid,
          id,
          Store.id(bind.get("roleId")));
      for (Object y : (List<?>) bind.get("scopes")) {
        var s = (Map<?, ?>) y;
        String type = Objects.toString(s.get("scopeType"));
        if (!Set.of("ALL", "ROOM", "DEPARTMENT", "SHARED").contains(type))
          throw new ApiException(422, "INVALID_SCOPE", "授权范围不合法");
        db.update(
            "insert into core.user_role_scope(id,user_role_id,scope_type,room_id,department_id)"
                + " values(?1,?2,?3,?4,?5)",
            UUID.randomUUID(),
            bid,
            type,
            s.get("roomId") == null ? null : Store.id(s.get("roomId")),
            s.get("departmentId") == null ? null : Store.id(s.get("departmentId")));
      }
    }
    db.update(
        "update core.app_user set auth_version=auth_version+1,version=version+1 where id=?1", id);
    events.audit(u, "USER_ROLE_BINDINGS_CHANGED", "user", id, null, null, Map.of("roles", roleIds));
    return Map.of(
        "id", id, "roleBindings", bindings(id), "version", Store.number(old, "version") + 1);
  }

  @Transactional(readOnly = true)
  public Object config(UUID u) {
    access.require(u, "config:manage");
    return Map.of(
        "version",
        db.count("select version from core.config_revision where id=1"),
        "items",
        db.list("select key,value_json as value,version from core.system_config order by key"));
  }

  @Transactional
  public Object configUpdate(UUID u, Map<String, Object> b) {
    access.require(u, "config:manage");
    var revision = db.need("select * from core.config_revision where id=1 for update");
    DomainPolicy.version(revision, b.get("expectedVersion"));
    for (Object x : (List<?>) b.get("changes")) {
      var c = (Map<?, ?>) x;
      String key = Objects.toString(c.get("key"));
      Object value = c.get("value");
      if (!Set.of("M1_MAX_DURATION_HOURS", "M2_MAX_DURATION_HOURS").contains(key)
          || !(value instanceof Number n)
          || n.doubleValue() != n.intValue()
          || n.intValue() < 1
          || n.intValue() > 12) throw new ApiException(422, "INVALID_CONFIG", "最长时长必须为1至12整数小时");
      db.update(
          "update core.system_config set value_json=cast(?1 as"
              + " jsonb),updated_by=?2,version=version+1,updated_at=?3 where key=?4",
          value.toString(),
          u,
          clock.instant(),
          key);
    }
    db.update("update core.config_revision set version=version+1 where id=1");
    events.audit(u, "CONFIG_UPDATED", "config", new UUID(0, 1), null, null, b.get("changes"));
    return config(u);
  }
}
