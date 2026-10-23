// @ts-ignore
/* eslint-disable */
import { request } from "../../../api";

/** Reservations GET /api/v1/reservations Only the current user. Filters apply before count and pagination; ordered by startAt descending then id. Invalid timestamps or non-increasing date range return 400. GET /api/v1/reservations */
export async function getApiV1Reservations(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1ReservationsParams,
  options?: { [key: string]: any }
) {
  return request<CoreAPI.ReservationPage>("/api/v1/reservations", {
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

/** Reservations POST /api/v1/reservations POST /api/v1/reservations */
export async function postApiV1Reservations(
  body: CoreAPI.ReservationCreate,
  options?: { [key: string]: any }
) {
  return request<CoreAPI.Reservation>("/api/v1/reservations", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** Reservations GET /api/v1/reservations/{id} GET /api/v1/reservations/${param0} */
export async function getApiV1ReservationsId(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1ReservationsIdParams,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<CoreAPI.Reservation>(`/api/v1/reservations/${param0}`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** Reservations POST /api/v1/reservations/{id}/cancel POST /api/v1/reservations/${param0}/cancel */
export async function postApiV1ReservationsIdCancel(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.postApiV1ReservationsIdCancelParams,
  body: CoreAPI.VersionCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<CoreAPI.Reservation>(`/api/v1/reservations/${param0}/cancel`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}

/** Reservations POST /api/v1/reservations/{id}/check-in POST /api/v1/reservations/${param0}/check-in */
export async function postApiV1ReservationsIdCheckIn(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.postApiV1ReservationsIdCheckInParams,
  body: CoreAPI.CheckinCommand,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<CoreAPI.Reservation>(
    `/api/v1/reservations/${param0}/check-in`,
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

/** Reservations GET /api/v1/violations GET /api/v1/violations */
export async function getApiV1Violations(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1ViolationsParams,
  options?: { [key: string]: any }
) {
  return request<CoreAPI.ViolationPage>("/api/v1/violations", {
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
