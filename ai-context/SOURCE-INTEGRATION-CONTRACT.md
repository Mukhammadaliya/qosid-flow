# Source-Integration Contract (PULL mode)

This document specifies **exactly what a source system must expose** so that
qosid-flow can pull work from it, execute the outbound HTTP calls, and save the
results back. qosid-flow depends on **this contract**, not on any particular
source project — **Biruni is the reference implementation**, but any system that
exposes bmb-style tables on its side and the REST endpoints below can drive
qosid-flow unchanged.

## Overview — two ingress models

qosid-flow has two independent ways to receive work:

| Model | Direction | How | Coupling |
|-------|-----------|-----|----------|
| **(a) PUSH / REST** | caller → qosid-flow | Any system calls `/qosid/api/v1/queue/**` (transparent proxy → `202 ACCEPTED` + polling URL). Fully generic. | None — see `CLAUDE.md` → REST API Endpoints. |
| **(b) PULL** | qosid-flow → source | qosid-flow polls the source every `gateway.polling.interval-ms` (default 5s) and drains pending requests. | This contract. |

**This document specifies model (b), PULL.** For model (a) see `CLAUDE.md`.

The internal pipeline (E1 pull → Kafka → consumer + Circuit Breaker → HTTP →
response save/callback) is described in `ARCHITECTURE.md`. In code, the PULL
connector is `SourceClient` (`app/.../oracle/SourceClient.java`), which implements
`RequestSourceClient` (pull) and `ResponseSinkClient` (save).

All three endpoints below share one base URL and one outbound auth configuration,
set through the Settings UI (there is no per-endpoint host). Nothing is hardcoded
to Biruni.

---

## 1. Pull endpoint — hand pending requests to qosid-flow

- **HTTP method:** `GET`
- **URL:** `gateway.source.base-url` + `gateway.source.request-pull-uri` (both UI-configured; blank ⇒ pull stays dormant, no calls made)
- **Auth:** the configured source-channel auth header(s) (see §4)
- **Response:** a **JSON array** of request objects. An **empty array `[]` means "nothing to pull"** — this is the normal idle state and the pipeline stays dormant with no error.

> The pull endpoint is **consuming** by contract: qosid-flow expects that
> returning a request marks it in-flight on the source side (Biruni's
> `requests$pull` sets status `N → P`). Do not return the same request twice
> unless it must genuinely be retried — qosid-flow has its own idempotency, but
> a non-consuming pull will re-deliver forever.

### Fields qosid-flow reads from each array element

These are the `@JsonProperty` wire names bound to the internal `RequestMessage`.
Unknown extra fields are ignored (lenient deserialization).

| Wire field | Type | Required | Meaning |
|------------|------|----------|---------|
| *(tenant field)* — default `company_id` | number/string | yes* | Primary tenant id. Wire name is `gateway.tenant.field-name` (default `company_id`). Combined with `request_id` to form the internal composite id `{tenant}:{requestId}`. |
| *(extra tenant field)* — default `filial_id` | number/string | no | Secondary tenant id (e.g. branch). Wire name is `gateway.tenant.extra-field-name` (default `filial_id`). Leave the setting **blank** if the source has no second tenant level. |
| `request_id` | number | yes | Source-side unique request id. Correlates the later save. |
| `base_url` | string | yes | Absolute base URL of the external API to call. |
| `uri` | string | no | Path appended to `base_url`. |
| `params` | string | no | "URL parameters including path and query string" (BMB wording, e.g. `/John?year=2025`). **Appended verbatim** to `base_url + uri` — the gateway inserts nothing (since v2.4.2, PR #34). So a query must carry its own `?` (`?a=1&b=2`), and a path segment may precede it (`Catalog_X?$select=Ref_Key`, `Document_D(guid'…')`, `AccumulationRegister_X/Balance?$filter=…`) — the form OData senders need because the entity name is non-ASCII or per-request and cannot live in the static `uri`. A **bare `a=1&b=2` is NOT joined with `?` any more** — it would become `…/patha=1&b=2`; senders that relied on the gateway inserting the separator must add the `?` themselves. The REST proxy (`/qosid/api/v1/queue/**`) prefixes the client's query string with `?` before enqueuing (`ProxyController.querySeparated`), so REST callers are unaffected. Rationale: a path (`Document_Foo`) and a valueless flag (`debug`) are indistinguishable, so any guessing rule fails one of them. See `HttpRequestService.buildFullUrl`. |
| `method` | string | yes | HTTP method for the outbound call (`GET`/`POST`/…). |
| `headers` | object **or** JSON string | no | Headers to forward. Accepts either a JSON object or a JSON-encoded string (tolerant deserializer). |
| `body` | string | no | Request body for the outbound call. |
| `oauth2_provider` | string | no (nullable) | If set, qosid-flow fetches provider credentials (see §3) and injects a Bearer token on the outbound call. `null`/absent ⇒ no OAuth2. |
| `callback_procedure` | string | no | Source-side procedure/marker echoed back on save. |
| `project_code` | string | no | Source project/tenant grouping code. |
| `source_table` | string | no | Origin table (diagnostics / save correlation). |
| `source_id` | number | no | Origin row id (diagnostics / save correlation). |

\* The tenant field is required in the sense that a null tenant id makes the
composite id unusable and the request is rejected to the DLQ. If your system has
no multi-tenancy, still emit a constant tenant field (e.g. `"company_id": 0`).

> **Tenant translation:** qosid-flow's internal model calls these `tenantId` /
> `extraTenantId`; the wire names are translated at the boundary by
> `TenantMappingService` using the configured field names — see SAFETY rule 30.
> The internal model has **no** hardcoded `company_id` binding.

### Example pull response

```json
[
  {
    "company_id": 100,
    "filial_id": 5,
    "request_id": 900123,
    "base_url": "https://api.example.com",
    "uri": "/v1/orders",
    "method": "POST",
    "headers": { "Content-Type": "application/json" },
    "body": "{\"orderId\":42}",
    "oauth2_provider": "supplier22",
    "callback_procedure": "pkg_orders.on_done",
    "project_code": "SB",
    "source_table": "orders_queue",
    "source_id": 88231
  }
]
```

An idle source simply returns `[]`.

---

## 2. Save endpoint — receive the result back

- **HTTP method:** `POST`
- **URL:** `gateway.source.base-url` + `gateway.source.response-save-uri`
- **Auth:** the configured source-channel auth header(s) (see §4)
- **Content-Type:** `application/json`
- **Body:** a **JSON array** of save objects (currently one element per POST).

Each save object:

| Field | Type | Meaning |
|-------|------|---------|
| *(tenant field)* — default `company_id` | number | Same tenant field/name as the pull (translated from internal `tenantId`). `extra` is **not** re-sent on save. |
| `request_id` | number | The `request_id` from the pulled request being answered. |
| `response` | object | `{ "status": <int http status>, "content_type": <string>, "body": <any> }`. |
| `error_message` | string \| null | Non-null when the call failed (HTTP error, circuit-breaker, timeout, …). |

A `2xx` response from the save endpoint = saved OK; anything else is treated as a
failure and retried (see SAFETY rule 6).

### Example save request body

```json
[
  {
    "company_id": 100,
    "request_id": 900123,
    "response": {
      "status": 200,
      "content_type": "application/json",
      "body": { "ok": true, "orderId": 42 }
    },
    "error_message": null
  }
]
```

---

## 3. OAuth2 provider-data endpoint — resolve outbound token credentials

Used **only** when a pulled request carries a non-null `oauth2_provider`.
qosid-flow asks the source for that provider's token-endpoint credentials, then
mints/refreshes a Bearer token per request (cached under `oauth2:token:<provider>`).

- **HTTP method:** `POST`
- **URL:** `gateway.source.base-url` + `gateway.source.oauth2-provider-uri`
- **Auth:** the configured source-channel auth header(s) (see §4)
- **Content-Type:** `application/json`
- **Request body:** `{ <tenant-field>, "oauth2_provider": <name> }`

```json
{ "company_id": 100, "oauth2_provider": "supplier22" }
```

- **Response body** (parsed into `ProviderProperties`, camelCase keys):

| Field | Type | Meaning |
|-------|------|---------|
| `type` | string | `standard` = RFC 6749 `application/x-www-form-urlencoded`; `biruni` or `json-body` = JSON body posted to the token endpoint. Absent/other ⇒ `standard`. |
| `tokenUrl` | string | OAuth2 `client_credentials` token endpoint. Required. |
| `clientId` | string | Client id. Required. |
| `clientSecret` | string | Client secret. |
| `scope` | string | Optional scope. |

```json
{
  "type": "standard",
  "tokenUrl": "https://auth.example.com/oauth/token",
  "clientId": "qosid-client",
  "clientSecret": "s3cr3t",
  "scope": "orders.write"
}
```

> The `type` value `"biruni"` is a **frozen** provider-type token (matched against
> the source's `bmb_oauth2_providers.type` column). `"json-body"` is the newer
> alias for the same JSON-body wire format; both select `JsonBodyOAuth2Client`.

---

## 4. Source-channel outbound auth

All three endpoints above are called with the **same** outbound auth, configured
via the Settings UI (`GET/PUT/POST /qosid/admin/api/source-auth[/test]`) and stored
as `gateway.source.auth.*`:

- `basic` (default) — HTTP Basic; falls back to yaml `SOURCE_USERNAME`/`SOURCE_PASSWORD` when the settings DB has no credentials.
- `apikey` — a configurable header (`gateway.source.auth.apikey.header`, default `X-Api-Key`) carrying a secret value.
- `oauth2` — `client_credentials`; token cached in the **isolated** Redis key `source:oauth2:token` (never the per-request `oauth2:token:<provider>` namespace — SAFETY rule 31). Mint client selectable via `gateway.source.auth.oauth2.client-type` (`standard` \| `json-body`/`biruni`).

See `CONFIGURATION.md` → `gateway.source.auth.*` for the full key list.

---

## 5. Tenant model (optional)

Multi-tenancy is optional:

- Single-tenant / no tenant concept: use one tenant field (default `company_id`)
  and leave `gateway.tenant.extra-field-name` **blank**.
- Two-level tenancy: set both `gateway.tenant.field-name` and
  `gateway.tenant.extra-field-name` to your wire names. `extra` is read on pull
  and (when set) used for OAuth2 fetch keys, but is **not** re-sent on save.

Field names are read live from settings per call — a UI change takes effect
immediately.

---

## 6. The truth about coupling

qosid-flow has **no compile-time or runtime dependency on the Biruni project**.
The only coupling is:

1. This documented JSON contract (§1–§3), and
2. The two provider-type token values `standard` and `biruni`/`json-body` (§3),
   of which `biruni` is a frozen wire/DB value, not a code dependency.

Any system that exposes the three endpoints above (backed by bmb-style
request/response tables and REST on its side) can use qosid-flow in PULL mode.
Biruni is simply the first — the **reference** — implementation.

---

## 7. Adding a new source connector in code

The PULL mechanism is behind two interfaces in `app/.../source/`:

- `RequestSourceClient` — `List<RequestMessage> pullRequests()`
- `ResponseSinkClient` — `boolean saveResponse(ResponseSaveRequest)`

`SourceClient` (`app/.../oracle/SourceClient.java`) is the **reference
implementation** of both — an HTTP client over the `gateway.source.*` endpoints
with `SourceAuthService` auth and `TenantMappingService` boundary translation. To
integrate a fundamentally different source (e.g. a direct DB or message queue),
implement these two interfaces in a new `@Component` and route tenant fields
through `TenantMappingService` at ingress/egress (SAFETY rule 30). See
`EXTENSION-GUIDE.md` for the general recipe.
