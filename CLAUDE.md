# Request Gateway Service (qosid-flow)

Java 21 + Spring Boot 3.3.0 microservice that pulls HTTP requests from Oracle/Biruni, routes them through Kafka for load balancing, sends to external APIs with Circuit Breaker + OAuth2 + retry, and saves responses back. Package: `uz.greenwhite.gateway`.

## AI Context Documentation

Read these files for full project understanding:

- `@ai-context/OVERVIEW.md` — Project identity, mission, tech stack, ports, repository structure
- `@ai-context/ARCHITECTURE.md` — E1-E5 pipeline stages, component map, data flow, cross-cutting concerns
- `@ai-context/CONVENTIONS.md` — Code style, naming, DI, logging, error handling, metrics patterns
- `@ai-context/SAFETY-RULES.md` — **READ FIRST before modifying core pipeline code.** NEVER DO list, protected zones, checklists
- `@ai-context/EXTENSION-GUIDE.md` — Step-by-step recipes: add consumer, OAuth2 provider, API integration, metric, config, notification
- `@ai-context/CONFIGURATION.md` — All config parameters with types, defaults, env vars
- `@ai-context/DOMAIN-GLOSSARY.md` — Key terms: Biruni, CompanyId, DLQ, CB, Pipeline Stage, etc.

## Critical Safety Rules (Top 5)

1. **Never change Kafka MANUAL ack to AUTO** — causes message loss (`KafkaConfig.java`)
2. **Never remove idempotency logic** — causes duplicate processing (`RequestConsumer.java`)
3. **Never bypass Circuit Breaker** — causes cascading failures (`HttpRequestService.java`)
4. **Never remove Integer.MAX_VALUE safety net** — causes infinite retries when Redis is down (`RequestStateService.java`)
5. **Never remove retry logic in ResponseConsumer** — causes response data loss (`ResponseConsumer.java`)

Full safety rules: `@ai-context/SAFETY-RULES.md`

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
Oracle/Biruni → [E1: Pull] → [E2: Kafka Produce] → [E3: Consumer + Redis Lock] → [E4: HTTP + CB] → [E5: Save to Oracle]
                                                                                         ↓ failure
                                                                                   [DLQ → Telegram Alert]
```
