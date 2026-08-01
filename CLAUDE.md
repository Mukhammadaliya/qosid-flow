# Qosid Flow (qosid-flow)

Java 21 + Spring Boot 3.3.0 microservice that pulls HTTP requests from a pluggable **source system** (Biruni is the reference implementation; see `ai-context/SOURCE-INTEGRATION-CONTRACT.md`) **or accepts them via REST** (`/qosid/api/v1/queue/**`), routes them through Kafka for load balancing, sends to external APIs with Circuit Breaker + OAuth2 + retry, stores response bodies in RocksDB (7-day retention), fires per-request callbacks, and saves source-originated responses back to the source. The source connector is `SourceClient` (`oracle/` package). Package: `uz.greenwhite.gateway`.

## AI Context Documentation

Read these files for full project understanding:

- `ai-context/OVERVIEW.md` — Project identity, mission, tech stack, ports, repository structure
- `ai-context/ARCHITECTURE.md` — E1-E5 pipeline stages, component map, data flow, cross-cutting concerns
- `ai-context/CONVENTIONS.md` — Code style, naming, DI, logging, error handling, metrics patterns
- `ai-context/SAFETY-RULES.md` — **READ FIRST before modifying core pipeline code.** NEVER DO list, protected zones, checklists
- `ai-context/EXTENSION-GUIDE.md` — Step-by-step recipes: add consumer, OAuth2 provider, API integration, metric, config, notification
- `ai-context/CONFIGURATION.md` — All config parameters with types, defaults, env vars
- `ai-context/DOMAIN-GLOSSARY.md` — Key terms: Biruni, TenantId, DLQ, CB, Pipeline Stage, etc.

## Critical Safety Rules (Top 5)

1. **Never change Kafka MANUAL ack to AUTO** — causes message loss (`KafkaConfig.java`)
2. **Never remove idempotency logic** — causes duplicate processing (`OracleRequestConsumer.java`, `ApiRequestConsumer.java`)
3. **Never bypass Circuit Breaker** — causes cascading failures (`HttpRequestService.java`)
4. **Never remove Integer.MAX_VALUE safety net** — causes infinite retries when Redis is down (`RequestStateService.java`)
5. **Never merge Oracle and API consumer logic** — causes cascading failures between flows (`kafka/consumer/oracle/`, `kafka/consumer/api/`)

Acknowledgement has three further rules that are just as load-bearing and easy to break silently: **32** (`asyncAcks` is incompatible with `nack` — re-verify on every Kafka bump), **39** (every ack goes through `OrderedAckTracker`), **40** (a parked CB record is completed by `CbRequeueScheduler` alone, never also in the caller's `finally`). Breaking any of them loses messages with no DLQ record, no alert and no metric.

Full safety rules: `ai-context/SAFETY-RULES.md`

## Branching & Release Policy

**Only two branches are allowed in the repository: `master` and `debug`.**

- `master` — stable, production-ready and the GitHub default branch. Deployed to prod from this branch.
- `debug` — active development. All feature work, fixes, and experiments happen here.

**Rules:**
- Do NOT create long-lived feature branches (`feature/*`, `fix/*`, `main`, etc.) — work on `debug` directly.
- Do NOT push directly to `master` — always go through a PR from `debug`.
- When `debug` is stable and ready for release, open a PR `debug` → `master` and merge it.

**Release: the merge is the only manual step — CI owns the tag.**
1. On `debug`, bump the version in **7 places**: `VERSION.md`, four poms (root, `settings-core`, and the `<parent>` + `qosid-settings-core` dependency in each of `app`/`ops`), and `frontend/package.json`; also refresh the version in `OVERVIEW.md` and the JAR paths in `CONFIGURATION.md`. Check the diff line by line — `resilience4j-spring-boot3` is pinned at `2.2.0` and has already been bumped by accident once. Exactly 7 version lines change.
2. Merge `debug` → `master` via PR (`gitleaks` gates it).
3. `.github/workflows/build.yml` fires on a push to `master` **that changes `VERSION.md`**: it derives `v<version>`, fails if that tag exists, builds, and publishes the GitHub Release with both JARs. **Never create the tag by hand.** A merge without a `VERSION.md` change just produces no release.
4. On prod server: `git pull`, `mvn clean package -DskipTests`, **update the hard-coded JAR name in the systemd unit** (`sed` + `daemon-reload` — forgetting this leaves the service unable to start), restart `gateway-service` and `qosid-ops`. Recreate Docker containers only when `docker-compose.yml` itself changed.

## Documentation Maintenance Rule

**Har bir kod o'zgarishidan keyin** quyidagi doc fayllarni tekshirib, aktualizatsiya qiling:
- Class nomi o'zgarsa → `ARCHITECTURE.md`, `SAFETY-RULES.md`, `CONVENTIONS.md`, `DOMAIN-GLOSSARY.md`, `EXTENSION-GUIDE.md`
- Config o'zgarsa → `CONFIGURATION.md`, `application.yml`
- Topic/consumer o'zgarsa → `ARCHITECTURE.md`, `CONFIGURATION.md`, `DOMAIN-GLOSSARY.md`
- Safety rule o'zgarsa → `SAFETY-RULES.md`, `CLAUDE.md` (Top 5)

**Ghost code tekshirish:** O'chirilgan yoki nomlantirilgan class/method/config'ni barcha `.java` va `ai-context/` fayllardan grep qilib, eski reference qolmaganini tasdiqlang.

## REST API Endpoints

| Method          | Path                                             | Purpose                                                                                  |
|-----------------|--------------------------------------------------|------------------------------------------------------------------------------------------|
| POST/GET/PUT/DELETE/PATCH | `/qosid/api/v1/queue/**`                | Transparent proxy — auth gate → queue to Kafka → `202 ACCEPTED` with polling URL. `BiruniUpload: param\|alone` header activates multipart upload to S3 (3x retry); on S3 failure falls back to multipart forwarding to Biruni for DB storage. `X-Callback-Url` header requests a callback when done. |
| GET             | `/qosid/api/v1/requests/{id}/status`             | Status + inline result (when small & JSON/text) or `resultUrl` pointer, plus `httpStatus` (the upstream status code, from the stored response metadata — also present on `FAILED`, omitted while no response is stored). **ORACLE-sourced requests answer `404`** — their composite id is enumerable and the result returns to the source over the save leg (SAFETY rule 34). |
| GET             | `/qosid/api/v1/requests/{id}/result`             | Full response body from RocksDB, original `Content-Type` + HTTP status. **ORACLE-sourced requests answer `404`**, checked before RocksDB is touched (SAFETY rule 34). |
| POST            | `/qosid/api/v1/callback`                         | Inbound callback receiver (currently logs + 200).                                        |
| GET/PUT/POST    | `/qosid/admin/api/source-auth[/test]`            | Admin-managed source-channel (Biruni pull/save) outbound auth: `basic`\|`apikey`\|`oauth2`. Session auth, secrets masked on GET. `POST .../test` fires a bounded test call, always returns structured JSON. **PUT → `409 stop-required`** (D stop-to-edit) — writes go through ops while stopped. |
| POST            | `/qosid/admin/api/tenant-mapping/test`           | Admin diagnostics (session auth): paste a sample Biruni pull JSON, see the tenantId/extraTenantId the current `gateway.tenant.*` field-name config would extract. |
| GET             | `/qosid/admin/api/logs[/labels]`                 | Admin (session auth): read-only Loki log proxy for the Errors module — DLQ/JAR/Docker log sources via LogQL; Loki-down degrades to empty+error, never 500. |
| GET             | `/qosid/admin/api/stats`                         | Admin (session auth): read-only Prometheus proxy for the Statistics dashboard — preset PromQL (kpi/funnel/throughput/errors/latency); Prometheus-down/unknown-preset degrade to empty+error, never 500. |
| GET/POST/PUT    | `/qosid/admin/api/ai/{status,analyze,config}`    | Admin (session auth): opt-in AI error analysis — status gate, on-demand analyze of a log entry via a configurable OpenAI-compatible endpoint (secret redaction, never-500); dormant until `gateway.ai.*` configured. **PUT config → `409 stop-required`** (D stop-to-edit). |
| GET/PUT/POST    | `/qosid/admin/api/telegram-config[/test]`        | Admin (session auth): DLQ Telegram-alert konfiguratsiyasi — DB-managed (token AES-secret, maskalangan), env-var fallback. **PUT → `409 stop-required`** (D stop-to-edit). `POST .../test` saqlangan config bilan sinov xabari, never-500. |
| GET/POST/PUT     | `/qosid/admin/api/**`                           | Admin control-plane (B1, D stop-to-edit): session auth (login/login-token/health are the only permitAll routes), CSRF-protected, isolated `SecurityFilterChain` — settings reads + `GET /settings/registry` (per-key effect-class metadata `LIVE`\|`REQUIRES_RESTART`\|`READ_ONLY`), audit log, admin login/logout, `POST /login-token` (one-time ops-handoff token → session, avto-ulanish). **ALL settings writes answer `409 {"error":"stop-required"}`** — editing happens via the ops sidecar while the gateway is stopped. Default account `admin`/`admin` on first boot; one-shot reset via `ADMIN_RESET`/`ADMIN_PASSWORD_RESET`. **While the password is still the default, every admin route except `login`/`login-token`/`logout`/`me`/`change-password`/`health` answers `403 {"error":"password-change-required"}`** (`gateway.admin.force-password-change`, default `true`; the login/`me` responses carry a `passwordChangeRequired` hint for the SPA). The ops sidecar enforces the same gate and exposes its own `change-password` — see SAFETY rule 35. See `ai-context/ARCHITECTURE.md` → Admin Control-Plane API. |
| GET/POST/PUT    | `/ops/api/{login,logout,me,status,start,stop,logs/stream,handoff,settings[/{key},/audit]}` | Ops sidecar (separate JVM, :8092, API-only — no HTML page). Session auth (`OPS_SESSION` cookie) + CSRF (`OPS-XSRF-TOKEN` cookie / `X-OPS-XSRF-TOKEN` header) against the shared H2 admin account; login rate-limited same as gateway admin login (5 fail/60s per existing username → `429 {"error":"rate-limited"}`). `login`/`me` return the same `passwordChangeRequired` hint as the gateway — the SPA's only source of it while the gateway is stopped, and `POST /ops/api/change-password` is where the change goes then. **Stop-to-edit settings API (D):** `GET/PUT /ops/api/settings[/{key}]` + audit — writes shared H2 directly via settings-core, `409 service-running` while the gateway is up, AES secrets need `SETTINGS_ENCRYPTION_KEY` in the ops env. `POST /ops/api/handoff` mints the one-time login-token for the SPA's avto-ulanish after start. Backing UI is the SPA's `/ops` page (status poll, start/stop with confirm, live SSE log). Stop-to-edit settings: `GET /ops/api/settings` returns the **union** of editable registry keys (`stored:false` when no DB row yet, so never-saved keys stay editable while stopped) and existing DB rows; internal marker keys (`admin.*`, `gateway._meta.*`, `gateway.settings.migration-completed`) are hidden from the list and rejected on PUT; DB-managed source-auth/AI keys remain in the list (secrets masked) for typed-page read-back; the SPA's General list hides them client-side so each setting is configured in exactly one place. See `ai-context/ARCHITECTURE.md` → Ops console (sidecar). |

Pluggable auth: implement `AuthorizationProvider` — the default `NoOpAuthorizationProvider` steps aside. See `ai-context/EXTENSION-GUIDE.md` Recipe 0.

## Quick Commands

```bash
mvn clean package -DskipTests        # Build JAR (root'da — reactor: core+app+ops)
mvn spring-boot:run -pl app           # Run locally
mvn spring-boot:run -pl app -Dspring-boot.run.profiles=dev  # Run with test endpoints
docker-compose up -d                  # Start infrastructure (Kafka, Redis, Prometheus, Grafana)
docker-compose down                   # Stop infrastructure
```

## Key Architecture

```
Oracle/Biruni → [E1: Pull] ──┐
                             │
HTTP (/qosid/api/v1/queue/**) ┤→ [Kafka] → [Consumer + Redis Lock] → [HTTP + CB] → [ResponseConsumer]
  (ProxyController + auth +  │                                                           │
   BiruniUpload → S3 (3x) +  │                                   ┌───────────────────────┼──────────────────┐
   fallback → multipart DB)   │                                   ↓                       ↓                  ↓
                              │                         Save to Oracle             RocksDB (7d)         Callback
                              │                         (source=ORACLE only)       (always, if body)    (if callbackUrl)
                              │                                   ↓ failure
                              └─────────────────────────────────→ DLQ → Telegram Alert
```

Status / result reads: `GET /qosid/api/v1/requests/{id}/{status,result}` read from Redis + RocksDB; **ORACLE-sourced requests are refused with `404`** (enumerable id, result already goes back to the source). Inline threshold `gateway.response.threshold-size` (default 1024 bytes).

**B1 (Settings control-plane, v1.4.0):** a parallel, session-authenticated admin surface (`/qosid/admin/api/**`) backed by an embedded H2 DB — runtime settings (cache + AES-encrypted secrets + yaml→DB migration) and a single admin account. Isolated via its own `SecurityFilterChain` (SAFETY rule 29); the E1-E5 pipeline above is untouched.

**C-faza (Tenant boundary translation, v1.5.0):** internal model renamed `companyId`/`filialId` → `tenantId`/`extraTenantId` (composite ID format unchanged, no migration). `TenantMappingService` translates at exactly two boundaries — Biruni pull ingress (`toInternal`) and Biruni save + OAuth2 fetch egress (`toExternal`) — using configurable wire field names (`gateway.tenant.field-name`/`extra-field-name`, defaults `company_id`/`filial_id`, dynamic per call). REST-sourced tenant resolves from the auth principal first, `gateway.tenant.default-rest-tenant` as fallback. See `ai-context/ARCHITECTURE.md` → Tenant boundary translation, and SAFETY rule 30.

**B3 (Source channel outbound auth, v1.6.0):** the Biruni pull (E1) and save (E5) legs resolve their outbound auth via `SourceAuthService` — `basic` (default, yaml `SOURCE_USERNAME`/`SOURCE_PASSWORD` fallback), `apikey`, or `oauth2` (`client_credentials`, token cached in the isolated `source:oauth2:token` Redis key — never the per-request `oauth2:token:<provider>` namespace, SAFETY rule 31; mint client selectable via `gateway.source.auth.oauth2.client-type` = `standard` RFC 6749 form (default) \| `biruni` proprietary JSON for Smartup/Biruni sources) — selected and configured via `/qosid/admin/api/source-auth`. Default behavior (`type=basic` + fallback) is unchanged from pre-B3. See `ai-context/CONFIGURATION.md` → `gateway.source.auth.*`.

**Ops console sidecar (`ops/` module, `qosid-ops`):** a separate JVM on :8092, **API-only** (`/ops/api/**` — no server-rendered page) that starts/stops the gateway JVM via operator-configured commands, reports up/down status (HTTP health poll), and live-streams `gateway.log` over SSE — auth against the same H2 admin account (shared settings DB via `AUTO_SERVER`), with its own cookie names (`OPS_SESSION` session cookie, `OPS-XSRF-TOKEN`/`X-OPS-XSRF-TOKEN` CSRF cookie/header) so it coexists with the gateway's `JSESSIONID`/`XSRF-TOKEN` on one origin. Deployed as its own systemd unit (`ops/qosid-ops.service`); the service user needs scoped permission for `systemctl start/stop gateway-service`, and the start-command must be a command that EXITS (never a foreground `java -jar`). The operator UI is the React SPA's `/ops` page (status badge, start/stop with confirm modal, live SSE log) — the SPA's single login form POSTs to both `/qosid/admin/api/login` and `/ops/api/login` and degrades to Ops+Settings when the gateway is unreachable. In production the SPA is **no longer embedded in the gateway JAR**; nginx serves `frontend/dist` statically and reverse-proxies `/qosid/` → :8090 and `/ops/api/` → :8092 — see `deploy/nginx-qosid.conf`. See `ai-context/ARCHITECTURE.md` → Ops console (sidecar) and `ai-context/CONFIGURATION.md` → `ops.*`.

**D (Stop-to-edit sozlamalar + Maven reactor + avto-ulanish, v1.14.0):** the repo is a 3-module Maven reactor (`settings-core` — shared registry/cipher/repositories in `uz.greenwhite.qosid.settings`, used by BOTH JVMs; `app` — the gateway, JAR now at `app/target/`; `ops`). Settings are edited ONLY while the gateway is stopped: all gateway settings-write endpoints answer `409 {"error":"stop-required"}`; the ops sidecar exposes `GET/PUT /ops/api/settings[/{key}]` (+audit) writing the shared H2 directly, guarded by `409 service-running` while the gateway is up (the old `restartPending` flag/`RestartPendingResetRunner` are gone — every edit applies on the next start by construction). After an ops-driven start the SPA reconnects without the login page via the login-token handoff: `POST /ops/api/handoff` → one-time 60s token (SHA-256 hash in the shared `login_handoff` table) → `POST /qosid/admin/api/login-token` → fresh session. See `ai-context/ARCHITECTURE.md` → Settings editability / Ops console.

**Adaptive CB backpressure (v2.3.0, three phases):** a CB-blocked message is no longer re-queued immediately. **Phase 1** parks it in memory (`CbRequeueScheduler`, one instance per flow) and re-checks the circuit on a doubling delay (`cb-requeue-delay-ms` 500 ms → `cb-requeue-max-delay-ms` 30 s, in-memory reads that never touch Kafka); give-up is by **time** (`max-message-age-ms`), enforced by the scheduler on every fire, and `cb-max-requeues` (1000) is now only a runaway guard. The parked record's Kafka offset stays uncommitted until the requeue is produced, so **rising consumer lag while a circuit is open is the expected signal**, and the record is completed by the scheduler alone (SAFETY 40). **Phase 2** stops our own burst from opening the circuit at all: `HostConcurrencyLimiter` caps concurrent in-flight calls per upstream host (`gateway.api.max-concurrent-per-host`, 25) and a refused permit takes the same park path. **Phase 3** makes the alert channel survive an incident: DLQ alerts coalesce per (reason-class, host) and Telegram's 429 `retry_after` is obeyed with no retry (SAFETY 41). Motivation and evidence: on 2026-07-27 one circuit window DLQ'd **241 live production requests inside a single minute** (14:27 UTC, 317 across two minutes — re-counted from Loki on 2026-08-01; the earlier "212 in ~1 s" was wrong, the densest single second held 21) and its alerts became 14 delivered messages plus 298 rate-limit failures; on 2026-07-28 a production burst **5× denser** (~330 req/s) drained with the circuit never opening, **zero loss, zero backpressure DLQ**, and 20 DLQ records producing 2 Telegram messages. See `ai-context/ARCHITECTURE.md` → E3/E4 back-pressure, `CONFIGURATION.md` → `gateway.retry.cb-*` / `gateway.api.max-concurrent-per-host` / `gateway.telegram.coalesce-window-ms`, and SAFETY-RULES 23, 40, 41.
