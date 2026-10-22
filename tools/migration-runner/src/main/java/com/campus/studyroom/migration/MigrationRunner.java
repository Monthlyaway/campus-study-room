package com.campus.studyroom.migration;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class MigrationRunner {
  static final Map<String, List<String>> ROLES = new LinkedHashMap<>();

  static {
    ROLES.put(
        "STUDENT",
        List.of(
            "seat:search",
            "reservation:create",
            "reservation:read_own",
            "reservation:cancel_own",
            "reservation:check_in",
            "violation:read_own",
            "repair:create",
            "repair:read_own",
            "repair:cancel_own",
            "repair:accept_own",
            "repair:rate_own",
            "notification:read_own",
            "notification:mark_read_own"));
    ROLES.put(
        "ROOM_APPLICANT",
        List.of(
            "room_application:create", "room_application:read_own", "room_application:cancel_own"));
    ROLES.put("DEPT_APPROVER", List.of("room_application:review_dept"));
    ROLES.put(
        "SPACE_APPROVER",
        List.of("room_application:review_space", "room_application:cancel_scope"));
    ROLES.put(
        "PROPERTY_DISPATCHER",
        List.of("repair:read_scope", "repair:assign", "repair:cancel_scope"));
    ROLES.put("REPAIRER", List.of("repair:read_assigned", "repair:work"));
    ROLES.put(
        "PROPERTY_ADMIN",
        List.of(
            "repair:read_scope",
            "repair:accept_scope",
            "repair:retry_sync",
            "notification:manage"));
    ROLES.put("ROOM_ADMIN", List.of("room:manage", "room:display_code"));
    ROLES.put(
        "OPERATOR", List.of("statistics:read", "reservation:read_scope", "violation:read_scope"));
    ROLES.put(
        "SYSTEM_ADMIN",
        List.of(
            "user:manage", "role:manage", "config:manage", "audit:read", "notification:manage"));
  }

  static String env(String k, String fallback) {
    return System.getenv().getOrDefault(k, fallback);
  }

  static UUID id(String s) {
    return UUID.nameUUIDFromBytes(("studyroom:" + s).getBytes(StandardCharsets.UTF_8));
  }

  public static void main(String[] args) throws Exception {
    String mode = args.length == 0 ? "migrate" : args[0];
    String url = env("DB_URL", "jdbc:postgresql://localhost:5432/studyroom"),
        user = env("DB_USERNAME", "studyroom_migration"),
        password = env("DB_PASSWORD", "");
    if (mode.equals("migrate")) {
      for (String schema : List.of("session", "core", "repair"))
        Flyway.configure()
            .dataSource(url, user, password)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration/" + schema)
            .load()
            .migrate();
      return;
    }
    try (Connection c = DriverManager.getConnection(url, user, password)) {
      c.setAutoCommit(false);
      switch (mode) {
        case "reference" -> reference(c);
        case "demo" -> {
          if (!Set.of("local", "dev", "test").contains(env("ENV_NAME", "local")))
            throw new IllegalStateException("Demo seed forbidden outside local/dev/test");
          reference(c);
          demo(c);
        }
        case "bootstrap" -> {
          reference(c);
          bootstrap(c);
        }
        default -> throw new IllegalArgumentException("Expected migrate|reference|demo|bootstrap");
      }
      c.commit();
      System.out.println("Completed " + mode + " seed idempotently.");
    }
  }

  static void exec(Connection c, String sql, Object... args) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
      p.executeUpdate();
    }
  }

  static boolean exists(Connection c, String sql, Object... args) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
      try (var r = p.executeQuery()) {
        return r.next() && r.getLong(1) > 0;
      }
    }
  }

  static void reference(Connection c) throws SQLException {
    exec(
        c,
        "insert into core.department(id,code,name) values(?,'SYSTEM','系统管理') on conflict(code) do"
            + " nothing",
        id("department:SYSTEM"));
    Set<String> permissions = new TreeSet<>();
    ROLES.values().forEach(permissions::addAll);
    for (String p : permissions)
      exec(
          c,
          "insert into core.permission(id,code,description) values(?,?,?) on conflict(code) do"
              + " nothing",
          id("permission:" + p),
          p,
          p);
    for (var r : ROLES.entrySet()) {
      boolean present = exists(c, "select count(*) from core.role where code=?", r.getKey());
      exec(
          c,
          "insert into core.role(id,code,name) values(?,?,?) on conflict(code) do nothing",
          id("role:" + r.getKey()),
          r.getKey(),
          r.getKey());
      if (!present)
        for (String p : r.getValue())
          exec(
              c,
              "insert into core.role_permission(role_id,permission_id) values(?,?) on conflict do"
                  + " nothing",
              id("role:" + r.getKey()),
              id("permission:" + p));
    }
    exec(
        c,
        "insert into core.system_config(key,value_json)"
            + " values('M1_MAX_DURATION_HOURS','4'),('M2_MAX_DURATION_HOURS','8') on conflict do"
            + " nothing");
  }

  static void user(
      Connection c, String username, String name, String dept, String pw, String... roles)
      throws SQLException {
    UUID uid = id("user:" + username);
    if (!exists(c, "select count(*) from core.app_user where username=?", username)) {
      exec(
          c,
          "insert into"
              + " core.app_user(id,username,school_no,display_name,password_hash,email,department_id)"
              + " values(?,?,?,?,?,?,?)",
          uid,
          username,
          "DEMO-" + username,
          name,
          "{bcrypt}" + new BCryptPasswordEncoder(12).encode(pw),
          username + "@example.invalid",
          id("department:" + dept));
      exec(c, "insert into core.booking_guard(user_id) values(?)", uid);
      for (String role : roles) {
        UUID bind = id("binding:" + username + ":" + role);
        exec(
            c,
            "insert into core.user_role(id,user_id,role_id) values(?,?,?)",
            bind,
            uid,
            id("role:" + role));
        if (!Set.of("STUDENT", "ROOM_APPLICANT", "REPAIRER").contains(role))
          exec(
              c,
              "insert into core.user_role_scope(id,user_role_id,scope_type) values(?,?,'ALL')",
              id("scope:" + bind),
              bind);
      }
    }
  }

  static String password(String key) {
    String p = env(key, "");
    if (p.length() < 12 || p.length() > 64 || p.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException(key + " must contain 12–64 characters / <=72 UTF-8 bytes");
    return p;
  }

  static void demo(Connection c) throws SQLException {
    String pw = password("DEMO_PASSWORD");
    for (String d : List.of("CS", "ART"))
      exec(
          c,
          "insert into core.department(id,code,name) values(?,?,?) on conflict(code) do nothing",
          id("department:" + d),
          d,
          d.equals("CS") ? "计算机学院" : "人文学院");
    exec(
        c,
        "insert into core.campus(id,code,name) values(?,'MAIN','主校区') on conflict(code) do nothing",
        id("campus:MAIN"));
    exec(
        c,
        "insert into core.building(id,campus_id,code,name) values(?,?,'LIBRARY','知行楼') on"
            + " conflict(code) do nothing",
        id("building:LIBRARY"),
        id("campus:MAIN"));
    for (String room : List.of("R101", "R201", "R301")) {
      UUID rid = id("room:" + room);
      String dept = room.equals("R201") ? "CS" : room.equals("R301") ? "ART" : null;
      exec(
          c,
          "insert into core.room(id,building_id,code,name,department_id) values(?,?,?,?,?) on"
              + " conflict(code) do nothing",
          rid,
          id("building:LIBRARY"),
          room,
          room.equals("R101") ? "静思自习室" : room.equals("R201") ? "计算机学院研习室" : "人文学院阅览室",
          dept == null ? null : id("department:" + dept));
      for (int n = 1; n <= 10; n++)
        exec(
            c,
            "insert into core.seat(id,room_id,code,has_power) values(?,?,?,?) on"
                + " conflict(room_id,code) do nothing",
            id("seat:" + room + ":" + n),
            rid,
            String.format("%02d", n),
            n % 2 == 0);
      for (int day = 1; day <= 7; day++)
        exec(
            c,
            "insert into core.opening_rule(id,room_id,weekday,start_minute,end_minute)"
                + " values(?,?,?,480,1320) on conflict(id) do nothing",
            id("opening:" + room + ":" + day),
            rid,
            day);
    }
    user(c, "student", "林同学", "CS", pw, "STUDENT");
    user(c, "student2", "陈同学", "ART", pw, "STUDENT");
    user(c, "applicant", "社团申请人", "CS", pw, "STUDENT", "ROOM_APPLICANT");
    user(c, "dept-approver", "院系审批员", "SYSTEM", pw, "DEPT_APPROVER");
    user(c, "space-approver", "场地审批员", "SYSTEM", pw, "SPACE_APPROVER");
    user(c, "dispatcher", "物业调度员", "SYSTEM", pw, "PROPERTY_DISPATCHER");
    user(c, "repairer", "维修人员", "SYSTEM", pw, "REPAIRER");
    user(c, "property-admin", "物业管理员", "SYSTEM", pw, "PROPERTY_ADMIN");
    user(c, "room-admin", "教室管理员", "SYSTEM", pw, "ROOM_ADMIN");
    user(c, "operator", "运营人员", "SYSTEM", pw, "OPERATOR");
    user(c, "admin", "系统管理员", "SYSTEM", pw, "SYSTEM_ADMIN");
  }

  static void bootstrap(Connection c) throws SQLException {
    if (exists(
        c,
        "select count(*) from core.user_role ur join core.role r on r.id=ur.role_id join"
            + " core.app_user u on u.id=ur.user_id where r.code='SYSTEM_ADMIN' and u.enabled"))
      return;
    String name = env("ADMIN_USERNAME", "").trim().toLowerCase(Locale.ROOT),
        email = env("ADMIN_EMAIL", "").trim(),
        pw = password("ADMIN_PASSWORD");
    if (name.isBlank()
        || !email.contains("@")
        || Set.of("DemoPassword123!", "ChangeMe123456!", "admin12345678").contains(pw))
      throw new IllegalArgumentException(
          "Supply a unique administrator username, email and secure password");
    UUID uid = UUID.randomUUID(), bind = UUID.randomUUID();
    exec(
        c,
        "insert into core.app_user(id,username,display_name,password_hash,email,department_id)"
            + " values(?,?,?,?,?,?)",
        uid,
        name,
        "系统管理员",
        "{bcrypt}" + new BCryptPasswordEncoder(12).encode(pw),
        email,
        id("department:SYSTEM"));
    exec(c, "insert into core.booking_guard(user_id) values(?)", uid);
    exec(
        c,
        "insert into core.user_role(id,user_id,role_id) values(?,?,?)",
        bind,
        uid,
        id("role:SYSTEM_ADMIN"));
    exec(
        c,
        "insert into core.user_role_scope(id,user_role_id,scope_type) values(?,?,'ALL')",
        UUID.randomUUID(),
        bind);
  }
}
