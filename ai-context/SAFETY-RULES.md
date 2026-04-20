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

### 16. Never use Java `"\u0000"` (NULL char) for placeholder matching

`ServletFileUploadEntity.replacePlaceholder()` must use `"\\u0000"` (literal 6-char string) to match JSON-encoded null placeholders. Java `"\u0000"` is a single NULL byte — it will never match the literal `\u0000` in JSON. This caused silent data loss (SHA not replaced → backend 500).

### 17. Never widen `RocksDBProperties.responseRetentionDays` without also reviewing the cleanup cron

`ResponseStorageService.cleanupExpiredResponses` evicts entries older than `retentionDays * 86_400_000 ms`. Increasing retention without a disk-capacity review will fill the volume silently — RocksDB writes will eventually start failing and the `/result` endpoint will return 404s intermittently.

---

## Protected Zones

These files contain production-critical logic. Before modifying any of them, run through the Required Checklist below.

| File | Critical Logic | Risk if Broken |
|------|---------------|----------------|
| `OracleRequestConsumer.java`, `ApiRequestConsumer.java` | Idempotency check + distributed lock + manual ack + async HTTP delegation | Duplicate processing, message loss, thread pool exhaustion |
| `OracleResponseConsumer.java` | Retry logic + Oracle save + manual ack + RocksDB | Response data loss, Oracle inconsistency |
| `ApiResponseConsumer.java` | RocksDB + callback trigger + manual ack | Response data loss, lost callbacks |
| `HttpRequestService.java` | Circuit Breaker + OAuth2 token injection + per-endpoint timeouts + callbackUrl/source propagation | Cascading failures, auth failures, timeout mismatches, lost callbacks |
| `RequestStateService.java` | Redis state CRUD + distributed lock (tryLock/releaseLock) + Integer.MAX_VALUE safety | Duplicate processing, infinite retries, state corruption |
| `KafkaConfig.java` | Topic creation + consumer factories + MANUAL ack mode + error handlers | Message loss, consumer misconfiguration, missing topics |
| `OAuth2TokenRedisCache.java` | Distributed token lock + cache with TTL + fallback on Redis failure | Token race conditions, auth failures, Redis lock leaks |
| `CallbackSender.java` | `@Retryable` (3 attempts, 1s backoff) + `@Recover` swallow | Lost callbacks, consumer stalls on bad URL |
| `RocksDBConfig.java` | Singleton DB instance + `@PreDestroy` close | On-disk corruption across restarts |
| `ResponseStorageService.java` | Scheduled retention cleanup + body/metadata dual-write | Disk exhaustion, orphaned metadata |
| `ProxyController.java` | Auth gate + URL prefix strip + BiruniUpload parsing + S3 retry/fallback + hop-by-hop header filter + `files` header + `source="API"` tagging | Bypass of auth, cross-source mix-up, unvalidated uploads, S3 fallback failure |
| `FileService.java` | S3 upload with 3x retry + content-type validation + fallback flag | Unvalidated uploads, missing retry on S3 failure |
| `ContentTypeValidator.java` | MIME whitelist enforcement | Open file store, security exposure |

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
