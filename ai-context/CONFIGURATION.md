# Configuration Reference

Complete reference for all configuration parameters. Every property has a default value and can be overridden via environment variables.

## gateway.source.* — Oracle/Biruni Data Source

**Properties class:** `GatewayProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.source.base-url` | String | `http://localhost:8080/smartup24` | `SOURCE_BASE_URL` | Biruni API base URL. **LIVE-editable** via the Settings UI — `BiruniClient` resolves it per call through `SettingsService` (yaml/env value is the fallback). |
| `gateway.source.username` | String | `` (empty, mandatory env var) | `SOURCE_USERNAME` | Basic Auth username |
| `gateway.source.password` | String | `` (empty, mandatory env var) | `SOURCE_PASSWORD` | Basic Auth password |
| `gateway.source.request-pull-uri` | String | `/b/biruni/bmb/requests$pull` | `SOURCE_PULL_URI` | URI for pulling requests. **LIVE-editable** (resolved per call via `SettingsService`). |
| `gateway.source.response-save-uri` | String | `/b/biruni/bmb/requests$save` | `SOURCE_SAVE_URI` | URI for saving responses. **LIVE-editable** (resolved per call via `SettingsService`). |
| `gateway.source.oauth2-provider-uri` | String | `/b/biruni/bmb/requests$get_oauth_provider_data` | `SOURCE_OAUTH2_PROVIDER_URI` | URI for OAuth2 provider config |
| `gateway.source.connection-timeout` | int | `60` | `SOURCE_TIMEOUT` | Connection timeout in seconds |

## gateway.source.auth.* — Source Channel Outbound Auth (B3)

**No static `*Properties` class** — these 10 keys live only in the settings DB, read per-call via `SettingsService.get`/`getSecret` by `SourceAuthService` (`source/SourceAuthService.java`) and `SourceTokenService` (`source/SourceTokenService.java`). **UI-managed** exclusively via `GET/PUT /qosid/admin/api/source-auth` and `POST /qosid/admin/api/source-auth/test` (`SourceAuthController`) — there is no yaml/env equivalent for these keys except the basic-auth fallback noted below. Applied by `BiruniClient` on both `pullRequests()` (E1) and `saveResponse()` (E5) via `sourceAuthService.authHeaders()`.

| Property | Type | Default | Secret | Description |
|----------|------|---------|--------|-------------|
| `gateway.source.auth.type` | String | `basic` | no | Selects the auth scheme: `basic`, `apikey`, or `oauth2`. |
| `gateway.source.auth.basic.username` | String | `` (empty) | no | Basic Auth username for the source channel. Migrated once from `gateway.source.username` (`SOURCE_USERNAME`) on first boot. |
| `gateway.source.auth.basic.password` | String | `` (empty) | **yes** | Basic Auth password. Migrated once from `gateway.source.password` (`SOURCE_PASSWORD`) on first boot (skipped with a WARN if `SETTINGS_ENCRYPTION_KEY` is blank). |
| `gateway.source.auth.apikey.header` | String | `X-Api-Key` | no | Header name used to carry the API key. |
| `gateway.source.auth.apikey.value` | String | `` (empty) | **yes** | API key value. |
| `gateway.source.auth.oauth2.token-url` | String | `` (empty) | no | Token endpoint for the source-channel OAuth2 client (`client_credentials`, RFC 6749 form-urlencoded via `StandardOAuth2Client`). |
| `gateway.source.auth.oauth2.client-id` | String | `` (empty) | no | OAuth2 client id. |
| `gateway.source.auth.oauth2.client-secret` | String | `` (empty) | **yes** | OAuth2 client secret. |
| `gateway.source.auth.oauth2.scope` | String | `` (empty) | no | Optional OAuth2 scope. |
| `gateway.source.auth.oauth2.cache-ttl-seconds` | String | `` (empty = use token's own `expires_in` minus 15s margin) | no | Optional cap on the cached-token TTL; the effective TTL is always `min(configured, token's expires_in - 15s)` — never longer than the token's own lifetime. |

**Basic yaml fallback:** if `gateway.source.auth.basic.username` is blank (encryption disabled at first boot, or the settings row was never populated), `SourceAuthService.basicHeader()` falls back to `GatewayProperties.getUsername()`/`getPassword()` — i.e. the original `gateway.source.username`/`gateway.source.password` (`SOURCE_USERNAME`/`SOURCE_PASSWORD`) yaml/env values above. This is what keeps prod pull/save working unchanged for deployments that never touch the new admin UI (default `type=basic` + fallback).

**Isolated OAuth2 token cache:** when `type=oauth2`, `SourceTokenService` caches the minted token under the Redis key `source:oauth2:token` — a namespace deliberately separate from the per-request forwarding cache (`oauth2:token:<provider>`, `OAuth2TokenRedisCache`). See SAFETY-RULES.md rule 31.

## gateway.kafka.* — Kafka Settings

**Properties class:** `KafkaProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.kafka.bootstrap-servers` | String | `localhost:19092` | `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address |
| `gateway.kafka.group-id` | String | `gateway-service-group` | `KAFKA_GROUP_ID` | Consumer group ID |
| `gateway.kafka.topics.oracle-request` | String | `gateway.oracle.request` | `KAFKA_TOPIC_ORACLE_REQUEST` | Oracle request topic |
| `gateway.kafka.topics.oracle-response` | String | `gateway.oracle.response` | `KAFKA_TOPIC_ORACLE_RESPONSE` | Oracle response topic |
| `gateway.kafka.topics.oracle-dlq` | String | `gateway.oracle.dlq` | `KAFKA_TOPIC_ORACLE_DLQ` | Oracle Dead Letter Queue |
| `gateway.kafka.topics.api-request` | String | `gateway.api.request` | `KAFKA_TOPIC_API_REQUEST` | API proxy request topic |
| `gateway.kafka.topics.api-response` | String | `gateway.api.response` | `KAFKA_TOPIC_API_RESPONSE` | API proxy response topic |
| `gateway.kafka.topics.api-dlq` | String | `gateway.api.dlq` | `KAFKA_TOPIC_API_DLQ` | API proxy Dead Letter Queue |

## gateway.polling.* — Request Puller

**Properties class:** (inline in `RequestPuller.java` via `@Scheduled`/`@ConditionalOnProperty`)

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.polling.enabled` | boolean | `true` | `POLLING_ENABLED` | Enable/disable request pulling |
| `gateway.polling.interval-ms` | long | `5000` | `POLLING_INTERVAL_MS` | Poll interval in milliseconds |

## gateway.http.* — HTTP Client

**Properties class:** `HttpProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.http.connect-timeout-ms` | int | `10000` | `HTTP_CONNECT_TIMEOUT` | HTTP connect timeout (ms) |
| `gateway.http.read-timeout-ms` | int | `30000` | `HTTP_READ_TIMEOUT` | HTTP read timeout (ms) |
| `gateway.http.write-timeout-ms` | int | `30000` | `HTTP_WRITE_TIMEOUT` | HTTP write timeout (ms) |
| `gateway.http.endpoint-timeouts` | Map | `{}` | — | Per-domain timeout overrides (domain -> ms) |
| `gateway.http.max-connections` | int | `500` | `HTTP_MAX_CONNECTIONS` | Reactor Netty pool max concurrent connections |
| `gateway.http.max-idle-time-ms` | long | `60000` | `HTTP_MAX_IDLE_TIME` | Idle connection eviction threshold |
| `gateway.http.max-life-time-ms` | long | `300000` | `HTTP_MAX_LIFE_TIME` | Max total connection lifetime |
| `gateway.http.pending-acquire-timeout-ms` | long | `45000` | `HTTP_PENDING_ACQUIRE` | Max wait for a free connection from the pool |

**Per-endpoint timeout example:**
```yaml
gateway:
  http:
    endpoint-timeouts:
      "slow-api.example.com": 60000
      "fast-api.example.com": 5000
```

## gateway.retry.* — Retry Settings

**Properties class:** `RetryProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.retry.max-attempts` | int | `3` | `RETRY_MAX_ATTEMPTS` | Maximum retry attempts |
| `gateway.retry.interval-ms` | long | `3000` | `RETRY_INTERVAL_MS` | Interval between retries (ms) |
| `gateway.retry.retryable-statuses` | String | `408,429,500,502,503,504` | `RETRY_STATUSES` | HTTP statuses that trigger retry |
| `gateway.retry.max-message-age-ms` | long | `300000` | `RETRY_MAX_MESSAGE_AGE` | Max message age (ms) before expiry. Default 5 min. |

## gateway.concurrency.* — Dynamic Scaling

**Properties class:** `ConcurrencyProperties.java`

Oracle and API flows have **independent** concurrency and thread pool settings:

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.concurrency.oracle-min` | int | `5` | `ORACLE_CONCURRENCY_MIN` | Oracle consumer min threads |
| `gateway.concurrency.oracle-max` | int | `10` | `ORACLE_CONCURRENCY_MAX` | Oracle consumer max threads |
| `gateway.concurrency.api-min` | int | `5` | `API_CONCURRENCY_MIN` | API consumer min threads |
| `gateway.concurrency.api-max` | int | `10` | `API_CONCURRENCY_MAX` | API consumer max threads |
| `gateway.concurrency.monitor-interval-ms` | long | `10000` | `CONCURRENCY_MONITOR_INTERVAL` | Lag check interval (ms) |
| `gateway.concurrency.scale-up-threshold` | long | `50` | `CONCURRENCY_SCALE_UP` | Scale up when lag exceeds this |
| `gateway.concurrency.scale-down-threshold` | long | `10` | `CONCURRENCY_SCALE_DOWN` | Scale down when lag below this |
| `gateway.concurrency.scale-step` | int | `2` | — | Threads to add/remove per step |
| `gateway.concurrency.scale-cooldown-ms` | long | `30000` | — | Minimum time between scaling events |
| `gateway.concurrency.topic-partitions` | int | `10` | `KAFKA_PARTITIONS` | Kafka partitions per topic |
| `gateway.concurrency.thread-pool-multiplier` | int | `2` | `THREAD_POOL_MULTIPLIER` | HTTP thread pool max = `oracleMax/apiMax * multiplier` |
| `gateway.concurrency.thread-pool-queue-capacity` | int | `50` | `THREAD_POOL_QUEUE` | HTTP thread pool queue size before rejection |

**Thread pools:** `oracleHttpExecutor` (oracle-min/max) and `apiHttpExecutor` (api-min/max) — mustaqil.

## gateway.telegram.* — Telegram Notifications

**Properties class:** `TelegramProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.telegram.enabled` | boolean | `true` | `TELEGRAM_ENABLED` | Enable Telegram DLQ alerts |
| `gateway.telegram.bot-token` | String | `` (empty, mandatory env var) | `TELEGRAM_BOT_TOKEN` | Telegram Bot API token. A blank token disables the service with a WARN log (`Telegram notification DISABLED: bot token is blank`) instead of failing startup. |
| `gateway.telegram.chat-id` | Long | `0` | `TELEGRAM_CHAT_ID` | Target chat ID |
| `gateway.telegram.message-thread-id` | Integer | `null` | `TELEGRAM_MESSAGE_THREAD_ID` | Optional topic thread ID |
| `gateway.telegram.connect-timeout-ms` | int | `5000` | — | Telegram API connect timeout |
| `gateway.telegram.read-timeout-ms` | int | `5000` | — | Telegram API read timeout |

## gateway.redis.* — Redis Settings

**Properties class:** `RedisProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.redis.state-ttl-hours` | long | `72` | — | Request state TTL in hours |
| `gateway.redis.lock-ttl-seconds` | long | `300` | — | Distributed lock TTL in seconds |

**Spring Redis (standard):**

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `spring.data.redis.host` | String | `localhost` | `REDIS_HOST` | Redis server host |
| `spring.data.redis.port` | int | `6379` | `REDIS_PORT` | Redis server port |

## resilience4j.circuitbreaker.* — Circuit Breaker

**Configuration:** in `application.yml` under `resilience4j.circuitbreaker.configs.default`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `sliding-window-size` | int | `10` | Number of calls in sliding window |
| `failure-rate-threshold` | int | `50` | Failure percentage to trigger OPEN (%) |
| `wait-duration-in-open-state` | Duration | `30s` | Time in OPEN before HALF_OPEN |
| `permitted-number-of-calls-in-half-open-state` | int | `3` | Test calls in HALF_OPEN |
| `slow-call-duration-threshold` | Duration | `10s` | Threshold for slow call detection |
| `slow-call-rate-threshold` | int | `50` | Slow call percentage to trigger OPEN (%) |

## gateway.api.* — API Proxy Target

**Properties class:** `ApiProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.api.base-url` | String | `http://localhost:8080/smartup24/b` | `API_BASE_URL` | Upstream base URL, **including any path prefix** (e.g. `/b`). API proxy requests are forwarded to `{baseUrl}{stripped client URI}`. Client URIs therefore omit the `/b` prefix — e.g. client sends `/qosid/api/v1/queue/sb/sbr/…` and qosid-flow calls `https://app.smartup24.com/b/sb/sbr/…`. Prod: `https://app.smartup24.com/b` (env var must include `/b`). **LIVE-editable** — `ProxyController` resolves it per request via `SettingsService`. |

## gateway.proxy.* — REST Proxy Layout

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.proxy.prefix` | String | `/qosid/api/v1/queue` | — | URI prefix stripped when forwarding the proxied request. |
| `gateway.proxy.context-path` | String | `/qosid` | — | Application context path (informational). |
| `gateway.proxy.trusted-proxies` | List<String> | `127.0.0.1` | `TRUSTED_PROXIES` | Peers whose X-Forwarded-For/X-Real-IP are trusted. |

## gateway.response.* — Response Inlining

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.response.threshold-size` | int | `1024` | `RESPONSE_THRESHOLD_SIZE` | Max body length (bytes) inlined on `/status`; larger bodies surface as `resultUrl`. Same threshold drives callback inlining. |

## gateway.rocksdb.* — Response Body Storage

**Properties class:** `RocksDBProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.rocksdb.path` | String | `./data/rocksdb` | `ROCKSDB_PATH` | Filesystem path for the RocksDB database (auto-created). |
| `gateway.rocksdb.response-retention-days` | int | `7` | `ROCKSDB_RETENTION_DAYS` | Age after which a stored response is evicted by the cleanup job. |
| `gateway.rocksdb.cleanup-cron` | String | `0 0 0 * * *` | — | Spring cron expression for `ResponseStorageService.cleanupExpiredResponses`. |

## gateway.s3.* — File Upload Backend

**Properties class:** `FileProperties.java` · **Client:** `S3Client.java` (AWS S3 SDK, works with AWS S3 / Cloudflare R2 / any S3-compatible backend).

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.s3.enabled` | boolean | `false` | `S3_ENABLED` | Gate: client bean registered only when true. |
| `gateway.s3.endpoint` | String | — | `S3_ENDPOINT` | S3 endpoint URL (AWS region endpoint or R2 account URL). |
| `gateway.s3.access-key` | String | — | `S3_ACCESS_KEY` | |
| `gateway.s3.secret-key` | String | — | `S3_SECRET_KEY` | |
| `gateway.s3.temp-bucket` | String | — | `S3_TEMP_BUCKET` | Temp-upload bucket name. |
| `gateway.s3.permanent-bucket` | String | — | `S3_PERMANENT_BUCKET` | Permanent-upload bucket (default target when `FileData.bucket` is null). |
| `gateway.s3.link-expiration-hours` | int | `24` | `S3_LINK_EXPIRY` | Presigned GET URL lifetime (hours). |

## gateway.tenant.* — Tenant Field Mapping

**Read by:** `TenantMappingService` (`tenant/TenantMappingService.java`) — reads `field-name`/`extra-field-name` from `SettingsService` on every call (dynamic, no restart needed). Also read by `ProxyController` (`default-rest-tenant`, `trust-extra-tenant-header`).

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.tenant.field-name` | String | `company_id` | `TENANT_FIELD` | Wire JSON field name for the primary tenant identifier. Used both at the Biruni pull ingress (`TenantMappingService.toInternal` reads this field into `tenantId`) and at the Biruni save / OAuth2 provider-fetch egress (`toExternal` writes `tenantId` back out under this key). **Must be non-blank** — `PUT /qosid/admin/api/settings/gateway.tenant.field-name` with a blank/whitespace value returns `400` (`SettingsController` validates before `SettingsService.set`). Changing it takes effect on the **next** Biruni pull/save/fetch (dynamic, no restart). |
| `gateway.tenant.extra-field-name` | String | `filial_id` | `EXTRA_TENANT_FIELD` | Wire JSON field name for the secondary tenant identifier (e.g. branch/filial), read alongside the primary one at ingress. May be blank to disable extra-tenant extraction (`extraTenantId` stays `null`). Not used at egress — `toExternal` only ever emits the primary tenant field. |
| `gateway.tenant.default-rest-tenant` | String | `0` | `DEFAULT_REST_TENANT` | Fallback tenant id for REST-sourced (`/qosid/api/v1/queue/**`) requests, used **only** when the authenticated principal (`AuthResult.getTenantId()`) supplies none — e.g. the default `NoOpAuthorizationProvider`, which never resolves a tenant. Principal-first: a real `AuthorizationProvider` that does resolve a tenant always wins over this default. Must parse as a `Long` — `PUT .../gateway.tenant.default-rest-tenant` with a non-numeric value returns `400`. |
| `gateway.tenant.trust-extra-tenant-header` | boolean | `false` | — | Opt-in: when `true`, `ProxyController` honors the client-supplied `X-Extra-Tenant-Id` request header as `extraTenantId` for REST-sourced requests, but only as a fallback when the auth principal itself supplies none. **Tradeoff:** since this header is entirely client-controlled, enabling it lets any caller impersonate an arbitrary `extraTenantId` (branch/filial) — only turn it on for deployments where the REST auth layer doesn't already resolve a real `extraTenantId` and the impersonation risk is acceptable. Not present in `application.yml` and not migrated to the settings DB by `SettingsMigrationRunner` — set it explicitly via `PUT /qosid/admin/api/settings/gateway.tenant.trust-extra-tenant-header` if needed; `SettingsService.get(...)` falls back to the `false` code default until then. |

`field-name`, `extra-field-name`, and `default-rest-tenant` are migrated into the settings DB on first boot (see `gateway.settings.*` below) and are read dynamically by `TenantMappingService`/`ProxyController` — changing them via the admin API takes effect immediately, unlike most other B1-migrated keys which the E1-E5 pipeline still reads from yaml.

### `POST /qosid/admin/api/tenant-mapping/test` — Tenant Mapping Diagnostics

**Controller:** `TenantMappingController`. Session-authenticated (admin control-plane). Lets an operator paste a sample Biruni pull element and see what the *current* field-name configuration would extract, without a live pull.

Request body: any JSON object (the sample payload), e.g. `{"company_id":100,"filial_id":5}`.

Response `200`:
```json
{"tenantId":100,"extraTenantId":5,"tenantField":"company_id","extraField":"filial_id"}
```

`tenantId`/`extraTenantId` are `null` when the configured field is absent from the sample or not a number. Uses `TenantMappingService.extract()` — the same logic as the live Biruni-pull ingress path, so this endpoint reflects real behavior, not a separate implementation.

### `X-Extra-Tenant-Id` header (REST proxy)

Optional request header on `/qosid/api/v1/queue/**`. Only consulted when both (a) the auth principal did not resolve an `extraTenantId`, and (b) `gateway.tenant.trust-extra-tenant-header` is `true`. Otherwise ignored. See the tradeoff note above.

## gateway.loki.* — Errors Log Proxy (D2)

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.loki.url` | String | `http://localhost:3100` | `LOKI_URL` | Loki base URL for the Errors log-query proxy (`/qosid/admin/api/logs`); settings-overridable — `LogQueryService` resolves it per request via `SettingsService` with this yaml/env value as the fallback. |

## gateway.prometheus.* — Statistics Dashboard Proxy (D3)

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.prometheus.url` | String | `http://localhost:9090` | `PROMETHEUS_URL` | Prometheus base URL for the Statistics dashboard proxy (`/qosid/admin/api/stats`); settings-overridable — `StatsQueryService` resolves it per request via `SettingsService` with this yaml/env value as the fallback. |

**Note:** `management.metrics.distribution.percentiles-histogram` is enabled for `gateway.request.e2e.duration` and `gateway.http.request.duration` (`application.yml`) so the dashboard can compute p95 via `histogram_quantile`. This adds roughly ~70 `_bucket` series per timer to `/actuator/prometheus`.

## gateway.ai.* — AI Error Analysis (D5)

Settings-DB-only keys (no yaml/env counterpart) — managed via `GET/PUT /qosid/admin/api/ai/config` (session auth) and read at request time by `AiAnalysisService` through `SettingsService`.

| Property | Type | Default | Secret | Description |
|----------|------|---------|--------|-------------|
| `gateway.ai.base-url` | String | `` (empty = disabled) | no | Base URL of an OpenAI-compatible LLM endpoint (`<base-url>/chat/completions` is called). |
| `gateway.ai.api-key` | String | `` (empty) | **yes** (AES) | Bearer key for the LLM endpoint. Masked as `***` on GET; a `***` PUT value never overwrites the stored key. Requires `SETTINGS_ENCRYPTION_KEY`. |
| `gateway.ai.model` | String | `` (empty) | no | Model name sent in the chat-completion request (e.g. `gpt-4o-mini`). |

**Opt-in / default OFF:** the feature is enabled only when all three values are set — otherwise `/ai/status` reports `enabled=false` and `/ai/analyze` returns `{analysis: null, error: "AI not configured"}`. Secrets are redacted (`AiRedactor`) before any context leaves the gateway, and every failure degrades to a structured error — never a 500. The LLM call is bounded (connect 5s / read 30s, `AiClientConfig`).

## gateway.settings.* — Control-Plane (B1)

**Properties class:** `SettingsProperties.java` (bootstrap-only — these three values live in yaml/env, never in the settings DB itself, since the DB doesn't exist until they're read).

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.settings.db-path` | String | `./data/settings` | `SETTINGS_DB_PATH` | H2 JDBC path/URL fragment (`jdbc:h2:<db-path>;MODE=LEGACY`) for the control-plane database — admin accounts, runtime settings, audit log. File-mode by default; tests use `mem:<name>`. |
| `gateway.settings.encryption-key` | String | `` (blank) | `SETTINGS_ENCRYPTION_KEY` | AES-GCM master key (any length — SHA-256'd internally) for at-rest encryption of settings marked `secret=true`. **Blank disables secrets**: `SettingsCipher.isEnabled()` returns false, `SettingsService.set(..., secret=true, ...)` throws `IllegalStateException`, and `getSecret()` logs a WARN and returns the caller's default instead of decrypting. Non-secret settings are unaffected. |
| `gateway.settings.session-ttl-minutes` | long | `480` | `ADMIN_SESSION_TTL_MIN` | Intended admin HttpSession TTL (8h). Read into `SettingsProperties` but **not yet wired** to `HttpSession.setMaxInactiveInterval` or `server.servlet.session.timeout` — the servlet container's default session timeout currently governs actual expiry. Treat as reserved/forward-looking until wired. |

**Runtime settings migrated from yaml → DB on first boot** (see `SettingsMigrationRunner.MIGRATABLE_KEYS`): `gateway.api.base-url`, `gateway.retry.max-attempts`, `gateway.retry.interval-ms`, `gateway.retry.retryable-statuses`, `gateway.retry.max-message-age-ms`, `gateway.http.connect-timeout-ms`, `gateway.http.read-timeout-ms`, `gateway.response.threshold-size`, `gateway.polling.enabled`, `gateway.polling.interval-ms`, `gateway.tenant.field-name`, `gateway.tenant.extra-field-name`, `gateway.tenant.default-rest-tenant`. After migration, these are edited via `PUT /qosid/admin/api/settings/{key}` and stored in the settings DB. DB values now DO take effect: LIVE keys are read per-use via `SettingsService`, and REQUIRES_RESTART keys are injected into the Spring `Environment` on the next boot by `SettingsEnvironmentPostProcessor` (see Settings Editability below). Bootstrap-only values (Kafka, Redis, ports, `gateway.settings.db-path` itself) are never migrated and stay yaml/env-only.

## Settings Editability — Effect Classes (Registry)

`SettingsRegistry.java` classifies every UI-editable key by how a change takes effect; `GET /qosid/admin/api/settings/registry` serves this to the SPA (color-coded), and `PUT /qosid/admin/api/settings/{key}` enforces it (READ_ONLY rejected; REQUIRES_RESTART sets the restart-pending flag). Keys not in the registry default to `REQUIRES_RESTART`.

- **LIVE** (🟢 applied immediately — read per-use via `SettingsService`): `gateway.api.base-url`, `gateway.source.base-url`, `gateway.source.request-pull-uri`, `gateway.source.response-save-uri`, `gateway.loki.url`, `gateway.prometheus.url`, `gateway.tenant.field-name`, `gateway.tenant.extra-field-name`, `gateway.tenant.default-rest-tenant`.
- **REQUIRES_RESTART** (🟡 persisted now, applied on next boot via `SettingsEnvironmentPostProcessor` — the DB `settings-db` property source ranks above `application.yml`, below env vars): `gateway.response.threshold-size` (`@Value`-bound in `StatusController`/`CallbackService`), `gateway.retry.max-attempts`, `gateway.retry.interval-ms`, `gateway.retry.retryable-statuses`, `gateway.retry.max-message-age-ms`, `gateway.http.connect-timeout-ms`, `gateway.http.read-timeout-ms`, `gateway.polling.enabled`, `gateway.polling.interval-ms`. Saving one sets the `restartPending` flag (banner in the UI); `RestartPendingResetRunner` clears it on the next successful boot.
- **READ_ONLY** (⚪ bootstrap/infra — greyed in the UI, `PUT` rejected; controlled only by yaml/env, which always outrank the settings DB): `gateway.settings.db-path`, `server.port`, `management.server.port`, `spring.kafka.bootstrap-servers`, `spring.data.redis.host`, `spring.data.redis.port`, `spring.profiles.active`. (`gateway.settings.encryption-key` is intentionally NOT in the registry at all.)

## Admin Account & Reset

| Env Var | Purpose |
|---------|---------|
| `ADMIN_RESET` | `true` → resets the single admin account's password back to the default (`admin`) on next boot. Applied at most once per directive (tracked via a hashed `admin.reset-marker` settings key) — leaving the var set does not re-clobber a password the admin later changed. |
| `ADMIN_PASSWORD_RESET` | Set to a specific value → resets the admin password to that value on next boot (same one-shot-per-directive semantics as `ADMIN_RESET`, tracked by a SHA-256 signature, not the plaintext). |

Default credentials on first boot (empty `admin_account` table): username `admin`, password `admin` — logged as a WARN telling the operator to change it immediately. There is exactly one admin account (`AdminAccountRepository`/`admin_account` table is single-row by design in this release).

## gateway.security.* — Content-Type Allowlist & Callback SSRF Guard

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.security.content-types` | List\<String\> | JSON/text/image/audio/video MIME list (see `application.yml`) | — | Whitelist enforced by `ContentTypeValidator`. Requests / uploads with other types are rejected with 400. |
| `gateway.security.callback-allowed-hosts` | List<String> | `[]` (empty) | `CALLBACK_ALLOWED_HOSTS` | X-Callback-Url host allowlist; empty = any public host. Private/loopback always blocked. |

## Server & Management

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `server.port` | int | `8090` | `SERVER_PORT` | Application HTTP port |
| `management.server.port` | int | `8091` | `MANAGEMENT_PORT` | Actuator/metrics port |
| `spring.lifecycle.timeout-per-shutdown-phase` | Duration | `30s` | — | Graceful shutdown timeout |
| `server.compression.enabled` | boolean | `true` | — | GZIP compression for JSON/text responses. |
| `server.tomcat.accept-count` | int | `1000` | `TOMCAT_ACCEPT_COUNT` | Accept queue size. |
| `server.tomcat.max-connections` | int | `10000` | `TOMCAT_MAX_CONNECTIONS` | Max concurrent connections. |
| `server.tomcat.connection-timeout` | Duration | `20s` | `TOMCAT_CONNECTION_TIMEOUT` | Idle connection timeout. |
| `server.tomcat.threads.max` | int | `2000` | `TOMCAT_MAX_THREADS` | Max worker threads. |
| `spring.servlet.multipart.max-file-size` | String | `50MB` | — | Max individual file upload size. |
| `spring.servlet.multipart.max-request-size` | String | `52MB` | — | Max total multipart request size. |

## Mandatory Environment Variables for Production

These **must** be set in production (no safe defaults):

| Env Var | Purpose |
|---------|---------|
| `KAFKA_BOOTSTRAP_SERVERS` | Production Kafka broker(s) |
| `REDIS_HOST` | Production Redis host |
| `SOURCE_BASE_URL` | Production Biruni API URL |
| `SOURCE_USERNAME` | Production Biruni credentials |
| `SOURCE_PASSWORD` | Production Biruni credentials |
| `TELEGRAM_BOT_TOKEN` | Telegram bot for DLQ alerts |
| `TELEGRAM_CHAT_ID` | Telegram chat for DLQ alerts |
| `SETTINGS_ENCRYPTION_KEY` | AES-GCM master key for settings-DB secrets. Without it, any attempt to save a `secret=true` setting via the admin API throws `IllegalStateException` — plan to set this before storing OAuth2 client secrets etc. in the control-plane DB. |

Optional but recommended in production:

| Env Var | Purpose |
|---------|---------|
| `CALLBACK_ALLOWED_HOSTS` | Restrict X-Callback-Url to an explicit host allowlist; empty = any public host accepted (SSRF guard still blocks private/loopback/CGNAT ranges regardless). |
| `TRUSTED_PROXIES` | Peers whose X-Forwarded-For/X-Real-IP are trusted for client-IP extraction. Defaults to `127.0.0.1` — **must be set to the real nginx peer address in prod**, otherwise the trusted-proxy check silently never matches the actual reverse-proxy hop and forwarded-for headers are ignored. |
