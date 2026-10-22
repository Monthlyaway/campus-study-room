// @ts-ignore
/* eslint-disable */
import { request } from "../../../api";

/** Auth GET /api/v1/auth/csrf GET /api/v1/auth/csrf */
export async function getApiV1AuthCsrf(options?: { [key: string]: any }) {
  return request<CoreAPI.Csrf>("/api/v1/auth/csrf", {
    method: "GET",
    ...(options || {}),
  });
}

/** Auth POST /api/v1/auth/login POST /api/v1/auth/login */
export async function postApiV1AuthLogin(
  body: CoreAPI.Login,
  options?: { [key: string]: any }
) {
  return request<CoreAPI.CurrentUser>("/api/v1/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** Auth POST /api/v1/auth/logout POST /api/v1/auth/logout */
export async function postApiV1AuthLogout(options?: { [key: string]: any }) {
  return request<any>("/api/v1/auth/logout", {
    method: "POST",
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
export async function getApiV1Version(options?: { [key: string]: any }) {
  return request<CoreAPI.ServiceVersion>("/api/v1/version", {
    method: "GET",
    ...(options || {}),
  });
}
