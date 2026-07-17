# Qosid Flow (qosid-flow)

Java 21 + Spring Boot 3.3.0 microservice that pulls HTTP requests from Oracle/Biruni **or accepts them via REST** (`/qosid/api/v1/queue/**`), routes them through Kafka for load balancing, sends to external APIs with Circuit Breaker + OAuth2 + retry, stores response bodies in RocksDB (7-day retention), fires per-request callbacks, and saves Oracle-source responses back to Biruni. Package: `uz.greenwhite.gateway`.

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

Full safety rules: `ai-context/SAFETY-RULES.md`

## Branching & Release Policy

**Only two branches are allowed in the repository: `master` and `debug`.**

- `master` — stable, production-ready and the GitHub default branch. Deployed to prod from this branch.
- `debug` — active development. All feature work, fixes, and experiments happen here.

**Rules:**
- Do NOT create long-lived feature branches (`feature/*`, `fix/*`, `main`, etc.) — work on `debug` directly.
- Do NOT push directly to `master` — always go through a PR from `debug`.
- When `debug` is stable and ready for release, open a PR `debug` → `master`, merge, and tag.

**Release is MANUAL (for now):**
1. Merge `debug` → `master` via PR.
2. Tag `master` with `v<version>` (e.g., `v1.0.0`) — annotated tag with release notes.
3. Push tag: `git push upstream v<version>`.
4. GitHub Actions workflow (`.github/workflows/build.yml`) builds the JAR when `VERSION.md` changes on `master`. For manual-only releases, trigger the workflow or attach a locally-built JAR to the GitHub Release by hand.
5. On prod server: `git pull`, `mvn clean package -DskipTests`, restart the systemd service. Other services remain in Docker.

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
| GET             | `/qosid/api/v1/requests/{id}/status`             | Status + inline result (when small & JSON/text) or `resultUrl` pointer.                  |
| GET             | `/qosid/api/v1/requests/{id}/result`             | Full response body from RocksDB, original `Content-Type` + HTTP status.                  |
| POST            | `/qosid/api/v1/callback`                         | Inbound callback receiver (currently logs + 200).                                        |
| GET/PUT/POST    | `/qosid/admin/api/source-auth[/test]`            | Admin-managed source-channel (Biruni pull/save) outbound auth: `basic`\|`apikey`\|`oauth2`. Session auth, secrets masked on GET. `POST .../test` fires a bounded test call, always returns structured JSON. |
| POST            | `/qosid/admin/api/tenant-mapping/test`           | Admin diagnostics (session auth): paste a sample Biruni pull JSON, see the tenantId/extraTenantId the current `gateway.tenant.*` field-name config would extract. |
| GET             | `/qosid/admin/api/logs[/labels]`                 | Admin (session auth): read-only Loki log proxy for the Errors module — DLQ/JAR/Docker log sources via LogQL; Loki-down degrades to empty+error, never 500. |
| GET             | `/qosid/admin/api/stats`                         | Admin (session auth): read-only Prometheus proxy for the Statistics dashboard — preset PromQL (kpi/funnel/throughput/errors/latency); Prometheus-down/unknown-preset degrade to empty+error, never 500. |
| GET/POST/PUT    | `/qosid/admin/api/ai/{status,analyze,config}`    | Admin (session auth): opt-in AI error analysis — status gate, on-demand analyze of a log entry via a configurable OpenAI-compatible endpoint (secret redaction, never-500); dormant until `gateway.ai.*` configured. |
| GET/POST/PUT     | `/qosid/admin/api/**`                           | Admin control-plane (B1): session auth (login/health are the only permitAll routes), CSRF-protected, isolated `SecurityFilterChain` — settings CRUD + `GET /settings/registry` (per-key effect-class metadata `LIVE`\|`REQUIRES_RESTART`\|`READ_ONLY` + `restartPending` flag; read-only keys rejected on PUT), audit log, admin login/logout. Default account `admin`/`admin` on first boot; one-shot reset via `ADMIN_RESET`/`ADMIN_PASSWORD_RESET`. See `ai-context/ARCHITECTURE.md` → Admin Control-Plane API. |
| GET/POST        | `/ops/api/{login,logout,me,status,start,stop,logs/stream}` | Ops sidecar (separate JVM, :8092, API-only — no HTML page). Session auth (`OPS_SESSION` cookie) + CSRF (`OPS-XSRF-TOKEN` cookie / `X-OPS-XSRF-TOKEN` header) against the shared H2 admin account. Backing UI is the SPA's `/ops` page (status poll, start/stop with confirm, live SSE log). See `ai-context/ARCHITECTURE.md` → Ops console (sidecar). |

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

Status / result reads: `GET /qosid/api/v1/requests/{id}/{status,result}` read from Redis + RocksDB. Inline threshold `gateway.response.threshold-size` (default 1024 bytes).

**B1 (Settings control-plane, v1.4.0):** a parallel, session-authenticated admin surface (`/qosid/admin/api/**`) backed by an embedded H2 DB — runtime settings (cache + AES-encrypted secrets + yaml→DB migration) and a single admin account. Isolated via its own `SecurityFilterChain` (SAFETY rule 29); the E1-E5 pipeline above is untouched.

**C-faza (Tenant boundary translation, v1.5.0):** internal model renamed `companyId`/`filialId` → `tenantId`/`extraTenantId` (composite ID format unchanged, no migration). `TenantMappingService` translates at exactly two boundaries — Biruni pull ingress (`toInternal`) and Biruni save + OAuth2 fetch egress (`toExternal`) — using configurable wire field names (`gateway.tenant.field-name`/`extra-field-name`, defaults `company_id`/`filial_id`, dynamic per call). REST-sourced tenant resolves from the auth principal first, `gateway.tenant.default-rest-tenant` as fallback. See `ai-context/ARCHITECTURE.md` → Tenant boundary translation, and SAFETY rule 30.

**B3 (Source channel outbound auth, v1.6.0):** the Biruni pull (E1) and save (E5) legs resolve their outbound auth via `SourceAuthService` — `basic` (default, yaml `SOURCE_USERNAME`/`SOURCE_PASSWORD` fallback), `apikey`, or `oauth2` (`client_credentials`, token cached in the isolated `source:oauth2:token` Redis key — never the per-request `oauth2:token:<provider>` namespace, SAFETY rule 31) — selected and configured via `/qosid/admin/api/source-auth`. Default behavior (`type=basic` + fallback) is unchanged from pre-B3. See `ai-context/CONFIGURATION.md` → `gateway.source.auth.*`.

**Ops console sidecar (`ops/` module, `qosid-ops`):** a separate JVM on :8092, **API-only** (`/ops/api/**` — no server-rendered page) that starts/stops the gateway JVM via operator-configured commands, reports up/down status (HTTP health poll), and live-streams `gateway.log` over SSE — auth against the same H2 admin account (shared settings DB via `AUTO_SERVER`), with its own cookie names (`OPS_SESSION` session cookie, `OPS-XSRF-TOKEN`/`X-OPS-XSRF-TOKEN` CSRF cookie/header) so it coexists with the gateway's `JSESSIONID`/`XSRF-TOKEN` on one origin. Deployed as its own systemd unit (`ops/qosid-ops.service`); the service user needs scoped permission for `systemctl start/stop gateway-service`, and the start-command must be a command that EXITS (never a foreground `java -jar`). The operator UI is the React SPA's `/ops` page (status badge, start/stop with confirm modal, live SSE log) — the SPA's single login form POSTs to both `/qosid/admin/api/login` and `/ops/api/login` and degrades to Ops-only when the gateway is unreachable. In production the SPA is **no longer embedded in the gateway JAR**; nginx serves `frontend/dist` statically and reverse-proxies `/qosid/` → :8090 and `/ops/api/` → :8092 — see `deploy/nginx-qosid.conf`. See `ai-context/ARCHITECTURE.md` → Ops console (sidecar) and `ai-context/CONFIGURATION.md` → `ops.*`.
