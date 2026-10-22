import type { User } from "./services/types";
export const can = (user: User | null, permission: string) =>
  !!user?.permissions?.includes(permission);
export const canAny = (user: User | null, permissions: string[]) =>
  permissions.some((p) => can(user, p));
export default function access(initialState: { currentUser?: User }) {
  return {
    can: (permission: string) =>
      can(initialState.currentUser || null, permission),
  };
}

const roleLabels: Record<string, string> = {
  STUDENT: "学生",
  ROOM_APPLICANT: "教室申请人",
  DEPT_APPROVER: "院系审批员",
  SPACE_APPROVER: "空间审批员",
  ROOM_ADMIN: "空间管理员",
  PROPERTY_DISPATCHER: "物业派单员",
  PROPERTY_ADMIN: "物业管理员",
  REPAIRER: "维修人员",
  OPERATOR: "运营人员",
  SYSTEM_ADMIN: "系统管理员",
};
const permissionLabels: Record<string, string> = {
  "seat:search": "查找可用座位",
  "reservation:create": "创建个人预约",
  "reservation:read_own": "查看本人预约",
  "reservation:read_scope": "查看范围内预约",
  "reservation:cancel_own": "取消本人预约",
  "reservation:check_in": "预约签到",
  "violation:read_own": "查看本人未签到记录",
  "violation:read_scope": "查看范围内未签到记录",
  "room_application:create": "申请整间教室",
  "room_application:read_own": "查看本人教室申请",
  "room_application:cancel_own": "撤销本人申请",
  "room_application:cancel_scope": "撤销范围内申请",
  "room_application:review_dept": "院系审批",
  "room_application:review_space": "空间审批",
  "repair:create": "上报设施故障",
  "repair:read_own": "查看本人报修",
  "repair:read_assigned": "查看派给我的工单",
  "repair:read_scope": "查看范围内工单",
  "repair:cancel_own": "取消本人报修",
  "repair:cancel_scope": "取消范围内工单",
  "repair:assign": "派发维修工单",
  "repair:work": "处理维修工单",
  "repair:accept_own": "验收本人报修",
  "repair:rate_own": "评价本人报修",
  "repair:retry_sync": "重试资源同步",
  "notification:read_own": "查看本人通知",
  "notification:mark_read_own": "标记本人通知已读",
  "notification:manage": "管理通知送达",
  "room:manage": "管理空间与座位",
  "room:display_code": "展示教室签到编码",
  "user:manage": "管理校园账号",
  "role:manage": "配置角色权限",
  "config:manage": "调整系统参数",
  "statistics:read": "查看使用统计",
  "audit:read": "查看操作审计",
};
export const roleLabel = (role?: { code?: string; name?: string; [key: string]: any }) =>
  role
    ? role.name && role.name !== role.code
      ? role.name
      : roleLabels[role.code || ""] || role.name || role.code || ""
    : "";
export const permissionLabel = (permission: any) =>
  permissionLabels[
    typeof permission === "string" ? permission : permission.code
  ] ||
  permission.description ||
  permission.code ||
  permission;
