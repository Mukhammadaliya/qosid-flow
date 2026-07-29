# Qosid Flow

**Version:** 2.3.1  
**Java:** 21 | **Spring Boot:** 3.5.16 | **Spring Cloud:** 2025.0.3  
**Package:** `uz.greenwhite.gateway` (Maven reactor: `settings-core` + `app` + `ops`)

---

## 1. Overview

Qosid Flow is a **message processing pipeline** that pulls HTTP requests from an Oracle database, routes them through Kafka, sends them to external APIs, and saves responses back to Oracle. It replaces the legacy Biruni Message Broker (Apache Artemis + RocksDB) with a modern, cloud-native architecture.

**Core mission:** Pull requests from Oracle → marshal through Kafka → send HTTP to external APIs → save response back to Oracle.

**Key differences from the legacy system:**

| Aspect | Legacy (Artemis + RocksDB) | New (Kafka + Redis) |
|--------|---------------------------|---------------------|
| Message Queue | Apache Artemis (JMS) | Apache Kafka / RedPanda |
| State Store | RocksDB (embedded, single node) | Redis (distributed, HA-capable) |
| HTTP Client | RestClient (blocking) | WebClient (reactive, non-blocking) |
| Circuit Breaker | ❌ None | ✅ Resilience4j (per-endpoint) |
| Scaling | Vertical only | Horizontal (Kafka partitions) |
| Monitoring | Minimal | Prometheus + Grafana + Telegram |
| OAuth2 Tokens | In-memory (per-instance) | Redis-shared (distributed lock) |

---

## 2. Architecture

### 2.1 Pipeline — 5 Stages (E1–E5)

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ E1:Oracle│───▶│ E2:Kafka │───▶│E3:Consume│───▶│ E4: HTTP │───▶│ E5:Oracle│
│  (Pull)  │    │(Produce) │    │(Process) │    │(External)│    │  (Save)  │
└──────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘
  Scheduler      Producer       Consumer        WebClient       ResponseSaver
  5s interval    Kafka topic    Idempotent      Circuit Break   Save + Status
  Batch pull     Async send     Redis lock      OAuth2 auto     Update to C/F
```

**Each stage in detail:**

| Stage | Name | File | What it does |
|-------|------|------|--------------|
| E1 | Source Pull | `RequestPuller.java` + `SourceClient.java` | Pulls `status=N` requests from the source system in batches (every 5 sec) |
| E2 | Kafka Produce | `RequestProducer.java` | Sends requests to the flow's topic (`gateway.oracle.request` / `gateway.api.request`) |
| E3 | Consumer Process | `OracleRequestConsumer.java` / `ApiRequestConsumer.java` | Idempotency check, Redis lock, async dispatch, CB park branch. **Two classes on purpose — never merge them** (SAFETY-RULES 5) |
| E4 | HTTP Request | `HttpRequestService.java` (+ `HostConcurrencyLimiter`) | Sends to the external API via WebClient: per-host permit → Circuit Breaker → OAuth2 |
| E5 | Response Processing | `OracleResponseConsumer.java` / `ApiResponseConsumer.java` | Oracle save + RocksDB, or RocksDB + callback; updates status to `C` or `F` |

### 2.2 Request Status Lifecycle

```
N (New)        → Newly created in Oracle
P (Pulled)     → Pulled by the Gateway
S (Sent)       → Sent to the external API
C (Completed)  → Successfully completed
F (Failed)     → Failed after all retries
```

### 2.3 Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                          QOSID FLOW                               │
│                                                                   │
│  ┌─────────────┐     ┌──────────────────────────────────┐       │
│  │RequestPuller│────▶│         KAFKA TOPICS              │       │
│  │ (Scheduler) │     │  gateway.request.new              │       │
│  └─────────────┘     │  gateway.request.response         │       │
│                      │  gateway.request.dlq              │       │
│                      └────────┬──────────┬───────────────┘       │
│                               │          │                        │
│                               ▼          ▼                        │
│                      ┌────────────┐ ┌────────────┐               │
│                      │ Request    │ │ Response   │               │
│                      │ Consumer   │ │ Consumer   │               │
│                      └─────┬──────┘ └─────┬──────┘               │
│                            │              │                       │
│                            ▼              ▼                       │
│                      ┌────────────┐ ┌────────────┐               │
│                      │ HTTP Req.  │ │Oracle Save │               │
│                      │ Service    │ │            │               │
│                      │(WebClient) │ │            │               │
│                      └─────┬──────┘ └────────────┘               │
│                            │                                      │
│  ┌─────────────────────────┼──────────────────────────────────┐  │
│  │           REDIS         │                                   │  │
│  │  request:state:*   oauth2:token:*   request:lock:*         │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │  EXTERNAL APIs  │
                    │  (REST/OAuth2)  │
                    └─────────────────┘
```

---

## 3. Features — Detailed Explanation

### 3.1 🔄 Circuit Breaker (Resilience4j)

**What is it?** A protective mechanism that prevents cascading failures when an external API goes down. Works like an electrical fuse — when too many failures occur, it "trips open" and blocks further requests, giving the failing service time to recover.

**How it works — 3 states:**

```
CLOSED (Normal)  ──▶  OPEN (Blocking)  ──▶  HALF-OPEN (Testing)
   │                      │                      │
   │ If >50% of last     │ After 30s wait,      │ 3 test requests
   │ 10 requests fail →  │ automatically moves  │ are sent through.
   │ transitions to OPEN │ to HALF-OPEN →       │
   │                      │                      │
   │                      │  ← If 2 of 3 test   │
   │  ← If test requests │     requests fail →  │
   │     succeed →       │     returns to OPEN  │
   │     returns to      │                      │
   │     CLOSED          │                      │
```

**Configuration (application.yml):**
```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 10          # Analyzes the last 10 requests
        failure-rate-threshold: 50        # >50% failures → OPEN
        wait-duration-in-open-state: 30s  # Wait 30s in OPEN before testing
        permitted-number-of-calls-in-half-open-state: 3  # 3 test requests in HALF-OPEN
        slow-call-duration-threshold: 10s  # Requests >10s are considered "slow"
        slow-call-rate-threshold: 50       # >50% slow calls → OPEN
```

**Key architectural decisions:**
- **Per-domain CB:** Each external API base URL gets its own Circuit Breaker instance (e.g., `cb-api.example.com` and `cb-api.other.com` are independent). One API going down doesn't affect the other.
- **OAuth2 errors excluded from CB:** Token acquisition failures are treated as internal issues and don't count toward the CB failure rate. This prevents authentication glitches from falsely tripping the breaker.

**What happens when CB is OPEN (since v2.3.0 — this changed):**
- No HTTP call is made; the send path returns a synthetic 503 with `errorSource: "CIRCUIT_BREAKER"`.
- That is **back-pressure, not a failure.** The consumer **parks** the message in memory
  (`CbRequeueScheduler`) and re-checks the circuit on a doubling delay (500 ms → 30 s). When the
  circuit accepts traffic again the message is re-queued and processed normally. The attempt count
  is never incremented, and nothing is written to Oracle or the DLQ.
- The message's **Kafka offset stays uncommitted for the whole wait**, so a crash redelivers it
  rather than losing it. The visible side effect is **consumer lag rising while a circuit is open —
  that is expected and correct**, not a symptom.
- Give-up is by **time**: once a parked message is older than `gateway.retry.max-message-age-ms`
  (5 min) it becomes exactly one DLQ record (`Circuit breaker still open after <ms>ms`).
- Until v2.3.0 the message was re-queued to Kafka immediately instead — a ~20 ms spin that burned
  the old 50-requeue budget in about one second. On 2026-07-27 that turned a single 30 s circuit
  window into **212 DLQ'd live production requests** the upstream never saw.

**Not opening the circuit in the first place (v2.3.0, Phase 2):** `HostConcurrencyLimiter` caps our
own concurrent in-flight calls per upstream host (`gateway.api.max-concurrent-per-host`, default 25).
A refused permit returns the same CB-blocked shape, so it takes the same park-and-retry path. In a
production burst on 2026-07-28 (~330 req/s arriving, 5× denser than the incident) it deferred ~87 %
of the burst, **the circuit never opened, and nothing was lost or DLQ'd**.

### 3.2 🔐 OAuth2 Authentication

**What is it?** Automatic token acquisition and caching system for external APIs that require OAuth2 authentication.

**How it works:**
1. Incoming request has an `oauth2-provider` field
2. Redis is checked for a cached token
3. If no token or expired → acquire a new one from the token provider
4. Redis distributed lock ensures only ONE instance requests a new token
5. All other instances read from cache

**Why this matters:** If 15 consumer threads simultaneously need a token, without distributed locking all 15 would hit the token provider. With Redis lock, only 1 request goes out; the other 14 read from cache.

**Configuration:**
```yaml
gateway:
  oauth2:
    providers:
      main-app:
        type: biruni               # Token provider type
        token-url: http://server/oauth/token
        client-id: your-client-id
        client-secret: your-client-secret
        scope: read+write
```

**Redis keys:**
- `oauth2:token:{provider-name}` — Cached token (TTL = token expiry - 60s safety margin)
- `oauth2:lock:{provider-name}` — Distributed lock (only one instance refreshes at a time)

**Fallback behavior:** If Redis is down during token refresh but an expired token exists in cache, the expired token is returned as a fallback. This prevents total failure during transient Redis outages.

### 3.3 🔁 Retry Mechanism

**What is it?** Automatic retry for transient HTTP failures based on configurable status codes.

**Retryable status codes:** `408` (Request Timeout), `429` (Too Many Requests), `500` (Internal Server Error), `502` (Bad Gateway), `503` (Service Unavailable), `504` (Gateway Timeout)

**Example flow:**
```
Attempt 1: HTTP 503 → retryable status, retry...
Attempt 2: HTTP 503 → retryable status, retry...
Attempt 3: HTTP 503 → max attempts exhausted → sent to DLQ
```

**Configuration:**
```yaml
gateway:
  retry:
    max-attempts: 3                              # Maximum retry attempts
    interval-ms: 3000                            # Interval between retries (ms)
    retryable-statuses: 408,429,500,502,503,504  # Status codes that trigger retry
```

**Safety mechanism:** If Redis is unavailable and `attemptCount` cannot be read, `Integer.MAX_VALUE` is returned. This prevents infinite retry loops — the request goes directly to DLQ.

**Important distinction:** 4xx client errors (except 408/429) are NOT retried. A `400 Bad Request` means the request data is wrong — retrying won't fix it. These are treated as "successes" by the Circuit Breaker (the external API is working fine, the request is just invalid).

### 3.4 ☠️ Dead Letter Queue (DLQ)

**What is it?** A dedicated Kafka topic where messages are sent after all retry attempts have been exhausted. Used for analysis and manual reprocessing.

**Reasons a message enters DLQ:**
- Max retry attempts exhausted
- Circuit Breaker is OPEN (external API unavailable)
- Kafka serialization error (malformed data)
- HTTP timeout after all retries

**DLQ message format:**
```json
{
  "companyId": 220,
  "requestId": 452015,
  "originalTopic": "gateway.request.new",
  "failureReason": "HTTP 503 after 3 retries",
  "errorSource": "HTTP",
  "httpStatus": 503,
  "attemptCount": 3,
  "url": "POST https://api.example.com/v1/orders",
  "failedAt": "2025-01-15T10:30:00"
}
```

**DLQ strategy:** Requires manual intervention. There is no automatic reprocessing — this is intentional to avoid cascading failures. To reprocess, reset the request status to `N` in Oracle and the pipeline will pick it up again.

**Telegram notification:** Every DLQ message triggers a Telegram notification if configured.

### 3.5 ⚡ Dynamic Concurrency Scaling

**What is it?** Automatic adjustment of Kafka consumer thread count based on consumer lag (number of unprocessed messages).

**How it works:**
```
Lag = 5   (low)    → concurrency = 10 (min, scale down)
Lag = 30  (normal) → no change (neutral zone)
Lag = 100 (high)   → concurrency = 12 (scale up by step=2)
Lag = 200 (very high) → concurrency = 14 → 15 (max)
```

**Configuration:**
```yaml
gateway:
  concurrency:
    min-concurrency: 10        # Minimum consumer threads
    max-concurrency: 15        # Maximum consumer threads
    monitor-interval-ms: 10000 # Lag check interval (10 sec)
    scale-up-threshold: 50     # Lag >50 → scale up
    scale-down-threshold: 10   # Lag <10 → scale down
    scale-step: 2              # Change by ±2 threads at a time
    scale-cooldown-ms: 30000   # Minimum 30s between scaling events
```

**Cooldown mechanism:** Prevents rapid flip-flopping between scale levels. After any scaling event, a minimum of 30 seconds must pass before the next adjustment.

**Applies to both consumers:** `requestConsumer` (E3) and `responseConsumer` (E5) are independently scaled based on their respective topic lag.

### 3.6 🔒 Idempotency (Redis Lock)

**What is it?** Guarantees that each request is processed exactly once, even if Kafka redelivers the same message (which happens during consumer rebalances or failures).

**Mechanism:**
1. Consumer receives a message
2. `requestStateService.isCompleted(key)` — already finished? → skip
3. `requestStateService.tryLock(key)` — acquires a Redis lock (TTL: 300 sec)
4. HTTP request is sent
5. Lock is released after processing

**Redis keys:**
- `request:state:{companyId}:{requestId}` — Request state object (TTL: 72 hours)
- `request:lock:{companyId}:{requestId}` — Distributed lock (TTL: 300 seconds)

**If lock acquisition fails:** Another instance is processing this request. The current consumer acknowledges the message and moves on. Grafana metric: `gateway_consumer_skipped_total{reason="lock_failed"}`

### 3.7 📱 Telegram Notifications

**What is it?** Automatic Telegram messages when critical events occur (DLQ messages, system alerts).

**Configuration:** the four behavioral keys are **DB-managed** — edit them in the SPA's **Telegram** settings tab (`/qosid/admin/api/telegram-config`, stop-to-edit via the ops sidecar), where the bot-token is stored AES-encrypted and masked. When no DB row exists, `TelegramNotificationService` falls back per send to the env vars below; the two timeout keys are env/yaml-only.

```yaml
gateway:
  telegram:
    enabled: ${TELEGRAM_ENABLED:true}                    # DB-managed, env fallback
    bot-token: ${TELEGRAM_BOT_TOKEN:}                     # DB-managed secret, env fallback
    chat-id: ${TELEGRAM_CHAT_ID:0}                        # DB-managed, env fallback
    message-thread-id: ${TELEGRAM_MESSAGE_THREAD_ID:}     # DB-managed, env fallback (optional topic/thread)
    connect-timeout-ms: 5000                              # env/yaml only
    read-timeout-ms: 5000                                 # env/yaml only
```

**Disabled/unconfigured:** `TelegramNotificationService` is always the active bean (no separate no-op fallback bean). When Telegram is disabled or the effective bot-token is blank, DLQ alerts are skipped with a warn-log — no errors — so the service runs fine without Telegram in development.

### 3.8 🌐 Per-Endpoint HTTP Timeouts

**What is it?** Different timeout values for different external APIs. A slow legacy API can have 60s timeout while a fast microservice gets 5s.

```yaml
gateway:
  http:
    connect-timeout-ms: 10000     # Default connection timeout
    read-timeout-ms: 30000        # Default read timeout
    write-timeout-ms: 30000       # Default write timeout
    endpoint-timeouts:
      api.slow-service.com: 60000  # 60s for slow API
      api.fast-service.com: 5000   # 5s for fast API
```

### 3.9 🧵 HTTP Thread Pool (Dedicated)

**What is it?** Kafka consumer threads do NOT make HTTP calls directly. Instead, they delegate HTTP work to a dedicated `httpRequestExecutor` thread pool. This prevents slow external APIs from blocking Kafka message consumption.

**Configuration (auto-derived from concurrency settings):**
- Core pool size: `min-concurrency` (10)
- Max pool size: `max-concurrency * 2` (30)
- Queue capacity: 50 tasks
- Thread name prefix: `http-req-`

**Backpressure mechanism:** When the HTTP thread pool queue is full, the CallerRunsPolicy kicks in — the Kafka consumer thread itself runs the HTTP task. This naturally slows down message consumption, providing built-in backpressure.

---

## 4. Ports and Services

### 4.1 Application Ports

| Port | Service | Purpose |
|------|---------|---------|
| **8090** | Gateway Service (main) | Main HTTP server, test endpoints |
| **8091** | Gateway Service (management) | Health check, Prometheus metrics |

### 4.2 Infrastructure Ports

| Port | Service | UI / Purpose |
|------|---------|--------------|
| **19092** | RedPanda (Kafka) | Kafka protocol (external access) |
| **9092** | RedPanda (Kafka) | Kafka protocol (internal/docker) |
| **8080** | RedPanda Console | Kafka UI — view topics, browse messages |
| **8081** | RedPanda Schema Registry | Schema management |
| **6379** | Redis | Data store (state, locks, OAuth2 tokens) |
| **9090** | Prometheus | Metrics storage, PromQL queries |
| **3000** | Grafana | Dashboards, visualization (admin/admin123) |
| **9093** | Alertmanager | Alert routing → Telegram |
| **9121** | Redis Exporter | Redis metrics → Prometheus |

### 4.3 External Connections

| Port | Service | Notes |
|------|---------|-------|
| **8080** | Oracle/Biruni Application | Data source (request pull / response save) |
| **Various** | External APIs | Target APIs (with or without OAuth2) |

---

## 5. Monitoring and Observability

### 5.1 Prometheus Metrics

The Gateway exposes all metrics on port 8091 at `/actuator/prometheus`.

**Core metrics by pipeline stage:**

| Metric | Type | What it means |
|--------|------|---------------|
| `gateway_oracle_pull_total{result="success"}` | Counter | Requests successfully pulled from Oracle |
| `gateway_oracle_pull_total{result="error"}` | Counter | Oracle pull failures |
| `gateway_oracle_pull_empty_total` | Counter | Empty pull cycles (no pending requests) |
| `gateway_kafka_produce_total{result="success/error"}` | Counter | Messages sent to / failed sending to Kafka |
| `gateway_kafka_produce_retry_total` | Counter | Kafka produce retry attempts |
| `gateway_http_request_total{result="success"}` | Counter | Successful HTTP requests |
| `gateway_http_request_total{result="error_4xx"}` | Counter | Client errors (bad request data) |
| `gateway_http_request_total{result="error_5xx"}` | Counter | Server errors (external API issues) |
| `gateway_http_request_total{result="timeout"}` | Counter | HTTP timeouts |
| `gateway_http_request_timer` | Timer | HTTP request latency (duration) |
| `gateway_http_request_retry_total` | Counter | HTTP request retry attempts |
| `gateway_oracle_save_total{result="success/error"}` | Counter | Oracle save success/failure |
| `gateway_oracle_save_retry_total` | Counter | Oracle save retry attempts |
| `gateway_dlq_sent_total` | Counter | Messages sent to DLQ |
| `gateway_http_circuitbreaker_rejected_total` | Counter | Calls refused by an OPEN circuit |
| `gateway_http_circuitbreaker_requeue_delayed_total` | Counter | Messages **parked** to wait a circuit out (v2.3.0) |
| `gateway_http_circuitbreaker_requeue_pending{flow}` | Gauge | Currently parked, per flow. Rises during an outage and **must return to 0** once it clears; a plateau means the upstream is still refusing |
| `gateway_http_circuitbreaker_requeue_pending_bytes{flow}` | Gauge | Parked body bytes — this hits `cb-max-pending-bytes` before the count bound does |
| `gateway_http_circuitbreaker_requeue_exhausted_total` | Counter | Parked messages that aged past `max-message-age-ms` and were DLQ'd — **the real outage signal** |
| `gateway_http_circuitbreaker_requeue_rejected_total` | Counter | DLQ'd because the park buffer was full — service loss, alert on it |
| `gateway_http_host_limiter_rejected_total` | Counter | Calls deferred by the per-host concurrency bound. Rising while circuits stay CLOSED is **backpressure working**, not an error |
| `gateway_notification_alert_coalesced_total` | Counter | DLQ alerts folded into a summary or held back by a Telegram 429 |
| `gateway_consumer_received_total` | Counter | Messages received by consumer (**includes re-deliveries of parked messages**, so it exceeds the unique request count during a burst) |
| `gateway_consumer_skipped_total{reason="duplicate"}` | Counter | Skipped as already processed |
| `gateway_consumer_skipped_total{reason="lock_failed"}` | Counter | Skipped — another instance processing |
| `gateway_kafka_consumer_lag` | Gauge | Kafka consumer lag (pending messages) |
| `gateway_kafka_consumer_concurrency` | Gauge | Current concurrency level |
| `gateway_http_pool_active` | Gauge | Active threads in HTTP pool |
| `gateway_http_pool_size` | Gauge | HTTP thread pool size |
| `gateway_http_pool_queue` | Gauge | HTTP thread pool queue depth |

### 5.2 Grafana Dashboard

**File:** `infrastructure/dashboard_grafana.json`  
**UID:** `gateway-pipeline-monitoring`  
**Auto-refresh:** every 10 seconds

**Dashboard sections and what each panel means:**

#### 📊 Overview — Key Stats (Top Row)
Six stat panels — system health at a glance:

| Panel | Metric | Color Thresholds | What to watch for |
|-------|--------|-----------------|-------------------|
| Requests Pulled (5m) | Oracle pull success | 🟢 <50, 🟡 50-100, 🔴 >100 | Should match expected request volume |
| HTTP Success (5m) | HTTP success count | 🟢 <10, 🟡 10-50, 🔴 >50 | Should roughly match pulled count |
| 🔴 Total Errors (5m) | Sum of all pipeline errors | 🟢 0, 🟠 1-10, 🔴 >10 | Any red = investigate immediately |
| ☠️ DLQ Sent (5m) | DLQ messages | 🟢 0, 🟠 1-5, 🔴 >5 | Any non-zero = manual action needed |
| 🔄 Total Retries (5m) | All retry attempts | 🟢 0, 🟡 5-20, 🔴 >20 | Occasional retries normal; sustained = problem |
| Kafka Consumer Lag | Unprocessed messages | 🟢 <100, 🟡 100-500, 🔴 >500 | High lag = consumers can't keep up |

#### 🔴 Error Rate by Stage (Bottleneck Detection)
- **Error Rate by Pipeline Stage** — Stacked bar chart. Shows which stage (E1/E2/E4/E5) has the most errors. If E4 dominates → external API problems. If E1 dominates → Oracle connection issues.
- **HTTP Errors by Type** — Line chart. Breakdown of 4xx vs 5xx vs timeout. High 4xx = bad request data from Oracle. High 5xx = external API failing. High timeout = need to increase timeout or API is too slow.

#### ⏱️ Latency & Throughput
- **HTTP Request Latency (p50/p95/p99)** — Percentile histogram. p99 > 10s = trouble. p50 shows typical latency.
- **Pipeline Throughput** — Rate/sec for each stage (Pull, Produce, HTTP, Save). All four lines should track together. If one diverges → bottleneck at that stage.

#### 🔄 Kafka & Consumer Dynamics
- **Consumer Concurrency & Lag** — Shows current concurrency level alongside lag. When lag rises, concurrency should follow. If concurrency is max (15) but lag keeps growing → need more partitions or instances.
- **HTTP Thread Pool** — Active threads, pool size, queue size. Queue > 0 consistently = HTTP thread pool overwhelmed.
- **Consumer Events** — Received vs Skipped (duplicate/lock). High skip rate = rebalancing issues or multiple instances processing same partition.

#### ☠️ DLQ & Failures
- **DLQ Messages Over Time** — Red bar chart. Should be zero. Any bars = requests that failed all retries.
- **Oracle Poll: Empty vs Filled** — Shows whether Oracle has pending requests. Constant "empty" = no new work. Constant "filled" = request volume is high.

### 5.3 Alertmanager → Telegram

Alert rules are defined in `infrastructure/prometheus/gateway-alerts.yml`.

**Alert rules:**

| Alert Name | Condition | Severity | Meaning |
|------------|-----------|----------|---------|
| GatewayHighErrorRate | >5 errors in 5 minutes | critical | Serious pipeline issue, check logs |
| GatewayDLQMessages | Any DLQ message | warning | Failed request needs manual review |
| GatewayHighLag | Consumer lag > 500 | warning | Consumers can't keep up with incoming rate |
| GatewayOracleConnectionFailure | >3 pull errors | critical | Oracle/Biruni connection lost |

**Telegram message format:**
```
🔴 ALERT FIRING
━━━━━━━━━━━━━━━━━━
GatewayHighErrorRate
Severity: critical
Stage: E4
Status: firing

High error rate in HTTP stage: 12 errors in 5m

✅ RESOLVED
━━━━━━━━━━━━━━━━━━
GatewayHighErrorRate
🕐 Resolved at: 2025-01-15 10:35:00
```

---

## 6. Troubleshooting Guide

### 6.1 Message stuck at E1 (Oracle Pull)

**Symptoms:** `gateway_oracle_pull_total{result="error"}` increasing

**Check:**
1. Is Oracle/Biruni running? → `curl http://localhost:8080/health`
2. Are credentials correct? → Check `source.username/password` in `application-{profile}.yml`
3. Is pull URI correct? → `source.request-pull-uri`
4. Check logs: `grep "E1" logs/gateway.log`

### 6.2 Message stuck at E2 (Kafka Produce)

**Symptoms:** `gateway_kafka_produce_total{result="error"}` increasing

**Check:**
1. Is Kafka running? → `docker exec -it redpanda rpk cluster health`
2. Does topic exist? → `docker exec -it redpanda rpk topic list`
3. Serialization error? → Search logs for `SerializationException`
4. RedPanda Console: `http://localhost:8080` → Topics page

### 6.3 Message stuck at E3 (Consumer)

**Symptoms:** `gateway_consumer_skipped_total` increasing, lag growing

**Check:**
1. Duplicate skips? → `gateway_consumer_skipped_total{reason="duplicate"}` — stale state in Redis
2. Lock contention? → `gateway_consumer_skipped_total{reason="lock_failed"}` — another instance processing
3. Is Redis running? → `docker exec -it redis redis-cli ping`
4. Inspect Redis state: `docker exec -it redis redis-cli keys "request:state:*"`
5. Clear stale state if needed: `docker exec -it redis redis-cli del "request:state:COMPANY:REQUEST_ID"`

### 6.4 Message stuck at E4 (HTTP Request)

**Symptoms:** `gateway_http_request_total{result="error_5xx"}` or `timeout` increasing

**Check:**
1. Is Circuit Breaker open? → Search logs for `Circuit Breaker [cb-...] OPEN` or check Grafana CB panel
2. OAuth2 token issue? → Search logs for `OAuth2 token failed`
3. Is external API reachable? → Test directly with `curl`
4. Timeout too low? → Adjust `gateway.http.endpoint-timeouts` for specific domains
5. Thread pool exhausted? → Check `gateway_http_pool_queue` in Grafana. If consistently > 0, increase pool size.

**Since v2.3.0, "stuck at E4" usually means parked, not lost.** Read the metrics before the logs:

| What you see | What it means | Action |
|---|---|---|
| `requeue_pending{flow}` > 0, circuit OPEN, lag rising | Working as designed — messages are waiting out the outage, offsets deliberately uncommitted | Fix the upstream. Nothing is lost; they DLQ only after `max-message-age-ms` (5 min) |
| `host_limiter_rejected_total` rising, circuits **CLOSED** | Our own burst is being smoothed | None. Raise `gateway.api.max-concurrent-per-host` only if the upstream is provably idle |
| `requeue_exhausted_total` rising | Messages aged out while parked → real DLQ records | Genuine outage — the upstream did not come back within 5 minutes |
| `requeue_rejected_total` > 0 | Park buffer full; messages DLQ'd **untried** | Raise `cb-max-pending`/`cb-max-pending-bytes` or fix the upstream now |
| `exhausted` rising while `pending` never rises | The park is **not happening** — a regression | Check that the CB branch still parks (SAFETY-RULES 23, 40) |
| DLQ reason says `... after <n> requeues` | Pre-v2.3.0 count-based give-up came back | Regression — the delay path is bypassed |

**Do not measure a burst from `journalctl`.** Under load `systemd-journald` silently drops
messages (it dropped 88 881 during one production test), so log-derived counts undercount and can
look like data loss. Confirm with Redis instead:
`sed 's|^|GET request:state:|' ids.txt | docker exec -i redis redis-cli`, plus consumer lag and the
gauges above. Check whether it dropped:
`journalctl -u gateway-service --since X --until Y | grep -c "Suppressed"`.

### 6.5 Message stuck at E5 (Oracle Save)

**Symptoms:** `gateway_oracle_save_total{result="error"}` increasing

**Check:**
1. Oracle connection alive?
2. Is response format correct? (Oracle expects `snake_case`: `company_id`, `request_id`)
3. Search logs for `E5` errors

### 6.6 Investigating DLQ Messages

**How to view DLQ messages:**
```bash
# Via CLI
docker exec -it redpanda rpk topic consume gateway.request.dlq

# Via RedPanda UI
http://localhost:8080 → Topics → gateway.request.dlq → Messages
```

**How to reprocess:** Set the request status back to `N` in Oracle. The pipeline will pick it up again on the next pull cycle.

### 6.7 Quick Health Commands

```bash
# Application health
curl http://localhost:8091/actuator/health | jq

# Prometheus metrics (raw)
curl http://localhost:8091/actuator/prometheus | grep gateway_

# Kafka cluster health
docker exec -it redpanda rpk cluster health

# Kafka topic lag
docker exec -it redpanda rpk group describe gateway-service-group

# Redis connectivity
docker exec -it redis redis-cli ping

# Redis key count by pattern
docker exec -it redis redis-cli keys "request:state:*" | wc -l
docker exec -it redis redis-cli keys "request:lock:*" | wc -l
docker exec -it redis redis-cli keys "oauth2:token:*"
```

---

## 7. Configuration Reference

### 7.1 Profiles

| Profile | File | Usage |
|---------|------|-------|
| `dev` | `application-dev.yml` | Local development. All defaults pre-configured |
| `prod` | `application-prod.yml` | Production. All values from ENV variables |
| `test` | `application-test.yaml` | Test suite |

### 7.2 Complete Configuration Parameters

```yaml
# ========== GATEWAY CORE ==========
gateway:
  polling:
    enabled: true           # Enable/disable Oracle polling
    interval-ms: 5000       # Pull interval (ms)
    batch-size: 100         # Requests pulled per batch

  source:
    base-url: http://...    # Oracle/Biruni server URL
    username: admin          # Login username
    password: ***            # Login password
    request-pull-uri: /api/requests/pull   # Pull endpoint path
    response-save-uri: /api/requests/save  # Save endpoint path
    connection-timeout: 60   # Connection timeout (seconds)

  kafka:
    bootstrap-servers: localhost:19092
    group-id: gateway-service-group
    topics:
      request-new: gateway.request.new
      request-response: gateway.request.response
      request-dlq: gateway.request.dlq

  retry:
    max-attempts: 3
    interval-ms: 3000
    retryable-statuses: 408,429,500,502,503,504

  concurrency:
    min-concurrency: 10
    max-concurrency: 15
    monitor-interval-ms: 10000
    scale-up-threshold: 50
    scale-down-threshold: 10
    scale-step: 2
    scale-cooldown-ms: 30000
    topic-partitions: 10

  http:
    connect-timeout-ms: 10000
    read-timeout-ms: 30000
    write-timeout-ms: 30000
    endpoint-timeouts:
      api.slow-service.com: 60000
      api.fast-service.com: 5000

  oauth2:
    providers: {}            # OAuth2 provider configs

  telegram:
    enabled: false
    bot-token: ""
    chat-id: 0
    message-thread-id: null

# ========== REDIS ==========
spring.data.redis:
  host: localhost
  port: 6379
  state-ttl-hours: 72        # Request state TTL (hours)
  lock-ttl-seconds: 300      # Distributed lock TTL (seconds)

# ========== CIRCUIT BREAKER ==========
resilience4j.circuitbreaker.configs.default:
  sliding-window-size: 10
  failure-rate-threshold: 50
  wait-duration-in-open-state: 30s
  permitted-number-of-calls-in-half-open-state: 3
  slow-call-duration-threshold: 10s
  slow-call-rate-threshold: 50
  # Must stay true: the park's circuit poll is a passive read and never drives the
  # OPEN → HALF_OPEN transition itself (SAFETY-RULES 23).
  automatic-transition-from-open-to-half-open-enabled: true

# ========== CB BACK-PRESSURE (v2.3.0) ==========
gateway.retry:
  max-message-age-ms: 300000        # RETRY_MAX_MESSAGE_AGE — the real give-up deadline
  cb-requeue-delay-ms: 500          # RETRY_CB_REQUEUE_DELAY — first re-check, doubles from here
  cb-requeue-max-delay-ms: 30000    # RETRY_CB_REQUEUE_MAX_DELAY — one CB open-window
  cb-max-pending: 5000              # RETRY_CB_MAX_PENDING — parked messages per flow
  cb-max-pending-bytes: 268435456   # RETRY_CB_MAX_PENDING_BYTES — 256 MiB per flow
  cb-max-requeues: 1000             # RETRY_CB_MAX_REQUEUES — runaway guard ONLY, not a policy

gateway.api:
  max-concurrent-per-host: 25       # API_MAX_CONCURRENT_PER_HOST — <=0 disables the bound

gateway.telegram:
  coalesce-window-ms: 60000         # TELEGRAM_COALESCE_WINDOW_MS — one incident, one alert

# ========== SERVER ==========
server:
  port: 8090
  shutdown: graceful

management:
  server.port: 8091
  endpoints.web.exposure.include: health,info,prometheus
```

---

## 8. Installation Guide

### 8.1 Prerequisites

| Software | Version | Check |
|----------|---------|-------|
| Java JDK | 21+ | `java --version` |
| Maven | 3.9+ | `mvn --version` |
| Docker | 24+ | `docker --version` |
| Docker Compose | 2.0+ | `docker compose version` |
| Git | 2.0+ | `git --version` |

### 8.2 Clone the Repository

```bash
git clone <repository-url>
cd qosid-flow
```

### 8.3 Start Infrastructure

```bash
# Start all infrastructure services
docker compose up -d

# Check status
docker compose ps

# Expected running services:
# - redpanda          (Kafka)
# - redpanda-console  (Kafka UI)
# - redis             (State store)
# - redis-exporter    (Redis metrics)
# - prometheus         (Metrics)
# - grafana            (Dashboards)
# - alertmanager       (Alerts)
```

### 8.4 Verify Kafka Topics

```bash
# List existing topics
docker exec -it redpanda rpk topic list

# If topics don't exist, create them manually:
docker exec -it redpanda rpk topic create gateway.request.new -p 10
docker exec -it redpanda rpk topic create gateway.request.response -p 10
docker exec -it redpanda rpk topic create gateway.request.dlq -p 3
```

### 8.5 Verify Redis

```bash
docker exec -it redis redis-cli ping
# Expected: PONG
```

### 8.6 Build and Run the Application

```bash
# Build (skip tests for quick start)
./mvnw clean package -DskipTests

# Run in development mode
java -jar app/target/qosid-flow-*.jar --spring.profiles.active=dev

# Run in production mode
export SPRING_PROFILE=prod
export KAFKA_BOOTSTRAP_SERVERS=kafka:9092
export REDIS_HOST=redis
export SOURCE_BASE_URL=http://oracle-server:8080/app
export SOURCE_USERNAME=admin
export SOURCE_PASSWORD=secure_password
# ... (see .env.example for all variables)
java -jar app/target/qosid-flow-*.jar --spring.profiles.active=prod
```

### 8.7 Verify Startup

```bash
# Health check
curl http://localhost:8091/actuator/health | jq

# Check Prometheus metrics are flowing
curl http://localhost:8091/actuator/prometheus | head -20

# Send a test request
curl -X POST http://localhost:8090/api/test/request

# Send a test DLQ message (verify Telegram notification)
curl -X POST http://localhost:8090/api/test/dlq
```

### 8.8 Set Up Grafana

1. Open `http://localhost:3000` in browser
2. Login: `admin` / `admin123`
3. Go to **Connections → Data Sources → Add data source**
4. Select **Prometheus** → URL: `http://prometheus:9090` → Save & Test
5. Go to **Dashboards → Import**
6. Upload `infrastructure/dashboard_grafana.json`
7. Select the Prometheus data source when prompted
8. Dashboard should now show live data

### 8.9 Run with Docker (Full Stack)

```bash
# Build the application image
docker build -t qosid-flow .

# Or run everything together
docker compose --profile app up -d
```

### 8.10 Useful Commands Reference

```bash
# View application logs
docker compose logs -f gateway-service

# Watch Kafka messages in real-time
docker exec -it redpanda rpk topic consume gateway.request.new
docker exec -it redpanda rpk topic consume gateway.request.dlq

# Monitor Redis commands
docker exec -it redis redis-cli monitor

# Check Kafka consumer group lag
docker exec -it redpanda rpk group describe gateway-service-group

# Stop all services
docker compose down

# Stop and remove all data (clean restart)
docker compose down -v
```

---

## 9. Production Readiness Assessment

### 9.1 ✅ What's Ready

| Aspect | Status | Details |
|--------|--------|---------|
| 5-stage pipeline (E1-E5) | ✅ Working | Oracle → Kafka → Consumer → HTTP → Oracle |
| Circuit Breaker | ✅ Working | Per-endpoint, Resilience4j, auto-recovery |
| OAuth2 + Redis cache | ✅ Working | Distributed lock, shared token across instances |
| Retry + DLQ | ✅ Working | Configurable retries, DLQ with Telegram alerts |
| Idempotency | ✅ Working | Redis lock + state check, prevents duplicates |
| Dynamic Concurrency | ✅ Working | Lag-based auto-scaling (10-15 threads) |
| Prometheus Metrics | ✅ Working | 20+ custom metrics covering all stages |
| Grafana Dashboard | ✅ Working | Pipeline monitoring with multiple panels |
| Telegram Alerting | ✅ Working | DLQ + critical error notifications |
| Graceful Shutdown | ✅ Working | 30s timeout, in-flight request completion |
| Health Check | ✅ Working | `/actuator/health` with detailed component status |
| Docker Support | ✅ Working | Multi-stage Dockerfile, non-root user |
| Dev/Prod Profiles | ✅ Working | Environment-based configuration |
| Per-endpoint Timeouts | ✅ Working | Different timeout per external API |
| Dedicated HTTP Thread Pool | ✅ Working | Backpressure via CallerRunsPolicy |

### 9.2 ⚠️ Critical Issues for Production

See **Section 10** for the detailed task list and resolution plan.

### 9.3 Final Verdict

**The system works and can handle production workloads in dev/staging environments.** However, for full production readiness, the 4 critical issues (blocking `.block()`, Redis HA, Kafka cluster, deserialization security) must be addressed first.

**Rating:** 🟡 **Near Production-Ready** — estimated 1-2 weeks of additional work.

---

## 10. Production Readiness — Task List

### ✅ CRITICAL-1 (RESOLVED): Remove Blocking `.block()` in RequestConsumer

> **Done.** Both request consumers now subscribe to the `Mono` and handle the response in a
> callback; `grep -rn "\.block(" app/src/main/java/.../kafka/consumer/` returns nothing. The lock
> is released and the record acknowledged in the callback's `finally` — except for a parked
> CB-blocked message, whose record is completed by `CbRequeueScheduler` instead (SAFETY-RULES 40).
> The class named below has since been split per flow (`OracleRequestConsumer` /
> `ApiRequestConsumer`, SAFETY-RULES 5). The original analysis is kept because it explains *why*
> the pipeline is shaped this way.

**File (historical):** `src/main/java/uz/greenwhite/gateway/kafka/consumer/RequestConsumer.java`

**Current problem:**
```java
// Line in processRequest() method:
response = httpRequestService.sendRequest(message)
        .block(Duration.ofMillis(
        retryProperties.getIntervalMs() * retryProperties.getMaxAttempts() + 60_000));
```

**Why this is dangerous:**
- `httpRequestService.sendRequest()` returns a `Mono<ResponseMessage>` (reactive type)
- `.block()` converts it to a synchronous blocking call
- This blocks the `httpExecutor` thread for the entire HTTP duration (up to 69 seconds with default config)
- Under high load, all `httpExecutor` threads can become blocked waiting for HTTP responses
- When the thread pool is exhausted, the CallerRunsPolicy kicks in, which blocks the Kafka consumer thread
- Result: **complete pipeline stall** — no new messages are consumed

**The irony:** The project uses WebClient specifically for non-blocking HTTP, but `.block()` negates the entire benefit.

**What needs to be done:**

Replace the blocking call with a fully reactive chain. The `processRequest()` method should subscribe to the Mono reactively rather than blocking on it:

```java
// INSTEAD of:
ResponseMessage response = httpRequestService.sendRequest(message).block(...);
// ... handle response synchronously

// CHANGE TO:
        httpRequestService.sendRequest(message)
    .timeout(Duration.ofMillis(totalTimeoutMs))
        .subscribe(
        response -> handleResponse(key, message, response),
error -> handleFailedProcessing(key, message, error),
        () -> { /* completion */ }
                );
```

**Key considerations during migration:**
- The `ack.acknowledge()` call must happen AFTER the reactive chain completes, not before
- Timer metrics (`httpSample.stop(...)`) must be placed inside `doOnSuccess`/`doOnError` handlers
- The Redis lock `releaseLock(key)` must be in a `doFinally()` handler
- The `CompletableFuture.runAsync()` wrapper may no longer be necessary since WebClient is already non-blocking
- Test with concurrent requests to verify no race conditions

**Estimated effort:** 1-2 days (code change is small but requires careful testing)

---

### 🔴 CRITICAL-2: Redis High Availability

**Current problem:**
Redis runs as a single instance. If it crashes, ALL of these stop working simultaneously:
- **Idempotency locks** (`request:lock:*`) — duplicate processing risk
- **Request state** (`request:state:*`) — pipeline loses track of request status
- **OAuth2 token cache** (`oauth2:token:*`) — every request triggers token acquisition
- **OAuth2 distributed locks** (`oauth2:lock:*`) — token thundering herd problem

**Impact of Redis downtime:**
- Idempotency breaks → same request processed multiple times
- OAuth2 tokens lost → token provider gets hammered with requests
- State lost → requests may be re-pulled from Oracle and reprocessed
- **Result: data corruption, duplicate external API calls, potential financial impact**

**What needs to be done:**

**Option A: Redis Sentinel (Recommended for simplicity)**

Redis Sentinel provides automatic failover with a primary-replica setup:

```yaml
# docker-compose.yml additions
redis-master:
  image: redis:7-alpine
  command: redis-server /etc/redis/redis.conf
  volumes:
    - ./infrastructure/redis/redis.conf:/etc/redis/redis.conf

redis-replica:
  image: redis:7-alpine
  command: redis-server --replicaof redis-master 6379

redis-sentinel-1:
  image: redis:7-alpine
  command: redis-sentinel /etc/redis/sentinel.conf

redis-sentinel-2:
  image: redis:7-alpine
  command: redis-sentinel /etc/redis/sentinel.conf

redis-sentinel-3:
  image: redis:7-alpine
  command: redis-sentinel /etc/redis/sentinel.conf
```

Spring Boot configuration:
```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - redis-sentinel-1:26379
          - redis-sentinel-2:26379
          - redis-sentinel-3:26379
```

**Option B: Redis Cluster (For higher throughput)**

Redis Cluster provides sharding + replication, suitable when data volume is large:
```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - redis-1:6379
          - redis-2:6379
          - redis-3:6379
```

**Application code changes:**
- Spring Data Redis abstracts Sentinel/Cluster — **minimal code changes needed**
- Test that `setIfAbsent()` (used for distributed locks) works correctly in Sentinel/Cluster mode
- Verify TTL behavior for OAuth2 token cache

**Estimated effort:** 2-3 days (mostly infrastructure setup and testing)

---

### 🔴 CRITICAL-3: Kafka/RedPanda Cluster

**Current problem:**
RedPanda runs as a single node. If the disk fails or the container crashes:
- All unprocessed messages in `gateway.request.new` are **permanently lost**
- All pending responses in `gateway.request.response` are **permanently lost**
- DLQ messages are lost (no post-mortem analysis possible)
- Kafka consumer offsets may be lost → messages replayed or skipped on restart

**What needs to be done:**

Deploy a 3-node RedPanda/Kafka cluster with replication:

```yaml
# docker-compose.yml — 3-node RedPanda cluster
redpanda-0:
  image: redpandadata/redpanda:v24.1.1
  command:
    - redpanda start
    - --seeds redpanda-0:33145,redpanda-1:33145,redpanda-2:33145
    - --node-id 0
    # ... (network, ports config)

redpanda-1:
  image: redpandadata/redpanda:v24.1.1
  command:
    - redpanda start
    - --seeds redpanda-0:33145,redpanda-1:33145,redpanda-2:33145
    - --node-id 1

redpanda-2:
  image: redpandadata/redpanda:v24.1.1
  command:
    - redpanda start
    - --seeds redpanda-0:33145,redpanda-1:33145,redpanda-2:33145
    - --node-id 2
```

**Topic configuration for data safety:**
```bash
rpk topic create gateway.request.new \
  --partitions 10 \
  --replicas 3 \
  --config min.insync.replicas=2

rpk topic create gateway.request.response \
  --partitions 10 \
  --replicas 3 \
  --config min.insync.replicas=2

rpk topic create gateway.request.dlq \
  --partitions 3 \
  --replicas 3 \
  --config min.insync.replicas=2
```

**Producer configuration update (application.yml):**
```yaml
spring:
  kafka:
    producer:
      acks: all                  # Wait for all replicas to confirm
      retries: 3                 # Retry on transient failures
      properties:
        enable.idempotence: true # Prevent duplicate messages
        max.in.flight.requests.per.connection: 5
```

**Key parameters explained:**
- `replicas: 3` — each message stored on 3 nodes
- `min.insync.replicas: 2` — at least 2 replicas must confirm before the write is considered successful
- `acks: all` — producer waits for all in-sync replicas to acknowledge
- This combination means: **1 node can die without any data loss**

**Application code changes:** Only `bootstrap-servers` config changes (point to all 3 nodes). No code changes needed.

**Estimated effort:** 1-2 days (infrastructure setup, topic recreation, testing failover)

---

### ✅ CRITICAL-4 (RESOLVED — and it was never live): Kafka Deserialization Security

> **Investigated and closed 2026-07-28.** The `"*"` below sat in `spring.kafka.consumer.properties`
> — a block that **never applied to anything**. `KafkaConfig` declares its own `ConsumerFactory`
> bean, which makes Spring Boot's auto-configured one back off, and all six `@KafkaListener`s name
> a container factory built on that bean. The value that actually ran was
> `uz.greenwhite.gateway.*`, set in code. Verified behaviourally, not by reading: a record whose
> `__TypeId__` names `org.springframework.context.support.ClassPathXmlApplicationContext` is
> refused with *"is not in the trusted packages"* (`KafkaConsumerHardeningTest`, which also fails
> if anyone puts the wildcard back).
>
> **What the dead block did cost:** two 20 MiB fetch settings (`fetch.max.bytes`,
> `max.partition.fetch.bytes`) were written there for large payloads and silently did nothing.
> They now live in `KafkaConfig.consumerFactory()` with the rest, and the inert yaml block was
> deleted so nothing can hide there again. Note Spring also trusts `java.util` and `java.lang`
> unconditionally — a test that "proves" the restriction with a `java.util.HashMap` type id proves
> nothing.

**The configuration as it looked (inert):**
```yaml
spring:
  kafka:
    consumer:
      properties:
        spring.json.trusted.packages: "*"
```

**Why this is dangerous:**
- `"*"` allows deserialization of ANY Java class from Kafka messages
- An attacker who can write to the Kafka topic could craft a malicious payload
- This could lead to **Remote Code Execution (RCE)** — arbitrary code running on the Gateway server
- This is a well-known vulnerability class (Java deserialization attacks)

**What needs to be done:**

Restrict trusted packages to only your model classes:

```yaml
spring:
  kafka:
    consumer:
      properties:
        spring.json.trusted.packages: "uz.greenwhite.gateway.model.kafka"
```

This ensures only `RequestMessage`, `ResponseMessage`, and `DlqMessage` classes can be deserialized from Kafka messages. Any other class type will be rejected.

**Additional hardening (recommended):**

Add explicit type mappings in `KafkaConfig.java`:
```java
props.put(JsonDeserializer.TYPE_MAPPINGS,
    "request:uz.greenwhite.gateway.model.kafka.RequestMessage," +
            "response:uz.greenwhite.gateway.model.kafka.ResponseMessage," +
            "dlq:uz.greenwhite.gateway.model.kafka.DlqMessage");
```

And on the producer side, set the corresponding type headers:
```java
props.put(JsonSerializer.TYPE_MAPPINGS,
    "request:uz.greenwhite.gateway.model.kafka.RequestMessage," +
            "response:uz.greenwhite.gateway.model.kafka.ResponseMessage," +
            "dlq:uz.greenwhite.gateway.model.kafka.DlqMessage");
```

**Testing:** After making this change, send a test request through the full pipeline to verify serialization/deserialization still works correctly.

**Estimated effort:** 30 minutes (config change + testing)

---

### Summary: Production Readiness Task List

Status as of **v2.3.0 (2026-07-28)**. Items are struck through when verified done in the codebase,
not merely planned.

| # | Task | Priority | Status |
|---|------|----------|--------|
| 1 | ~~Remove `.block()` — convert to reactive chain~~ | 🔴 Critical | ✅ **Done** — consumers subscribe and complete in a callback; no `.block(` remains in `kafka/consumer/` |
| 2 | Redis HA (Sentinel or Cluster) | 🔴 Critical | ⬜ **Open** — still single-node. Mitigated, not solved: every Redis-unavailable path fails closed via the `Integer.MAX_VALUE` net (SAFETY-RULES 8), so a Redis outage DLQs rather than loops |
| 3 | Kafka 3-node cluster with replication | 🔴 Critical | ⬜ **Open** — single RedPanda node. Its disk filling caused the 2026-05-18 34-hour outage; the v2.3.0 park removed the requeue churn that filled it, but replication is still absent |
| 4 | ~~Restrict `spring.json.trusted.packages`~~ | 🔴 Critical | ✅ **Done / was never live** — the `"*"` was in an inert yaml block; the effective value has always been `uz.greenwhite.gateway.*`, now pinned by `KafkaConsumerHardeningTest`. The dead block is deleted and its two lost 20 MiB fetch settings moved into `KafkaConfig` |
| 5 | ~~Move Telegram bot token out of version control~~ | 🟡 Medium | ✅ **Done** — DB-managed (AES-encrypted, masked on read) with env fallback. The leaked token was revoked; see `docs/SECURITY-REMEDIATION.md` |
| 6 | ~~Rename POM artifact from `messagebroker`~~ | 🟡 Medium | ✅ **Done** — `qosid-flow`, now a 3-module reactor |
| 7 | ~~Add unit/integration tests~~ | 🟡 Medium | ✅ **Done** — 375 tests (settings-core 30 + app 289 + ops 56) |
| 8 | ~~Add log file rotation~~ | 🟢 Low | ✅ **Done** — `RollingFileAppender` with `maxFileSize`/`maxHistory`/`totalSizeCap` |
| 9 | ~~Remove legacy Biruni-specific names~~ | 🟢 Low | ✅ **Done** — `SourceClient` / `JsonBodyOAuth2Client`; the `"biruni"` provider-type string stays frozen on purpose (matched against an Oracle column) |

**What is actually left: 2 and 3 — both infrastructure, neither of them application logic.**
Recommended order: **3** (Kafka replication — the one that has already caused a real outage) → **2**
(Redis HA).

---

## 11. Working with Claude.AI — Project Setup Guide

This section explains how to set up and effectively use Claude.AI as a development assistant for the Qosid Flow project.

### 11.1 Creating a Claude Project

1. Go to [claude.ai](https://claude.ai)
2. Click **"Projects"** in the left sidebar → **"Create Project"**
3. Name it: `Qosid Flow` (or your preferred name)
4. Add a **Project Description** (optional):
   ```
   Qosid Flow — A message processing pipeline that replaces 
   the legacy Biruni Message Broker. Tech stack: Java 21, Spring Boot 3.4, 
   Kafka/RedPanda, Redis, Resilience4j, WebClient, Prometheus/Grafana.
   ```

### 11.2 Project Knowledge — Which Files to Upload

Upload these files as **Project Knowledge** so Claude always has context about the codebase. Go to **Project Settings → Knowledge** and add:

**Priority 1 — Core Architecture (upload first):**

| File | Why it matters |
|------|---------------|
| `src/main/resources/application.yml` | All configuration defaults, feature flags, structure |
| `src/main/resources/application-dev.yml` | Dev environment specifics |
| `src/main/resources/application-prod.yml` | Production environment variables |
| `docker-compose.yml` | Infrastructure topology, all services and ports |
| `pom.xml` | Dependencies, versions, build configuration |

**Priority 2 — Core Business Logic:**

| File | Why it matters |
|------|---------------|
| `app/src/.../kafka/consumer/oracle/OracleRequestConsumer.java` | E3, Oracle flow: idempotency, lock, threading, CB park branch |
| `app/src/.../kafka/consumer/api/ApiRequestConsumer.java` | E3, REST flow: the same logic, **deliberately never merged** (SAFETY-RULES 5) |
| `app/src/.../kafka/consumer/oracle/OracleResponseConsumer.java` | E5: source save + RocksDB |
| `app/src/.../kafka/consumer/api/ApiResponseConsumer.java` | E5: RocksDB + callback |
| `app/src/.../kafka/ack/OrderedAckTracker.java` | Contiguous-prefix acknowledgement — the guard against silent loss (SAFETY-RULES 39) |
| `app/src/.../kafka/retry/CbRequeueScheduler.java` | CB back-pressure park; sole owner of a parked record's completion (SAFETY-RULES 40) |
| `app/src/.../kafka/producer/RequestProducer.java` | E2: Kafka message production |
| `app/src/.../http/HttpRequestService.java` | E4: HTTP calls, Circuit Breaker, OAuth2, per-host permit |
| `app/src/.../http/HostConcurrencyLimiter.java` | Per-host concurrency bound (v2.3.0, Phase 2) |
| `app/src/.../oracle/RequestPuller.java` | E1: source polling scheduler |
| `app/src/.../state/RequestStateService.java` | Redis state management, locks, idempotency |

**Priority 3 — Configuration & Infrastructure:**

| File | Why it matters |
|------|---------------|
| `app/src/.../config/KafkaConfig.java` | Consumer/producer factories, error handling |
| `app/src/.../config/ThreadPoolConfig.java` | HTTP thread pools, `cbRequeueTimer` / `alertCoalesceTimer`, explicit `taskScheduler` |
| `app/src/.../concurrency/DynamicConcurrencyManager.java` | Auto-scaling logic |
| `app/src/.../concurrency/ConcurrencyMonitorService.java` | Lag monitoring |
| `app/src/.../oauth2/OAuth2TokenRedisCache.java` | Token caching, distributed lock |
| `app/src/.../metrics/GatewayMetrics.java` | All custom Prometheus metrics |
| `app/src/.../notification/TelegramNotificationService.java` | DLQ alerts: coalescing + Telegram 429 handling (SAFETY-RULES 41) |
| `app/src/.../health/SystemHealthIndicator.java` | Health check logic |

**Priority 4 — Supporting Files:**

| File | Why it matters |
|------|---------------|
| `infrastructure/prometheus/gateway-alerts.yml` | Alert rules |
| `infrastructure/alertmanager/alertmanager.yml` | Telegram notification config |
| `infrastructure/dashboard_grafana.json` | Dashboard panels and queries |
| `.env.example` | All environment variables documented |
| `Dockerfile` | Build and deployment config |

**Priority 5 — Documentation & Analysis (if space allows):**

| File | Why it matters |
|------|---------------|
| This README/documentation file | Full project context |
| `docs/1. Current architecture.md` | Legacy system architecture |
| `docs/analysis/` files | Migration analysis and decisions |
| `change-log/v0.1.x.md` | Feature history |

> **Tip:** Claude Projects have a knowledge size limit. If you hit the limit, prioritize Priority 1-3 files. The core `.java` files and `application.yml` are the most important — they allow Claude to give accurate, context-aware answers.

### 11.3 Recommended Project Instructions

Add these as **Custom Instructions** in your Claude Project settings. This ensures every conversation follows consistent working patterns:

```
📋 Project Working Rules

1. 🎯 Step-by-step approach
   - Give each recommendation as a single step
   - Move to the next step only after I confirm the current one is done
   - Never give multiple steps at once

2. 🔄 Working with alternatives
   - If multiple solutions exist, suggest only the first option
   - After I test it and report the result, move to the next option if needed
   - If unsuccessful, suggest the next alternative

3. 📚 Using source files
   - Always reference project source files for accuracy
   - Base all suggestions on the actual codebase, not assumptions

4. 🔍 At the start of each chat
   - Review all project source files for current state
   - Identify the latest configurations and code patterns
   - Find information relevant to my request

5. ✅ Confirmation process
   After each step, I will respond with one of:
   ✅ "Done" / "Ready" / "Working" → proceed to next step
   ❌ "Not working" / "Error" → analyze the issue and suggest alternative
   ❓ "Don't understand" → explain in more detail

6. 💰 Token optimization
   - Do not rewrite full files unless I explicitly ask
   - Only show the specific lines/sections that need to change
   - Use "find and replace" style: show OLD code → NEW code

7. 🌐 Language
   - Write code comments in English
   - Technical discussions can be in any language the user prefers
   - Documentation should be in English
```

### 11.4 Effective Prompting Patterns

**Pattern 1: Bug Investigation**
```
E4 stage'da HTTP timeout xatolik ko'paydi.
Grafana'da gateway_http_request_total{result="timeout"} oshyapti.
Logda: "E4: HTTP timeout for 220:452015"
Sababi nima bo'lishi mumkin?
```
*Why this works:* Specifies the exact stage, metric name, and log message. Claude can cross-reference with `HttpRequestService.java` and `RetryProperties.java`.

**Pattern 2: Feature Implementation**
```
OAuth2 provider qo'shish kerak. Provider nomi: "exchange-api".
Token URL: https://exchange.example.com/oauth/token
Grant type: client_credentials
Qaysi fayllarni o'zgartirish kerak?
```
*Why this works:* Clear requirements with specific values. Claude will reference `OAuth2TokenRedisCache.java`, `application.yml`, and the OAuth2 config pattern.

**Pattern 3: Configuration Change**
```
Consumer concurrency'ni 20 gacha oshirish kerak.
Hozir max-concurrency: 15.
Kafka partitions ham oshirish kerakmi?
```
*Why this works:* States current value and desired value. Claude will check `ConcurrencyProperties.java`, `KafkaConfig.java`, and warn about partition count requirements.

**Pattern 4: Performance Issue**
```
gateway_http_pool_queue Grafana'da doimiy 30+ ko'rsatyapti.
Thread pool yetishmayaptimi? Qanday optimize qilish mumkin?
```
*Why this works:* References specific Grafana metric. Claude will analyze `ThreadPoolConfig.java` and suggest pool size adjustments.

**Pattern 5: Architecture Decision**
```
Yangi tashqi API qo'shiladi. Bu API juda sekin (response 30-60 sek).
Hozirgi circuit breaker sozlamalari bilan muammo bo'ladimi?
Alohida sozlash kerakmi?
```
*Why this works:* Provides context about the constraint. Claude will reference per-endpoint timeout config and CB settings.

### 11.5 Common Workflows with Claude

**Workflow 1: Debugging a Failed Request**
```
You: "Request 220:452015 DLQ'ga tushdi. Sababi nima?"
Claude: → Checks DlqMessage format, ErrorSource enum, retry logic
        → Asks for DLQ message content or log excerpt
        → Traces through E1→E5 pipeline to identify failure point
```

**Workflow 2: Adding a New External API Integration**
```
You: "Yangi API qo'shish kerak: https://api.newservice.com"
Claude: → Step 1: OAuth2 provider config (if needed)
        → Step 2: Endpoint timeout config
        → Step 3: Test with TestController
        → Step 4: Verify Circuit Breaker creation in logs
```

**Workflow 3: Monitoring Setup**
```
You: "Yangi Grafana panel qo'shish kerak — OAuth2 token refresh rate"
Claude: → Step 1: Add metric to GatewayMetrics.java
        → Step 2: Instrument OAuth2TokenRedisCache.java
        → Step 3: Provide Grafana panel JSON
```

**Workflow 4: Infrastructure Scaling**
```
You: "Kafka partitions 10 dan 20 ga oshirish kerak"
Claude: → Step 1: Topic partition increase command
        → Step 2: Consumer concurrency config update
        → Step 3: Rebalancing considerations
        → Step 4: Monitoring verification
```

### 11.6 What Claude Can and Cannot Do with This Project

**Claude CAN:**
- Analyze any source file and explain how it works
- Suggest code changes with exact line references
- Debug issues based on log messages, metrics, or error descriptions
- Generate new classes/methods following existing code patterns
- Create Grafana panel JSON, Prometheus alert rules, Docker configs
- Review configuration changes for consistency and safety
- Explain architecture decisions and trade-offs
- Generate test cases based on existing test patterns

**Claude CANNOT:**
- Run the application or execute commands on your machine
- Access your running Grafana/Prometheus/Kafka instances
- See real-time logs or metrics (you must paste relevant excerpts)
- Access Git history (upload specific files or describe changes)
- Test whether a code change actually works (you must verify and report back)

### 11.7 Tips for Long-Running Development Sessions

1. **Start each chat with context:** "Hozir RequestConsumer'dagi `.block()` muammosini hal qilmoqchiman" — this helps Claude focus immediately.

2. **Paste error messages fully:** Stack traces, log lines, and metric values give Claude the most to work with. Don't summarize — paste the actual text.

3. **Confirm or deny each step:** The step-by-step workflow only works if you report results. "Ishladi" or "Bu xato chiqdi: [error]" keeps the conversation productive.

4. **Reference file names:** "HttpRequestService.java'dagi `sendRequest` metodini ko'r" is better than "HTTP yuborish kodini ko'r".

5. **Use Claude's memory:** Claude remembers context within a project. If you discussed a bug fix yesterday, you can say "Kechagi circuit breaker muammoga davom etamiz" and Claude will search past conversations.

6. **Upload updated files:** If you made significant changes to a file, upload the new version to Project Knowledge. Claude always reads from the uploaded files, not from memory of old versions.

---

## 12. Project Structure

```
qosid-flow/                                   # Maven reactor: qosid-parent
├── pom.xml                                   # Root aggregator (settings-core + app + ops)
├── VERSION.md                                # Version — changing it on master triggers the release
├── docker-compose.yml                        # RedPanda, Redis, Prometheus, Grafana, Loki, AlertManager
├── .env.example                              # Every env var, documented
│
├── settings-core/                            # Shared module used by BOTH JVMs (uz.greenwhite.qosid.settings)
│   └── src/main/                             #   SettingsRegistry, SettingsCipher (AES-GCM), repositories,
│                                             #   LoginRateLimiter, LoginHandoffRepository, settings-schema.sql
│
├── app/                                      # The gateway JVM (:8090, management :8091)
│   ├── src/main/java/uz/greenwhite/gateway/
│   │   ├── GatewayApplication.java
│   │   ├── admin/                            # Admin control-plane API (/qosid/admin/api/**)
│   │   ├── api/                              # REST layer: ProxyController, StatusController, CallbackController
│   │   ├── auth/                             # Pluggable AuthorizationProvider SPI
│   │   ├── callback/                         # Outbound callback dispatch (@Retryable + @Recover)
│   │   ├── concurrency/                      # Lag-driven consumer concurrency scaling
│   │   ├── config/                           # *Properties + *Config (Kafka, HTTP, thread pools, Redis, …)
│   │   ├── exception/                        # GlobalExceptionHandler + typed exceptions
│   │   ├── file/                             # Multipart parsing + S3-compatible upload
│   │   ├── health/                           # Custom health indicators
│   │   ├── http/
│   │   │   ├── HttpRequestService.java       # E4: WebClient + Circuit Breaker + OAuth2
│   │   │   └── HostConcurrencyLimiter.java   # Per-host in-flight bound (v2.3.0, Phase 2)
│   │   ├── kafka/
│   │   │   ├── ack/OrderedAckTracker.java    # Contiguous-prefix ack (SAFETY-RULES 39)
│   │   │   ├── retry/CbRequeueScheduler.java # CB park + delayed requeue (SAFETY-RULES 23, 40)
│   │   │   ├── consumer/
│   │   │   │   ├── oracle/                   # OracleRequestConsumer / ResponseConsumer / DlqConsumer
│   │   │   │   └── api/                      # ApiRequestConsumer / ResponseConsumer / DlqConsumer
│   │   │   │                                 #   NEVER merged with the oracle flow (SAFETY-RULES 5)
│   │   │   └── producer/RequestProducer.java # E2 + DLQ production
│   │   ├── logs/                             # Read-only Loki proxy (Errors module)
│   │   ├── metrics/GatewayMetrics.java       # Every custom Prometheus metric
│   │   ├── model/                            # kafka/ messages + enums/
│   │   ├── notification/                     # Telegram DLQ alerts (coalescing + 429, SAFETY-RULES 41)
│   │   ├── oauth2/                           # Token clients + Redis-backed cache
│   │   ├── oracle/                           # SourceClient (pull/save) + RequestPuller (E1)
│   │   ├── source/                           # Source-system SPI
│   │   ├── state/RequestStateService.java    # Redis state, locks, attempt/requeue counters
│   │   ├── stats/                            # Read-only Prometheus proxy (Statistics module)
│   │   ├── storage/                          # RocksDB response bodies (7-day retention)
│   │   ├── tenant/                           # Tenant boundary translation (C-faza)
│   │   └── validation/                       # Request validation
│   └── src/main/resources/
│       ├── application.yml                   # All configuration with env-var overrides
│       └── logback-spring.xml                # Rolling file appenders + DLQ JSON appender
│
├── ops/                                      # Ops console sidecar JVM (:8092, API-only)
│   ├── src/main/java/uz/greenwhite/ops/      # start/stop, status poll, SSE logs, stop-to-edit settings
│   └── qosid-ops.service                     # Its own systemd unit
│
├── frontend/                                 # React SPA (Vite). Built by app's `frontend` profile;
│                                             # nginx serves frontend/dist in production
├── deploy/nginx-qosid.conf                   # Reference single-origin nginx config
│
├── infrastructure/
│   ├── prometheus/{prometheus.yml,gateway-alerts.yml}
│   ├── alertmanager/alertmanager.yml
│   ├── redis/redis.conf
│   └── *.json                                # Grafana dashboards
│
├── ai-context/                               # Documentation for AI tools (read these first)
├── docs/                                     # Manual test plan, security remediation, plans
└── .github/workflows/build.yml               # Tag + GitHub Release on a VERSION.md change
```