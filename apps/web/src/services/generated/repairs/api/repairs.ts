// @ts-ignore
/* eslint-disable */
import { request } from "../../../api";

/** 此处后端没有提供注释 GET /api/v1/repairs */
export async function listRepairs(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: RepairAPI.listRepairsParams,
  options?: { [key: string]: any }
) {
  return request<{ data: RepairAPI.RepairOrderPage; requestId: string }>(
    "/api/v1/repairs",
    {
      method: "GET",
      params: {
        // view has a default value: mine
        view: "mine",

        // page has a default value: 1
        page: "1",
        // size has a default value: 20
        size: "20",
        ...params,
      },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/v1/repairs */
export async function createRepair(
  body: RepairAPI.RepairCreate,
  options?: { [key: string]: any }
) {
  return request<{ data: RepairAPI.RepairDetail; requestId: string }>(
    "/api/v1/repairs",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      data: body,
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/v1/repairs/${param0} */
export async function getRepair(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: RepairAPI.getRepairParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<{ data: RepairAPI.RepairDetail; requestId: string }>(
    `/api/v1/repairs/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /api/v1/repairs/${param0}/acceptance */
export async function acceptanceRepair(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: RepairAPI.acceptanceRepairParams,
  body: RepairAPI.RepairAcceptanceRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<{ data: RepairAPI.RepairDetail; requestId: string }>(
    `/api/v1/repairs/${param0}/acceptance`,
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

/** 此处后端没有提供注释 POST /api/v1/repairs/${param0}/assign */
export async function assignRepair(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: RepairAPI.assignRepairParams,
  body: RepairAPI.RepairAssign,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<{ data: RepairAPI.RepairDetail; requestId: string }>(
    `/api/v1/repairs/${param0}/assign`,
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

/** 此处后端没有提供注释 POST /api/v1/repairs/${param0}/cancel */
export async function cancelRepair(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: RepairAPI.cancelRepairParams,
  body: RepairAPI.RepairCancel,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<{ data: RepairAPI.RepairDetail; requestId: string }>(
    `/api/v1/repairs/${param0}/cancel`,
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

/** 此处后端没有提供注释 POST /api/v1/repairs/${param0}/complete */
export async function completeRepair(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: RepairAPI.completeRepairParams,
  body: RepairAPI.RepairComplete,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<{ data: RepairAPI.RepairDetail; requestId: string }>(
    `/api/v1/repairs/${param0}/complete`,
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

/** 此处后端没有提供注释 POST /api/v1/repairs/${param0}/rating */
export async function ratingRepair(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: RepairAPI.ratingRepairParams,
  body: RepairAPI.RepairRatingRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<{ data: RepairAPI.RepairDetail; requestId: string }>(
    `/api/v1/repairs/${param0}/rating`,
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

/** 此处后端没有提供注释 POST /api/v1/repairs/${param0}/retry-sync */
export async function retrySyncRepair(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: RepairAPI.retrySyncRepairParams,
  body: RepairAPI.RepairExpectedVersion,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/v1/repairs/${param0}/retry-sync`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/v1/repairs/${param0}/start */
export async function startRepair(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: RepairAPI.startRepairParams,
  body: RepairAPI.RepairExpectedVersion,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/v1/repairs/${param0}/start`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/v1/repairs/assignable-workers */
export async function listAssignableWorkers(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: RepairAPI.listAssignableWorkersParams,
  options?: { [key: string]: any }
) {
  return request<{ data: RepairAPI.RepairWorkerPage; requestId: string }>(
    "/api/v1/repairs/assignable-workers",
    {
      method: "GET",
      params: {
        // page has a default value: 1
        page: "1",
        // size has a default value: 20
        size: "20",
        ...params,
      },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /api/v1/repairs/audit-events */
export async function listRepairAuditEvents(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: RepairAPI.listRepairAuditEventsParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/repairs/audit-events", {
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
