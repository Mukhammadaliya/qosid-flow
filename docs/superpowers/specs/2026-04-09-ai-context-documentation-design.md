# AI Context Documentation Design Spec

**Date:** 2026-04-09  
**Status:** Approved  
**Goal:** Create comprehensive AI context documentation so that AI tools (Claude Code, Cursor, Copilot, AI agents) can work with the qosid-flow project without requiring manual explanation each session, while preventing dangerous changes to production-critical code.

---

## Problem Statement

1. Every new AI session requires full re-explanation of the project (pipeline architecture, patterns, conventions)
2. AI tools make dangerous changes to production-critical zones (idempotency, DLQ, Circuit Breaker, Redis locking)
3. No existing AI context files (no CLAUDE.md, no .cursorrules, no AGENTS.md)

## Target Audience

- **Claude Code** — primary tool, uses CLAUDE.md as entry point
- **Cursor / Copilot / other AI tools** — can be adapted later via wrapper files
- **AI agents** — CI/CD integrated agents for PR review, code generation
- **Team developers** — using any AI tool for fast project scaling

## Approach

**Modular ai-context/ folder with CLAUDE.md as navigator.**

---

## File Structure

```
CLAUDE.md                          <- Root entry point (~50-60 lines)
ai-context/
  OVERVIEW.md                      <- Project identity, mission, tech stack (~80 lines)
  ARCHITECTURE.md                  <- E1-E5 pipeline, component map, data flow (~150 lines)
  CONVENTIONS.md                   <- Code style, naming, package rules (~100 lines)
  SAFETY-RULES.md                  <- NEVER DO list, protected zones, checklists (~80 lines)
  EXTENSION-GUIDE.md               <- How to add new consumers, providers, integrations (~120 lines)
  CONFIGURATION.md                 <- All config params, env vars, defaults (~100 lines)
  DOMAIN-GLOSSARY.md               <- Key terms dictionary (~50 lines)
```

**Language:** English only (all AI tools perform best in English).

---

## File Specifications

### 1. CLAUDE.md (Root Entry Point)

**Purpose:** Minimal navigator file. AI tools read this first.

**Content:**
- 3-4 line project summary
- Direct references to each ai-context/ file with one-line description
- Top 5 safety rules inline (most critical — no need to navigate)
- Quick reference: key commands (build, run, test)
- Note about future Cursor/Copilot wrapper files

**Design principle:** CLAUDE.md = "map", ai-context/ = "full information"

### 2. ai-context/OVERVIEW.md

**Purpose:** Answer "what is this project?" for AI tools.

**Sections:**
1. **Project Identity** — name (Request Gateway Service / qosid-flow), version, Java 21 + Spring Boot 3.3.0, package `uz.greenwhite.gateway`
2. **Mission** — Pull HTTP requests from Oracle/Biruni, route through Kafka for load balancing, send to external APIs with resilience patterns, save responses back
3. **What This Is NOT** — not a REST API, no UI, not a monolith, not a CRUD app
4. **Tech Stack** — table format: Spring Boot, Kafka (RedPanda), Redis, Oracle/Biruni, Resilience4j, Micrometer/Prometheus/Grafana, Lombok
5. **Key Ports** — 8090 (app), 8091 (management), 19092 (Kafka), 6379 (Redis), 9090 (Prometheus), 3000 (Grafana), 8082 (RedPanda Console), 8085 (Redis Commander)
6. **Repository Structure** — top-level folders: src/, infrastructure/, .github/, ai-context/

### 3. ai-context/ARCHITECTURE.md

**Purpose:** Explain the pipeline so AI tools write code in the correct stage.

**Sections:**
1. **Pipeline Diagram (ASCII)** — E1 through E5 with DLQ branch
   ```
   Oracle/Biruni -> [E1: Pull] -> [E2: Kafka Produce] -> [E3: Consumer + Redis Lock] -> [E4: HTTP + CB] -> [E5: Save to Oracle]
                                                                                               |
                                                                                          (failure)
                                                                                               v
                                                                                        [DLQ -> Telegram Alert]
   ```
2. **Stage Breakdown** — for each stage (E1-E5):
   - Responsible class
   - Input/output
   - Possible failures
   - Retry/fallback strategy
3. **Component Map** — package-to-responsibility table:
   - `oracle/` -> E1, E5 (Biruni communication)
   - `kafka/consumer/` -> E3, E5
   - `kafka/producer/` -> E2
   - `http/` -> E4
   - `state/` -> Redis state management
   - `oauth2/` -> Token caching + distributed lock
   - `concurrency/` -> Dynamic scaling
   - `metrics/` -> Centralized metrics (54 metrics)
   - `config/` -> All configuration classes
   - `model/` -> Domain models and Kafka messages
   - `notification/` -> Telegram alerts
   - `validation/` -> Request validation
4. **Data Flow** — how RequestMessage and ResponseMessage transform through the pipeline
5. **Cross-cutting Concerns**:
   - Circuit Breaker: per-domain, CLOSED/OPEN/HALF_OPEN, thresholds
   - Idempotency: Redis dual-lock pattern (state check + distributed lock)
   - Dynamic Concurrency: lag-based, 10s monitor, +/-2 step scaling, 30s cooldown
6. **Request Status Lifecycle**: N(New) -> P(Processing) -> S(Sent) -> C(Completed) / F(Failed)

### 4. ai-context/CONVENTIONS.md

**Purpose:** AI-generated code matches existing codebase style.

**Sections:**
1. **Package Structure Rule** — new classes go into existing packages; new package only for new domain
2. **Naming Conventions** — table:
   - Service: `*Service.java`
   - Consumer: `*Consumer.java`
   - Config: `*Config.java`
   - Properties: `*Properties.java`
   - Model: plain name (`Request`, `Response`, `Token`)
   - Kafka message: `*Message.java`
   - Enum: singular (`RequestStatus`, `ErrorSource`)
3. **Configuration Pattern** — separate `*Properties.java` with `@ConfigurationProperties`, defaults in application.yml, env var override
4. **Dependency Injection** — constructor injection via `@RequiredArgsConstructor`, NO `@Autowired`
5. **Logging** — `@Slf4j`, structured: `log.info("Stage - Action: detail, key={}", value)`
6. **Error Handling** — try-catch only when recovery possible, domain-specific exceptions, catch-all only in top-level consumers
7. **Metrics** — all metrics centralized in `GatewayMetrics.java`, nowhere else
8. **Anti-patterns** — no `@Autowired` field injection, no static utility methods, no God classes

### 5. ai-context/SAFETY-RULES.md

**Purpose:** Prevent AI tools from breaking production-critical code.

**Sections:**
1. **NEVER DO List** (9 rules):
   - Never remove/simplify idempotency logic in RequestConsumer
   - Never reduce Redis lock TTL in RequestStateService
   - Never remove metrics from GatewayMetrics
   - Never change MANUAL ack to AUTO in KafkaConfig
   - Never bypass Circuit Breaker in HttpRequestService
   - Never remove retry logic in ResponseConsumer
   - Never alter DLQ flow in DlqConsumer/RequestProducer
   - Never remove Integer.MAX_VALUE safety net
   - Never replace CallerRunsPolicy
2. **Protected Zones** — files requiring extra caution:
   - RequestConsumer.java (idempotency + lock + ack)
   - ResponseConsumer.java (retry + save + ack)
   - HttpRequestService.java (CB + OAuth2 + timeout)
   - RequestStateService.java (Redis state + lock)
   - KafkaConfig.java (topics + error handlers + ack mode)
   - OAuth2TokenRedisCache.java (distributed token lock)
3. **Required Checklist** — before modifying any protected zone:
   - Is idempotency preserved?
   - Is retry logic correct?
   - Are metrics updated?
   - Is DLQ fallback present?
   - What happens if Redis is unavailable?
4. **Safe Zones** — free to modify:
   - TestController.java (dev only)
   - infrastructure/ (dashboards, alerts)
   - application.yml (adding new config)
   - New *Properties.java classes
   - Documentation files

### 6. ai-context/EXTENSION-GUIDE.md

**Purpose:** Step-by-step recipes for common extension patterns.

**Sections:**
1. **Add New Kafka Consumer** — files to create/modify, mandatory manual ack
2. **Add New OAuth2 Provider** — implement OAuth2Client interface, register in service, Redis cache key pattern
3. **Add New External API Integration** — use HttpRequestService or create custom class in http/, per-endpoint timeout, auto Circuit Breaker
4. **Add New Metric** — only in GatewayMetrics.java, naming pattern `gateway.{stage}.{action}.{type}`, update Grafana dashboard
5. **Add New Configuration** — Properties class + application.yml + env var pattern
6. **Add New Notification Channel** — implement NotificationService, use @ConditionalOnProperty

### 7. ai-context/CONFIGURATION.md

**Purpose:** Complete reference for all configuration parameters.

**Sections:**
1. **Config Groups Table** — for each group:
   - Property prefix
   - Properties class name
   - All params: name, type, default, env var, description
2. **Groups covered:**
   - `gateway.source.*` — Oracle/Biruni
   - `gateway.kafka.*` — Kafka
   - `gateway.polling.*` — Scheduler
   - `gateway.http.*` — HTTP client
   - `gateway.retry.*` — Retry
   - `gateway.concurrency.*` — Dynamic scaling
   - `gateway.telegram.*` — Notifications
   - `spring.data.redis.*` — Redis
   - `resilience4j.circuitbreaker.*` — Circuit Breaker
3. **Mandatory Environment Variables** — for production deployment

### 8. ai-context/DOMAIN-GLOSSARY.md

**Purpose:** Quick reference for domain-specific terms.

**Format:** Table with Term and Definition columns.

**Terms:** Biruni, CompanyId, Composite Key, Pipeline Stage (E1-E5), DLQ, CB (Circuit Breaker), RedPanda, Request Status (N/P/S/C/F), Protected Zone, Manual Ack, Safe Zone, Idempotency, Backpressure, CallerRunsPolicy, Safety Net (Integer.MAX_VALUE)

---

## Design Principles

1. **CLAUDE.md is small** — only navigation, most critical safety rules inline
2. **Each file has one job** — modular, independently readable
3. **Safety is separate and prominent** — SAFETY-RULES.md is the most important file
4. **Extension patterns are explicit** — AI follows recipes, not guesswork
5. **English only** — best AI tool performance
6. **No duplication with README.md** — ai-context/ is for AI tools, README.md is for humans
7. **Future-proof** — easy to add .cursorrules, AGENTS.md wrapper files later

## Out of Scope

- .cursorrules / AGENTS.md / .github/copilot-instructions.md (future wrapper files)
- Test documentation (no tests exist yet)
- Deployment documentation (separate concern)
- Automated spec validation tooling
