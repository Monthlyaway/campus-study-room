import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";
const source = await readFile(
  new URL("../src/services/api.ts", import.meta.url),
  "utf8"
);
const code = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
}).outputText;
async function client() {
  return import(
    `data:text/javascript;base64,${Buffer.from(
      code + "\n// " + crypto.randomUUID()
    ).toString("base64")}`
  );
}
const respond = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });
test("TC-D-01: writes send same-origin cookies, CSRF, request id and caller idempotency key", async () => {
  const calls = [];
  global.fetch = async (url, options) => {
    calls.push({ url, options });
    return respond(
      url.endsWith("/csrf")
        ? { data: { csrfToken: "csrf-value" } }
        : { data: { id: "reservation-1" }, requestId: "response-id" }
    );
  };
  const c = await client();
  const key = crypto.randomUUID();
  const result = await c.api("/reservations", {
    method: "POST",
    body: { seatId: "seat-1" },
    key,
  });
  assert.equal(result.id, "reservation-1");
  assert.equal(calls[1].options.credentials, "same-origin");
  assert.equal(calls[1].options.headers["X-CSRF-TOKEN"], "csrf-value");
  assert.equal(calls[1].options.headers["Idempotency-Key"], key);
  assert.match(calls[1].options.headers["X-Request-ID"], /^[\da-f-]{36}$/);
  assert.equal(calls[1].options.body, '{"seatId":"seat-1"}');
});
test("T10-06: uncertain network response remains uncertain and same-key retry is preserved", async () => {
  const calls = [];
  let writes = 0;
  global.fetch = async (url, options) => {
    if (url.endsWith("/csrf")) return respond({ data: { csrfToken: "t" } });
    calls.push(options);
    if (++writes === 1) throw new Error("response lost");
    return respond({ data: { id: "same-business-result" } });
  };
  const c = await client();
  const options = {
    method: "POST",
    body: { seatId: "one" },
    key: crypto.randomUUID(),
  };
  await assert.rejects(
    c.api("/reservations", options),
    (e) => e.code === "RESULT_UNCONFIRMED" && e.status === 0
  );
  assert.equal(
    (await c.api("/reservations", options)).id,
    "same-business-result"
  );
  assert.equal(
    calls[0].headers["Idempotency-Key"],
    calls[1].headers["Idempotency-Key"]
  );
  assert.equal(calls[0].body, calls[1].body);
});
test("T30-04: final approval conflict is a rejected promise, never a success", async () => {
  global.fetch = async (url) =>
    respond(
      url.endsWith("/csrf")
        ? { data: { csrfToken: "t" } }
        : {
            error: { code: "RESOURCE_CONFLICT", message: "该时段已有占用" },
            requestId: "trace-1",
          },
      url.endsWith("/csrf") ? 200 : 409
    );
  const c = await client();
  await assert.rejects(
    c.api("/room-applications/a/decisions", {
      method: "POST",
      body: { decision: "APPROVE", expectedVersion: 1 },
      key: crypto.randomUUID(),
    }),
    (e) =>
      e.status === 409 &&
      e.code === "RESOURCE_CONFLICT" &&
      e.requestId === "trace-1"
  );
});
test("TC-D-01: login omits business idempotency key and CSRF is renewed after login", async () => {
  const calls = [];
  let csrf = 0;
  global.fetch = async (url, options) => {
    calls.push({ url, options });
    return respond(
      url.endsWith("/csrf")
        ? { data: { csrfToken: `t${++csrf}` } }
        : { data: { userId: "u" } }
    );
  };
  const c = await client();
  await c.api("/auth/login", {
    method: "POST",
    body: { username: "u", password: "private" },
  });
  assert.equal(calls[1].options.headers["Idempotency-Key"], undefined);
  c.clearCsrf();
  await c.csrf();
  assert.equal(csrf, 2);
});
test("T10-06: list query omits undefined filters and preserves encoded offsets", async () => {
  const c = await client();
  const q = c.query({
    startAt: "2026-10-23T09:00:00+08:00",
    roomId: undefined,
    page: 1,
    hasPower: false,
  });
  const parsed = new URLSearchParams(q);
  assert.equal(parsed.has("roomId"), false);
  assert.equal(parsed.get("startAt"), "2026-10-23T09:00:00+08:00");
  assert.equal(parsed.get("hasPower"), "false");
  assert.deepEqual(c.items({ items: [1], total: 1 }), [1]);
});

test("T10-06: generated client absolute auth path uses login semantics", async () => {
  const calls = [];
  global.fetch = async (url, options) => {
    calls.push({ url, options });
    return respond(
      url.endsWith("/csrf")
        ? { data: { csrfToken: "token" } }
        : { data: { userId: "generated" } }
    );
  };
  const c = await client();
  const result = await c.request("/api/v1/auth/login", {
    method: "POST",
    data: { username: "u", password: "not-logged" },
  });
  assert.equal(result.userId, "generated");
  assert.equal(calls[1].url, "/api/v1/auth/login");
  assert.equal(calls[1].options.headers["Idempotency-Key"], undefined);
});
