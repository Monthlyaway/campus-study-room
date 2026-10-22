// @ts-ignore
/* eslint-disable */
import { request } from "../../../api";

/** Administration GET /api/v1/audit-events GET /api/v1/audit-events */
export async function getApiV1AuditEvents(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1AuditEventsParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/audit-events", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",

      ...params,
    },
    ...(options || {}),
  });
}

/** Administration GET /api/v1/notification-deliveries GET /api/v1/notification-deliveries */
export async function getApiV1NotificationDeliveries(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1NotificationDeliveriesParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/notification-deliveries", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",
      ...params,
    },
    ...(options || {}),
  });
}

/** Administration POST /api/v1/notification-deliveries/{id}/retry POST /api/v1/notification-deliveries/${param0}/retry */
export async function postApiV1NotificationDeliveriesIdRetry(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.postApiV1NotificationDeliveriesIdRetryParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(
    `/api/v1/notification-deliveries/${param0}/retry`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      params: { ...queryParams },
      data: body,
      ...(options || {}),
    }
  );
}

/** Administration GET /api/v1/notifications GET /api/v1/notifications */
export async function getApiV1Notifications(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1NotificationsParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/notifications", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",
      ...params,
    },
    ...(options || {}),
  });
}

/** Administration POST /api/v1/notifications/{id}/read POST /api/v1/notifications/${param0}/read */
export async function postApiV1NotificationsIdRead(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.postApiV1NotificationsIdReadParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/notifications/${param0}/read`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** Administration GET /api/v1/permissions GET /api/v1/permissions */
export async function getApiV1Permissions(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1PermissionsParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/permissions", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",
      ...params,
    },
    ...(options || {}),
  });
}

/** Administration GET /api/v1/roles GET /api/v1/roles */
export async function getApiV1Roles(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1RolesParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/roles", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",
      ...params,
    },
    ...(options || {}),
  });
}

/** Administration POST /api/v1/roles POST /api/v1/roles */
export async function postApiV1Roles(
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/roles", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** Administration DELETE /api/v1/roles/{id} DELETE /api/v1/roles/${param0} */
export async function deleteApiV1RolesId(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.deleteApiV1RolesIdParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/roles/${param0}`, {
    method: "DELETE",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** Administration PUT /api/v1/roles/{id}/permissions PUT /api/v1/roles/${param0}/permissions */
export async function putApiV1RolesIdPermissions(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.putApiV1RolesIdPermissionsParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/roles/${param0}/permissions`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** Administration GET /api/v1/statistics/usage GET /api/v1/statistics/usage */
export async function getApiV1StatisticsUsage(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1StatisticsUsageParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/statistics/usage", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",
      ...params,
    },
    ...(options || {}),
  });
}

/** Administration GET /api/v1/system-config GET /api/v1/system-config */
export async function getApiV1SystemConfig(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1SystemConfigParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/system-config", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",
      ...params,
    },
    ...(options || {}),
  });
}

/** Administration PATCH /api/v1/system-config PATCH /api/v1/system-config */
export async function patchApiV1SystemConfig(
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/system-config", {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** Administration GET /api/v1/users GET /api/v1/users */
export async function getApiV1Users(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1UsersParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/users", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",
      ...params,
    },
    ...(options || {}),
  });
}

/** Administration POST /api/v1/users POST /api/v1/users */
export async function postApiV1Users(
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/users", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** Administration PATCH /api/v1/users/{id} PATCH /api/v1/users/${param0} */
export async function patchApiV1UsersId(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.patchApiV1UsersIdParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/users/${param0}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** Administration POST /api/v1/users/{id}/reset-password POST /api/v1/users/${param0}/reset-password */
export async function postApiV1UsersIdResetPassword(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.postApiV1UsersIdResetPasswordParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(
    `/api/v1/users/${param0}/reset-password`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      params: { ...queryParams },
      data: body,
      ...(options || {}),
    }
  );
}

/** Administration PUT /api/v1/users/{id}/role-bindings PUT /api/v1/users/${param0}/role-bindings */
export async function putApiV1UsersIdRoleBindings(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.putApiV1UsersIdRoleBindingsParams,
  body: CoreAPI.RoleBindingCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/users/${param0}/role-bindings`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}
