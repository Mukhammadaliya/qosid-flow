# Project Overview

## Project Identity

| Field | Value |
|-------|-------|
| **Name** | Qosid Flow (qosid-flow) |
| **Version** | 2.3.1 |
| **Language** | Java 21 |
| **Framework** | Spring Boot 3.5.16 |
| **Base Package** | `uz.greenwhite.gateway` |
| **Group ID** | `uz.greenwhite` |
| **Artifact ID** | `qosid-flow` |
| **Build Tool** | Maven |

## Mission

A message processing pipeline that pulls HTTP request definitions from Oracle/Biruni database, routes them through Kafka for load balancing and decoupled processing, sends them to external APIs with resilience patterns (Circuit Breaker, retry, OAuth2), and saves responses back to Oracle. Replaces legacy Apache Artemis + RocksDB system with cloud-native Kafka + Redis.

## What This Is NOT

- **Not primarily a UI application** — the core is a backend gateway; the React SPA (`frontend/`, served by nginx since v1.7.0, cutover v1.13.0) is a control-plane/admin surface layered on top, not the product itself
- **Not a monolith** — single-purpose microservice focused on request gateway processing
- **Not a CRUD app** — does not manage entities; it orchestrates HTTP request delivery with resilience

> Historically this service was pull-only (Oracle → Kafka). As of the feature-parity upgrade it additionally accepts inbound HTTP via the REST API Layer — see the components below.

## Tech Stack

| Technology | Purpose | Version |
|-----------|---------|---------|
| Spring Boot | Application framework | 3.5.16 |
| Spring Cloud | Cloud patterns (Circuit Breaker) | 2025.0.3 |
| Spring Kafka | Kafka producer/consumer | (Spring Boot managed) |
| Spring Data Redis | State management, caching, distributed locks | (Spring Boot managed) |
| Spring WebFlux | Reactive HTTP client (WebClient) | (Spring Boot managed) |
| Spring Retry | @Retryable callback dispatch | (Spring Boot managed) |
| Resilience4j | Circuit Breaker pattern | 2.2.0 |
| Micrometer + Prometheus | Metrics collection and export | (Spring Boot managed) |
| Oracle JDBC | Database connectivity (via Biruni REST) | 23.4.0.24.05 |
| RocksDB | Local disk-backed response body storage | 9.7.3 |
| AWS SDK (S3) | S3-compatible object storage file upload (AWS S3 / Cloudflare R2 / etc.) | 2.27.24 |
| Lombok | Boilerplate reduction | (Spring Boot managed) |
| Jackson | JSON serialization | (Spring Boot managed) |
| RedPanda | Kafka-compatible broker (local dev) | 24.1.1 |
| Redis | State, locks, OAuth2 token cache | 7.2 |
| Grafana | Monitoring dashboards | 10.4.1 |
| AlertManager | Alert routing | 0.28.0 |

> **On the supported line since 2026-07-27.** The service ran on `3.3.x` until then, which went
> end-of-life in mid-2025 (`3.3.13` was its last release). The move to `3.5.16` + Spring Cloud
> `2025.0.3` brought `spring-kafka` `3.2.10 → 3.3.16`, `spring-security-web` `6.3.10 → 6.5.11`
> and Jackson `2.19+`. The ack semantics this service depends on were re-verified against the new
> Kafka version rather than assumed — see SAFETY-RULES 32. Next line up is Boot `4.x`, a genuine
> major (Spring Framework 7, Jackson 3); do not treat it as a bump.

## Added Components (Feature-Parity Upgrade)

| Component | Package | Purpose |
|-----------|---------|---------|
| REST API Layer | `api/` | Transparent proxy `POST/GET/PUT/DELETE/PATCH /qosid/api/v1/queue/**` (Accept-Encoding stripped from forwarded headers to avoid Cloudflare HTTP/1.1 decode artifacts); status/result endpoints at `/qosid/api/v1/requests/{id}/{status,result}`; callback receiver at `/qosid/api/v1/callback`. Upstream JSON responses are defensively decompressed in `HttpRequestService` based on `Content-Encoding`. |
| Authorization Framework | `auth/` | Pluggable `AuthorizationProvider` SPI, default `NoOpAuthorizationProvider`. |
| Exception Handling | `exception/` | `@RestControllerAdvice` producing uniform `ErrorResponse` JSON for all API errors. |
| RocksDB Storage | `storage/` | Disk-backed response bodies keyed by `compositeId`, 7-day retention via `@Scheduled` cleanup cron. |
| Callback System | `callback/` | `@Retryable` HTTP POST sender with `@Recover` (3 attempts, 1s backoff). |
| File Upload | `file/` + `file/s3/` | Multipart handling with SHA-256 dedup via a single S3-compatible client (`S3Client`). |

## Key Ports

| Port | Service | Notes |
|------|---------|-------|
| 8090 | Gateway Application | Main HTTP server (`server.port`) |
| 8091 | Management / Actuator | Health, info, Prometheus metrics (`management.server.port`) |
| 19092 | Kafka (RedPanda) | Message broker |
| 6379 | Redis | State, locks, OAuth2 cache |
| 9090 | Prometheus | Metrics storage |
| 3000 | Grafana | Dashboards (admin/admin123) |
| 8082 | RedPanda Console | Kafka UI |
| 8085 | Redis Commander | Redis UI |
| 9093 | AlertManager | Alert routing |
| 9121 | Redis Exporter | Redis metrics for Prometheus |

## Repository Structure

```
qosid-flow/                              # Maven reactor (qosid-parent): settings-core + app + ops
  settings-core/                         # Shared settings module (qosid-settings-core, plain JAR)
  app/src/main/java/uz/greenwhite/gateway/   # Gateway application source code
    api/             # REST API layer (ProxyController, StatusController, CallbackController)
    auth/            # Pluggable authorization SPI
    callback/        # Outbound callback dispatch with retry
    config/          # Configuration classes (*Properties.java, *Config.java)
    controller/      # Dev-only test endpoints
    exception/       # GlobalExceptionHandler + typed exceptions
    file/            # Multipart parsing, content-type validation, S3 clients
    health/          # Custom health indicators
    http/            # HTTP client with Circuit Breaker + OAuth2 + per-host concurrency limit
    kafka/           # Kafka consumers and producers
      ack/           #   Ordered acknowledgement (OrderedAckTracker, SAFETY-RULES 39)
      retry/         #   Parked CB-blocked requeues (CbRequeueScheduler, SAFETY-RULES 23/40)
    metrics/         # Centralized metrics (GatewayMetrics.java)
    model/           # Domain models, Kafka messages, enums
    notification/    # Telegram DLQ alerts
    oauth2/          # OAuth2 token management
    oracle/          # Oracle/Biruni client (pull/save)
    source/          # Data source interfaces
    state/           # Redis state management
    storage/         # RocksDB response body storage
    concurrency/     # Dynamic concurrency scaling
    validation/      # Request validation
  app/src/main/resources/
    application.yml      # All configuration with env var overrides
    logback-spring.xml   # Logging configuration
  ops/                   # Ops console sidecar (qosid-ops, separate JVM :8092) + stop-to-edit settings API
  frontend/              # React SPA (Vite; not a Maven module — app's default-on `frontend` profile builds dist; nginx serves it in prod)
  deploy/                # Reference nginx config (nginx-qosid.conf)
  infrastructure/        # Docker monitoring stack configs
    prometheus/          # Prometheus scrape config + alert rules
    alertmanager/        # AlertManager configuration
    *.json               # Grafana dashboard definitions
  .github/workflows/     # CI/CD: build JAR + create GitHub Release
  ai-context/            # AI tool documentation (this folder)
  docker-compose.yml     # Local dev: RedPanda, Redis, Prometheus, Grafana
  pom.xml                # Root aggregator pom (qosid-parent); module poms in settings-core/, app/, ops/
  VERSION.md             # Version number (triggers CI/CD release)
  README.md              # Human-readable documentation
```

## Quick Commands

```bash
# Build (root'da — reactor: core+app+ops)
mvn clean package -DskipTests

# Run locally
mvn spring-boot:run -pl app

# Run with dev profile (enables TestController)
mvn spring-boot:run -pl app -Dspring-boot.run.profiles=dev

# Start infrastructure (Kafka, Redis, Prometheus, Grafana)
docker-compose up -d

# Stop infrastructure
docker-compose down
```

## Branching & Release

The repository uses **two branches only**: `master` (stable, production — GitHub default) and `debug` (active development).

- Do NOT create feature branches or a `main` branch — work on `debug`.
- Do NOT push directly to `master` — merge via PR from `debug`.
- Upstream (`greenwhite/qosid-flow`) is the source of truth. Developer forks (e.g., `origin`) should stay in sync with upstream.

### Release process

The human step is **merging the PR**; tagging and the GitHub Release are automatic.

1. On `debug`, bump the version in **7 places** — `VERSION.md`, the four poms (root, `settings-core`,
   `app`, `ops`; in `app`/`ops` both the `<parent>` block and the `qosid-settings-core` dependency),
   and `frontend/package.json` — plus the version references in `OVERVIEW.md` (this table) and the
   JAR paths in `CONFIGURATION.md`. **Verify the diff line by line:** `resilience4j-spring-boot3` is
   pinned at `2.2.0` and a careless search-and-replace has already bumped it once by accident. A
   correct bump touches exactly 7 version lines.
2. Open a PR `debug` → `master` on `greenwhite/qosid-flow`; merge when the checks are green
   (`gitleaks` runs on every PR).
3. `.github/workflows/build.yml` fires **on any push to `master` that changes `VERSION.md`**. It
   reads the version, refuses to proceed if `v<version>` already exists, builds with
   `mvn clean package -DskipTests`, and publishes a GitHub Release tagged `v<version>` with
   generated notes and both JARs attached (`app/target/*.jar`, `ops/target/qosid-ops.jar`).
   **Do not create the tag by hand** — the workflow owns it, and a pre-existing tag makes it fail.
   A docs-only or fix-only merge that leaves `VERSION.md` untouched simply produces no release;
   `workflow_dispatch` is there for a manual re-run.
4. Deploy to prod by hand — see the deploy runbook in `CONFIGURATION.md`. In short: `git pull` on
   the server, `mvn clean package -DskipTests`, **update the hard-coded JAR name in the systemd
   unit**, restart `gateway-service` and `qosid-ops`. Everything else runs in Docker via
   `docker-compose`, which only needs recreating when `docker-compose.yml` itself changed.
