# Architecture

## How It Works (To'liq flow)

```
Main Server (HAProxy)
     |
     |  /qosid/api/v1/queue/** prefixli request keldi
     |
     v
ProxyController (qosid-flow)
     |  Auth tekshirish
     |  BiruniUpload bo'lsa → fayllarni S3 ga yuklash
     |  RequestMessage yaratish (source="API")
     |
     v
Kafka (gateway.request.new topic)
     |  Navbatga qo'yildi
     |
     v
RequestConsumer
     |  Navbatdan o'qish
     |  Idempotency check (Redis)
     |  Distributed lock (Redis)
     |
     v
HttpRequestService
     |  Circuit Breaker tekshirish
     |  OAuth2 token olish (kerak bo'lsa)
     |  Main app ga HTTP request yuborish
     |
     v
Main App (tashqi API)
     |  Requestni qayta ishlash
     |  Javob qaytarish
     |
     v
ResponseConsumer
     |  Javobni RocksDB ga saqlash (7 kun retention)
     |  Callback yuborish (agar X-Callback-Url berilgan bo'lsa)
     |  Redis state yangilash → COMPLETED
     |
     v
Client javobni oladi:
     GET /qosid/api/v1/requests/{id}/status  → inline result yoki resultUrl
     GET /qosid/api/v1/requests/{id}/result  → to'liq response body (RocksDB dan)
```

## Pipeline Diagram (texnik)

```
                                   KAFKA
Oracle/Biruni                    (RedPanda)                  External APIs
     |                              |                             |
     |   E1: Pull     E2: Produce  |   E3: Consume   E4: HTTP   |   E5: Save
     |   (5s poll)    (async)       |   (+ Redis)     (+ CB)     |   (+ retry)
     +-------->  RequestPuller -----+----> gateway.request.new   |
              source="ORACLE"       |         |                   |
                                    |    Oracle/ApiRequestConsumer ->+
 HTTP clients                       |         |                   |
     |                              |    (idempotency check)      |
     +-->  /qosid/api/v1/queue/**   |    (distributed lock)       |
          ProxyController ----------+    HttpRequestService ----->+  (Circuit Breaker)
              source="API"          |         |                   |  (OAuth2 if needed)
              (auth + multipart)    |         v                   |
                                    |    gateway.request.response |
                                    |         |                   |
                                    |    Oracle/ApiResponseConsumer +
                                    |         |                   |
                                    |    +--> RocksDB (always if body)
                                    |    +--> Callback (if callbackUrl)
                                    |    +--> Oracle/Biruni (only if source="ORACLE")
                                    |                             |
                             (on failure, after retries)          |
                                    |                             |
                              gateway.request.dlq                 |
                                    |                             |
                              Oracle/ApiDlqConsumer --> Telegram      |

 HTTP clients (status/result lookup)
     |
     +-->  /qosid/api/v1/requests/{id}/status  StatusController
     +-->  /qosid/api/v1/requests/{id}/result  StatusController
     +-->  /qosid/api/v1/callback              CallbackController
```

The pipeline has **two entry points** that both feed `gateway.request.new`:

- **E1 Oracle pull** — original polling loop, tags `RequestMessage.source="ORACLE"`.
- **E0 REST proxy** — `ProxyController` at `/qosid/api/v1/queue/**`, tags `source="API"`. Runs `AuthorizationProvider.authorize(...)` first. For `BiruniUpload` requests: parses multipart, uploads files to S3 (3x retry via `FileService.tryUploadFiles()`), replaces `"\u0000<index>"` placeholders with SHAs. **S3 success:** sends JSON POST with `files` metadata header (Biruni `run()` path, `store_kind='S'`). **S3 failure:** falls back to multipart forwarding with `BiruniUpload` header (Biruni `runFileUploadParam()` path, files saved to DB). Hop-by-hop headers (`host`, `connection`, etc.) are filtered via `HOP_BY_HOP_HEADERS`. Enqueues via `RequestProducer.sendApiRequest()`.

**Response routing by consumer:** `OracleResponseConsumer` saves to Biruni + RocksDB. `ApiResponseConsumer` saves to RocksDB + fires callback. No source-based `if/else` — routing is done at the topic level.

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
| **Input** | `RequestMessage` from E1 or E0 |
| **Output** | Oracle → `gateway.oracle.request`, API → `gateway.api.request` |
| **Key** | `compositeId` (companyId:requestId) for partition affinity |
| **Methods** | `sendOracleRequest()`, `sendApiRequest()`, `sendOracleResponse()`, `sendApiResponse()`, `sendToOracleDlq()`, `sendToApiDlq()` |
| **On Failure** | After retries exhausted, sends to source-specific DLQ |

### E3: Consumer Process — `OracleRequestConsumer.java` / `ApiRequestConsumer.java`

Oracle and API flows use **independent** consumers, topics, and thread pools:

| Aspect | Oracle | API |
|--------|--------|-----|
| **Consumer** | `OracleRequestConsumer` | `ApiRequestConsumer` |
| **Topic** | `gateway.oracle.request` | `gateway.api.request` |
| **Factory** | `oracleRequestConsumerFactory` | `apiRequestConsumerFactory` |
| **Thread Pool** | `oracleHttpExecutor` | `apiHttpExecutor` |
| **Ack** | MANUAL | MANUAL |
| **Idempotency** | Redis check + lock | Redis check + lock |
| **On Failure** | Retry → `sendOracleRequest()` or DLQ | Retry → `sendApiRequest()` or DLQ |

### E4: HTTP Request — `HttpRequestService.java`

| Aspect | Detail |
|--------|--------|
| **Input** | `RequestMessage` from E3 |
| **Output** | Oracle → `gateway.oracle.response`, API → `gateway.api.response` |
| **Circuit Breaker** | Per-domain Resilience4j CB. If OPEN, returns 503 immediately. |
| **OAuth2** | If `oauth2Provider` set, fetches token via `OAuth2ProviderService` and adds `Authorization` header |
| **Timeouts** | Default: connect 10s, read 30s. Per-endpoint overrides via `gateway.http.endpoint-timeouts` |
| **On Success** | Records CB success, sends ResponseMessage to response topic |
| **On Failure** | Records CB failure, checks if retryable (408, 429, 5xx) |

### E5: Response Processing — `OracleResponseConsumer.java` / `ApiResponseConsumer.java`

| Aspect | Oracle | API |
|--------|--------|-----|
| **Consumer** | `OracleResponseConsumer` | `ApiResponseConsumer` |
| **Topic** | `gateway.oracle.response` | `gateway.api.response` |
| **Factory** | `oracleResponseConsumerFactory` | `apiResponseConsumerFactory` |
| **RocksDB** | Saqlaydi (body mavjud bo'lsa) | Saqlaydi (body mavjud bo'lsa) |
| **Callback** | Yo'q | `CallbackService.sendCallbackIfNeeded()` |
| **Oracle save** | `saveWithRetry()` → Biruni API | Yo'q |
| **Ack** | MANUAL | MANUAL |

## Component Map

| Package | Responsibility | Key Classes |
|---------|---------------|-------------|
| `oracle/` | E1 (Pull) — Biruni API communication | `RequestPuller`, `BiruniClient` |
| `kafka/producer/` | E2 — Kafka message production | `RequestProducer` |
| `kafka/consumer/oracle/` | E3 + E5 — Oracle request/response/DLQ | `OracleRequestConsumer`, `OracleResponseConsumer`, `OracleDlqConsumer` |
| `kafka/consumer/api/` | E3 + E5 — API request/response/DLQ | `ApiRequestConsumer`, `ApiResponseConsumer`, `ApiDlqConsumer` |
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
- **Core Size:** `minConcurrency` (default 10)
- **Max Size:** `maxConcurrency * threadPoolMultiplier` (default 30)
- **Queue:** 50 tasks
- **Rejection Policy:** `RejectWithLogging` — when queue full, throws `RejectedExecutionException`. Consumer catches it and re-queues the message to Kafka without incrementing attempt count. Consumer thread stays free.
- **Non-blocking:** Both request consumers use `.subscribe()` instead of `.block()` — threads are freed immediately after dispatching HTTP request.
- **Message Expiry:** Messages older than `gateway.retry.max-message-age-ms` (default 5 min) are failed immediately to prevent infinite re-queue loops.
- **CB-aware retry:** Circuit Breaker and OAuth2 errors are NOT retried — they cause immediate failure to prevent cascading loops.

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
