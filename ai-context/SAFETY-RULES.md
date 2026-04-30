# Safety Rules

Critical rules that prevent AI tools from breaking production systems. Read this file before modifying any core pipeline code.

## NEVER DO — Absolute Rules

These actions will break production reliability. Never do them regardless of context.

### 1. Never remove or simplify idempotency logic in `OracleRequestConsumer.java` / `ApiRequestConsumer.java`

The dual-check pattern (`isCompleted` + `tryLock`) prevents duplicate request processing during Kafka rebalances and multi-instance deployments. Removing either check causes duplicate HTTP requests to external APIs.

### 2. Never reduce Redis lock TTL in `RequestStateService.java`

Lock TTL (default 300s) must exceed the maximum possible HTTP request duration. Reducing it causes concurrent processing of the same request by multiple instances.

### 3. Never remove metrics from `GatewayMetrics.java`

All metrics are used by Prometheus alert rules and Grafana dashboards. Removing any metric breaks monitoring and alerting, making production issues invisible.

### 4. Never change MANUAL ack to AUTO in `KafkaConfig.java`

Manual acknowledgment ensures messages are only acknowledged after successful processing. AUTO ack causes message loss when processing fails — the message is already acknowledged and won't be redelivered.

### 5. Never bypass Circuit Breaker in `HttpRequestService.java`

The Circuit Breaker protects external APIs from being overwhelmed during outages. Bypassing it causes cascading failures: the gateway floods a failing API, which makes recovery slower for both systems.

### 6. Never remove retry logic in `OracleResponseConsumer.java`

The `saveWithRetry()` method handles transient Oracle/Biruni failures. Without it, a single network glitch causes permanent data loss — the HTTP response is lost and never saved to Oracle.

### 7. Never alter DLQ flow in `OracleDlqConsumer.java` / `ApiDlqConsumer.java` / `RequestProducer.java`

The Dead Letter Queue is the last safety net for failed messages. Altering the flow (e.g., auto-replay, filtering) can cause infinite retry loops or silent message loss.

### 8. Never remove the `Integer.MAX_VALUE` safety net

In `RequestStateService`, `getAttemptCount()` and `incrementAttempt()` return `Integer.MAX_VALUE` when Redis is unavailable. This ensures retry logic always thinks max retries are exceeded, preventing infinite retry loops when Redis is down.

### 9. Never replace `RejectWithLogging` in `ThreadPoolConfig.java`

`RejectWithLogging` throws `RejectedExecutionException` when the HTTP thread pool is full. The consumer catches this and re-queues the message to Kafka. Never replace with `CallerRunsPolicy` (blocks consumer thread, caused 4.5M retry production incident) or `DiscardPolicy` (silently drops requests).

### 10. Never merge Oracle and API consumer logic into a single class

Oracle and API flows use separate topics, consumers, and thread pools by design. `OracleResponseConsumer` saves to Biruni; `ApiResponseConsumer` does NOT. Merging them re-introduces the coupling that caused cascading failures — one flow's outage would stall the other.

### 11. Never disable `@EnableRetry` or remove `@Recover` from `CallbackSender`

`@EnableRetry` on `GatewayApplication` is what makes `@Retryable` active — without it, `sendCallback` silently becomes a single-attempt call (no retries, no backoff). The `@Recover` method is what stops exhausted retries from propagating out of the Kafka consumer; remove it and a bad callback URL kills message acknowledgment, which then stalls the partition.

### 12. Never remove the `@PreDestroy` close on `RocksDBConfig`

`RocksDB.close()` must run on shutdown to flush memtables and release file locks. Without it, repeated restarts can corrupt the on-disk database (LOCK file left behind, WAL not flushed) — the next startup will fail with `IOError: lock` and manual recovery is required.

### 13. Never bypass `ContentTypeValidator` for uploaded files

`FileService.uploadFiles` calls `contentTypeValidator.validate(...)` per file. This is the sole guard against clients uploading arbitrary MIME types to S3. Removing the call turns the service into an open file store — security exposure, storage cost, and potential abuse.

### 14. Never remove S3 retry or fallback logic in `FileService` / `ProxyController`

`FileService.tryUploadFiles()` retries S3 upload 3 times with backoff. On failure, `ProxyController` falls back to multipart forwarding so Biruni saves files to DB (`biruni_filespace`). Removing retry causes immediate failure on transient S3 errors. Removing fallback causes permanent data loss when S3 is unavailable.

### 15. Never forward hop-by-hop headers to upstream

`ProxyController.extractHeaders()` filters `host`, `connection`, `transfer-encoding`, etc. via `HOP_BY_HOP_HEADERS`. Forwarding `host` causes Tomcat virtual host routing failure (500). Forwarding `content-length` after body transformation causes request corruption.

### 16. Never remove `accept-encoding` from the stripped-header set

`HOP_BY_HOP_HEADERS` in `ProxyController.java` intentionally includes `accept-encoding` beyond the RFC hop-by-hop set. Clients (especially Postman with default `gzip, deflate, br`) would otherwise cause Cloudflare on HTTP/1.1 to return partially-encoded bytes that reactor-netty can only half-decode, leaving `/result` with unreadable raw-deflate prefix (`0x0b 0x9c...`). qosid-flow's own Tomcat still compresses outbound `/result` responses based on the **client's** Accept-Encoding, so transparency for readers is preserved — but upstream calls must always request plain bodies.

### 17. Never re-enable reactor-netty `.compress(true)` or remove `decompressIfNeeded`

`HttpClientConfig` deliberately omits `.compress(true)` (was enabled briefly in v1.0.4, broke Cloudflare responses). Instead `HttpRequestService.decompressIfNeeded(body, headers)` handles gzip/deflate/x-gzip defensively on both the success and error paths, reading `Content-Encoding` from the response. Re-enabling the Netty-level toggle or dropping the helper reintroduces the garbled-body bug. Unknown encodings pass through and are logged at DEBUG.

### 18. Never drop the upstream error body in `HttpRequestService.buildErrorResponse`

`buildErrorResponse` extracts `getResponseBodyAsByteArray()` + `Content-Type` from `WebClientResponseException` and places them on `ResponseMessage`. Without this, `ApiResponseConsumer`'s `if (body != null)` branch is skipped, `/result` returns 404 on failed upstream calls, and debugging a 4xx/5xx from smartup24 requires grep-chasing logs. Keep the body capture and let the consumer persist it.

### 19. Never remove the `NoResourceFoundException` handler in `GlobalExceptionHandler`

Spring Boot 3.2+ throws `NoResourceFoundException` for unmapped routes. The handler in `GlobalExceptionHandler` returns 404 with a proper `ErrorResponse` JSON. Without it the generic `Exception` handler catches it and surfaces HTTP 500 with a confusing "No static resource" body — breaks client expectations for typo'd endpoints and triggers false-positive alerts.

### 20. Never use Java `"\u0000"` (NULL char) for placeholder matching

`ServletFileUploadEntity.replacePlaceholder()` must use `"\\u0000"` (literal 6-char string) to match the `\u0000<index>` placeholder emitted by `JSON.stringify`. Java `"\u0000"` is a single NULL byte — it will never match the literal `\u0000` in JSON. The method also matches the JSON-string-wrapped form `\"\\u0000<i>\"` (escaped quotes + doubled backslash) so clients can send either the plain JSON body or a JSON-encoded string without SHA substitution silently failing (→ backend 500 observed pre-v1.0.3).

### 21. Never widen `RocksDBProperties.responseRetentionDays` without also reviewing the cleanup cron

`ResponseStorageService.cleanupExpiredResponses` evicts entries older than `retentionDays * 86_400_000 ms`. Increasing retention without a disk-capacity review will fill the volume silently — RocksDB writes will eventually start failing and the `/result` endpoint will return 404s intermittently.

### 22. Never strip or duplicate the `/b` upstream prefix — it lives in `API_BASE_URL`

The API proxy composes upstream URLs as `baseUrl + <stripped client URI>`. By convention `gateway.api.base-url` (env `API_BASE_URL`) carries the full upstream path prefix — **including `/b`** — so client URIs DON'T. Example: client sends `/qosid/api/v1/queue/sb/sbr/moderator/product/measure_list:table`, qosid-flow strips `/qosid/api/v1/queue`, leaving `/sb/sbr/…`, and concatenates with `https://app.smartup24.com/b` → `https://app.smartup24.com/b/sb/sbr/…`. Putting `/b` in the client path **and** in `baseUrl` produces `/b/b/…` (404). Dropping it from `baseUrl` bypasses the upstream prefix entirely (404 from smartup24 for `/sb/…`). Prod `.env` MUST set `API_BASE_URL=https://app.smartup24.com/b`.

### 23. Never treat `errorSource=CIRCUIT_BREAKER` as a permanent failure in `*RequestConsumer.handleFailedResponse`

CB-blocked responses (HTTP 503 from `HttpRequestService.buildCircuitBreakerResponse`) are **transient back-pressure**, not external API failures. Both `OracleRequestConsumer` and `ApiRequestConsumer` must short-circuit on `"CIRCUIT_BREAKER".equals(errorSourceStr)` to **re-queue the message via `sendOracleRequest`/`sendApiRequest` without incrementing `attemptCount`**. The `processRequest` message-expiry check (`max-message-age-ms`, default 5 min) is the safety net that converts genuinely-stuck CB-blocked messages into DLQ alerts after the deadline. If you re-add CIRCUIT_BREAKER to the `isNonRetryableSource` predicate (or any equivalent), every CB-OPEN burst will produce one DLQ alert per blocked request — the v1.0.8 incident produced ~2200 Telegram alerts/day from this exact bug. OAuth2 stays non-retryable (real auth issue, immediate DLQ); only CIRCUIT_BREAKER changed.

---

## Protected Zones

These files contain production-critical logic. Before modifying any of them, run through the Required Checklist below.

| File | Critical Logic | Risk if Broken |
|------|---------------|----------------|
| `OracleRequestConsumer.java`, `ApiRequestConsumer.java` | Idempotency check + distributed lock + manual ack + async HTTP delegation | Duplicate processing, message loss, thread pool exhaustion |
| `OracleResponseConsumer.java` | Retry logic + Oracle save + manual ack + RocksDB | Response data loss, Oracle inconsistency |
| `ApiResponseConsumer.java` | RocksDB + callback trigger + manual ack | Response data loss, lost callbacks |
| `HttpRequestService.java` | Circuit Breaker + OAuth2 token injection + per-endpoint timeouts + callbackUrl/source propagation + defensive `decompressIfNeeded` + upstream error body capture | Cascading failures, auth failures, timeout mismatches, lost callbacks, garbled `/result` bodies, dropped 5xx error payloads |
| `RequestStateService.java` | Redis state CRUD + distributed lock (tryLock/releaseLock) + Integer.MAX_VALUE safety | Duplicate processing, infinite retries, state corruption |
| `KafkaConfig.java` | Topic creation + consumer factories + MANUAL ack mode + error handlers | Message loss, consumer misconfiguration, missing topics |
| `OAuth2TokenRedisCache.java` | Distributed token lock + stale-while-revalidate (async pre-refresh at 80% TTL via `oauth2RefreshExecutor`) + 15s wait budget + fallback on Redis failure | Token race conditions, thundering-herd 401 storms, auth failures, Redis lock leaks |
| `CallbackSender.java` | `@Retryable` (3 attempts, 1s backoff) + `@Recover` swallow | Lost callbacks, consumer stalls on bad URL |
| `RocksDBConfig.java` | Singleton DB instance + `@PreDestroy` close | On-disk corruption across restarts |
| `ResponseStorageService.java` | Scheduled retention cleanup + body/metadata dual-write | Disk exhaustion, orphaned metadata |
| `ProxyController.java` | Auth gate + URL prefix strip (`/qosid/api/v1/queue`) + BiruniUpload parsing + S3 retry/fallback + hop-by-hop + Accept-Encoding filter + `files` header + `source="API"` tagging | Bypass of auth, cross-source mix-up, unvalidated uploads, S3 fallback failure, Cloudflare gzip decode artifacts |
| `FileService.java` | S3 upload with 3x retry + content-type validation + fallback flag | Unvalidated uploads, missing retry on S3 failure |
| `ContentTypeValidator.java` | MIME whitelist enforcement | Open file store, security exposure |
| `HttpClientConfig.java` | Netty `HttpClient` setup — pool, timeouts, **no `.compress(true)`** (see Rule 17) | Re-enabling Netty compression reintroduces Cloudflare gzip decode artifacts |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice` mapping of auth / validation / upload / not-found / generic exceptions to uniform `ErrorResponse` | Unmapped routes leak 500, validation errors become opaque stack traces to clients |

## Required Checklist

Before modifying **any** Protected Zone file, verify all of these:

- [ ] **Idempotency preserved?** — Does the change maintain the dual-check pattern (isCompleted + tryLock)? Can the same message be safely reprocessed?
- [ ] **Retry logic correct?** — Are retry counts, intervals, and retryable status codes preserved? Is there still a DLQ fallback after max retries?
- [ ] **Metrics updated?** — If adding new code paths, are corresponding metrics recorded in `GatewayMetrics.java`? Are existing metrics still triggered?
- [ ] **DLQ fallback present?** — Do all failure paths eventually lead to either successful processing or DLQ? No silent drops?
- [ ] **Redis unavailable scenario?** — What happens if Redis is down? Does the code degrade gracefully? Is the Integer.MAX_VALUE safety net preserved?
- [ ] **Manual ack preserved?** — Is Kafka message acknowledgment still manual and happening at the right time (after processing, not before)?
- [ ] **Thread safety?** — If touching concurrent code, are shared resources properly synchronized? Are locks released in finally blocks?

## Safe Zones

These files can be modified without the above checklist:

| File/Location | Why Safe |
|--------------|----------|
| `TestController.java` | Dev-only (`@Profile("dev")`), never runs in production |
| `infrastructure/` | Monitoring configs (dashboards, alerts) — no runtime impact |
| `application.yml` | Adding new config properties is safe (existing ones have defaults) |
| New `*Properties.java` classes | Adding config classes doesn't affect existing logic |
| `ai-context/` and other docs | Documentation files |
| `docker-compose.yml` | Local dev infrastructure only |
| `logback-spring.xml` | Logging config changes |
| `pom.xml` | Adding dependencies (but verify compatibility) |
