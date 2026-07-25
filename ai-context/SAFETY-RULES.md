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

**v1.15.0 fix — the net was dead until 2026-07-24.** `RequestMessage.createdAt` was never
set on any production ingress path (only in the dev-profile `TestController`), and
`isMessageExpired` returns `false` when `createdAt` is null — so the safety net described
above never fired once and the CB-blocked re-queue looped at Kafka round-trip speed
(root cause of the 2026-05-18 Redpanda 57 GB disk-full outage). `createdAt` is now stamped
at **both** ingress points — `ProxyController` (REST) and `TenantMappingService.toInternal`
(source pull) — **never remove either one**, or this rule's backstop (and rule 24's) goes
dead again. Second layer: `gateway.retry.cb-max-requeues` (default 50) counts the re-queues
in Redis via `RequestStateService.incrementCbRequeue` (`request:cb-requeue:<compositeId>`,
request-state TTL, `Integer.MAX_VALUE` fail-closed per rule 8) and past the cap the message
takes the normal `handlePermanentFailure` → DLQ path, incrementing
`gateway.http.circuitbreaker.requeue.exhausted`. `attemptCount` is STILL not incremented and
CIRCUIT_BREAKER is STILL never added to `isNonRetryableSource` — the cap only bounds a
genuinely stuck message, so the v1.0.8 alert-per-request storm cannot come back.

### 24. Never call `OAuth2ProviderService.evictToken` without also setting `RequestStateService.markTokenEvicted`

The 401-retry-with-fresh-token path in `OracleRequestConsumer.handleFailedResponse` and `ApiRequestConsumer.handleFailedResponse` evicts the cached OAuth2 token and re-queues the message **without incrementing `attemptCount`** — same back-pressure-style pattern as CB-blocked re-queue (Rule 23). The `markTokenEvicted` flag is the loop guard: on the second 401 for the same `compositeId`, `isTokenEvicted` returns true, the eviction branch is skipped, and the request falls through to the standard retry/permanent-failure path. Removing the flag write turns this into an infinite evict→re-mint→401→evict loop that hammers the OAuth endpoint forever (and the `processRequest` `max-message-age-ms` safety net is the only thing that would eventually stop it, ~5 min later, after the OAuth endpoint has been pounded). The flag's TTL matches request state TTL (default 72h) so it dies with the request lifecycle. `isTokenEvicted` returns `true` on Redis failure (fail-closed) — a Redis outage degrades to existing pre-v1.1.0 behavior (immediate DLQ on 401), never to a runaway loop.

### 25. Never ack a request message when the Redis idempotency gate itself fails

`OracleRequestConsumer`/`ApiRequestConsumer` wrap `isCompleted`+`tryLock` in a dedicated
try/catch that calls `ack.nack(Duration.ofSeconds(5))` and returns. Acking on a Redis
outage silently drops the message (pre-A-faza bug). Do not "simplify" this into the
generic catch below it — that one acks.

### 26. Never swallow RocksDB write failures

`RocksDBClient.putResponse`/`putResponseMetadata` throw `StorageWriteException` on failure
and `ResponseStorageService.saveResponse` propagates it. Response consumers catch it and
keep `hasResult=false`. Swallowing the exception re-creates phantom COMPLETED states whose
`/result` returns 404.

### 27. Never release a Redis lock without ownership check

Both `RequestStateService.releaseLock` and `OAuth2TokenRedisCache.releaseLock` use a
compare-and-delete Lua script against the owner token issued at acquire time. A blind
`DEL` deletes another instance's lock after TTL takeover → concurrent processing.

### 28. Never forward X-Callback-Url without CallbackUrlValidator

`ProxyController` validates the callback URL at accept time: http/https only, public
addresses only, optional `gateway.security.callback-allowed-hosts` allowlist. Removing the
check reopens SSRF (internal endpoints, cloud metadata) via a client-controlled header.
`CallbackSender` re-validates at send time (its `callbackUrlValidator.validate(url)` call
is the first line of `sendCallback`, before the retryable HTTP POST) to narrow the
DNS-rebinding window between accept and delivery — removing either check reopens SSRF via
that gap.

### 29. Never merge the admin SecurityFilterChain with the pipeline chain

`AdminSecurityConfig` defines TWO chains: `adminSecurityChain` (order 1, `/qosid/admin/api/**`, session-required, SPA-compatible CSRF on) and `apiSecurityChain` (order 2, everything else, permitAll, CSRF disabled). Collapsing them into one, removing the `securityMatcher`, or re-enabling CSRF on the api chain, locks or breaks `/qosid/api/**` — the pipeline stops accepting requests (a re-enabled CSRF filter alone breaks every `POST /qosid/api/v1/queue/**`). Actuator runs on the separate management port (8091) with its own context, so it is not governed by this main-context chain. `SecurityIsolationTest` guards the pipeline GET **and** POST openness; keep it green.

### 30. Never bypass TenantMappingService at the Biruni boundary

Source pull ingress (`SourceClient.pullRequests`) MUST route each element through
`TenantMappingService.toInternal`, and Biruni save + OAuth fetch egress MUST build
their tenant key via `toExternal`. The internal model uses `tenantId`/`extraTenantId`
with NO `@JsonProperty("company_id")` binding — deserializing a Biruni pull directly
into `RequestMessage` again would leave `tenantId` null (all requests → DLQ). The
default field names are `company_id`/`filial_id` so bmb is unaffected; hardcoding the
key instead of reading the setting breaks any deployment that reconfigures it.

### 31. Never cache the source OAuth2 token under the per-request namespace

`SourceTokenService` caches the source-channel token under `source:oauth2:token`,
deliberately isolated from the per-request forwarding cache (`oauth2:token:<provider>`,
`OAuth2TokenRedisCache`). Using the shared namespace would let a per-request provider
eviction wipe the source token (stalling all pulls) or a "source"-named provider
collide with it. Keep the two caches separate; keep the source cache TTL capped at
the token's own `expires_in` so an expired token is never served on the pull/save channel.

### 32. Never enable `setAsyncAcks(true)` in `KafkaConfig.buildFactory` while the request consumers `nack`

`asyncAcks` looks like the obvious fix for a real defect: both request consumers call `ack` from
the reactive/`CompletableFuture` path, so acks arrive **out of offset order**, and `AckMode.MANUAL`
commits only the highest acknowledged offset — a fast-finishing higher offset can mark a
still-running lower offset as "committed", and on restart that message is never redelivered
(silent loss: no DLQ record, no alert, no metric).

**But it cannot be enabled as things stand.** Spring Kafka's `ConsumerAcknowledgment.nack(Duration)`
opens with `Assert.state(..., "nack() is not supported with out-of-order commits")` — verified in
the bytecode of `spring-kafka-3.2.0`. Both request consumers `nack`: on a Redis-gate failure
(rule 25) and on lock contention (rule 33). Turning `asyncAcks` on therefore converts those two
graceful back-pressure paths into a thrown `IllegalStateException`, which escapes the listener into
an error handler that only logs — so the message is lost on exactly the paths that exist to prevent
loss. It makes the problem worse, not better.

**Order of work if you want to fix out-of-order commits:** first replace both `nack` sites with a
bounded re-queue (the `incrementCbRequeue` pattern from rule 23 is the model — note a Redis outage
must not degrade into "DLQ everything", which is why `nack` is still the better answer for the
rule-25 path today), and only then enable `asyncAcks`. `KafkaAsyncAcksTest` guards the current
state and explains why. Read with rule 4: `MANUAL` never changes either way.

### 33. Never ack a request message on Redis lock contention

When `tryLock` returns false, `OracleRequestConsumer`/`ApiRequestConsumer` must call
`ack.nack(Duration.ofSeconds(10))`, **not** `ack.acknowledge()`. "Another instance is working
on it" only holds in a healthy multi-instance steady state; after a restart or SIGKILL the dead
instance's locks stay orphaned until the lock TTL (300s, rule 2) reaps them, and acking would
silently discard every message redelivered inside that window. Rule 25 covers a Redis gate
*failure*; this rule covers lock *contention* — both end in `nack`, never in a silent drop.

### 34. Never remove the ORACLE-source gate from `StatusController`

`/qosid/api/v1/requests/{id}/{status,result}` is unauthenticated by design (the B4 decision:
the transparent proxy and its `request_id` polling cannot carry inbound auth). That is
acceptable only because REST-sourced ids are `UUID`-derived (~59 bits, unguessable). The
**ORACLE-sourced half is not**: `request_id` is a source-side sequential integer and
`tenantId` is a small integer, so `100:900123` and its neighbours are trivially enumerable.
On 2026-07-24 an unauthenticated `GET .../result` returned HTTP 200 with a 20,022-byte
upstream response body.

Both endpoints therefore refuse ORACLE-sourced requests, and `getResult` performs that check
**before touching RocksDB**. Two details that look incidental but are not:

- The refusal is **404, not 403**. A 403 confirms the id exists and hands an enumerator a
  working oracle. Do not "improve" the status code.
- A `null` source stays readable, so Redis rows written before `source` was persisted do not
  suddenly 404. Removing that fallback would break in-flight requests on the deploy that ships
  a change here.

The gate depends on `RequestStateService.createInitialState(compositeId, source)` actually
being called with `message.getSource()` from both request consumers. Drop that argument and the
gate silently stops gating — nothing fails, reads just become open again.

### 35. Never move the default-password gate onto `apiSecurityChain`

`DefaultPasswordGateFilter` blocks every admin route except an exact allowlist
(`login`, `login-token`, `logout`, `me`, `change-password`, `health`) while the stored admin
password still matches the seeded default. It is registered **only** on `adminSecurityChain`,
**after** Spring Security's `AuthorizationFilter`.

Both of those placements are load-bearing. On `apiSecurityChain` the filter would block
`/qosid/api/**` and stop the pipeline accepting requests (rule 29). Before the
`AuthorizationFilter` it would answer `403` to unauthenticated callers, telling an anonymous
prober that the deployment is still on the default password.

Match the allowlist by **exact path**, never by prefix — a prefix match on `settings` would
expose `settings/audit`.

The ops sidecar carries the same gate for the same reason: it authenticates against the same
shared H2 account, so leaving it ungated would keep the whole attack open (stop the gateway,
rewrite `gateway.api.base-url`, start it). Because settings are edited while the gateway is
**stopped**, the ops side must also keep its own `change-password` route reachable — otherwise
an operator on a fresh install can neither edit settings nor fix the password anywhere.

### 36. Never make the XSRF cookies `HttpOnly`, and never hard-code `Secure` on the console cookies

Both CSRF repositories are built with `CookieCsrfTokenRepository.withHttpOnlyFalse()` on purpose.
The SPA reads `XSRF-TOKEN` (`frontend/src/api/client.ts`) and `OPS-XSRF-TOKEN`
(`frontend/src/api/ops.ts`) from `document.cookie` and echoes the raw value in
`X-XSRF-TOKEN` / `X-OPS-XSRF-TOKEN` — the pattern `SpaCsrfTokenRequestHandler` exists to
support. "Hardening" either cookie to `HttpOnly` makes the SPA unable to read it, so every
state-changing admin/ops call answers `403`. `HttpOnly` on the two **session** cookies
(`JSESSIONID`, `OPS_SESSION`) is unconditional and correct — no JS reads those.

`Secure` is the mirror-image trap. Production currently has **no TLS and no nginx**: the
console is reached over plain HTTP through an SSH tunnel, and a browser silently *discards* a
`Secure` cookie on a plain-HTTP response — no error, login just never sticks. It therefore
stays behind `COOKIE_SECURE` (default `false`, wired to `server.servlet.session.cookie.secure`
in both modules plus `gateway.security.cookie-secure` / `ops.security.cookie-secure`). Do not
"fix" the default to `true`; flip the env var in **both** systemd units the day TLS lands.
Note that `false` does not disable HTTPS detection: Tomcat ORs it with `request.isSecure()`,
and the CSRF repositories only call `setSecure(true)` when the flag is on — so never replace
that conditional with an unconditional `setSecure(cookieSecure)`, which would strip `Secure`
from genuine HTTPS responses. `SameSite=Lax` (not `Strict`) is also deliberate: same-origin
XHR is unaffected by either, but `Strict` drops the cookie on a top-level navigation into the
console. Guards: `AdminCookieHardeningTest`, `AdminCookieSecureFlagTest`, `OpsCookieHardeningTest`.

### 37. Never expose the actuator `shutdown` endpoint, in any profile

The management port (8091) has no authentication, so `POST /actuator/shutdown` is an
unauthenticated remote kill switch — and a single mis-set `SPRING_PROFILES_ACTIVE=dev` would
carry a "dev-only" exposure straight into production. The `on-profile: dev` document in
`application.yml` that used to add it (`include: …,shutdown` + `endpoint.shutdown.enabled: true`)
was removed; nothing depended on it (the `ops-local-stop.cmd` its comment named is not in the
repo, the ops sidecar stops the gateway with `systemctl stop gateway-service`, and no test or
script calls the endpoint). Prod already stops cleanly via SIGTERM + `server.shutdown: graceful`;
local dev stops with Ctrl+C. `ActuatorShutdownDisabledTest` reads the shipped yaml and fails the
build if `shutdown` reappears in any profile document — do not weaken it to a
context-boot test, which cannot see a profile document that is not active.

### 38. Never lower `gateway.proxy.max-body-size` below what the pipeline already carries, and never extend it to the multipart branch

`ProxyController.readBodyBounded` bounds only the **non-multipart** body (previously an
unbounded `readAllBytes()` — a memory-exhaustion vector on an endpoint that is unauthenticated
by default). Two properties of the default are load-bearing:

- **20 MiB is not arbitrary** — it is the Kafka message ceiling already configured on every leg
  (`max.request.size`, `fetch.max.bytes`, `max.partition.fetch.bytes` = `20971520`). The body
  rides as a JSON string field of `RequestMessage`, so the record is never smaller than the
  body: anything larger already fails at the producer today. Matching the numbers is what makes
  the gate provably unable to reject a request that currently succeeds. Lowering it (e.g. to a
  "sensible" 1 MB) starts 413-ing live traffic; raising it without raising the three Kafka
  limits in lockstep just moves the failure back to the producer.
- **The `BiruniUpload` multipart branch must stay excluded** — real S3 uploads are bounded by
  `spring.servlet.multipart.max-file-size` (50MB) / `max-request-size` (52MB). Applying the
  20 MiB proxy limit there would break production file uploads.

Keep both checks in `readBodyBounded`: `Content-Length` alone is client-supplied (an
understated value would walk straight through) and a chunked request carries no length at all,
so the streaming counter is what actually enforces the cap. Guard:
`ProxyControllerBodyLimitTest`.

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
| `ProxyController.java` | Auth gate + URL prefix strip (`/qosid/api/v1/queue`) + BiruniUpload parsing + S3 retry/fallback + hop-by-hop + Accept-Encoding filter + `files` header + `source="API"` tagging + bounded raw-body read (`readBodyBounded`) | Bypass of auth, cross-source mix-up, unvalidated uploads, S3 fallback failure, Cloudflare gzip decode artifacts, unbounded body buffering (see rule 38) |
| `FileService.java` | S3 upload with 3x retry + content-type validation + fallback flag | Unvalidated uploads, missing retry on S3 failure |
| `ContentTypeValidator.java` | MIME whitelist enforcement | Open file store, security exposure |
| `HttpClientConfig.java` | Netty `HttpClient` setup — pool, timeouts, **no `.compress(true)`** (see Rule 17) | Re-enabling Netty compression reintroduces Cloudflare gzip decode artifacts |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice` mapping of auth / validation / upload / not-found / generic exceptions to uniform `ErrorResponse` | Unmapped routes leak 500, validation errors become opaque stack traces to clients |
| `AdminSecurityConfig.java` | Two isolated `SecurityFilterChain` beans (admin session-gated, pipeline permitAll) with explicit `@Order` + `securityMatcher`; CSRF cookie built `withHttpOnlyFalse()` + `SameSite=Lax` + conditional `setSecure` | Merging chains or dropping `securityMatcher` locks the pipeline/actuator behind admin auth (see Rule 29); HttpOnly-ing the XSRF cookie or hard-coding `Secure` breaks the SPA / login (see Rule 36) |
| `OpsSecurityConfig.java` | Renamed ops cookies (`OPS_SESSION` / `OPS-XSRF-TOKEN`) + same `withHttpOnlyFalse()` / `SameSite=Lax` / conditional `setSecure` contract + ops default-password gate | Cookie-name collision with the gateway on one origin corrupts both sessions; HttpOnly/`Secure` mistakes break ops login (see Rules 35, 36) |
| `TenantMappingService.java` | Biruni-boundary `toInternal`/`toExternal` field-name translation (dynamic, per-call from `SettingsService`) | Bypassing it at ingress leaves `tenantId` null (all requests → DLQ); bypassing at egress or hardcoding the field name breaks any deployment with reconfigured field names (see Rule 30) |
| `SourceTokenService.java` | Isolated `source:oauth2:token` cache key + TTL capped at token's `expires_in` | Sharing the per-request `oauth2:token:*` namespace lets a provider eviction stall all Biruni pulls, or serves an expired token on the source channel (see Rule 31) |
| `DynamicConcurrencyManager.java` | Lag-driven `setConcurrency`; container stop/start on scale-up is OFF by default (`gateway.concurrency.allow-restart-on-scale-up`) | A restart triggers a consumer-group rebalance and every in-flight ack landing after `stop()` is a no-op — those offsets stay uncommitted and get redelivered, i.e. message loss exactly when load is highest |
| `StatusController.java` | Public polling endpoints: ORACLE-source refusal (404) before any RocksDB read | Enumerable composite ids expose every source-originated response body of the retention window (see rule 34) |
| `DefaultPasswordGateFilter.java` (gateway) + the ops sidecar's equivalent | Blocks all but an exact allowlist while the admin password is the seeded default | On the wrong chain it kills the pipeline; misplaced it leaks password state to anonymous callers; a missing ops-side gate leaves the redirect attack fully open (see rule 35) |

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
| `docker-compose.yml` | Infrastructure only — no gateway runtime logic. **Not "local dev only":** production runs this same file for Redpanda/Redis/Prometheus/Grafana/Loki (only the JVMs are outside Docker), so every published port is bound to `127.0.0.1`. A bare `"6379:6379"` binds `0.0.0.0` **and bypasses the host firewall** (Docker writes its own nat/`DOCKER-USER` rules, so `ufw deny` does not apply) — that is how unauthenticated Redis/Redpanda/Grafana ended up network-reachable. Keep the `127.0.0.1:` prefix on every port; container-to-container traffic uses compose DNS names on the internal network and is unaffected. |
| `logback-spring.xml` | Logging config changes |
| `pom.xml` | Adding dependencies (but verify compatibility) |
