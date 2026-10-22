// @ts-ignore
/* eslint-disable */
import { request } from "../../../api";

/** MasterData GET /api/v1/availability/rooms GET /api/v1/availability/rooms */
export async function getApiV1AvailabilityRooms(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1AvailabilityRoomsParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/availability/rooms", {
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

/** MasterData GET /api/v1/availability/seats GET /api/v1/availability/seats */
export async function getApiV1AvailabilitySeats(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1AvailabilitySeatsParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/availability/seats", {
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

/** MasterData GET /api/v1/buildings GET /api/v1/buildings */
export async function getApiV1Buildings(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1BuildingsParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/buildings", {
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

/** MasterData POST /api/v1/buildings POST /api/v1/buildings */
export async function postApiV1Buildings(
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/buildings", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** MasterData PATCH /api/v1/buildings/{id} PATCH /api/v1/buildings/${param0} */
export async function patchApiV1BuildingsId(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.patchApiV1BuildingsIdParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/buildings/${param0}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** MasterData GET /api/v1/campuses GET /api/v1/campuses */
export async function getApiV1Campuses(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1CampusesParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/campuses", {
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

/** MasterData POST /api/v1/campuses POST /api/v1/campuses */
export async function postApiV1Campuses(
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/campuses", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** MasterData PATCH /api/v1/campuses/{id} PATCH /api/v1/campuses/${param0} */
export async function patchApiV1CampusesId(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.patchApiV1CampusesIdParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/campuses/${param0}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** MasterData GET /api/v1/departments GET /api/v1/departments */
export async function getApiV1Departments(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1DepartmentsParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/departments", {
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

/** MasterData POST /api/v1/departments POST /api/v1/departments */
export async function postApiV1Departments(
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/departments", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** MasterData PATCH /api/v1/departments/{id} PATCH /api/v1/departments/${param0} */
export async function patchApiV1DepartmentsId(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.patchApiV1DepartmentsIdParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/departments/${param0}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** MasterData GET /api/v1/rooms GET /api/v1/rooms */
export async function getApiV1Rooms(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1RoomsParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/rooms", {
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

/** MasterData POST /api/v1/rooms POST /api/v1/rooms */
export async function postApiV1Rooms(
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/rooms", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/v1/rooms/${param0} */
export async function getRoom(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getRoomParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<any>(`/api/v1/rooms/${param0}`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** MasterData PATCH /api/v1/rooms/{id} PATCH /api/v1/rooms/${param0} */
export async function patchApiV1RoomsId(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.patchApiV1RoomsIdParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/rooms/${param0}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** MasterData GET /api/v1/rooms/{id}/availability GET /api/v1/rooms/${param0}/availability */
export async function getApiV1RoomsIdAvailability(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1RoomsIdAvailabilityParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/rooms/${param0}/availability`, {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",

      ...queryParams,
    },
    ...(options || {}),
  });
}

/** MasterData GET /api/v1/rooms/{id}/display-code GET /api/v1/rooms/${param0}/display-code */
export async function getApiV1RoomsIdDisplayCode(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1RoomsIdDisplayCodeParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/rooms/${param0}/display-code`, {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",
      ...queryParams,
    },
    ...(options || {}),
  });
}

/** MasterData PUT /api/v1/rooms/{id}/opening-rules PUT /api/v1/rooms/${param0}/opening-rules */
export async function putApiV1RoomsIdOpeningRules(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.putApiV1RoomsIdOpeningRulesParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/rooms/${param0}/opening-rules`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** MasterData GET /api/v1/rooms/{id}/resource-claims GET /api/v1/rooms/${param0}/resource-claims */
export async function getApiV1RoomsIdResourceClaims(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1RoomsIdResourceClaimsParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(
    `/api/v1/rooms/${param0}/resource-claims`,
    {
      method: "GET",
      params: {
        // page has a default value: 1
        page: "1",
        // size has a default value: 20
        size: "20",
        ...queryParams,
      },
      ...(options || {}),
    }
  );
}

/** MasterData GET /api/v1/rooms/{id}/seats GET /api/v1/rooms/${param0}/seats */
export async function getApiV1RoomsIdSeats(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1RoomsIdSeatsParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/rooms/${param0}/seats`, {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",
      ...queryParams,
    },
    ...(options || {}),
  });
}

/** MasterData POST /api/v1/rooms/{id}/seats POST /api/v1/rooms/${param0}/seats */
export async function postApiV1RoomsIdSeats(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.postApiV1RoomsIdSeatsParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/rooms/${param0}/seats`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** MasterData PATCH /api/v1/seats/{id} PATCH /api/v1/seats/${param0} */
export async function patchApiV1SeatsId(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.patchApiV1SeatsIdParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<Record<string, any>>(`/api/v1/seats/${param0}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}
