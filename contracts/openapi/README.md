# API contracts

`core.yaml` describes the 56 public core operations. `internal-core.yaml` describes the five private core operations used by repair. `repairs.yaml` belongs to the repair service. These files use JSON syntax, which is valid YAML, so repository tooling can read them without an additional YAML parser.

Every public core operation has a named request/response model where a body exists. Paged responses declare `items`, `page`, `size`, and `total`. Errors reference the shared `Error` model; rate limiting documents `Retry-After`. Request models keep the current behavior for additional fields: the complete original JSON contributes to the idempotency hash, including unknown fields and the distinction between missing and explicit null. Action-dependent audit snapshots and notification payloads are the only intentionally variable response values.

Query parameters reflect implemented filters. Room lists accept `buildingId` and `departmentId` before count/pagination. Availability accepts its resource filters; application lists accept `view`/`status`; statistics accepts inclusive local date bounds and room/department filters. Detail, configuration, display-code and authentication operations do not advertise ignored pagination parameters. The current role and permission catalog endpoints return a fixed first page of up to 100 entries; they do not claim arbitrary pagination support.

New password resets and logout return 204 without a response body. Seat creation returns 201 and notification retry returns 202. Earlier password-reset idempotency records retain their original 200 response when replayed; rewriting a completed record would break replay semantics. The public proxy must reject all internal paths; the internal contract uses machine HTTP Basic authentication, without browser cookies or CSRF.

Run from `apps/web`:

```sh
pnpm generate
pnpm typecheck
pnpm test
```

Umi's `dataFields: ["data"]` generates unwrapped data return types, matching the shared request adapter. Do not edit generated sources. The private contract is deliberately not part of the browser client.

`python3 tests/integration/run_core_contract.py` uses isolated PostgreSQL and real random-port HTTP. Its assertions check response required fields, types, enums, nullability, formats, and undeclared fields against these schemas. The checker covers the schema features used in this repository; it is not a general-purpose OpenAPI validator. A successful generator run or a schema check alone does not establish that every operation's permissions and business behavior have been exercised. Java request DTO migration remains a separate implementation step.
