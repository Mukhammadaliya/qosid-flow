# Architecture

## Pipeline Diagram

```
                                   KAFKA
Oracle/Biruni                    (RedPanda)                  External APIs
     |                              |                             |
     |   E1: Pull     E2: Produce  |   E3: Consume   E4: HTTP   |   E5: Save
     |   (5s poll)    (async)       |   (+ Redis)     (+ CB)     |   (+ retry)
     +-------->  RequestPuller -----+----> gateway.request.new   |
              source="ORACLE"       |         |                   |
                                    |    RequestConsumer -------->+
 HTTP clients                       |         |                   |
     |                              |    (idempotency check)      |
     +-->  /qosid/queue/**          |    (distributed lock)       |
          ProxyController ----------+    HttpRequestService ----->+  (Circuit Breaker)
              source="API"          |         |                   |  (OAuth2 if needed)
              (auth + multipart)    |         v                   |
                                    |    gateway.request.response |
                                    |         |                   |
                                    |    ResponseConsumer --------+
                                    |         |                   |
                                    |    +--> RocksDB (always if body)
                                    |    +--> Callback (if callbackUrl)
                                    |    +--> Oracle/Biruni (only if source="ORACLE")
                                    |                             |
                             (on failure, after retries)          |
                                    |                             |
                              gateway.request.dlq                 |
                                    |                             |
                              DlqConsumer --> Telegram Alert      |

 HTTP clients (status/result lookup)
     |
     +-->  /qosid/api/v1/requests/{id}/status  StatusController
     +-->  /qosid/api/v1/requests/{id}/result  StatusController
     +-->  /qosid/api/v1/callback              CallbackController
```

The pipeline now has **two entry points** that both feed `gateway.request.new`:

- **E1 Oracle pull** — original polling loop, tags `RequestMessage.source="ORACLE"`.
- **E0 REST proxy** — `ProxyController` at `/qosid/queue/**`, tags `source="API"`. Runs `AuthorizationProvider.authorize(...)` first, optionally parses multipart via `BiruniUpload` header (uploading files to MinIO/R2 and rewriting `"\u0000<index>"` placeholders with SHAs), then enqueues via `RequestProducer.sendRequest`.

**ResponseConsumer source routing:** body is always written to RocksDB (when non-null) and callbacks are always fired (when `callbackUrl` present). Only `ORACLE`-source responses flow through the original Biruni save step — `API`-source responses stop at RocksDB + Redis + callback.

## Stage Breakdown

### E1: Oracle Pull — `RequestPuller.java`

| Aspect | Detail |
|--------|--------|
| **Trigger** | `@Scheduled(fixedDelayString = "${gateway.polling.interval-ms:5000}")` |
| **Condition** | `@ConditionalOnProperty(name = "gateway.polling.enabled", havingValue = "true")` |
| **Input** | HTTP GET to Biruni API (`/b/biruni/bmb/requests$pull`) |
| **Output** | List of `RequestMessage` objects |
| **Validation** | `RequestValidator.validate()` — checks companyId, requestId, baseUrl, method |
| **On Failure** | Logs error, increments `oraclePullError` metric |
| **Flow** | Pull -> Validate each -> Send valid ones to E2 -> DLQ invalid ones |

### E2: Kafka Produce — `RequestProducer.java`

| Aspect | Detail |
|--------|--------|
| **Input** | `RequestMessage` from E1 |
| **Output** | Message sent to `gateway.request.new` Kafka topic |
| **Key** | `compositeId` (companyId:requestId) for partition affinity |
| **Retry** | 3 attempts, 3s interval (in `RequestPuller.sendToKafkaWithRetry`) |
| **On Failure** | After retries exhausted, sends to DLQ |

### E3: Consumer Process — `RequestConsumer.java`

| Aspect | Detail |
|--------|--------|
| **Trigger** | `@KafkaListener(id = "requestConsumer", topics = "${gateway.kafka.topics.request-new}")` |
| **Container Factory** | `requestConsumerFactory` with MANUAL ack, dynamic concurrency |
| **Idempotency** | 1. Check `requestStateService.isCompleted(key)` — skip if done |
| | 2. Try `requestStateService.tryLock(key)` — skip if locked |
| **Processing** | Submit to `httpExecutor` thread pool -> call E4 |
| **Ack** | Manual acknowledgment after async task submitted |
| **On Failure** | Check attempt count -> retry or send to DLQ |

### E4: HTTP Request — `HttpRequestService.java`

| Aspect | Detail |
|--------|--------|
| **Input** | `RequestMessage` from E3 |
| **Output** | `ResponseMessage` sent to `gateway.request.response` topic |
| **Circuit Breaker** | Per-domain Resilience4j CB. If OPEN, returns 503 immediately. |
| **OAuth2** | If `oauth2Provider` set, fetches token via `OAuth2ProviderService` and adds `Authorization` header |
| **Timeouts** | Default: connect 10s, read 30s. Per-endpoint overrides via `gateway.http.endpoint-timeouts` |
| **On Success** | Records CB success, sends ResponseMessage to response topic |
| **On Failure** | Records CB failure, checks if retryable (408, 429, 5xx) |

### E5: Oracle Save — `ResponseConsumer.java`

| Aspect | Detail |
|--------|--------|
| **Trigger** | `@KafkaListener(id = "responseConsumer", topics = "${gateway.kafka.topics.request-response}")` |
| **Container Factory** | `responseConsumerFactory` with MANUAL ack |
| **Input** | `ResponseMessage` from Kafka |
| **Output** | HTTP POST to Biruni API (`/b/biruni/bmb/requests$save`) |
| **Retry** | `saveWithRetry()` — max 3 attempts, 3s interval |
| **On Failure** | After retries, saves error response to Oracle |
| **Ack** | Manual acknowledgment after save attempt |

## Component Map

| Package | Responsibility | Key Classes |
|---------|---------------|-------------|
| `oracle/` | E1 (Pull) + E5 (Save) — Biruni API communication | `RequestPuller`, `BiruniClient` |
| `kafka/producer/` | E2 — Kafka message production | `RequestProducer` |
| `kafka/consumer/` | E3 + E5 + DLQ — Kafka message consumption | `RequestConsumer`, `ResponseConsumer`, `DlqConsumer` |
| `http/` | E4 — HTTP client with CB + OAuth2 | `HttpRequestService` |
| `state/` | Redis state management + distributed locks | `RequestStateService` |
| `oauth2/` | OAuth2 token caching + distributed locking | `OAuth2ProviderService`, `OAuth2TokenRedisCache`, `OAuth2ProviderFetcher` |
| `oauth2/client/` | Pluggable OAuth2 client implementations | `OAuth2Client` (interface), `BiruniOAuth2Client` |
| `oauth2/model/` | OAuth2 data models | `Token`, `ProviderProperties`, `OAuth2TokenRequest` |
| `concurrency/` | Dynamic consumer thread scaling | `DynamicConcurrencyManager`, `ConcurrencyMonitorService`, `ConcurrencyMetrics` |
| `metrics/` | Centralized metrics (all metrics in one class) | `GatewayMetrics` |
| `model/` | Domain models | `Request`, `Response`, `RequestState`, `ResponseSaveRequest` |
| `model/kafka/` | Kafka message models | `RequestMessage`, `ResponseMessage`, `DlqMessage` |
| `model/enums/` | Enumerations | `RequestStatus`, `ErrorSource` |
| `config/` | All configuration and bean definitions | `KafkaConfig`, `RedisConfig`, `HttpClientConfig`, `ThreadPoolConfig`, `*Properties` |
| `notification/` | Alert notifications | `NotificationService` (interface), `TelegramNotificationService`, `NoOpNotificationService` |
| `validation/` | Validation logic | `RequestValidator` |
| `source/` | Data source interfaces | `RequestSourceClient`, `ResponseSinkClient` |
| `health/` | Custom health checks | `SystemHealthIndicator` |
| `controller/` | Dev-only test endpoints | `TestController` (profile: dev) |

## Data Flow

```
Oracle/Biruni Response (JSON)
    |
    v
RequestMessage (Kafka message model)
  - companyId, requestId, baseUrl, uri, params, method, headers, body, oauth2Provider
    |
    v  [E2: Sent to gateway.request.new topic, key = compositeId]
    |
    v  [E3: Consumed, idempotency checked, lock acquired]
    |
    v  [E4: HTTP request sent to baseUrl+uri+params]
    |
    v
ResponseMessage (Kafka message model)
  - companyId, requestId, httpStatus, contentType, body, errorMessage, errorSource
    |
    v  [Sent to gateway.request.response topic, key = compositeId]
    |
    v  [E5: Consumed, saved to Oracle]
    |
    v
ResponseSaveRequest (Oracle save payload)
  - company_id, request_id, response {status, content_type, body}, error_message
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

- **State Key:** `request:state:{companyId}:{requestId}` (TTL: 72 hours)
- **Lock Key:** `request:lock:{companyId}:{requestId}` (TTL: 300 seconds)
- **Dual Check:**
  1. `isCompleted(key)` — if status is COMPLETED or FAILED, skip (already processed)
  2. `tryLock(key)` — Redis `SET NX` with TTL. If fails, another instance is processing it
- **Safety Net:** If Redis unavailable, `getAttemptCount()` returns `Integer.MAX_VALUE` to prevent infinite retries
- **Managed by:** `RequestStateService.java`

### Dynamic Concurrency Scaling

- **Monitor:** `ConcurrencyMonitorService` checks Kafka consumer lag every 10s
- **Manager:** `DynamicConcurrencyManager` adjusts consumer thread count
- **Range:** min 3 (default) to max 15 threads
- **Scale Step:** +/- 2 threads per adjustment
- **Cooldown:** 30s minimum between scaling events
- **Threshold:** Scale up when lag > 50, scale down when lag < 10
- **Scope:** Request and Response consumers scaled independently

### Thread Pool & Backpressure

- **HTTP Executor:** Separate thread pool for HTTP requests (`httpRequestExecutor`)
- **Core Size:** `minConcurrency` (default 3)
- **Max Size:** `maxConcurrency * 2` (default 30)
- **Queue:** 50 tasks
- **Rejection Policy:** `CallerRunsPolicy` — when queue full, caller thread runs the task (natural backpressure)

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
