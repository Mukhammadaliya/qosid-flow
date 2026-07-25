# Architecture

## How It Works (To'liq flow)

```
Main Server (HAProxy)
     |
     |  /qosid/api/v1/queue/** prefixli request keldi
     |
     v
ProxyController (qosid-flow)
     |  Auth tekshirish
     |  BiruniUpload bo'lsa → fayllarni S3 ga yuklash
     |  RequestMessage yaratish (source="API")
     |
     v
Kafka (gateway.request.new topic)
     |  Navbatga qo'yildi
     |
     v
RequestConsumer
     |  Navbatdan o'qish
     |  Idempotency check (Redis)
     |  Distributed lock (Redis)
     |
     v
HttpRequestService
     |  Circuit Breaker tekshirish
     |  OAuth2 token olish (kerak bo'lsa)
     |  Main app ga HTTP request yuborish
     |
     v
Main App (tashqi API)
     |  Requestni qayta ishlash
     |  Javob qaytarish
     |
     v
ResponseConsumer
     |  Javobni RocksDB ga saqlash (7 kun retention)
     |  Callback yuborish (agar X-Callback-Url berilgan bo'lsa)
     |  Redis state yangilash → COMPLETED
     |
     v
Client javobni oladi:
     GET /qosid/api/v1/requests/{id}/status  → inline result yoki resultUrl
     GET /qosid/api/v1/requests/{id}/result  → to'liq response body (RocksDB dan)
```

## Pipeline Diagram (texnik)

```
                                   KAFKA
Oracle/Biruni                    (RedPanda)                  External APIs
     |                              |                             |
     |   E1: Pull     E2: Produce  |   E3: Consume   E4: HTTP   |   E5: Save
     |   (5s poll)    (async)       |   (+ Redis)     (+ CB)     |   (+ retry)
     +-------->  RequestPuller -----+----> gateway.request.new   |
              source="ORACLE"       |         |                   |
                                    |    Oracle/ApiRequestConsumer ->+
 HTTP clients                       |         |                   |
     |                              |    (idempotency check)      |
     +-->  /qosid/api/v1/queue/**   |    (distributed lock)       |
          ProxyController ----------+    HttpRequestService ----->+  (Circuit Breaker)
              source="API"          |         |                   |  (OAuth2 if needed)
              (auth + multipart)    |         v                   |
                                    |    gateway.request.response |
                                    |         |                   |
                                    |    Oracle/ApiResponseConsumer +
                                    |         |                   |
                                    |    +--> RocksDB (always if body)
                                    |    +--> Callback (if callbackUrl)
                                    |    +--> Oracle/Biruni (only if source="ORACLE")
                                    |                             |
                             (on failure, after retries)          |
                                    |                             |
                              gateway.request.dlq                 |
                                    |                             |
                              Oracle/ApiDlqConsumer --> Telegram      |

 HTTP clients (status/result lookup)
     |
     +-->  /qosid/api/v1/requests/{id}/status  StatusController
     +-->  /qosid/api/v1/requests/{id}/result  StatusController
     +-->  /qosid/api/v1/callback              CallbackController
```

**Read gate (v1.15.0):** both polling endpoints refuse **ORACLE-sourced** requests with `404`,
and `/result` performs that check before touching RocksDB. The endpoints are unauthenticated by
design (B4), which is only safe for REST-sourced ids because those are `UUID`-derived (~59 bits);
an ORACLE composite id is `<small tenantId>:<sequential request_id>` and therefore enumerable.
The discriminator is `RequestState.source`, persisted by
`RequestStateService.createInitialState(compositeId, source)`. A `null` source stays readable so
pre-upgrade Redis rows do not start 404-ing. See SAFETY-RULES rule 34.

The pipeline has **two entry points** that both feed `gateway.request.new`:

- **E1 Oracle pull** — original polling loop, tags `RequestMessage.source="ORACLE"`.
- **E0 REST proxy** — `ProxyController` at `/qosid/api/v1/queue/**`, tags `source="API"`. Runs `AuthorizationProvider.authorize(...)` first. For `BiruniUpload` requests: parses multipart, uploads files to S3 (3x retry via `FileService.tryUploadFiles()`), replaces both plain (`"\u0000<index>"`) and JSON-string-wrapped (`\"\\u0000<i>\"`) placeholders with SHAs. **S3 success:** sends JSON POST with `files` metadata header (Biruni `run()` path, `store_kind='S'`). **S3 failure:** falls back to multipart forwarding with `BiruniUpload` header (Biruni `runFileUploadParam()` path, files saved to DB). `HOP_BY_HOP_HEADERS` filter strips hop-by-hop headers **and** `accept-encoding` so upstream returns plain bodies (prevents Cloudflare HTTP/1.1 gzip decode artifacts). **Non-multipart bodies are read through a bounded `readBodyBounded(...)`** — `gateway.proxy.max-body-size` (`PROXY_MAX_BODY_SIZE`, default 20 MiB = the Kafka message ceiling), checking the client `Content-Length` up front *and* counting bytes while streaming so an understated/chunked length cannot slip past; over the cap → `PayloadTooLargeException` → HTTP 413, never enqueued. The `BiruniUpload` multipart branch is deliberately **not** covered — it keeps the larger `spring.servlet.multipart.*` limits (50MB/52MB) so real S3 uploads are unchanged. Enqueues via `RequestProducer.sendApiRequest()`.

**Response routing by consumer:** `OracleResponseConsumer` saves to Biruni + RocksDB. `ApiResponseConsumer` saves to RocksDB + fires callback. No source-based `if/else` — routing is done at the topic level.

## Stage Breakdown

### E1: Oracle Pull — `RequestPuller.java`

| Aspect | Detail |
|--------|--------|
| **Trigger** | `@Scheduled(fixedDelayString = "${gateway.polling.interval-ms:5000}")` |
| **Condition** | `@ConditionalOnProperty(name = "gateway.polling.enabled", havingValue = "true")` |
| **Input** | HTTP GET to Biruni API (`/b/biruni/bmb/requests$pull`) |
| **Output** | List of `RequestMessage` objects |
| **Validation** | `RequestValidator.validate()` — checks tenantId, requestId, baseUrl, method |
| **On Failure** | Logs error, increments `oraclePullError` metric |
| **Dormant when unconfigured** | If the effective `gateway.source.base-url` OR `request-pull-uri` (settings-DB → env → yaml) is blank, `SourceClient.pullRequests()` skips the cycle silently — no HTTP call, no `oraclePullError` increment, returns empty — and logs one WARN per unconfigured episode until the endpoints are set via the Settings UI. |
| **Flow** | Pull -> Validate each -> Send valid ones to E2 -> DLQ invalid ones |

> **Source-integration contract:** the pull/save/OAuth2-provider endpoints and JSON shapes qosid-flow expects from ANY source system (Biruni is the reference implementation) are specified in [`SOURCE-INTEGRATION-CONTRACT.md`](SOURCE-INTEGRATION-CONTRACT.md). The connector class is `SourceClient` (renamed from the former Biruni-specific name; package `oracle/` unchanged); the OAuth2 JSON-body client is `JsonBodyOAuth2Client` (its `getName()` still returns `"biruni"` — a frozen provider-type value matched against the Oracle `bmb_oauth2_providers.type` column).

**Source channel auth (B3):** `SourceClient.pullRequests()` resolves the outbound auth header via `SourceAuthService.authHeaders()` — `basic` (default, yaml fallback), `apikey`, or `oauth2` per the `gateway.source.auth.type` setting. OAuth2 tokens are minted by `SourceTokenService` and cached under the Redis key `source:oauth2:token`, kept isolated from the per-request forwarding cache (`oauth2:token:<provider>`) — see SAFETY-RULES.md rule 31. The mint client is selectable via `gateway.source.auth.oauth2.client-type` (`standard` = RFC 6749 form-urlencoded via `StandardOAuth2Client` (default); `json-body`, or the legacy alias `biruni` = JSON body via `JsonBodyOAuth2Client`, for Smartup/Biruni-style sources).

### E2: Kafka Produce — `RequestProducer.java`

| Aspect | Detail |
|--------|--------|
| **Input** | `RequestMessage` from E1 or E0 |
| **Output** | Oracle → `gateway.oracle.request`, API → `gateway.api.request` |
| **Key** | `compositeId` (tenantId:requestId) for partition affinity |
| **Methods** | `sendOracleRequest()`, `sendApiRequest()`, `sendOracleResponse()`, `sendApiResponse()`, `sendToOracleDlq()`, `sendToApiDlq()` |
| **On Failure** | After retries exhausted, sends to source-specific DLQ |

### E3: Consumer Process — `OracleRequestConsumer.java` / `ApiRequestConsumer.java`

Oracle and API flows use **independent** consumers, topics, and thread pools:

| Aspect | Oracle | API |
|--------|--------|-----|
| **Consumer** | `OracleRequestConsumer` | `ApiRequestConsumer` |
| **Topic** | `gateway.oracle.request` | `gateway.api.request` |
| **Factory** | `oracleRequestConsumerFactory` | `apiRequestConsumerFactory` |
| **Thread Pool** | `oracleHttpExecutor` | `apiHttpExecutor` |
| **Ack** | MANUAL | MANUAL |
| **Idempotency** | Redis check + lock | Redis check + lock |
| **On Failure** | Retry → `sendOracleRequest()` or DLQ | Retry → `sendApiRequest()` or DLQ |

**Ack semantics:** `AckMode.MANUAL` for every factory (`KafkaConfig.buildFactory`); `asyncAcks`
is deliberately **off**. `ack` is called from the reactive callback once the HTTP response lands,
so acks arrive **out of offset order**, which `MANUAL` alone does not handle — a fast higher
offset can mask a still-running lower one, which is then never redelivered. `asyncAcks` would fix
that but is mutually exclusive with `nack()`, which both request consumers rely on, so it stays
off until those paths are converted to a bounded re-queue (SAFETY-RULES rule 32 has the full
reasoning and the order of work). Nothing is ever acked away silently: on lock
contention the message is `nack(10s)`-ed back to Kafka (rule 33), on a Redis gate failure
`nack(5s)` (rule 25), and both an expired message (`gateway.retry.max-message-age-ms`, measured
from the `createdAt` stamped at ingress) and a message past the CB re-queue cap
(`gateway.retry.cb-max-requeues`) go to the DLQ via `handlePermanentFailure` (rule 23).

### E4: HTTP Request — `HttpRequestService.java`

| Aspect | Detail |
|--------|--------|
| **Input** | `RequestMessage` from E3 |
| **Output** | Oracle → `gateway.oracle.response`, API → `gateway.api.response` |
| **Circuit Breaker** | Per-domain Resilience4j CB. If OPEN, returns 503 immediately. |
| **OAuth2** | If `oauth2Provider` set, fetches token via `OAuth2ProviderService` and adds `Authorization` header |
| **Timeouts** | Default: connect 10s, read 30s. Per-endpoint overrides via `gateway.http.endpoint-timeouts` |
| **Compression** | `HttpClientConfig` does NOT enable reactor-netty `.compress(true)` (broken with Cloudflare). `decompressIfNeeded(body, headers)` handles `Content-Encoding: gzip/deflate/x-gzip` defensively on both success and error paths so `/result` always gets plain bytes. |
| **On Success** | Records CB success, sends ResponseMessage to response topic (body decompressed if needed) |
| **On Failure** | Records CB failure, checks if retryable (408, 429, 5xx). `buildErrorResponse` captures the upstream error body via `WebClientResponseException.getResponseBodyAsByteArray()` so failed 4xx/5xx bodies are retrievable via `/result`. |

### E5: Response Processing — `OracleResponseConsumer.java` / `ApiResponseConsumer.java`

| Aspect | Oracle | API |
|--------|--------|-----|
| **Consumer** | `OracleResponseConsumer` | `ApiResponseConsumer` |
| **Topic** | `gateway.oracle.response` | `gateway.api.response` |
| **Factory** | `oracleResponseConsumerFactory` | `apiResponseConsumerFactory` |
| **RocksDB** | Saqlaydi (body mavjud bo'lsa) | Saqlaydi (body mavjud bo'lsa) |
| **Callback** | Yo'q | `CallbackService.sendCallbackIfNeeded()` |
| **Oracle save** | `saveWithRetry()` → Biruni API | Yo'q |
| **Ack** | MANUAL | MANUAL |

**Source channel auth (B3):** `SourceClient.saveResponse()` (the Oracle-save leg) applies the same `SourceAuthService.authHeaders()` resolution as the E1 pull — same `gateway.source.auth.type` setting, same isolated OAuth2 cache. See CONFIGURATION.md → `gateway.source.auth.*` and SAFETY-RULES.md rule 31.

## Component Map

| Package | Responsibility | Key Classes |
|---------|---------------|-------------|
| `oracle/` | E1 (Pull) — source system communication (e.g. Biruni) | `RequestPuller`, `SourceClient` |
| `kafka/producer/` | E2 — Kafka message production | `RequestProducer` |
| `kafka/consumer/oracle/` | E3 + E5 — Oracle request/response/DLQ | `OracleRequestConsumer`, `OracleResponseConsumer`, `OracleDlqConsumer` |
| `kafka/consumer/api/` | E3 + E5 — API request/response/DLQ | `ApiRequestConsumer`, `ApiResponseConsumer`, `ApiDlqConsumer` |
| `http/` | E4 — HTTP client with CB + OAuth2 | `HttpRequestService` |
| `state/` | Redis state management + distributed locks | `RequestStateService` |
| `oauth2/` | OAuth2 token caching + distributed locking | `OAuth2ProviderService`, `OAuth2TokenRedisCache`, `OAuth2ProviderFetcher` |
| `oauth2/client/` | Pluggable OAuth2 client implementations | `OAuth2Client` (interface), `JsonBodyOAuth2Client` (`getName()="biruni"`, JSON body), `StandardOAuth2Client` (`type=standard`, RFC 6749 form-urlencoded, e.g. Regos) |
| `oauth2/model/` | OAuth2 data models | `Token`, `ProviderProperties`, `OAuth2TokenRequest` |
| `concurrency/` | Dynamic consumer thread scaling | `DynamicConcurrencyManager`, `ConcurrencyMonitorService`, `ConcurrencyMetrics` |
| `metrics/` | Centralized metrics (all metrics in one class) | `GatewayMetrics` |
| `model/` | Domain models | `Request`, `Response`, `RequestState`, `ResponseSaveRequest` |
| `model/kafka/` | Kafka message models | `RequestMessage`, `ResponseMessage`, `DlqMessage` |
| `model/enums/` | Enumerations | `RequestStatus`, `ErrorSource` |
| `config/` | All configuration and bean definitions | `KafkaConfig`, `RedisConfig`, `HttpClientConfig`, `ThreadPoolConfig`, `*Properties` |
| `notification/` | Alert notifications | `NotificationService` (interface), `TelegramNotificationService` (always the active bean; config resolved per send from settings-DB → env fallback; disabled → warn-log only) |
| `validation/` | Validation logic | `RequestValidator` |
| `source/` | Data source interfaces + source-channel outbound auth (B3) | `RequestSourceClient`, `ResponseSinkClient`, `SourceAuthService` (resolves basic\|apikey\|oauth2 per `gateway.source.auth.type`), `SourceTokenService` (isolated `source:oauth2:token` cache) |
| `health/` | Custom health checks | `SystemHealthIndicator` |
| `controller/` | Dev-only test endpoints | `TestController` (profile: dev) |
| `settings/` (app) | Control-plane glue: cache + AES secrets facade, yaml→DB migration, boot-time DB→Environment layer | `SettingsService` (cache + `getSecret`), `SettingsMigrationRunner`, `SettingsEnvironmentPostProcessor` (boot-time DB→Environment property source), `SettingsDbConfig` (HikariCP pinning + core beans) |
| `uz.greenwhite.qosid.settings` (**settings-core module**) | Shared single-source settings code used by BOTH JVMs (app + ops): registry metadata, AES-GCM cipher, H2 repositories | `SettingsRegistry` (per-key effect-class metadata, `isKnown`/`isEditable` allowlist), `SettingsCipher` (POJO, AES-GCM — format frozen by `SettingsCipherContractTest`), `SettingsRepository`, `SettingsRecord`, `AdminAccountRepository`, `AdminAccount`, `LoginRateLimiter`, `LoginHandoffRepository` (one-time login-handoff tokens), `settings-schema.sql` |
| `admin/` | Control-plane: admin session auth + read API + login-token handoff at `/qosid/admin/api/**` (all settings WRITES answer `409 stop-required` — editing goes through the ops sidecar while the service is stopped) | `AdminAuthController` (+ `/login-token`), `AdminAuthService`, `AdminAccountInitializer`, `SettingsController`, `HealthController` |

**B1 note:** the settings/admin control-plane is additive — it does not touch the E1-E5 pipeline. `AdminSecurityConfig` isolates it behind its own `SecurityFilterChain` so the pipeline and actuator remain permitAll (see Cross-Cutting Concerns → Admin Control-Plane Security below).

## Admin Control-Plane API

**Forced password change (v1.15.0).** While the stored admin password still matches the seeded
default, every admin route except an exact allowlist (`login`, `login-token`, `logout`, `me`,
`change-password`, `health`) answers `403 {"error":"password-change-required"}`. Enforcement is
server-side — `DefaultPasswordGateFilter`, registered only on `adminSecurityChain` and **after**
`AuthorizationFilter` so anonymous callers still get `401` and learn nothing about the password
state. `gateway.admin.force-password-change` (default `true`) gates it; the login and `me`
responses carry a `passwordChangeRequired` hint the SPA uses to force its form.

The ops sidecar enforces the same gate against the same shared H2 account — without it the
attack the gate exists to stop is untouched (log in on :8092, stop the gateway, rewrite
`gateway.api.base-url`, start it again). Because settings are edited while the gateway is
**stopped**, ops also exposes its own `change-password`; otherwise a fresh install could be
locked out with no reachable way to leave the default password. See SAFETY-RULES rule 35.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/qosid/admin/api/health` | permitAll | Liveness probe for the admin chain itself (also proves the chain's allowlist reaches a real handler, not a coincidental 404). Any admin-chain request (including this one) seeds the `XSRF-TOKEN` cookie via `CsrfCookieFilter`, which forces the deferred token to render on every request — not just this endpoint. |
| POST | `/qosid/admin/api/login` | permitAll | `{username, password}` → creates an `HttpSession` + `SecurityContext` with `ROLE_ADMIN` on success. Rate-limited (`LoginRateLimiter`: 5 failures / 60s per username). Unknown usernames are not rate-limited (bounds the limiter's map to real accounts) but still return the same auth failure. |
| POST | `/qosid/admin/api/login-token` | permitAll | `{token}` — one-time login-handoff token minted by `POST /ops/api/handoff`. SHA-256 hash looked up + consumed in `login_handoff` (60s TTL, single use); valid → same session-establishment path as `/login` (session rotation, `ROLE_ADMIN`) → `200 {username, status:AUTHENTICATED}`; invalid/expired/reused → `401 {"error":"invalid-token"}`. Same `LoginRateLimiter` policy as `/login`. Powers the SPA's avto-ulanish after an ops-driven gateway start. |
| POST | `/qosid/admin/api/logout` | session | Invalidates the session and clears the `SecurityContext`. |
| GET | `/qosid/admin/api/me` | session | Returns the authenticated admin's username. |
| POST | `/qosid/admin/api/change-password` | session | Changes the admin password (min 8 chars, must not be the literal `admin`); invalidates the session afterward (re-login required). |
| GET | `/qosid/admin/api/settings` | session | Lists all settings (`SettingsService.getAllMasked()`) — secret values render as `***`. |
| GET | `/qosid/admin/api/settings/registry` | session | Registry-driven view for the Settings UI: every `SettingsRegistry` key with group, label, input type, effect-class (`LIVE`/`REQUIRES_RESTART`/`READ_ONLY`), current value (DB override → `Environment` fallback). (The `restartPending` flag was removed in D — under stop-to-edit every change is applied on the next start by construction.) |
| PUT | `/qosid/admin/api/settings/{key}` | session | **Always `409 {"error":"stop-required"}`** (D stop-to-edit): the gateway never accepts settings writes while running. Editing goes through `PUT /ops/api/settings/{key}` while the service is stopped. The internal `SettingsService.set` write path remains (used by migration/initializers), only the HTTP surface is closed. |
| GET | `/qosid/admin/api/settings/audit` | session | Last 200 rows from `settings_audit` (key, old/new value — secrets masked, changed-by, timestamp). |
| GET | `/qosid/admin/api/source-auth` | session | Current source-channel auth config (`SourceAuthController`) — `type` + per-scheme fields, secrets masked (`***`). |
| PUT | `/qosid/admin/api/source-auth` | session | **Always `409 {"error":"stop-required"}`** (D stop-to-edit) — same for `PUT /ai/config`; tenant keys have no dedicated PUT (they ride the generic settings PUT, equally 409). The diagnostic POSTs (`/source-auth/test`, `/tenant-mapping/test`, `/ai/analyze`) still work while running. |
| POST | `/qosid/admin/api/source-auth/test` | session | Fires one bounded (5s connect/read) GET against the configured source pull URI using the resolved auth headers; always returns `200` with `{ok, httpStatus, message}` — never throws on an unreachable/misconfigured source. |
| GET | `/qosid/admin/api/telegram-config` | session | Current DLQ Telegram-alert config (`TelegramConfigController`) — `enabled`/`chatId`/`messageThreadId` plus `botToken` masked as `***`. Backed by the DB-managed `gateway.telegram.*` keys (env-var fallback per send). |
| PUT | `/qosid/admin/api/telegram-config` | session | **Always `409 {"error":"stop-required"}`** (D stop-to-edit) — editing goes through the ops sidecar's generic settings API while the gateway is stopped. |
| POST | `/qosid/admin/api/telegram-config/test` | session | Sends one bounded test message using the stored config via `TelegramNotificationService.sendTest()`; always returns `200` with `{ok, ...}` (`{ok:false, error:...}` when disabled/failed, token redacted) — never a 500. |

First-boot default account: `admin` / `admin` (logged as WARN). One-shot reset via `ADMIN_RESET=true` or `ADMIN_PASSWORD_RESET=<value>` — see `ai-context/CONFIGURATION.md` → Admin Account & Reset.

### Settings editability — registry, boot-time layer, stop-to-edit flow

Three pieces make DB-stored settings actually take effect (A-loyiha settings-editability, hardened by D stop-to-edit):

- **`SettingsRegistry`** (static, no Spring): per-key metadata — group, uz label, input type, and effect-class. `LIVE` keys are read per-use via `SettingsService.get(key, fallback)` (e.g. `gateway.api.base-url` in `ProxyController`, `gateway.source.base-url`/pull/save URIs in `SourceClient`, `gateway.source.oauth2-provider-uri` in `OAuth2ProviderFetcher`, tenant field names in `TenantMappingService`, Loki/Prometheus URLs). `REQUIRES_RESTART` keys are bound into beans at startup (`@ConfigurationProperties`/`@Value`). `READ_ONLY` keys are bootstrap/infra (ports, Kafka, Redis, `gateway.settings.db-path`, active profile) — shown greyed in the UI and rejected on PUT. Unknown keys are NOT editable (`isEditable(key)` is the allowlist: PUT rejects them, boot-time injection skips them); `effectClassOf` still reports `REQUIRES_RESTART` for unknown keys, but that is display-only. Drives `GET /settings/registry`, the `SettingsController` write guard, and the `SettingsEnvironmentPostProcessor` injection allowlist.
- **`SettingsEnvironmentPostProcessor`** (registered in `spring.factories`, ordered after config-data): at boot it opens the H2 settings DB directly (JDBC, `IFEXISTS=TRUE`) and injects non-secret rows as a `settings-db` `MapPropertySource` ranked **above** `application.yml` (below `systemEnvironment`). **Allowlist:** only EDITABLE registry keys (`SettingsRegistry.isEditable`) are injected — read-only infra keys (ports, Kafka, Redis, db-path) and unknown keys in the DB are never injected, so the settings DB cannot repoint infra. For editable keys the DB value overrides both the yaml default AND its `${ENV:default}` placeholder env var (the placeholder resolves inside the lower-ranked yaml source) — this is what makes UI edits of `REQUIRES_RESTART` settings apply on next boot with zero per-consumer changes. Secrets never enter the Environment (runtime `SettingsService.getSecret` only); any failure (first boot, no DB file) is swallowed and yaml defaults apply. **DEPLOY NOTE:** once a `gateway.*` key has a settings-DB row (the B1 migration seeds many on first boot), changing its systemd env var no longer takes effect — the DB/UI is the source of truth for that key.
- **Stop-to-edit flow (D):** settings are edited ONLY while the gateway JVM is stopped, via the ops sidecar (`GET/PUT /ops/api/settings*` — ops writes the shared H2 directly through settings-core, guarded by `GatewayStatusService.isUp()` → `409 {"error":"service-running"}` while the gateway answers its health URL). Every gateway-side settings write endpoint answers `409 {"error":"stop-required"}` unconditionally. Consequence: by the time the gateway boots, all edits are already in the DB and the `SettingsEnvironmentPostProcessor` applies them — there is no "restart pending" intermediate state anymore (the old `restartPending` flag + `RestartPendingResetRunner` were removed). The SPA reconnects automatically after a start via the login-token handoff (see Ops console below).

## Errors module — Loki log aggregation (D2)

The Errors module gives admins read-only visibility into three log sources without SSH. The DLQ
consumers write a best-effort structured-JSON audit line per dead-lettered message to `dlq.log`;
Promtail ships `gateway.log` (JAR), `dlq.log`, and Docker container logs to Loki (7-day retention,
bound to `127.0.0.1` — never exposed publicly). The gateway proxies Loki server-side:
`GET /qosid/admin/api/logs` (session auth) builds a LogQL query per source (`dlq`/`jar`/`docker`)
via `LogQlBuilder` (user input escaped), `LogQueryService` resolves the Loki base URL per request
(`gateway.loki.url` setting, yaml/`LOKI_URL` fallback) and degrades Loki-down or bad input to
`{entries: [], error}` — never a 500. `/logs/labels` lists label values (e.g. container names).
The SPA renders three pages (DLQ, JAR, Docker). D2 absorbed the originally-planned D4 (Loki)
scope, so there is no separate D4 phase.

## Statistics module — Prometheus metrics dashboard (D3)

The Statistics module gives admins a read-only metrics dashboard over the internal Prometheus
(loopback-bound — never exposed publicly). The browser never sends PromQL: `GET
/qosid/admin/api/stats?preset=<name>` (session auth) maps one of 5 fixed presets
(`StatPresets.java`) — `kpi`, `funnel`, `throughput`, `errors`, `latency` — to preset PromQL,
which `StatsQueryService` runs against Prometheus (`gateway.prometheus.url` setting,
yaml/`PROMETHEUS_URL` fallback) via instant (`/query`) or range (`/query_range`) calls.
Prometheus-down or unknown-preset degrade to an empty result + error string — never a 500.
p95 latencies come from `histogram_quantile` over the percentile histograms enabled for
`gateway.request.e2e.duration` and `gateway.http.request.duration` (see CONFIGURATION.md).
The SPA renders `/statistics` with @ant-design/plots charts.

Note: within the `kpi` preset, `dlq` uses the range-dependent `${window}` token (service-computed
from `to-from`, clamped 60s..30d) while the rate KPIs use a fixed `[5m]` — this asymmetry is
intentional (DLQ counts accumulate over the selected range; rates are a current snapshot), not a bug.

## AI error analysis (D5)

Opt-in, on-demand AI analysis of a single error/log entry — the final D-phase, completing the
5-part admin upgrade (D1 UI shell, D2 Errors/Loki, D3 Statistics, D5 AI; D4 was absorbed into D2).
Dormant by default: `AiAnalysisService.enabled()` is true only when all three `gateway.ai.*`
settings (base-url, api-key, model) are configured via `PUT /qosid/admin/api/ai/config`.
`POST /qosid/admin/api/ai/analyze` (session auth) builds a compact context from the entry,
passes it through `AiRedactor` (bearer/basic tokens and key/value or JSON-quoted secrets masked)
BEFORE anything leaves the gateway, then calls a configurable OpenAI-compatible
`/chat/completions` endpoint (bounded RestClient: connect 5s / read 30s). Any failure —
unconfigured, unreachable, bad key — degrades to `{analysis: null, error}`, never a 500.
The SPA exposes an "AI tahlil" button in the shared error drawer (Errors pages) plus an AI tab
in Settings (config form + status gate). D5 absorbs nothing from other phases.

## Tenant boundary translation (C-faza)

The internal core (Kafka messages, Redis keys, RocksDB, composite IDs) speaks only
`tenantId`/`extraTenantId` — plain `Long` fields on `RequestMessage`/`ResponseMessage`
with **no** `@JsonProperty("company_id")` binding. The main app's wire shape
(field names configurable, default `company_id`/`filial_id`) is translated at exactly
two boundary points by `TenantMappingService` (`tenant/TenantMappingService.java`):

- **Ingress** — `SourceClient.pullRequests()` maps every raw source pull element through
  `TenantMappingService.toInternal(JsonNode)`, which builds the `RequestMessage` (via a
  lenient, unknown-properties-tolerant `ObjectMapper` copy) and separately extracts
  `tenantId`/`extraTenantId` using the *configured* field names read from
  `SettingsService` (`gateway.tenant.field-name` / `gateway.tenant.extra-field-name`).
- **Egress** — `TenantMappingService.toExternal(Long tenantId)` builds a
  `{configuredField: tenantId}` map, used by both `SourceClient.saveResponse()` (source
  save) and `OAuth2ProviderFetcher.fetchFromOracle()` (OAuth2 provider-config fetch).

Both directions read the field names **dynamically per call** from `SettingsService` —
changing `gateway.tenant.field-name` via the admin API takes effect on the very next
Biruni pull / save / OAuth2 fetch, no restart needed. Defaults (`company_id`/`filial_id`)
reproduce the pre-C-faza wire shape exactly, so bmb (the main app) is unaffected unless
someone reconfigures the field names.

**REST-sourced tenant:** `ProxyController` resolves `tenantId` from the authenticated
principal (`AuthResult.getTenantId()`) first, falling back to the
`gateway.tenant.default-rest-tenant` setting only when the provider supplies none (e.g.
the default `NoOpAuthorizationProvider`). `extraTenantId` similarly prefers
`AuthResult.getExtraTenantId()`; only when the principal supplies none AND
`gateway.tenant.trust-extra-tenant-header` is explicitly enabled does it fall back to the
client-supplied `X-Extra-Tenant-Id` header (see `CONFIGURATION.md` for the
impersonation tradeoff this opt-in accepts).

**Composite ID unchanged:** `RequestMessage.getCompositeId()` still returns
`tenantId + ":" + requestId` — same string shape as the old `companyId:requestId`, so
Redis keys, Kafka message keys, and `/qosid/api/v1/requests/{id}/*` polling URLs need
**no migration**.

See SAFETY-RULES.md rule 30 for what breaks if this translation is bypassed.

## Data Flow

```
Oracle/Biruni Response (JSON, wire field e.g. company_id/filial_id)
    |
    v  [TenantMappingService.toInternal — ingress boundary translation]
    |
    v
RequestMessage (Kafka message model)
  - tenantId, extraTenantId, requestId, baseUrl, uri, params, method, headers, body, oauth2Provider
    |
    v  [E2: Sent to gateway.request.new topic, key = compositeId]
    |
    v  [E3: Consumed, idempotency checked, lock acquired]
    |
    v  [E4: HTTP request sent to baseUrl+uri+params]
    |
    v
ResponseMessage (Kafka message model)
  - tenantId, requestId, httpStatus, contentType, body, errorMessage, errorSource
    |
    v  [Sent to gateway.request.response topic, key = compositeId]
    |
    v  [E5: Consumed, saved to Oracle]
    |
    v  [TenantMappingService.toExternal — egress boundary translation]
    |
    v
ResponseSaveRequest (Oracle save payload)
  - {configured tenant field}: tenantId, request_id, response {status, content_type, body}, error_message
```

## Cross-Cutting Concerns

### Circuit Breaker (Resilience4j)

- **Scope:** Per-domain (extracts domain from `baseUrl` for CB instance name)
- **Sliding Window:** 10 calls
- **Failure Rate Threshold:** 50% -> triggers OPEN
- **Slow Call Threshold:** >10s duration, >50% rate -> triggers OPEN
- **OPEN Duration:** 30s before transitioning to HALF_OPEN
- **HALF_OPEN:** 3 permitted test calls
- **OAuth2 errors excluded:** Token failures do not count toward CB failure rate
- **Config class:** `resilience4j.circuitbreaker.configs.default.*` in `application.yml`

### Idempotency (Redis)

- **State Key:** `request:state:{tenantId}:{requestId}` (TTL: 72 hours)
- **Lock Key:** `request:lock:{tenantId}:{requestId}` (TTL: 300 seconds)
- **Dual Check:**
  1. `isCompleted(key)` — if status is COMPLETED or FAILED, skip (already processed)
  2. `tryLock(key)` — Redis `SET NX` with TTL. If fails, another instance is processing it
- **Safety Net:** If Redis unavailable, `getAttemptCount()` returns `Integer.MAX_VALUE` to prevent infinite retries
- **Managed by:** `RequestStateService.java`
- **Gate failure (A-faza):** If Redis itself fails during `isCompleted`/`tryLock`, the consumer nacks (5s) instead of acking — the message is redelivered, never dropped.
- **Lock ownership (A-faza):** lock values are per-acquire owner tokens; release is compare-and-delete.

### Dynamic Concurrency Scaling

- **Monitor:** `ConcurrencyMonitorService` checks Kafka consumer lag every 10s
- **Manager:** `DynamicConcurrencyManager` adjusts consumer thread count
- **Range:** min 3 (default) to max 15 threads
- **Scale Step:** +/- 2 threads per adjustment
- **Cooldown:** 30s minimum between scaling events
- **Threshold:** Scale up when lag > 50, scale down when lag < 10
- **Scope:** Request and Response consumers scaled independently
- **No restart on scale-up (v1.15.0):** the manager only calls `setConcurrency(...)`; it does NOT `stop()`/`start()` the container unless `gateway.concurrency.allow-restart-on-scale-up` is explicitly enabled (default `false`). A restart rebalances the group and in-flight acks landing after `stop()` are lost — the new target applies on the next natural rebalance/restart instead.

### Thread Pool & Backpressure

- **HTTP Executor:** Separate thread pool for HTTP requests (`httpRequestExecutor`)
- **Core Size:** `minConcurrency` (default 10)
- **Max Size:** `maxConcurrency * threadPoolMultiplier` (default 30)
- **Queue:** 50 tasks
- **Rejection Policy:** `RejectWithLogging` — when queue full, throws `RejectedExecutionException`. Consumer catches it and re-queues the message to Kafka without incrementing attempt count. Consumer thread stays free.
- **Non-blocking:** Both request consumers use `.subscribe()` instead of `.block()` — threads are freed immediately after dispatching HTTP request.
- **Message Expiry:** Messages older than `gateway.retry.max-message-age-ms` (default 5 min) are failed immediately to prevent infinite re-queue loops.
- **CB-aware retry:** OAuth2 errors are NOT retried — they cause immediate DLQ. Circuit Breaker blocks (`errorSource=CIRCUIT_BREAKER`) ARE retried by transparent re-queue (no attempt count increment) — CB-blocked is transient back-pressure, not a permanent failure. The message-expiry safety net (`max-message-age-ms`) ensures genuinely down upstreams still surface in DLQ after the deadline. **Re-queue cap (v1.15.0):** the re-queues are also counted in Redis (`request:cb-requeue:<compositeId>`, `RequestStateService.incrementCbRequeue`) and bounded by `gateway.retry.cb-max-requeues` (default 50) — past the cap the message takes the permanent-failure → DLQ path and increments `gateway.http.circuitbreaker.requeue.exhausted`. Redis down ⇒ `Integer.MAX_VALUE` ⇒ immediate DLQ (fail-closed), never an unbounded loop.
- **OAuth2-aware 401 retry (v1.1.0):** When an upstream API returns 401 to a request whose `oauth2_provider` is set, the consumer evicts the cached token (`OAuth2ProviderService.evictToken`) and re-queues the request without incrementing the attempt counter. A per-request flag (`request:token-evicted:<compositeId>`, same TTL as request state) prevents repeating the eviction more than once per request — on the second 401 the request falls through to the standard permanent-failure path. Recovers from smartup's periodic "stuck token" glitch in one Kafka round-trip instead of waiting 3h for token TTL expiry.

### Admin Control-Plane Security (B1)

- **Two isolated `SecurityFilterChain` beans** in `AdminSecurityConfig`: `adminSecurityChain` (`@Order(1)`, `securityMatcher("/qosid/admin/api/**")`, session-required except `/login` and `/health`, CSRF **on** via `CookieCsrfTokenRepository` with a SPA-compatible `SpaCsrfTokenRequestHandler` + `CsrfCookieFilter` so real HTTP clients — curl/Postman/an SPA — can echo the raw `XSRF-TOKEN` cookie value in the `X-XSRF-TOKEN` header) and `apiSecurityChain` (`@Order(2)`, `securityMatcher("/**")`, `permitAll`, CSRF off, `STATELESS`).
- **Why two chains:** the pipeline (`/qosid/api/**`) must stay open regardless of admin auth state. A single merged chain (or a missing `securityMatcher`) would put everything behind session auth — pipeline requests would 401/redirect. See SAFETY-RULES.md rule 29. (Actuator runs on the separate management port (8091) governed by its own auto-configuration, not by `AdminSecurityConfig` — out of scope for these chains by construction.)
- **Cookie hardening (all four console cookies).** `JSESSIONID` + `OPS_SESSION` are `HttpOnly`; `XSRF-TOKEN` + `OPS-XSRF-TOKEN` are deliberately **not** (the SPA reads them from `document.cookie` and echoes them in the CSRF header — HttpOnly there 403s every write). All four are `SameSite=Lax` (`Strict` would drop the cookie on a top-level navigation into the console; same-origin XHR is unaffected by either). `Secure` is opt-in behind one env var, `COOKIE_SECURE` (`server.servlet.session.cookie.secure` in both modules' yaml + `gateway.security.cookie-secure` + `ops.security.cookie-secure`), **default `false` because prod currently has no TLS/nginx** — the console is reached over plain HTTP through an SSH tunnel and a browser silently discards a `Secure` cookie on a plain-HTTP response, so forcing it would break login outright. `false` still leaves Spring's/Tomcat's `request.isSecure()` fallback in place, so genuine HTTPS requests are already marked Secure. Details + the switch-on procedure: `ai-context/CONFIGURATION.md` → "Session & CSRF Cookie Hardening" and "Deferred Activation".
- **Guard tests:** `SecurityIsolationTest` asserts the pipeline GET/POST paths stay reachable and the admin paths stay gated (it does not assert anything about actuator, which lives on a different port). `AdminCsrfFlowTest` asserts, over a real servlet (no MockMvc `.with(csrf())` shortcut), that a real HTTP client can capture the `XSRF-TOKEN` cookie and successfully echo it in `X-XSRF-TOKEN` to log in. `AdminCookieHardeningTest` / `AdminCookieSecureFlagTest` / `OpsCookieHardeningTest` pin the cookie attributes above (including that the XSRF cookies stay JS-readable, and that `COOKIE_SECURE=true` really works). Keep all green.
- **Actuator `shutdown` is exposed in no profile.** The management port (8091) has no authentication, so `POST /actuator/shutdown` would be an unauthenticated remote kill switch; the `dev`-profile document that used to enable it was removed and `ActuatorShutdownDisabledTest` fails the build if it returns. Local dev stops with Ctrl+C; prod stops with `systemctl stop` → SIGTERM → `server.shutdown: graceful`.

## Ops console (sidecar) — `qosid-ops`

A **separate JVM** in the `ops/` Maven module (package `uz.greenwhite.ops`, own `pom.xml`, JAR `qosid-ops.jar` via `finalName`), listening on **:8092** (`OPS_PORT`), deployed as its **own systemd unit** (`ops/qosid-ops.service`). It is fully independent of the gateway process — its whole point is to stay up when the gateway is down, so an operator can restart the gateway and watch its logs from a browser. Since C-faza (v1.13.0) the module is **API-only** — there is no server-rendered `/ops` page anymore; the operator UI lives in the unified React SPA (`frontend/`) and talks to this module purely over `/ops/api/**`.

| Concern | Class | How |
|---------|-------|-----|
| Gateway up/down | `GatewayStatusService` | HTTP GET to `ops.gateway.health-url` (default: gateway admin `/qosid/admin/api/health` on :8090), 2s connect/read timeouts; `up = 2xx`; never throws. |
| Start/stop gateway | `GatewayControlService` | Runs the **operator-configured** (config-only, never user-supplied → no command injection) `ops.gateway.start-command`/`stop-command` via `ProcessBuilder` (whitespace-split, stderr merged); reads process output to EOF, then `waitFor(30s)`; returns `ControlResult(ok, exitCode, output)`; never throws. **The start command must EXIT** (e.g. `systemctl start gateway-service`) — a foreground `java -jar` would hold stdout open and hang the request (see CONFIGURATION.md → `ops.*`). |
| Log tail | `LogTailService` | Tails `ops.log-file` (`gateway.log`): last-N snapshot + incremental polling with rotation detection (anchor-bytes fingerprint) and partial-line holdback. |
| Stop-to-edit settings (D) | `OpsSettingsController`, `OpsSettingsService` | `GET /ops/api/settings` (registry-merged list, secrets masked `***`), `PUT /ops/api/settings/{key}` (`{value}` → `200 SAVED`), `GET /ops/api/settings/audit`. The list is the **union** of every EDITABLE registry key (value `""` and `stored:false` when no DB row exists yet — so keys never migrated/saved are still reachable in edit mode) and the existing DB rows (`stored:true`); READ_ONLY registry keys appear only when a row exists, and each row carries a `stored` boolean. Writes go straight to the shared H2 via settings-core (`SettingsRepository` + `SettingsCipher` beans in `OpsDbConfig`); guard: gateway UP → `409 {"error":"service-running"}`; unknown/read-only key → `400 {"error":"unknown-key"\|"not-editable"}`; secret without `ops.settings-encryption-key` → `400 {"error":"encryption-unavailable"}`. Never-500. First-time secret writes for DB-managed keys (source-auth/ai) resolve secret-ness from `SettingsRegistry`, not just the existing row. Internal marker keys (`admin.*`, `gateway._meta.*`, `gateway.settings.migration-completed` — `SettingsRegistry.isInternal`) are hidden from the list and rejected on PUT (`400 not-editable`); DB-managed keys (source-auth/AI) REMAIN in the list (secrets masked `***`) because the SPA's typed pages read them back via this generic GET in stop-to-edit mode. The SPA's General tab hides typed-form-managed keys client-side (`TYPED_FORM_KEYS`) so every setting is configured in exactly one place. |
| Login handoff (D avto-ulanish) | `OpsAuthController` (`POST /ops/api/handoff`), core `LoginHandoffRepository` | Authenticated operator mints a one-time token (SecureRandom 32B base64url, 60s TTL); only its SHA-256 hash lands in the shared `login_handoff` table. The SPA exchanges it at the gateway's `POST /qosid/admin/api/login-token` for a fresh admin session — so after an ops-driven start the operator is NOT bounced to the login page. Consume-once semantics live in `LoginHandoffRepository.consume` (select+delete, expiry-checked). |
| API + SSE fan-out | `OpsController` | `/ops/api/{status,start,stop}` + `GET /ops/api/logs/stream` (SSE: last-200-line snapshot on connect, then a `@Scheduled` 1s poll pushes new lines to all connected emitters). |
| Auth | `AdminAuthDao`, `OpsAuthController`, `OpsSecurityConfig` | Session login against the **same admin account as the gateway admin UI**: `OpsDbConfig` opens the gateway's H2 settings DB (`ops.settings-db-path`) in **AUTO_SERVER mixed mode** (`;MODE=LEGACY;AUTO_SERVER=TRUE`, `mem:`-guarded) — both `SettingsDbConfig`/`SettingsEnvironmentPostProcessor` (gateway side) and `OpsDbConfig` (ops side) use AUTO_SERVER so the two JVMs share one DB file. The gateway's `SettingsDbConfig` uses a **HikariCP pool (`minimumIdle=1`, `maxLifetime=0`)** that permanently holds a physical connection, pinning the H2 server role to the gateway JVM — without it the server role thrashed between JVMs on every short-lived connection close, and concurrent access (the SPA's parallel login POSTs) transiently failed (gateway login 500 / spurious ops 401 → SPA stuck in gateway-only mode with the Ops page hidden). `AdminAuthDao.verify` additionally retries the DB read once (400ms) so the remaining gateway-restart handoff window can't surface as a wrong-password 401, and applies the same `LoginRateLimiter` policy (per-JVM state) as the gateway's `/qosid/admin/api/login` (5 failures / 60s per username, limiter consulted only for existing accounts so unknown usernames stay a plain 401 — no enumeration via 429; checked before the password match, reset on success) — `OpsAuthController` surfaces it as `429 {"error":"rate-limited"}`. Single `SecurityFilterChain` on `/ops/**`: only `/ops/api/login` is permitAll (there are no page routes left to permit), everything else needs a session; CSRF cookie `OPS-XSRF-TOKEN` / header `X-OPS-XSRF-TOKEN` (`CookieCsrfTokenRepository` + plain `CsrfTokenRequestAttributeHandler`, no XOR) with an ops-local `CsrfCookieFilter`; login rotates the session (fixation defense) — same B1 patterns as the gateway. |

**Cookie rename rationale (C-faza, v1.13.0):** once the SPA and the ops sidecar sit behind one nginx origin, a browser sees both backends' cookies on the same domain. Spring Security's defaults (`JSESSIONID` session cookie, `XSRF-TOKEN` CSRF cookie/`X-XSRF-TOKEN` header) would collide with the gateway admin chain's own `JSESSIONID`/`XSRF-TOKEN`, silently corrupting whichever session was written last. Ops renames its pair — session cookie `OPS_SESSION` (`server.servlet.session.cookie.name` in `ops/src/main/resources/application.yml`), CSRF cookie `OPS-XSRF-TOKEN` / header `X-OPS-XSRF-TOKEN` (`OpsSecurityConfig`) — so the two independent sessions coexist without namespace clashes; the gateway keeps its original `JSESSIONID`/`XSRF-TOKEN` unchanged.

### Single-origin deploy & unified SPA (C-faza, v1.13.0)

The React SPA is no longer embedded in the gateway JAR — the old server-side forward controller and the Maven frontend-copy build step have both been removed. In production nginx is the single public origin and serves the SPA statically, so a UI-only release no longer requires a gateway restart:

```
Browser → nginx (443, TLS)
            ├─ /          → static frontend/dist  (React Router fallback: try_files $uri /index.html)
            ├─ /qosid/    → 127.0.0.1:8090         (gateway: pipeline + admin API, unchanged)
            └─ /ops/api/  → 127.0.0.1:8092         (ops sidecar API only; SSE-safe: proxy_buffering off,
                                                     proxy_cache off, proxy_read_timeout 1h, HTTP/1.1)
```

Reference config: `deploy/nginx-qosid.conf`. Local dev mirrors this via the Vite proxy (`/qosid` and `/ops/api` → :8090/:8092 respectively) — deliberately **not** `/ops`, since that path is the SPA's own page route, not something to forward.

**Single login / degraded mode:** the SPA has one login form that POSTs credentials to both `/qosid/admin/api/login` and `/ops/api/login` concurrently (both validate against the same H2 `admin_account`). A 401 from either backend is authoritative (bad credentials); a network error on one side (that backend simply unreachable) is not fatal as long as the other login succeeds.

| Gateway reachable | Ops reachable | Landing / nav behavior |
|---|---|---|
| yes | yes | full mode — Settings/Errors/Statistics/Ops all enabled, lands on `/settings`; Settings pages are **read-only** (stop-to-edit banner + lock icons) |
| no | yes | degraded mode — Ops nav **and Settings nav** enabled (Settings switches to edit mode over `/ops/api/settings`), Errors/Statistics disabled, warning banner shown, lands on `/ops` |
| yes | no | Ops nav item disabled, everything else works normally (Settings still read-only — editing needs the ops sidecar) |
| no | no | login fails outright (both unreachable) |

`AuthContext` polls `/qosid/admin/api/me` and `/ops/api/me` independently on refresh/login and drives `gatewayUp`/`opsUp`; `AppLayout` disables nav items and shows the banner off those two flags, and `HomeRedirect` sends `/` to `/ops` whenever the gateway is down.

**Avto-ulanish (D):** `OpsPage`'s 5s status poll watches for up/down transitions. On down→up it calls `AuthContext.attemptHandoff()` — `POST /ops/api/handoff` (one-time token) → `POST /qosid/admin/api/login-token` (fresh gateway session) → silent re-probe — so a gateway start flows straight back into full mode without the login page. Any handoff failure is swallowed (plain silent re-probe, degraded mode persists). On up→down the poll does a plain silent re-probe as before.

**Deploy requirements:** the systemd unit runs as user `qosid`, which needs permission to run exactly `systemctl start gateway-service` and `systemctl stop gateway-service` (polkit rule or sudoers `NOPASSWD` scoped to those two commands). Bind :8092 behind nginx / firewall — the console can stop the gateway. `OPS_SETTINGS_DB_PATH` must point at the **same** H2 path as the gateway's `gateway.settings.db-path`, and `SETTINGS_ENCRYPTION_KEY` must be set in the **ops** unit's environment too (same value as the gateway's) — otherwise secret settings can't be saved through the stop-to-edit API (`400 encryption-unavailable`).

## Request Status Lifecycle

```
N (New)         Created in Oracle, waiting to be pulled
    |
    v
P (Processing)  Pulled by gateway, being processed
    |
    v
S (Sent)        HTTP request sent to external API
    |
    +--> C (Completed)   Success response (2xx)
    |
    +--> F (Failed)      Error after all retries exhausted
```

Enum: `RequestStatus.java` with values `NEW("N")`, `PROCESSING("P")`, `SENT("S")`, `COMPLETED("C")`, `FAILED("F")`
