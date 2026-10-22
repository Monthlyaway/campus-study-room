package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import com.campus.studyroom.core.persistence.*;
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
  final ApplicationRepository applications;
  final ApprovalStepRepository steps;
  final ApprovalActionRepository actions;

  public ApplicationService(
      Store d,
      AccessService a,
      ResourceService r,
      Events e,
      Clock c,
      BookingService b,
      ApplicationRepository applications,
      ApprovalStepRepository steps,
      ApprovalActionRepository actions) {
    db = d;
    access = a;
    resource = r;
    events = e;
    clock = c;
    bookings = b;
    this.applications = applications;
    this.steps = steps;
    this.actions = actions;
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
    var config = bookings.snapshot("M2_MAX_DURATION_HOURS");
    int max = config.maxDurationHours();
    DomainPolicy.interval(start, end, now, max);
    resource.opening(room, start, end);
    resource.ensureAvailable(room, null, start, end);
    ApplicationEntity a = new ApplicationEntity();
    a.id = UUID.randomUUID();
    a.applicantId = user;
    a.applicantDepartmentId = Store.id(access.user(user).get("departmentId"));
    a.roomId = room;
    a.startAt = start;
    a.endAt = end;
    a.purposeTitle = DomainPolicy.text(b.get("purposeTitle"), 100);
    a.purposeDescription = DomainPolicy.text(b.get("purposeDescription"), 2000);
    a.status = "PENDING";
    a.currentStep = 1;
    a.expiresAt = start.isBefore(now.plusSeconds(86400)) ? start : now.plusSeconds(86400);
    a.configVersion = config.version();
    a.maxDurationHours = max;
    a.createdAt = a.updatedAt = now;
    applications.saveAndFlush(a);
    for (short i = 1; i <= 2; i++) {
      var step = new ApprovalStepEntity();
      step.id = UUID.randomUUID();
      step.applicationId = a.id;
      step.stepNo = i;
      step.requiredPermission =
          i == 1 ? "room_application:review_dept" : "room_application:review_space";
      step.status = i == 1 ? "PENDING" : "WAITING";
      steps.save(step);
    }
    steps.flush();
    events.task("M2_EXPIRE", "M2", a.id, a.expiresAt);
    events.task("M2_COMPLETE", "M2", a.id, end);
    events.audit(user, "APPLICATION_CREATED", "M2", a.id, room, null, "PENDING");
    return detail(user, a.id);
  }

  boolean canReview(UUID user, ApplicationEntity a) {
    if (user.equals(a.applicantId)) return false;
    if (a.currentStep == 1)
      return access.scope(user, "room_application:review_dept", a.roomId, a.applicantDepartmentId);
    return access.roomScope(user, "room_application:review_space", a.roomId)
        && !actions.existsByApplicationIdAndActorId(a.id, user);
  }

  boolean readable(UUID user, ApplicationEntity a) {
    return user.equals(a.applicantId)
        || access.scope(user, "room_application:review_dept", a.roomId, a.applicantDepartmentId)
        || access.roomScope(user, "room_application:review_space", a.roomId);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> detail(UUID user, UUID id) {
    var entity = applications.findById(id).orElseThrow(ApiException::notFound);
    if (!readable(user, entity)) throw ApiException.notFound();
    var a = db.need(DETAIL + "where a.id=?1", id);
    a.put(
        "steps",
        steps.findByApplicationIdOrderByStepNo(id).stream()
            .map(
                s ->
                    Map.of(
                        "stepNo",
                        s.stepNo,
                        "requiredPermission",
                        s.requiredPermission,
                        "status",
                        s.status))
            .toList());
    a.put(
        "actions",
        db.list(
            "select aa.step_no,aa.actor_id,u.display_name"
                + " actor_name,aa.decision,aa.comment,aa.occurred_at from core.approval_action aa"
                + " join core.app_user u on u.id=aa.actor_id where application_id=?1 order by"
                + " step_no",
            id));
    a.put("allowedActions", allowed(user, entity));
    return a;
  }

  List<String> allowed(UUID user, ApplicationEntity a) {
    List<String> result = new ArrayList<>();
    if (a.status.equals("PENDING") && clock.instant().isBefore(a.expiresAt) && canReview(user, a)) {
      result.add("approve");
      result.add("reject");
    }
    if (user.equals(a.applicantId)
        && (a.status.equals("PENDING")
            || a.status.equals("APPROVED") && clock.instant().isBefore(a.startAt)))
      result.add("cancel");
    else if (a.status.equals("APPROVED")
        && access.roomScope(user, "room_application:cancel_scope", a.roomId)) result.add("cancel");
    return result;
  }

  @Transactional(readOnly = true)
  public Object list(UUID u, String view, String status, int page, int size) {
    String
        dept =
            ReadQueries.scope(
                "room_application:review_dept", "?1", "a.room_id", "a.applicant_department_id"),
        space =
            ReadQueries.scope(
                "room_application:review_space", "?1", "a.room_id", "r.department_id");
    String review =
        "a.applicant_id<>?1 and ((a.current_step=1 and "
            + dept
            + ") or (a.current_step=2 and "
            + space
            + " and not exists(select 1 from core.approval_action ac where ac.application_id=a.id"
            + " and ac.actor_id=?1)))";
    String where =
        switch (view) {
          case "mine" -> "a.applicant_id=?1";
          case "pending-review" -> "a.status='PENDING' and " + review;
          case "reviewed" ->
              "("
                  + dept
                  + " or "
                  + space
                  + ") and exists(select 1 from core.approval_action ac where"
                  + " ac.application_id=a.id and ac.actor_id=?1)";
          default -> throw new ApiException(400, "INVALID_VIEW", "列表视图不正确");
        };
    List<Object> p = new ArrayList<>(List.of(u));
    if (status != null && !status.isBlank()) {
      p.add(status);
      where += " and a.status=?2";
    }
    String sql =
        "select a.*,r.name room_name,r.code room_code,("
            + review
            + ") can_review,("
            + ReadQueries.scope(
                "room_application:cancel_scope", "?1", "a.room_id", "r.department_id")
            + ") can_cancel_scope from core.room_application a join core.room r on r.id=a.room_id"
            + " where "
            + where
            + " order by a.created_at desc";
    var result = db.page(sql, page, size, p.toArray());
    for (var a : result.items()) {
      List<String> allowed = new ArrayList<>();
      boolean owner = u.equals(Store.id(a.get("applicantId")));
      if ("PENDING".equals(a.get("status"))
          && clock.instant().isBefore(DomainPolicy.time(a.get("expiresAt")))
          && Boolean.TRUE.equals(a.remove("canReview"))) {
        allowed.add("approve");
        allowed.add("reject");
      }
      if (owner
              && ("PENDING".equals(a.get("status"))
                  || "APPROVED".equals(a.get("status"))
                      && clock.instant().isBefore(DomainPolicy.time(a.get("startAt"))))
          || "APPROVED".equals(a.get("status")) && Boolean.TRUE.equals(a.remove("canCancelScope")))
        allowed.add("cancel");
      a.remove("canReview");
      a.remove("canCancelScope");
      a.put("allowedActions", allowed);
    }
    return result;
  }

  ApplicationEntity locked(UUID id) {
    UUID room = applications.roomId(id).orElseThrow(ApiException::notFound);
    resource.lock(room);
    return applications.lock(id).orElseThrow(ApiException::notFound);
  }

  @Transactional
  public Object decision(UUID user, UUID id, Map<String, Object> b) {
    var a = locked(id);
    if (!readable(user, a)) throw ApiException.notFound();
    DomainPolicy.version(a.version, b.get("expectedVersion"));
    DomainPolicy.requireState(a.status, "PENDING");
    if (!clock.instant().isBefore(a.expiresAt))
      throw new ApiException(422, "APPLICATION_EXPIRED", "申请已到审批截止时间");
    if (!canReview(user, a)) throw ApiException.forbidden();
    String decision = Objects.toString(b.get("decision"));
    if (!Set.of("APPROVE", "REJECT").contains(decision))
      throw new ApiException(422, "INVALID_DECISION", "审批结果不合法");
    String comment = Objects.toString(b.get("comment"), "").trim();
    if (decision.equals("REJECT")) comment = DomainPolicy.text(comment, 500);
    else if (comment.codePointCount(0, comment.length()) > 500)
      throw new ApiException(422, "INVALID_TEXT", "理由不得超过500字");
    short step = a.currentStep;
    if (decision.equals("APPROVE") && step == 2) {
      Room room = resource.lock(a.roomId);
      if (!room.enabled) throw new ApiException(422, "RESOURCE_DISABLED", "教室已停用");
      resource.opening(a.roomId, a.startAt, a.endAt);
      resource.ensureAvailable(a.roomId, null, a.startAt, a.endAt);
      resource.claim(a.roomId, null, a.startAt, a.endAt, "M2", id, a.version + 1, user);
      a.status = "APPROVED";
    } else if (decision.equals("REJECT")) a.status = "REJECTED";
    var action = new ApprovalActionEntity();
    action.id = UUID.randomUUID();
    action.applicationId = id;
    action.stepNo = step;
    action.actorId = user;
    action.decision = decision;
    action.comment = comment;
    action.occurredAt = clock.instant();
    actions.saveAndFlush(action);
    var completed = steps.findByApplicationIdAndStepNo(id, step).orElseThrow();
    completed.status = decision.equals("APPROVE") ? "APPROVED" : "REJECTED";
    steps.save(completed);
    if (decision.equals("APPROVE") && step == 1) {
      a.currentStep = 2;
      var next = steps.findByApplicationIdAndStepNo(id, (short) 2).orElseThrow();
      next.status = "PENDING";
      steps.save(next);
    }
    steps.flush();
    a.updatedAt = clock.instant();
    applications.saveAndFlush(a);
    events.audit(user, "APPLICATION_" + decision, "M2", id, a.roomId, "PENDING", a.status);
    if (!a.status.equals("PENDING"))
      events.notify(
          a.applicantId,
          a.roomId,
          a.status,
          "M2",
          id,
          "0",
          Map.of(
              "message",
              a.status.equals("APPROVED") ? "您的教室申请已通过" : "您的教室申请未通过",
              "applicationId",
              id));
    return detail(user, id);
  }

  @Transactional
  public Object cancel(UUID user, UUID id, Map<String, Object> b) {
    var a = locked(id);
    if (!readable(user, a)) throw ApiException.notFound();
    DomainPolicy.version(a.version, b.get("expectedVersion"));
    DomainPolicy.requireState(a.status, "PENDING", "APPROVED");
    boolean owner = user.equals(a.applicantId);
    String reason = "USER_CANCEL";
    if (!owner || a.status.equals("APPROVED") && !clock.instant().isBefore(a.startAt)) {
      if (!access.roomScope(user, "room_application:cancel_scope", a.roomId)
          || !a.status.equals("APPROVED")) throw ApiException.forbidden();
      reason = DomainPolicy.text(b.get("reason"), 500);
    }
    String before = a.status;
    a.status = "CANCELLED";
    a.cancelReason = reason;
    a.updatedAt = clock.instant();
    applications.saveAndFlush(a);
    resource.release("M2", id, user, a.roomId);
    events.audit(user, "APPLICATION_CANCELLED", "M2", id, a.roomId, before, a.status);
    events.notify(
        a.applicantId, a.roomId, "CANCELLED", "M2", id, "0", Map.of("message", "教室申请已取消"));
    return detail(user, id);
  }

  @Transactional
  public void task(Map<String, Object> t) {
    UUID id = Store.id(t.get("sourceId"));
    if (!applications.existsById(id)) return;
    var a = locked(id);
    String next = null;
    if (a.status.equals("PENDING") && !clock.instant().isBefore(a.expiresAt)) next = "EXPIRED";
    if (a.status.equals("APPROVED") && !clock.instant().isBefore(a.endAt)) next = "COMPLETED";
    if (next != null) {
      String before = a.status;
      a.status = next;
      a.updatedAt = clock.instant();
      applications.saveAndFlush(a);
      resource.release("M2", id, a.applicantId, a.roomId);
      events.systemAudit(a.applicantId, "APPLICATION_" + next, "M2", id, a.roomId, before, next);
    }
  }
}
