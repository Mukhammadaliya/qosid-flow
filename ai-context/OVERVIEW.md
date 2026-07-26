# Project Overview

## Project Identity

| Field | Value |
|-------|-------|
| **Name** | Qosid Flow (qosid-flow) |
| **Version** | 2.0.0 |
| **Language** | Java 21 |
| **Framework** | Spring Boot 3.3.13 |
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
| Spring Boot | Application framework | 3.3.13 |
| Spring Cloud | Cloud patterns (Circuit Breaker) | 2023.0.2 |
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

> **Spring Boot 3.3.x is end-of-life.** `3.3.13` (2025-06-19) is the last release of that line —
> it carries 13 patch releases of fixes over the `3.3.0` this project shipped with, but no
> future CVE fix will land there. The supported line is `3.5.x`. Moving to it is a scoped
> project, not a version bump: Spring Cloud must go to the `2025.0.x` train (it provides the
> Circuit Breaker — a protected zone), Spring Kafka to `3.3.x` (this service depends on precise
> `MANUAL` ack / `nack` / out-of-order-commit semantics — SAFETY rules 25, 32, 33), and Spring
> Security to `6.5`. Do it deliberately, with the full suite plus a Kafka ack-behaviour check.

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
    http/            # HTTP client with Circuit Breaker + OAuth2
    kafka/           # Kafka consumers and producers
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

### Release process (manual)

1. Open a PR `debug` → `master` on `greenwhite/qosid-flow` and merge when stable.
2. Create an annotated tag `v<version>` on the merged commit and push it:
   ```bash
   git tag -a v1.0.0 -m "Release notes..."
   git push upstream v1.0.0
   ```
3. A GitHub Actions workflow (`.github/workflows/build.yml`) builds a JAR and creates a GitHub Release **when `VERSION.md` changes on `master`**. For releases without a VERSION.md bump, attach the JAR to the GitHub Release manually.
4. Deploy to prod by hand: `git pull` on the server, `mvn clean package -DskipTests`, restart the systemd unit. Other services run in Docker via `docker-compose`.

Auto-release on every VERSION.md change is available but intentionally not used yet — all production releases are gated by a human step.
