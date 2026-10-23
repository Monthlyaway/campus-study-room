// @ts-ignore
/* eslint-disable */
import { request } from "../../../api";

/** Applications GET /api/v1/room-applications GET /api/v1/room-applications */
export async function getApiV1RoomApplications(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1RoomApplicationsParams,
  options?: { [key: string]: any }
) {
  return request<CoreAPI.ApplicationPage>("/api/v1/room-applications", {
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

/** Applications POST /api/v1/room-applications POST /api/v1/room-applications */
export async function postApiV1RoomApplications(
  body: CoreAPI.ApplicationCreate,
  options?: { [key: string]: any }
) {
  return request<CoreAPI.ApplicationDetail>("/api/v1/room-applications", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** Applications GET /api/v1/room-applications/{id} GET /api/v1/room-applications/${param0} */
export async function getApiV1RoomApplicationsId(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1RoomApplicationsIdParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<CoreAPI.ApplicationDetail>(
    `/api/v1/room-applications/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** Applications POST /api/v1/room-applications/{id}/cancel POST /api/v1/room-applications/${param0}/cancel */
export async function postApiV1RoomApplicationsIdCancel(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.postApiV1RoomApplicationsIdCancelParams,
  body: CoreAPI.ApplicationCancelCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<CoreAPI.ApplicationDetail>(
    `/api/v1/room-applications/${param0}/cancel`,
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

/** Applications POST /api/v1/room-applications/{id}/decisions POST /api/v1/room-applications/${param0}/decisions */
export async function postApiV1RoomApplicationsIdDecisions(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.postApiV1RoomApplicationsIdDecisionsParams,
  body: CoreAPI.DecisionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<CoreAPI.ApplicationDetail>(
    `/api/v1/room-applications/${param0}/decisions`,
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
