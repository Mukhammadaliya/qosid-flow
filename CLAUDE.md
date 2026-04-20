# Request Gateway Service (qosid-flow)

Java 21 + Spring Boot 3.3.0 microservice that pulls HTTP requests from Oracle/Biruni **or accepts them via REST** (`/qosid/api/v1/queue/**`), routes them through Kafka for load balancing, sends to external APIs with Circuit Breaker + OAuth2 + retry, stores response bodies in RocksDB (7-day retention), fires per-request callbacks, and saves Oracle-source responses back to Biruni. Package: `uz.greenwhite.gateway`.

## AI Context Documentation

Read these files for full project understanding:

- `ai-context/OVERVIEW.md` — Project identity, mission, tech stack, ports, repository structure
- `ai-context/ARCHITECTURE.md` — E1-E5 pipeline stages, component map, data flow, cross-cutting concerns
- `ai-context/CONVENTIONS.md` — Code style, naming, DI, logging, error handling, metrics patterns
- `ai-context/SAFETY-RULES.md` — **READ FIRST before modifying core pipeline code.** NEVER DO list, protected zones, checklists
- `ai-context/EXTENSION-GUIDE.md` — Step-by-step recipes: add consumer, OAuth2 provider, API integration, metric, config, notification
- `ai-context/CONFIGURATION.md` — All config parameters with types, defaults, env vars
- `ai-context/DOMAIN-GLOSSARY.md` — Key terms: Biruni, CompanyId, DLQ, CB, Pipeline Stage, etc.

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

Pluggable auth: implement `AuthorizationProvider` — the default `NoOpAuthorizationProvider` steps aside. See `ai-context/EXTENSION-GUIDE.md` Recipe 0.

## Quick Commands

```bash
mvn clean package -DskipTests        # Build JAR
mvn spring-boot:run                   # Run locally
mvn spring-boot:run -Dspring-boot.run.profiles=dev  # Run with test endpoints
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
