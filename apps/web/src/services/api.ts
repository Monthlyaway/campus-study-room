export interface ApiErrorBody {
  code: string;
  message: string;
  details?: Record<string, unknown>;
}
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public requestId = "",
    public details?: Record<string, unknown>
  ) {
    super(message);
  }
}
let token = "";
export function clearCsrf() {
  token = "";
}
export async function csrf(): Promise<string> {
  if (!token) {
    const response = await fetch("/api/v1/auth/csrf", {
      credentials: "same-origin",
    });
    const body = await response.json();
    if (!response.ok)
      throw new ApiError(
        response.status,
        "CSRF_UNAVAILABLE",
        "暂时无法建立安全连接，请稍后重试。"
      );
    token =
      body.data?.csrfToken || body.data?.token || body.csrfToken || body.token;
  }
  return token;
}
export async function api<T = any>(
  path: string,
  options: {
    method?: string;
    body?: unknown;
    key?: string;
    signal?: AbortSignal;
  } = {}
): Promise<T> {
  const relativePath = path.replace(/^\/api\/v1/, "");
  const method = options.method || "GET";
  const writing = method !== "GET";
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "X-Request-ID": crypto.randomUUID(),
  };
  if (writing) headers["X-CSRF-TOKEN"] = await csrf();
  if (writing && !relativePath.startsWith("/auth/"))
    headers["Idempotency-Key"] = options.key || crypto.randomUUID();
  let response: Response;
  try {
    response = await fetch(path.startsWith("/api/") ? path : `/api/v1${path}`, {
      method,
      headers,
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: "same-origin",
      signal: options.signal || AbortSignal.timeout(20000),
    });
  } catch {
    throw new ApiError(
      0,
      "RESULT_UNCONFIRMED",
      writing
        ? "网络连接中断，结果尚未确认。请使用“重试原请求”，不要重复新建。"
        : "暂时无法连接服务，请检查网络后重试。",
      headers["X-Request-ID"]
    );
  }
  if (response.status === 204) return undefined as T;
  const result = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (
      response.status === 401 &&
      relativePath !== "/auth/login" &&
      relativePath !== "/auth/me"
    )
      window.dispatchEvent(new Event("session-expired"));
    if (response.status === 403)
      window.dispatchEvent(new Event("permission-changed"));
    const e = result.error || {};
    const fallback: Record<number, string> = {
      401: "登录已失效，请重新登录。",
      403: "无此操作权限，请联系管理员。",
      404: "记录不存在或不在你的访问范围。",
      409: "数据或资源已变化，请刷新后重试。",
      429: "操作过于频繁，请稍后再试。",
      503: "服务暂时不可用，请稍后重试。",
    };
    throw new ApiError(
      response.status,
      e.code || "REQUEST_FAILED",
      e.message || fallback[response.status] || "操作未完成，请稍后重试。",
      result.requestId || headers["X-Request-ID"],
      e.details
    );
  }
  return result.data === undefined ? result : result.data;
}
export function query(values: Record<string, unknown>) {
  const p = new URLSearchParams();
  Object.entries(values).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  });
  return p.toString();
}
export function items<T = any>(data: any): T[] {
  return Array.isArray(data) ? data : data?.items || [];
}
// Adapter used by the official Umi OpenAPI generated client.
export async function request<T>(url: string, options: any = {}): Promise<T> {
  const suffix = options.params ? `?${query(options.params)}` : "";
  return api<T>(`${url}${suffix}`, {
    method: options.method,
    body: options.data,
    key: options.headers?.["Idempotency-Key"],
  });
}
