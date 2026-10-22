// @ts-ignore
/* eslint-disable */
import { request } from "../../../api";

/** Auth GET /api/v1/auth/csrf GET /api/v1/auth/csrf */
export async function getApiV1AuthCsrf(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1AuthCsrfParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/auth/csrf", {
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

/** Auth POST /api/v1/auth/login POST /api/v1/auth/login */
export async function postApiV1AuthLogin(
  body: CoreAPI.Login,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** Auth POST /api/v1/auth/logout POST /api/v1/auth/logout */
export async function postApiV1AuthLogout(
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/auth/logout", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** Auth GET /api/v1/auth/me GET /api/v1/auth/me */
export async function getApiV1AuthMe(options?: { [key: string]: any }) {
  return request<CoreAPI.CurrentUser>("/api/v1/auth/me", {
    method: "GET",
    ...(options || {}),
  });
}

/** Auth GET /api/v1/version GET /api/v1/version */
export async function getApiV1Version(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: CoreAPI.getApiV1VersionParams,
  options?: { [key: string]: any }
) {
  return request<Record<string, any>>("/api/v1/version", {
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
