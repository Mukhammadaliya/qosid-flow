# Project Overview

## Project Identity

| Field | Value |
|-------|-------|
| **Name** | Request Gateway Service (qosid-flow) |
| **Version** | 1.0.1 |
| **Language** | Java 21 |
| **Framework** | Spring Boot 3.3.0 |
| **Base Package** | `uz.greenwhite.gateway` |
| **Group ID** | `uz.greenwhite` |
| **Artifact ID** | `request-gateway-service` |
| **Build Tool** | Maven |

## Mission

A message processing pipeline that pulls HTTP request definitions from Oracle/Biruni database, routes them through Kafka for load balancing and decoupled processing, sends them to external APIs with resilience patterns (Circuit Breaker, retry, OAuth2), and saves responses back to Oracle. Replaces legacy Apache Artemis + RocksDB system with cloud-native Kafka + Redis.

## What This Is NOT

- **Not a REST API** — this service does not expose REST endpoints for external consumers (only dev test endpoints and actuator)
- **Not a UI application** — purely backend service, no frontend
- **Not a monolith** — single-purpose microservice focused on request gateway processing
- **Not a CRUD app** — does not manage entities; it orchestrates HTTP request delivery with resilience
- **Not a proxy** — it pulls requests from a database, not from incoming HTTP traffic

## Tech Stack

| Technology | Purpose | Version |
|-----------|---------|---------|
| Spring Boot | Application framework | 3.3.0 |
| Spring Cloud | Cloud patterns (Circuit Breaker) | 2023.0.2 |
| Spring Kafka | Kafka producer/consumer | (Spring Boot managed) |
| Spring Data Redis | State management, caching, distributed locks | (Spring Boot managed) |
| Spring WebFlux | Reactive HTTP client (WebClient) | (Spring Boot managed) |
| Resilience4j | Circuit Breaker pattern | 2.2.0 |
| Micrometer + Prometheus | Metrics collection and export | (Spring Boot managed) |
| Oracle JDBC | Database connectivity (via Biruni REST) | 23.4.0.24.05 |
| Lombok | Boilerplate reduction | (Spring Boot managed) |
| Jackson | JSON serialization | (Spring Boot managed) |
| RedPanda | Kafka-compatible broker (local dev) | 24.1.1 |
| Redis | State, locks, OAuth2 token cache | 7.2 |
| Grafana | Monitoring dashboards | 10.4.1 |
| AlertManager | Alert routing | 0.28.0 |

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
qosid-flow/
  src/main/java/uz/greenwhite/gateway/   # Application source code
    config/          # Configuration classes (*Properties.java, *Config.java)
    controller/      # Dev-only test endpoints
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
    concurrency/     # Dynamic concurrency scaling
    validation/      # Request validation
  src/main/resources/
    application.yml      # All configuration with env var overrides
    logback-spring.xml   # Logging configuration
  infrastructure/        # Docker monitoring stack configs
    prometheus/          # Prometheus scrape config + alert rules
    alertmanager/        # AlertManager configuration
    *.json               # Grafana dashboard definitions
  .github/workflows/     # CI/CD: build JAR + create GitHub Release
  ai-context/            # AI tool documentation (this folder)
  docker-compose.yml     # Local dev: RedPanda, Redis, Prometheus, Grafana
  pom.xml                # Maven dependencies
  VERSION.md             # Version number (triggers CI/CD release)
  README.md              # Human-readable documentation
```

## Quick Commands

```bash
# Build
mvn clean package -DskipTests

# Run locally
mvn spring-boot:run

# Run with dev profile (enables TestController)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Start infrastructure (Kafka, Redis, Prometheus, Grafana)
docker-compose up -d

# Stop infrastructure
docker-compose down
```
