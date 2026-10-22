package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import com.campus.studyroom.core.persistence.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
  final AccountRepository accounts;
  final DepartmentRepository departments;
  final CampusRepository campuses;
  final BuildingRepository buildings;
  final RoomRepository rooms;
  final SeatRepository seats;
  final OpeningRuleRepository openings;
  final RoleRepository roles;
  final PermissionRepository permissions;
  final UserRoleRepository userRoles;
  final RoleScopeRepository scopes;
  final RolePermissionRepository rolePermissions;
  final BookingGuardRepository guards;
  final SystemConfigRepository configs;
  final ConfigRevisionRepository revisions;
  final AdministrationResourceRepository claims;
  final AdministrationLocks locks;
  final AccessService access;
  final ResourceService resource;
  final Events events;
  final PasswordEncoder encoder;
  final Clock clock;
  @PersistenceContext EntityManager em;

  public AdminService(
      AccountRepository accounts,
      DepartmentRepository departments,
      CampusRepository campuses,
      BuildingRepository buildings,
      RoomRepository rooms,
      SeatRepository seats,
      OpeningRuleRepository openings,
      RoleRepository roles,
      PermissionRepository permissions,
      UserRoleRepository userRoles,
      RoleScopeRepository scopes,
      RolePermissionRepository rolePermissions,
      BookingGuardRepository guards,
      SystemConfigRepository configs,
      ConfigRevisionRepository revisions,
      AdministrationResourceRepository claims,
      AdministrationLocks locks,
      AccessService access,
      ResourceService resource,
      Events events,
      PasswordEncoder encoder,
      Clock clock) {
    this.accounts = accounts;
    this.departments = departments;
    this.campuses = campuses;
    this.buildings = buildings;
    this.rooms = rooms;
    this.seats = seats;
    this.openings = openings;
    this.roles = roles;
    this.permissions = permissions;
    this.userRoles = userRoles;
    this.scopes = scopes;
    this.rolePermissions = rolePermissions;
    this.guards = guards;
    this.configs = configs;
    this.revisions = revisions;
    this.claims = claims;
    this.locks = locks;
    this.access = access;
    this.resource = resource;
    this.events = events;
    this.encoder = encoder;
    this.clock = clock;
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
  static final Map<String, Class<?>> ENTITY =
      Map.of(
          "departments",
          DepartmentEntity.class,
          "campuses",
          CampusEntity.class,
          "buildings",
          BuildingEntity.class,
          "rooms",
          Room.class,
          "seats",
          SeatEntity.class);
  static final List<String> VISIBLE_PERMISSIONS =
      List.of(
          "room:manage", "repair:read_scope", "room_application:review_space", "statistics:read");

  static Pageable pageable(int page, int size, String sort) {
    if (page < 1 || size < 1 || size > 100)
      throw new ApiException(400, "INVALID_PAGE", "页码从1开始，每页1至100条");
    return PageRequest.of(page - 1, size, Sort.by(sort));
  }

  static <T> PageData<Map<String, Object>> page(Page<T> p, Function<T, Map<String, Object>> map) {
    return new PageData<>(
        p.getContent().stream().map(map).toList(),
        p.getNumber() + 1,
        p.getSize(),
        p.getTotalElements());
  }

  static UUID optionalId(Object value) {
    return value == null ? null : Store.id(value);
  }

  static void version(long version, Object expected) {
    DomainPolicy.version(Map.of("version", version), expected);
  }

  static String kind(String kind) {
    if (!TABLE.containsKey(kind)) throw ApiException.notFound();
    return kind;
  }

  @Transactional(readOnly = true)
  public Object masters(UUID u, String kind, Map<String, String> q, int page, int size) {
    kind(kind);
    var p = pageable(page, size, "code");
    return switch (kind) {
      case "departments" -> page(departments.findAll(p), AdminViews::master);
      case "campuses" -> page(campuses.findAll(p), AdminViews::master);
      case "buildings" ->
          page(
              buildings.findAll(
                  (root, query, cb) ->
                      q.containsKey("campusId")
                          ? cb.equal(root.get("campusId"), Store.id(q.get("campusId")))
                          : cb.conjunction(),
                  p),
              AdminViews::master);
      case "rooms" ->
          roomPage(
              rooms.visible(
                  u,
                  accounts.findById(u).orElseThrow(ApiException::notFound).departmentId,
                  optionalId(q.get("buildingId")),
                  optionalId(q.get("departmentId")),
                  VISIBLE_PERMISSIONS,
                  p));
      case "seats" -> {
        UUID room = Store.id(q.get("roomId"));
        if (!visibleRoom(u, room)) throw ApiException.notFound();
        yield page(seats.findByRoomId(room, p), AdminViews::master);
      }
      default -> throw ApiException.notFound();
    };
  }

  boolean visibleRoom(UUID u, UUID room) {
    return access.admissible(u, room)
        || VISIBLE_PERMISSIONS.stream().anyMatch(p -> access.roomScope(u, p, room));
  }

  PageData<Map<String, Object>> roomPage(Page<Room> page) {
    if (page.isEmpty())
      return new PageData<>(
          List.of(), page.getNumber() + 1, page.getSize(), page.getTotalElements());
    Map<UUID, BuildingEntity> buildingById = new HashMap<>();
    buildings
        .findAllById(page.map(r -> r.buildingId).toSet())
        .forEach(b -> buildingById.put(b.id, b));
    Map<UUID, CampusEntity> campusById = new HashMap<>();
    campuses
        .findAllById(buildingById.values().stream().map(b -> b.campusId).distinct().toList())
        .forEach(c -> campusById.put(c.id, c));
    Map<UUID, Long> seatCounts = new HashMap<>();
    seats
        .enabledCounts(page.map(r -> r.id).toList())
        .forEach(c -> seatCounts.put(c.getRoomId(), c.getSeatCount()));
    return page(
        page,
        r -> {
          var result = AdminViews.master(r);
          var building = buildingById.get(r.buildingId);
          result.put("buildingName", building.name);
          result.put("campusName", campusById.get(building.campusId).name);
          result.put("seatCount", seatCounts.getOrDefault(r.id, 0L));
          return result;
        });
  }

  Map<String, Object> roomView(Room room) {
    var m = AdminViews.master(room);
    var b = buildings.findById(room.buildingId).orElseThrow(ApiException::notFound);
    m.put("buildingName", b.name);
    m.put("campusName", campuses.findById(b.campusId).orElseThrow(ApiException::notFound).name);
    m.put("seatCount", seats.countByRoomIdAndEnabledTrue(room.id));
    return m;
  }

  @Transactional(readOnly = true)
  public Object room(UUID u, UUID id) {
    if (!visibleRoom(u, id)) throw ApiException.notFound();
    var m = roomView(rooms.findById(id).orElseThrow(ApiException::notFound));
    m.put(
        "openingRules",
        openings.findByRoomIdOrderByWeekdayAscStartMinuteAsc(id).stream()
            .map(AdminViews::opening)
            .toList());
    return m;
  }

  UUID masterRoom(String kind, UUID id, Map<String, Object> body) {
    return kind.equals("rooms")
        ? id
        : kind.equals("seats")
            ? (id == null
                ? Store.id(body.get("roomId"))
                : seats.findById(id).orElseThrow(ApiException::notFound).roomId)
            : null;
  }

  void manage(UUID u, String kind, UUID id, Map<String, Object> b) {
    kind(kind);
    if (kind.equals("rooms") || kind.equals("seats")) {
      UUID room = masterRoom(kind, id, b);
      if (room == null
          ? !access.createRoomScope(u, optionalId(b.get("departmentId")))
          : !access.roomScope(u, "room:manage", room)) throw ApiException.forbidden();
    } else access.require(u, "user:manage");
  }

  @Transactional
  public Object createMaster(UUID u, String kind, Map<String, Object> b) {
    manage(u, kind, null, b);
    UUID id = UUID.randomUUID();
    String code = DomainPolicy.text(b.get("code"), 32);
    String name = kind.equals("seats") ? null : DomainPolicy.text(b.get("name"), 100);
    Object entity;
    switch (kind) {
      case "departments" -> {
        var x = new DepartmentEntity();
        x.id = id;
        x.code = code;
        x.name = name;
        x.createdAt = clock.instant();
        x.updatedAt = x.createdAt;
        entity = departments.saveAndFlush(x);
      }
      case "campuses" -> {
        var x = new CampusEntity();
        x.id = id;
        x.code = code;
        x.name = name;
        entity = campuses.saveAndFlush(x);
      }
      case "buildings" -> {
        var x = new BuildingEntity();
        x.id = id;
        x.campusId = Store.id(b.get("campusId"));
        requireEnabled(parent(CampusEntity.class, x.campusId).enabled);
        x.code = code;
        x.name = name;
        entity = buildings.saveAndFlush(x);
      }
      case "rooms" -> {
        var x = new Room();
        x.id = id;
        x.buildingId = Store.id(b.get("buildingId"));
        requireEnabled(parent(BuildingEntity.class, x.buildingId).enabled);
        x.departmentId = optionalId(b.get("departmentId"));
        if (x.departmentId != null)
          requireEnabled(parent(DepartmentEntity.class, x.departmentId).enabled);
        x.code = code;
        x.name = name;
        x.enabled = true;
        entity = rooms.saveAndFlush(x);
      }
      case "seats" -> {
        var x = new SeatEntity();
        x.id = id;
        x.roomId = Store.id(b.get("roomId"));
        requireEnabled(resource.lock(x.roomId).enabled);
        x.code = code;
        x.hasPower = bool(b, "hasPower", false);
        entity = seats.saveAndFlush(x);
      }
      default -> throw ApiException.notFound();
    }
    events.audit(
        u,
        "MASTER_CREATED",
        TABLE.get(kind),
        id,
        kind.equals("rooms") ? id : null,
        null,
        Map.of("code", code));
    return AdminViews.master(entity);
  }

  <T> T parent(Class<T> type, UUID id) {
    T parent = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
    if (parent == null) throw ApiException.notFound();
    return parent;
  }

  static void requireEnabled(boolean enabled) {
    if (!enabled) throw new ApiException(422, "RESOURCE_DISABLED", "所属资源已停用");
  }

  static boolean bool(Map<String, Object> b, String key, boolean fallback) {
    if (!b.containsKey(key)) return fallback;
    if (!(b.get(key) instanceof Boolean v))
      throw new ApiException(422, "INVALID_BOOLEAN", "状态必须为布尔值");
    return v;
  }

  @Transactional
  public Object updateMaster(UUID u, String kind, UUID id, Map<String, Object> b) {
    manage(u, kind, id, b);
    UUID room = masterRoom(kind, id, b);
    if (room != null) resource.lock(room);
    Object old = em.find(ENTITY.get(kind), id, LockModeType.PESSIMISTIC_WRITE);
    if (old == null) throw ApiException.notFound();
    version(((Number) AdminViews.master(old).get("version")).longValue(), b.get("expectedVersion"));
    if (b.containsKey("departmentId")
        || b.containsKey("roomId")
        || b.containsKey("buildingId")
        || b.containsKey("campusId"))
      throw new ApiException(422, "IMMUTABLE_RELATION", "资源归属不能直接修改");
    if (Boolean.FALSE.equals(b.get("enabled"))) {
      if (room != null && claims.inUse(room, kind.equals("seats") ? id : null, clock.instant()))
        throw ApiException.conflict("RESOURCE_IN_USE", "存在有效占用，不能停用");
      boolean referenced =
          switch (kind) {
            case "departments" ->
                accounts.existsByDepartmentIdAndEnabledTrue(id)
                    || rooms.existsByDepartmentIdAndEnabledTrue(id);
            case "campuses" -> buildings.existsByCampusIdAndEnabledTrue(id);
            case "buildings" -> rooms.existsByBuildingIdAndEnabledTrue(id);
            default -> false;
          };
      if (referenced) throw ApiException.conflict("RESOURCE_IN_USE", "仍有启用数据引用此资源");
    }
    String name =
        b.containsKey("name") && !kind.equals("seats")
            ? DomainPolicy.text(b.get("name"), 100)
            : null;
    if (old instanceof DepartmentEntity x) {
      if (name != null) x.name = name;
      x.enabled = bool(b, "enabled", x.enabled);
      x.updatedAt = clock.instant();
    }
    if (old instanceof CampusEntity x) {
      if (name != null) x.name = name;
      x.enabled = bool(b, "enabled", x.enabled);
    }
    if (old instanceof BuildingEntity x) {
      if (name != null) x.name = name;
      x.enabled = bool(b, "enabled", x.enabled);
    }
    if (old instanceof Room x) {
      if (name != null) x.name = name;
      x.enabled = bool(b, "enabled", x.enabled);
    }
    if (old instanceof SeatEntity x) {
      x.enabled = bool(b, "enabled", x.enabled);
      x.hasPower = bool(b, "hasPower", x.hasPower);
    }
    em.flush();
    var result = AdminViews.master(old);
    events.audit(
        u,
        "MASTER_UPDATED",
        TABLE.get(kind),
        id,
        room,
        null,
        Map.of("version", result.get("version")));
    return result;
  }

  @Transactional
  public Object opening(UUID u, UUID room, Map<String, Object> b) {
    manage(u, "rooms", room, b);
    var r = resource.lock(room);
    version(r.version, b.get("expectedVersion"));
    if (claims.inUse(room, null, clock.instant()))
      throw ApiException.conflict("RESOURCE_IN_USE", "存在有效占用，不能修改开放时间");
    if (!(b.get("rules") instanceof List<?> list))
      throw new ApiException(422, "INVALID_RULES", "请提供一周开放时间");
    List<OpeningRuleEntity> ranges = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> rule))
        throw new ApiException(422, "INVALID_RULES", "开放时间格式不正确");
      int day = integer(rule.get("weekday"), "INVALID_RULES"),
          start = integer(rule.get("startMinute"), "INVALID_RULES"),
          end = integer(rule.get("endMinute"), "INVALID_RULES");
      if (day < 1
          || day > 7
          || start < 0
          || end > 1440
          || start >= end
          || start % 60 != 0
          || end % 60 != 0
          || ranges.stream()
              .anyMatch(a -> a.weekday == day && a.startMinute < end && a.endMinute > start))
        throw new ApiException(422, "INVALID_RULES", "开放区间必须为整小时且不能重叠");
      var x = new OpeningRuleEntity();
      x.id = UUID.randomUUID();
      x.roomId = room;
      x.weekday = (short) day;
      x.startMinute = start;
      x.endMinute = end;
      ranges.add(x);
    }
    openings.deleteByRoomId(room);
    openings.flush();
    openings.saveAllAndFlush(ranges);
    locks.changedRelation(r);
    events.audit(u, "OPENING_RULES_UPDATED", "room", room, room, null, b.get("rules"));
    return room(u, room);
  }

  static int integer(Object v, String code) {
    if (!(v instanceof Number n) || n.doubleValue() != n.intValue())
      throw new ApiException(422, code, "请输入整数");
    return n.intValue();
  }

  @Transactional(readOnly = true)
  public Object users(UUID u, int page, int size) {
    return users(u, "", page, size);
  }

  @Transactional(readOnly = true)
  public Object users(UUID u, String search, int page, int size) {
    access.require(u, "user:manage");
    String needle =
        Objects.toString(search, "")
            .trim()
            .toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    return page(
        accounts.findAll(
            (root, query, cb) ->
                needle.isEmpty()
                    ? cb.conjunction()
                    : cb.or(
                        cb.like(cb.lower(root.get("username")), "%" + needle + "%", '\\'),
                        cb.like(cb.lower(root.get("displayName")), "%" + needle + "%", '\\')),
            pageable(page, size, "username")),
        a -> {
          var m = AdminViews.user(a, false);
          m.put("roleBindings", bindings(a.id));
          return m;
        });
  }

  List<Map<String, Object>> bindings(UUID id) {
    return userRoles.findByUserId(id).stream()
        .map(
            b ->
                AdminViews.map(
                    "id",
                    b.id,
                    "roleId",
                    b.roleId,
                    "scopes",
                    scopes.findByUserRoleId(b.id).stream().map(AdminViews::scope).toList()))
        .toList();
  }

  void password(String p) {
    if (p.length() < 12
        || p.length() > 64
        || p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new ApiException(422, "INVALID_PASSWORD", "密码须12至64字符，UTF-8不超过72字节");
  }

  String email(Object v) {
    String e = DomainPolicy.text(v, 254);
    if (!e.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
      throw new ApiException(422, "INVALID_EMAIL", "邮箱格式不正确");
    return e;
  }

  @Transactional
  public Object createUser(UUID u, Map<String, Object> b) {
    access.require(u, "user:manage");
    String pw = Objects.toString(b.get("password"), "");
    password(pw);
    var a = new AccountEntity();
    a.id = UUID.randomUUID();
    a.username = DomainPolicy.text(b.get("username"), 64).toLowerCase(Locale.ROOT);
    a.schoolNo = b.get("schoolNo") == null ? null : DomainPolicy.text(b.get("schoolNo"), 32);
    a.displayName = DomainPolicy.text(b.get("displayName"), 64);
    a.email = email(b.get("email"));
    a.departmentId = Store.id(b.get("departmentId"));
    requireEnabled(parent(DepartmentEntity.class, a.departmentId).enabled);
    a.passwordHash = encoder.encode(pw);
    a.createdAt = clock.instant();
    a.updatedAt = a.createdAt;
    a = accounts.saveAndFlush(a);
    var guard = new BookingGuard();
    guard.userId = a.id;
    guards.saveAndFlush(guard);
    events.audit(u, "USER_CREATED", "user", a.id, null, null, Map.of("username", a.username));
    return AdminViews.user(a, false);
  }

  @Transactional
  public Object updateUser(UUID u, UUID id, Map<String, Object> b) {
    access.require(u, "user:manage");
    locks.membership();
    var a = accounts.lock(id).orElseThrow(ApiException::notFound);
    version(a.version, b.get("expectedVersion"));
    if (Boolean.FALSE.equals(b.get("enabled"))) lastAdmin(id);
    if (b.containsKey("email")) a.email = email(b.get("email"));
    if (b.containsKey("displayName")) a.displayName = DomainPolicy.text(b.get("displayName"), 64);
    if (b.containsKey("departmentId")) {
      a.departmentId = Store.id(b.get("departmentId"));
      requireEnabled(parent(DepartmentEntity.class, a.departmentId).enabled);
    }
    a.enabled = bool(b, "enabled", a.enabled);
    a.authVersion++;
    a.updatedAt = clock.instant();
    accounts.flush();
    events.audit(u, "USER_UPDATED", "user", id, null, null, Map.of("changedFields", b.keySet()));
    return AdminViews.user(a, false);
  }

  void lastAdmin(UUID id) {
    if (userRoles.hasRole(id, "SYSTEM_ADMIN") && accounts.otherEnabledAdministrators(id) == 0)
      throw ApiException.conflict("LAST_SYSTEM_ADMIN", "不能移除最后一个启用的系统管理员");
  }

  @Transactional
  public Object resetPassword(UUID u, UUID id, Map<String, Object> b) {
    access.require(u, "user:manage");
    var a = accounts.lock(id).orElseThrow(ApiException::notFound);
    version(a.version, b.get("expectedVersion"));
    String pw = Objects.toString(b.get("newPassword"), "");
    password(pw);
    a.passwordHash = encoder.encode(pw);
    a.authVersion++;
    a.updatedAt = clock.instant();
    accounts.flush();
    events.audit(u, "PASSWORD_RESET", "user", id, null, null, Map.of());
    return Map.of("updated", true);
  }

  @Transactional(readOnly = true)
  public Object roles(UUID u) {
    access.require(u, "role:manage");
    var found = roles.findByEnabledTrue(PageRequest.of(0, 100, Sort.by("code")));
    Map<UUID, List<String>> codesByRole = new HashMap<>();
    if (!found.isEmpty())
      permissions
          .codesForRoles(found.map(r -> r.id).toList())
          .forEach(
              row ->
                  codesByRole
                      .computeIfAbsent(row.getRoleId(), ignored -> new ArrayList<>())
                      .add(row.getCode()));
    return page(
        found,
        r -> {
          var m = AdminViews.role(r);
          m.put("permissionCodes", codesByRole.getOrDefault(r.id, List.of()));
          return m;
        });
  }

  @Transactional
  public Object createRole(UUID u, Map<String, Object> b) {
    access.require(u, "role:manage");
    var r = new RoleEntity();
    r.id = UUID.randomUUID();
    r.code = DomainPolicy.text(b.get("code"), 64);
    r.name = DomainPolicy.text(b.get("name"), 100);
    r = roles.saveAndFlush(r);
    events.audit(u, "ROLE_CREATED", "role", r.id, null, null, Map.of());
    return AdminViews.role(r);
  }

  @Transactional
  public Object rolePermissions(UUID u, UUID id, Map<String, Object> b) {
    access.require(u, "role:manage");
    locks.membership();
    var r = roles.lock(id).orElseThrow(ApiException::notFound);
    version(r.version, b.get("expectedVersion"));
    if (!(b.get("permissionCodes") instanceof List<?> codes))
      throw new ApiException(422, "INVALID_PERMISSIONS", "权限列表不能为空");
    if (r.code.equals("SYSTEM_ADMIN") && !codes.containsAll(List.of("user:manage", "role:manage")))
      throw ApiException.conflict("SYSTEM_ROLE_PROTECTED", "系统管理员必须保留账号和权限管理能力");
    Set<UUID> ids = new LinkedHashSet<>();
    for (Object code : codes)
      ids.add(
          permissions
              .findByCode(Objects.toString(code))
              .orElseThrow(() -> new ApiException(422, "UNKNOWN_PERMISSION", "只能分配已注册的权限"))
              .id);
    rolePermissions.deleteByRoleId(id);
    rolePermissions.flush();
    for (UUID permission : ids) {
      var rp = new RolePermissionEntity();
      rp.roleId = id;
      rp.permissionId = permission;
      rolePermissions.save(rp);
    }
    rolePermissions.flush();
    locks.changedRelation(r);
    for (var a : accounts.assignedToRole(id)) {
      a.authVersion++;
      a.updatedAt = clock.instant();
    }
    accounts.flush();
    events.audit(
        u, "ROLE_PERMISSIONS_CHANGED", "role", id, null, null, Map.of("permissionCodes", codes));
    return AdminViews.role(r);
  }

  @Transactional
  public Object deleteRole(UUID u, UUID id, Map<String, Object> b) {
    access.require(u, "role:manage");
    locks.membership();
    var r = roles.lock(id).orElseThrow(ApiException::notFound);
    version(r.version, b.get("expectedVersion"));
    if (r.code.equals("SYSTEM_ADMIN") || userRoles.existsByRoleId(id))
      throw ApiException.conflict("ROLE_IN_USE", "此角色仍被关联或为关键角色，不能停用");
    r.enabled = false;
    roles.flush();
    events.audit(u, "ROLE_DISABLED", "role", id, null, null, Map.of());
    return Map.of("disabled", true);
  }

  @Transactional
  public Object roleBindings(UUID u, UUID id, Map<String, Object> b) {
    access.require(u, "role:manage");
    locks.membership();
    var a = accounts.lock(id).orElseThrow(ApiException::notFound);
    version(a.version, b.get("expectedVersion"));
    if (!(b.get("bindings") instanceof List<?> list))
      throw new ApiException(422, "INVALID_BINDINGS", "请提供角色绑定");
    Set<UUID> roleIds = new LinkedHashSet<>();
    boolean keepsAdmin = false;
    List<UserRoleEntity> newBindings = new ArrayList<>();
    List<RoleScopeEntity> newScopes = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> bind) || !(bind.get("scopes") instanceof List<?> scopeList))
        throw new ApiException(422, "INVALID_BINDINGS", "请提供角色与授权范围");
      UUID rid = Store.id(bind.get("roleId"));
      if (!roleIds.add(rid)) throw new ApiException(422, "DUPLICATE_ROLE", "角色不能重复");
      var r = roles.findById(rid).filter(x -> x.enabled).orElseThrow(ApiException::notFound);
      if (r.code.equals("STUDENT") && a.schoolNo == null)
        throw new ApiException(422, "SCHOOL_NUMBER_REQUIRED", "学生账号必须有学号");
      if (r.code.equals("SYSTEM_ADMIN")) keepsAdmin = true;
      var ur = new UserRoleEntity();
      ur.id = UUID.randomUUID();
      ur.userId = id;
      ur.roleId = rid;
      newBindings.add(ur);
      Set<String> seen = new HashSet<>();
      for (Object scope : scopeList) {
        if (!(scope instanceof Map<?, ?> s))
          throw new ApiException(422, "INVALID_SCOPE", "授权范围不合法");
        var rs = new RoleScopeEntity();
        rs.id = UUID.randomUUID();
        rs.userRoleId = ur.id;
        rs.scopeType = Objects.toString(s.get("scopeType"));
        rs.roomId = optionalId(s.get("roomId"));
        rs.departmentId = optionalId(s.get("departmentId"));
        boolean valid =
            switch (rs.scopeType) {
              case "ALL", "SELF", "SHARED" -> rs.roomId == null && rs.departmentId == null;
              case "ROOM" -> rs.roomId != null && rs.departmentId == null;
              case "DEPARTMENT" -> rs.departmentId != null && rs.roomId == null;
              default -> false;
            };
        if (!valid || !seen.add(rs.scopeType + ":" + rs.roomId + ":" + rs.departmentId))
          throw new ApiException(422, "INVALID_SCOPE", "授权范围不合法或重复");
        if (rs.roomId != null && !rooms.existsById(rs.roomId)) throw ApiException.notFound();
        if (rs.departmentId != null && !departments.existsById(rs.departmentId))
          throw ApiException.notFound();
        newScopes.add(rs);
      }
    }
    if (!keepsAdmin) lastAdmin(id);
    userRoles.deleteByUserId(id);
    userRoles.flush();
    userRoles.saveAllAndFlush(newBindings);
    scopes.saveAllAndFlush(newScopes);
    a.authVersion++;
    a.updatedAt = clock.instant();
    accounts.flush();
    events.audit(u, "USER_ROLE_BINDINGS_CHANGED", "user", id, null, null, Map.of("roles", roleIds));
    return Map.of("id", id, "roleBindings", bindings(id), "version", a.version);
  }

  @Transactional(readOnly = true)
  public Object config(UUID u) {
    access.require(u, "config:manage");
    return Map.of(
        "version",
        revisions.findById(1).orElseThrow(ApiException::notFound).version,
        "items",
        configs.findAll(Sort.by("key")).stream()
            .map(c -> AdminViews.map("key", c.key, "value", c.value, "version", c.version))
            .toList());
  }

  @Transactional
  public Object configUpdate(UUID u, Map<String, Object> b) {
    access.require(u, "config:manage");
    var revision = revisions.lock().orElseThrow(ApiException::notFound);
    version(revision.version, b.get("expectedVersion"));
    if (!(b.get("changes") instanceof List<?> changes) || changes.isEmpty())
      throw new ApiException(422, "INVALID_CONFIG", "请提供配置修改");
    Set<String> seen = new HashSet<>();
    List<Map<String, Object>> previousValues = new ArrayList<>();
    List<Map<String, Object>> nextValues = new ArrayList<>();
    for (Object item : changes) {
      if (!(item instanceof Map<?, ?> c)) throw new ApiException(422, "INVALID_CONFIG", "配置格式不正确");
      String key = Objects.toString(c.get("key"));
      int value = integer(c.get("value"), "INVALID_CONFIG");
      if (!Set.of("M1_MAX_DURATION_HOURS", "M2_MAX_DURATION_HOURS").contains(key)
          || value < 1
          || value > 12
          || !seen.add(key)) throw new ApiException(422, "INVALID_CONFIG", "最长时长必须为1至12整数小时且键不能重复");
      var x = configs.findById(key).orElseThrow(ApiException::notFound);
      previousValues.add(Map.of("key", key, "value", x.value));
      nextValues.add(Map.of("key", key, "value", value));
      x.value = value;
      x.updatedBy = u;
      x.updatedAt = clock.instant();
    }
    configs.flush();
    locks.changedRelation(revision);
    events.audit(u, "CONFIG_UPDATED", "config", new UUID(0, 1), null, previousValues, nextValues);
    return config(u);
  }
}
