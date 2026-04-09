# Safety Rules

Critical rules that prevent AI tools from breaking production systems. Read this file before modifying any core pipeline code.

## NEVER DO — Absolute Rules

These actions will break production reliability. Never do them regardless of context.

### 1. Never remove or simplify idempotency logic in `RequestConsumer.java`

The dual-check pattern (`isCompleted` + `tryLock`) prevents duplicate request processing during Kafka rebalances and multi-instance deployments. Removing either check causes duplicate HTTP requests to external APIs.

### 2. Never reduce Redis lock TTL in `RequestStateService.java`

Lock TTL (default 300s) must exceed the maximum possible HTTP request duration. Reducing it causes concurrent processing of the same request by multiple instances.

### 3. Never remove metrics from `GatewayMetrics.java`

All metrics are used by Prometheus alert rules and Grafana dashboards. Removing any metric breaks monitoring and alerting, making production issues invisible.

### 4. Never change MANUAL ack to AUTO in `KafkaConfig.java`

Manual acknowledgment ensures messages are only acknowledged after successful processing. AUTO ack causes message loss when processing fails — the message is already acknowledged and won't be redelivered.

### 5. Never bypass Circuit Breaker in `HttpRequestService.java`

The Circuit Breaker protects external APIs from being overwhelmed during outages. Bypassing it causes cascading failures: the gateway floods a failing API, which makes recovery slower for both systems.

### 6. Never remove retry logic in `ResponseConsumer.java`

The `saveWithRetry()` method handles transient Oracle/Biruni failures. Without it, a single network glitch causes permanent data loss — the HTTP response is lost and never saved to Oracle.

### 7. Never alter DLQ flow in `DlqConsumer.java` / `RequestProducer.java`

The Dead Letter Queue is the last safety net for failed messages. Altering the flow (e.g., auto-replay, filtering) can cause infinite retry loops or silent message loss.

### 8. Never remove the `Integer.MAX_VALUE` safety net

In `RequestStateService`, `getAttemptCount()` and `incrementAttempt()` return `Integer.MAX_VALUE` when Redis is unavailable. This ensures retry logic always thinks max retries are exceeded, preventing infinite retry loops when Redis is down.

### 9. Never replace `CallerRunsPolicy` in `ThreadPoolConfig.java`

`CallerRunsPolicy` provides natural backpressure: when the HTTP thread pool queue is full, the caller thread (Kafka consumer) processes the request itself, naturally slowing down consumption. Replacing with `AbortPolicy` or `DiscardPolicy` causes request loss or exceptions.

---

## Protected Zones

These files contain production-critical logic. Before modifying any of them, run through the Required Checklist below.

| File | Critical Logic | Risk if Broken |
|------|---------------|----------------|
| `RequestConsumer.java` | Idempotency check + distributed lock + manual ack + async HTTP delegation | Duplicate processing, message loss, thread pool exhaustion |
| `ResponseConsumer.java` | Retry logic + Oracle save + manual ack + error response fallback | Response data loss, Oracle inconsistency |
| `HttpRequestService.java` | Circuit Breaker + OAuth2 token injection + per-endpoint timeouts | Cascading failures, auth failures, timeout mismatches |
| `RequestStateService.java` | Redis state CRUD + distributed lock (tryLock/releaseLock) + Integer.MAX_VALUE safety | Duplicate processing, infinite retries, state corruption |
| `KafkaConfig.java` | Topic creation + consumer factories + MANUAL ack mode + error handlers | Message loss, consumer misconfiguration, missing topics |
| `OAuth2TokenRedisCache.java` | Distributed token lock + cache with TTL + fallback on Redis failure | Token race conditions, auth failures, Redis lock leaks |

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
