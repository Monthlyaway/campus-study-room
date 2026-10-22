package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {
  final Store db;
  final AccessService access;
  final ResourceService resource;
  final Events events;
  final Clock clock;
  final BookingService bookings;

  public ApplicationService(
      Store d, AccessService a, ResourceService r, Events e, Clock c, BookingService b) {
    db = d;
    access = a;
    resource = r;
    events = e;
    clock = c;
    bookings = b;
  }

  static final String DETAIL =
      "select a.*,r.name room_name,r.code room_code from core.room_application a join core.room r"
          + " on r.id=a.room_id ";

  @Transactional
  public Object create(UUID user, Map<String, Object> b) {
    access.require(user, "room_application:create");
    UUID room = Store.id(b.get("roomId"));
    Room r = resource.lock(room);
    if (!access.admissible(user, room)) throw ApiException.notFound();
    if (!r.enabled) throw new ApiException(422, "RESOURCE_DISABLED", "教室已停用");
    Instant start = DomainPolicy.time(b.get("startAt")),
        end = DomainPolicy.time(b.get("endAt")),
        now = clock.instant();
    int max = bookings.max("M2_MAX_DURATION_HOURS");
    DomainPolicy.interval(start, end, now, max);
    resource.opening(room, start, end);
    resource.ensureAvailable(room, null, start, end);
    UUID id = UUID.randomUUID();
    UUID dept = Store.id(access.user(user).get("departmentId"));
    Instant expiry = start.isBefore(now.plusSeconds(86400)) ? start : now.plusSeconds(86400);
    db.update(
        "insert into"
            + " core.room_application(id,applicant_id,applicant_department_id,room_id,start_at,end_at,purpose_title,purpose_description,status,current_step,expires_at,config_version,max_duration_hours)"
            + " values(?1,?2,?3,?4,?5,?6,?7,?8,'PENDING',1,?9,?10,?11)",
        id,
        user,
        dept,
        room,
        start,
        end,
        DomainPolicy.text(b.get("purposeTitle"), 100),
        DomainPolicy.text(b.get("purposeDescription"), 2000),
        expiry,
        bookings.configVersion(),
        max);
    for (int i = 1; i <= 2; i++)
      db.update(
          "insert into core.approval_step(id,application_id,step_no,required_permission,status)"
              + " values(?1,?2,?3,?4,?5)",
          UUID.randomUUID(),
          id,
          i,
          i == 1 ? "room_application:review_dept" : "room_application:review_space",
          i == 1 ? "PENDING" : "WAITING");
    events.task("M2_EXPIRE", "M2", id, expiry);
    events.task("M2_COMPLETE", "M2", id, end);
    events.audit(user, "APPLICATION_CREATED", "M2", id, room, null, "PENDING");
    return detail(user, id);
  }

  boolean canReview(UUID user, Map<String, Object> a) {
    if (user.equals(Store.id(a.get("applicantId")))) return false;
    int step = ((Number) a.get("currentStep")).intValue();
    UUID room = Store.id(a.get("roomId"));
    if (step == 1)
      return access.scope(
          user, "room_application:review_dept", room, Store.id(a.get("applicantDepartmentId")));
    return access.roomScope(user, "room_application:review_space", room)
        && db.count(
                "select count(*) from core.approval_action where application_id=?1 and actor_id=?2",
                Store.id(a.get("id")),
                user)
            == 0;
  }

  boolean readable(UUID user, Map<String, Object> a) {
    return user.equals(Store.id(a.get("applicantId")))
        || access.scope(
            user,
            "room_application:review_dept",
            Store.id(a.get("roomId")),
            Store.id(a.get("applicantDepartmentId")))
        || access.roomScope(user, "room_application:review_space", Store.id(a.get("roomId")));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> detail(UUID user, UUID id) {
    var a = db.need(DETAIL + "where a.id=?1", id);
    if (!readable(user, a)) throw ApiException.notFound();
    a.put(
        "steps",
        db.list(
            "select step_no,required_permission,status from core.approval_step where"
                + " application_id=?1 order by step_no",
            id));
    a.put(
        "actions",
        db.list(
            "select aa.step_no,aa.actor_id,u.display_name"
                + " actor_name,aa.decision,aa.comment,aa.occurred_at from core.approval_action aa"
                + " join core.app_user u on u.id=aa.actor_id where application_id=?1 order by"
                + " step_no",
            id));
    List<String> actions = new ArrayList<>();
    String state = a.get("status").toString();
    if (state.equals("PENDING")
        && clock.instant().isBefore(DomainPolicy.time(a.get("expiresAt")))
        && canReview(user, a)) {
      actions.add("approve");
      actions.add("reject");
    }
    if (user.equals(Store.id(a.get("applicantId")))
        && (state.equals("PENDING")
            || state.equals("APPROVED")
                && clock.instant().isBefore(DomainPolicy.time(a.get("startAt")))))
      actions.add("cancel");
    else if (state.equals("APPROVED")
        && access.roomScope(user, "room_application:cancel_scope", Store.id(a.get("roomId"))))
      actions.add("cancel");
    a.put("allowedActions", actions);
    return a;
  }

  @Transactional(readOnly = true)
  public Object list(UUID u, String view, String status, int page, int size) {
    var all = db.list(DETAIL + "order by a.created_at desc");
    var rows =
        all.stream()
            .filter(a -> readable(u, a))
            .filter(a -> status == null || status.isBlank() || status.equals(a.get("status")))
            .filter(
                a ->
                    switch (view) {
                      case "pending-review" -> "PENDING".equals(a.get("status")) && canReview(u, a);
                      case "reviewed" ->
                          db.count(
                                  "select count(*) from core.approval_action where"
                                      + " application_id=?1 and actor_id=?2",
                                  Store.id(a.get("id")),
                                  u)
                              > 0;
                      default -> u.equals(Store.id(a.get("applicantId")));
                    })
            .map(a -> detail(u, Store.id(a.get("id"))))
            .toList();
    return Pages.of(rows, page, size);
  }

  @Transactional
  public Object decision(UUID user, UUID id, Map<String, Object> b) {
    var a = db.need("select * from core.room_application where id=?1", id);
    UUID room = Store.id(a.get("roomId"));
    Room r = resource.lock(room);
    a = db.need("select * from core.room_application where id=?1 for update", id);
    if (!readable(user, a)) throw ApiException.notFound();
    DomainPolicy.version(a, b.get("expectedVersion"));
    DomainPolicy.requireState(a, "PENDING");
    if (!clock.instant().isBefore(DomainPolicy.time(a.get("expiresAt"))))
      throw new ApiException(422, "APPLICATION_EXPIRED", "申请已到审批截止时间");
    if (!canReview(user, a)) throw ApiException.forbidden();
    String decision = Objects.toString(b.get("decision"));
    if (!Set.of("APPROVE", "REJECT").contains(decision))
      throw new ApiException(422, "INVALID_DECISION", "审批结果不合法");
    String comment = Objects.toString(b.get("comment"), "").trim();
    if (decision.equals("REJECT")) comment = DomainPolicy.text(comment, 500);
    else if (comment.length() > 500) throw new ApiException(422, "INVALID_TEXT", "理由不得超过500字");
    int step = ((Number) a.get("currentStep")).intValue();
    String status = "PENDING";
    if (decision.equals("APPROVE") && step == 2) {
      if (!r.enabled) throw new ApiException(422, "RESOURCE_DISABLED", "教室已停用");
      Instant start = DomainPolicy.time(a.get("startAt")), end = DomainPolicy.time(a.get("endAt"));
      resource.opening(room, start, end);
      resource.ensureAvailable(room, null, start, end);
      resource.claim(room, null, start, end, "M2", id, Store.number(a, "version") + 1, user);
      status = "APPROVED";
    } else if (decision.equals("REJECT")) status = "REJECTED";
    db.update(
        "insert into"
            + " core.approval_action(id,application_id,step_no,actor_id,decision,comment,occurred_at)"
            + " values(?1,?2,?3,?4,?5,?6,?7)",
        UUID.randomUUID(),
        id,
        step,
        user,
        decision,
        comment,
        clock.instant());
    db.update(
        "update core.approval_step set status=?1 where application_id=?2 and step_no=?3",
        decision.equals("APPROVE") ? "APPROVED" : "REJECTED",
        id,
        step);
    if (decision.equals("APPROVE") && step == 1)
      db.update(
          "update core.approval_step set status='PENDING' where application_id=?1 and step_no=2",
          id);
    db.update(
        "update core.room_application set status=?1,current_step=?2,version=version+1,updated_at=?3"
            + " where id=?4",
        status,
        decision.equals("APPROVE") && step == 1 ? 2 : step,
        clock.instant(),
        id);
    events.audit(user, "APPLICATION_" + decision, "M2", id, room, "PENDING", status);
    if (!status.equals("PENDING"))
      events.notify(
          Store.id(a.get("applicantId")),
          room,
          status,
          "M2",
          id,
          "0",
          Map.of(
              "message",
              status.equals("APPROVED") ? "您的教室申请已通过" : "您的教室申请未通过",
              "applicationId",
              id));
    return detail(user, id);
  }

  @Transactional
  public Object cancel(UUID user, UUID id, Map<String, Object> b) {
    var a = db.need("select * from core.room_application where id=?1", id);
    UUID room = Store.id(a.get("roomId"));
    resource.lock(room);
    a = db.need("select * from core.room_application where id=?1 for update", id);
    if (!readable(user, a)) throw ApiException.notFound();
    DomainPolicy.version(a, b.get("expectedVersion"));
    DomainPolicy.requireState(a, "PENDING", "APPROVED");
    boolean owner = user.equals(Store.id(a.get("applicantId")));
    String reason = "USER_CANCEL";
    if (!owner
        || "APPROVED".equals(a.get("status"))
            && !clock.instant().isBefore(DomainPolicy.time(a.get("startAt")))) {
      if (!access.roomScope(user, "room_application:cancel_scope", room)
          || !"APPROVED".equals(a.get("status"))) throw ApiException.forbidden();
      reason = DomainPolicy.text(b.get("reason"), 500);
    }
    db.update(
        "update core.room_application set"
            + " status='CANCELLED',cancel_reason=?1,version=version+1,updated_at=?2 where id=?3",
        reason,
        clock.instant(),
        id);
    resource.release("M2", id, user, room);
    events.audit(user, "APPLICATION_CANCELLED", "M2", id, room, a.get("status"), "CANCELLED");
    events.notify(
        Store.id(a.get("applicantId")),
        room,
        "CANCELLED",
        "M2",
        id,
        "0",
        Map.of("message", "教室申请已取消"));
    return detail(user, id);
  }

  @Transactional
  public void task(Map<String, Object> t) {
    UUID id = Store.id(t.get("sourceId"));
    var a = db.one("select * from core.room_application where id=?1", id);
    if (a == null) return;
    UUID room = Store.id(a.get("roomId"));
    resource.lock(room);
    a = db.need("select * from core.room_application where id=?1 for update", id);
    String state = a.get("status").toString(), next = null;
    if (state.equals("PENDING") && !clock.instant().isBefore(DomainPolicy.time(a.get("expiresAt"))))
      next = "EXPIRED";
    if (state.equals("APPROVED") && !clock.instant().isBefore(DomainPolicy.time(a.get("endAt"))))
      next = "COMPLETED";
    if (next != null) {
      UUID user = Store.id(a.get("applicantId"));
      db.update(
          "update core.room_application set status=?1,version=version+1,updated_at=?2 where id=?3",
          next,
          clock.instant(),
          id);
      resource.release("M2", id, user, room);
      events.audit(user, "APPLICATION_" + next, "M2", id, room, state, next);
    }
  }
}
