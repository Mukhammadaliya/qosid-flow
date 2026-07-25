# Configuration Reference

Complete reference for all configuration parameters. Every property has a default value; most map to an environment variable via a `${ENV:default}` yaml placeholder. **Caveat (settings-DB precedence):** for EDITABLE registry keys that have a settings-DB row, the DB value overrides both the yaml default and its placeholder env var (see Settings Editability below) — the env-var column then only documents the fallback used before a DB row exists.

## gateway.source.* — Oracle/Biruni Data Source

**Properties class:** `GatewayProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.source.base-url` | String | `` (empty — configured via Settings UI) | `SOURCE_BASE_URL` | Source system API base URL (e.g. Biruni). **Request-lifecycle endpoint — configured ONLY via the Settings UI**, not yaml (yaml/env carries an empty transitional fallback). **LIVE-editable** — `SourceClient` resolves it per call through `SettingsService`. **Dormant pull:** if this OR `request-pull-uri` is blank, the E1 pull cycle skips silently (no HTTP, no pull-error metric) and warns once until configured. |
| `gateway.source.username` | String | `` (empty, mandatory env var) | `SOURCE_USERNAME` | Basic Auth username |
| `gateway.source.password` | String | `` (empty, mandatory env var) | `SOURCE_PASSWORD` | Basic Auth password |
| `gateway.source.request-pull-uri` | String | `` (empty — configured via Settings UI) | `SOURCE_PULL_URI` | URI for pulling requests. **Request-lifecycle endpoint — configured ONLY via the Settings UI** (empty transitional env fallback). **LIVE-editable** (resolved per call via `SettingsService`). Blank ⇒ E1 pull stays dormant (see `base-url`). |
| `gateway.source.response-save-uri` | String | `` (empty — configured via Settings UI) | `SOURCE_SAVE_URI` | URI for saving responses. **Request-lifecycle endpoint — configured ONLY via the Settings UI** (empty transitional env fallback). **LIVE-editable** (resolved per call via `SettingsService`). |
| `gateway.source.oauth2-provider-uri` | String | `` (empty — configured via Settings UI) | `SOURCE_OAUTH2_PROVIDER_URI` | URI for OAuth2 provider config. **Request-lifecycle endpoint — configured ONLY via the Settings UI** (empty transitional env fallback). **LIVE-editable** (registry key, effect-class LIVE) — `OAuth2ProviderFetcher` resolves it per call through `SettingsService`. |
| `gateway.source.connection-timeout` | int | `60` | `SOURCE_TIMEOUT` | Connection timeout in seconds |

## gateway.source.auth.* — Source Channel Outbound Auth (B3)

**No static `*Properties` class** — these 10 keys live only in the settings DB, read per-call via `SettingsService.get`/`getSecret` by `SourceAuthService` (`source/SourceAuthService.java`) and `SourceTokenService` (`source/SourceTokenService.java`). **UI-managed** exclusively via `GET/PUT /qosid/admin/api/source-auth` and `POST /qosid/admin/api/source-auth/test` (`SourceAuthController`) — there is no yaml/env equivalent for these keys except the basic-auth fallback noted below. Applied by `SourceClient` on both `pullRequests()` (E1) and `saveResponse()` (E5) via `sourceAuthService.authHeaders()`.

| Property | Type | Default | Secret | Description |
|----------|------|---------|--------|-------------|
| `gateway.source.auth.type` | String | `basic` | no | Selects the auth scheme: `basic`, `apikey`, or `oauth2`. |
| `gateway.source.auth.basic.username` | String | `` (empty) | no | Basic Auth username for the source channel. Migrated once from `gateway.source.username` (`SOURCE_USERNAME`) on first boot. |
| `gateway.source.auth.basic.password` | String | `` (empty) | **yes** | Basic Auth password. Migrated once from `gateway.source.password` (`SOURCE_PASSWORD`) on first boot (skipped with a WARN if `SETTINGS_ENCRYPTION_KEY` is blank). |
| `gateway.source.auth.apikey.header` | String | `X-Api-Key` | no | Header name used to carry the API key. |
| `gateway.source.auth.apikey.value` | String | `` (empty) | **yes** | API key value. |
| `gateway.source.auth.oauth2.token-url` | String | `` (empty) | no | Token endpoint for the source-channel OAuth2 client (`client_credentials`). Wire format depends on `client-type` below. |
| `gateway.source.auth.oauth2.client-type` | String | `standard` (values: `standard` \| `json-body` \| `biruni`) | no | Which client mints the source token: `standard` = RFC 6749 form-urlencoded (`StandardOAuth2Client`); `json-body` (or the legacy alias `biruni`) = JSON body (`JsonBodyOAuth2Client`, for Smartup/Biruni-style sources like smartup24). Absent/blank ⇒ `standard` (backwards compatible). |
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
| `gateway.retry.max-message-age-ms` | long | `300000` | `RETRY_MAX_MESSAGE_AGE` | Max message age (ms) before expiry. Default 5 min. Measured from `createdAt`, stamped at both ingress points (`ProxyController`, `TenantMappingService.toInternal`). |
| `gateway.retry.cb-max-requeues` | int | `50` | `RETRY_CB_MAX_REQUEUES` | Cap on CB-blocked re-queues per request (Redis counter `request:cb-requeue:<compositeId>`); past the cap the message goes to DLQ. `attemptCount` is still untouched — see SAFETY-RULES rule 23. |

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
| `gateway.concurrency.allow-restart-on-scale-up` | boolean | `false` | `CONCURRENCY_ALLOW_RESTART` | Allow `stop()`/`start()` of the listener container on scale-up. **Off by default** — a restart rebalances the consumer group and in-flight acks landing after `stop()` are lost; the new concurrency target still applies on the next natural rebalance/restart. See SAFETY-RULES → Protected Zones (`DynamicConcurrencyManager.java`). |

**Thread pools:** `oracleHttpExecutor` (oracle-min/max) and `apiHttpExecutor` (api-min/max) — mustaqil.

## gateway.telegram.* — Telegram Notifications

**Properties class:** `TelegramProperties.java` (env/yaml fallback + timeouts only).

The four **behavioral** keys below are now **DB-managed** (settings DB, like `gateway.source.auth.*`/`gateway.ai.*`): edited via the SPA's **Telegram** tab (`GET/PUT /qosid/admin/api/telegram-config`, `POST .../test`) or the ops stop-to-edit generic settings API while the gateway is stopped. They are NOT in the editable registry (not shown in the General settings list — see `TYPED_FORM_KEYS`) and are NOT boot-injected into the `Environment`. `TelegramNotificationService` resolves each one **per send**: a settings-DB row wins; when no DB row exists it falls back to the env var (`TelegramProperties`). The two timeout keys have no DB row — they stay env/yaml-only and are read once at bean construction.

| Property | Type | Default | Source | Description |
|----------|------|---------|--------|-------------|
| `gateway.telegram.enabled` | boolean | `true` | DB-managed, env fallback `TELEGRAM_ENABLED` | Enable Telegram DLQ alerts. Resolved per send. |
| `gateway.telegram.bot-token` | String | `` (empty) | DB-managed **secret** (AES), env fallback `TELEGRAM_BOT_TOKEN` | Telegram Bot API token. Masked as `***` on `GET /telegram-config`; a blank effective token disables the service (warn-log only, `sendDlqAlert` skips) instead of failing startup. Requires `SETTINGS_ENCRYPTION_KEY` to store in the DB. |
| `gateway.telegram.chat-id` | Long | `0` | DB-managed, env fallback `TELEGRAM_CHAT_ID` | Target chat ID. Resolved per send. |
| `gateway.telegram.message-thread-id` | Integer | `null` | DB-managed, env fallback `TELEGRAM_MESSAGE_THREAD_ID` | Optional topic thread ID. Resolved per send. |
| `gateway.telegram.connect-timeout-ms` | int | `5000` | env/yaml only | Telegram API connect timeout (bean-construction time). |
| `gateway.telegram.read-timeout-ms` | int | `5000` | env/yaml only | Telegram API read timeout (bean-construction time). |

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
| `gateway.api.base-url` | String | `` (empty — configured via Settings UI) | `API_BASE_URL` | Upstream base URL, **including any path prefix** (e.g. `/b`). **Request-lifecycle endpoint — configured ONLY via the Settings UI** (empty transitional env fallback). API proxy requests are forwarded to `{baseUrl}{stripped client URI}`. Client URIs therefore omit the `/b` prefix — e.g. client sends `/qosid/api/v1/queue/sb/sbr/…` and qosid-flow calls `https://app.smartup24.com/b/sb/sbr/…`. Prod: `https://app.smartup24.com/b` (must include `/b`). **LIVE-editable** — `ProxyController` resolves it per request via `SettingsService`. |

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
| `gateway.tenant.trust-extra-tenant-header` | boolean | `false` | — | Opt-in: when `true`, `ProxyController` honors the client-supplied `X-Extra-Tenant-Id` request header as `extraTenantId` for REST-sourced requests, but only as a fallback when the auth principal itself supplies none. **Tradeoff:** since this header is entirely client-controlled, enabling it lets any caller impersonate an arbitrary `extraTenantId` (branch/filial) — only turn it on for deployments where the REST auth layer doesn't already resolve a real `extraTenantId` and the impersonation risk is acceptable. Not present in `application.yml` and not migrated to the settings DB by `SettingsMigrationRunner` — set it via the Tenant mapping UI page or `PUT /qosid/admin/api/settings/gateway.tenant.trust-extra-tenant-header` (registry: Tenant group, LIVE) if needed; `SettingsService.get(...)` falls back to the `false` code default until then. |

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
| `gateway.settings.db-path` | String | `./data/settings` | `SETTINGS_DB_PATH` | H2 JDBC path/URL fragment (`jdbc:h2:<db-path>;MODE=LEGACY;AUTO_SERVER=TRUE` — `AUTO_SERVER` is omitted for `mem:` paths, which H2 rejects) for the control-plane database — admin accounts, runtime settings, audit log. File-mode by default; tests use `mem:<name>`. `AUTO_SERVER` (mixed mode) lets the `qosid-ops` sidecar co-open the same DB file (see `ops.*` below). |
| `gateway.settings.encryption-key` | String | `` (blank) | `SETTINGS_ENCRYPTION_KEY` | AES-GCM master key (any length — SHA-256'd internally) for at-rest encryption of settings marked `secret=true`. **Blank disables secrets**: `SettingsCipher.isEnabled()` returns false, `SettingsService.set(..., secret=true, ...)` throws `IllegalStateException`, and `getSecret()` logs a WARN and returns the caller's default instead of decrypting. Non-secret settings are unaffected. |
| `gateway.settings.session-ttl-minutes` | long | `480` | `ADMIN_SESSION_TTL_MIN` | Intended admin HttpSession TTL (8h). Read into `SettingsProperties` but **not yet wired** to `HttpSession.setMaxInactiveInterval` or `server.servlet.session.timeout` — the servlet container's default session timeout currently governs actual expiry. Treat as reserved/forward-looking until wired. |

**Runtime settings migrated from yaml → DB on first boot** (see `SettingsMigrationRunner.MIGRATABLE_KEYS`): `gateway.api.base-url`, `gateway.retry.max-attempts`, `gateway.retry.interval-ms`, `gateway.retry.retryable-statuses`, `gateway.retry.max-message-age-ms`, `gateway.http.connect-timeout-ms`, `gateway.http.read-timeout-ms`, `gateway.response.threshold-size`, `gateway.polling.enabled`, `gateway.polling.interval-ms`, `gateway.tenant.field-name`, `gateway.tenant.extra-field-name`, `gateway.tenant.default-rest-tenant`. After migration, these are edited via `PUT /ops/api/settings/{key}` **while the gateway is stopped** (D stop-to-edit — the gateway's own `PUT /qosid/admin/api/settings/{key}` always answers `409 stop-required`) and stored in the settings DB. DB values now DO take effect: LIVE keys are read per-use via `SettingsService`, and REQUIRES_RESTART keys are injected into the Spring `Environment` on the next boot by `SettingsEnvironmentPostProcessor` (see Settings Editability below). Bootstrap-only values (Kafka, Redis, ports, `gateway.settings.db-path` itself) are never migrated, are not editable, and stay yaml/env-only. **DEPLOY NOTE:** once a `gateway.*` key has a settings-DB row (this migration seeds the keys above on first boot), changing its systemd env var (`API_URL`, `RETRY_MAX_ATTEMPTS`, ...) no longer takes effect — the DB value outranks the yaml `${ENV:default}` placeholder, so the DB/UI is the source of truth for that key from then on.

## Settings Editability — Effect Classes (Registry)

`SettingsRegistry` (settings-core, `uz.greenwhite.qosid.settings`) classifies every UI-editable key by how a change takes effect; `GET /qosid/admin/api/settings/registry` serves this to the SPA, and `PUT /ops/api/settings/{key}` (the only write surface since D stop-to-edit) enforces it via the `SettingsRegistry.isEditable` allowlist: only registry keys with effect-class LIVE or REQUIRES_RESTART are writable — READ_ONLY **and unknown** keys are rejected with 400. The same allowlist gates `SettingsEnvironmentPostProcessor` injection, so a non-editable key in the DB is never injected into the `Environment`. Under stop-to-edit the LIVE/REQUIRES_RESTART distinction no longer changes UI behavior (edits happen only while the service is down, so everything applies on the next start); it still gates the allowlist and documents how a key is consumed.

- **LIVE** (🟢 applied immediately — read per-use via `SettingsService`): `gateway.api.base-url`, `gateway.source.base-url`, `gateway.source.request-pull-uri`, `gateway.source.response-save-uri`, `gateway.source.oauth2-provider-uri`, `gateway.loki.url`, `gateway.prometheus.url`, `gateway.tenant.field-name`, `gateway.tenant.extra-field-name`, `gateway.tenant.default-rest-tenant`, `gateway.tenant.trust-extra-tenant-header`.
- **REQUIRES_RESTART** (🟡 persisted now, applied on next boot via `SettingsEnvironmentPostProcessor` — the DB `settings-db` property source ranks above `application.yml`, so it also overrides the yaml `${ENV:default}` placeholder env vars): `gateway.response.threshold-size` (`@Value`-bound in `StatusController`/`CallbackService`), `gateway.retry.max-attempts`, `gateway.retry.interval-ms`, `gateway.retry.retryable-statuses`, `gateway.retry.max-message-age-ms`, `gateway.http.connect-timeout-ms`, `gateway.http.read-timeout-ms`, `gateway.polling.enabled`, `gateway.polling.interval-ms`. (The old `restartPending` flag + `RestartPendingResetRunner` were removed in D — stop-to-edit makes "applied on next start" the universal rule.)
- **READ_ONLY** (⚪ bootstrap/infra — greyed in the UI, `PUT` rejected, never injected from the settings DB (allowlist); controlled only by yaml/env): `gateway.settings.db-path`, `server.port`, `management.server.port`, `spring.kafka.bootstrap-servers`, `spring.data.redis.host`, `spring.data.redis.port`, `spring.profiles.active`. (`gateway.settings.encryption-key` is intentionally NOT in the registry at all; RocksDB path and other unlisted infra keys are equally non-editable because unknown keys are outside the allowlist.)

## gateway.admin.* — Forced Password Change

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.admin.force-password-change` | boolean | `true` | — | While the stored admin password still matches the seeded default, every `/qosid/admin/api/**` route except `login`, `login-token`, `logout`, `me`, `change-password`, `health` answers `403 {"error":"password-change-required"}` (`DefaultPasswordGateFilter`, registered on `adminSecurityChain` only and after `AuthorizationFilter` so anonymous callers still get `401`). No env var by design — it exists so the test classpath can switch it off; **never set it to `false` in production**. See SAFETY-RULES rule 35. |

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

## ops.* — Ops Console Sidecar (`qosid-ops`, separate JVM)

Config for the `ops/` module (`ops/src/main/resources/application.yml`) — **not** part of the gateway's config. Runs as its own process/systemd unit (`ops/qosid-ops.service`).

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `server.port` | int | `8092` | `OPS_PORT` | Ops console HTTP port. Bind behind nginx / firewall — the console can stop the gateway. |
| `ops.gateway.health-url` | String | `http://localhost:8090/qosid/admin/api/health` | `OPS_GATEWAY_HEALTH_URL` | URL polled by `GatewayStatusService` (2s connect/read timeouts); gateway is "up" iff it answers 2xx. |
| `ops.gateway.start-command` | String | `` (blank) | `OPS_GATEWAY_START_COMMAND` | Command run to start the gateway. Blank → `start` returns `ok=false, "command not configured"`. **MUST be a command that EXITS** (e.g. `systemctl start gateway-service`, or a script that launches the gateway in the background) — `GatewayControlService` reads the process output to EOF **before** returning, so a foreground `java -jar` would hold stdout open and hang the request indefinitely (the 30s `waitFor` cap only applies after output EOF). Trusted config value, whitespace-split — no shell, no user input. |
| `ops.gateway.stop-command` | String | `` (blank) | `OPS_GATEWAY_STOP_COMMAND` | Command run to stop the gateway (e.g. `systemctl stop gateway-service`). Same exits-only constraint as `start-command`. |
| `ops.admin.force-password-change` | boolean | `true` | — | The ops-side twin of `gateway.admin.force-password-change`. Ops authenticates against the same shared H2 account, so without this the whole attack stays open (log in on :8092, stop the gateway, rewrite `gateway.api.base-url`, start it). Allowlist: `login`, `logout`, `me`, `change-password`, `status` — `status` is allowlisted because the SPA polls it constantly and it is read-only. Blocked: `start`, `stop`, `settings`, `settings/{key}`, `settings/audit`, `logs/stream`, `handoff`. Ops has its own `POST /ops/api/change-password` because settings are edited while the gateway is **stopped** — without it a fresh install could not leave the default password anywhere. Unlike the gateway the result is **not** cached: the ops JVM outlives gateway restarts, so a gateway `ADMIN_RESET` could re-seed the default under a latched ops process and hold the gate open. Test-classpath override only; **never `false` in production**. |
| `ops.log-file` | String | `./logs/gateway.log` | `OPS_LOG_FILE` | Gateway log file tailed by `LogTailService` / streamed over SSE. Absent/unreadable file degrades to an empty stream, never an error. |
| `ops.settings-db-path` | String | `./data/settings` | `OPS_SETTINGS_DB_PATH` | H2 path of the **gateway's** settings DB — must equal the gateway's `gateway.settings.db-path` so ops auth verifies against the same `admin_account`. `OpsDbConfig` opens it as `jdbc:h2:<path>;MODE=LEGACY;AUTO_SERVER=TRUE` (`AUTO_SERVER` omitted for `mem:`). |
| `ops.settings-encryption-key` | String | `` (blank) | `SETTINGS_ENCRYPTION_KEY` | AES-GCM master key for the stop-to-edit settings API's secret writes (`PUT /ops/api/settings/{key}` on a secret key). **Must equal the gateway's `SETTINGS_ENCRYPTION_KEY`** — same DB, same cipher format (`SettingsCipherContractTest` freezes it). Blank → secret PUTs answer `400 {"error":"encryption-unavailable"}`; non-secret settings still editable. |
| `server.servlet.session.cookie.name` | String | `OPS_SESSION` | — | Ops session cookie name, set in `ops/src/main/resources/application.yml`. Renamed off Spring's `JSESSIONID` default so the ops session never collides with the gateway admin chain's own `JSESSIONID` once both sit behind one nginx origin — not overridable via env var, change it in the ops `application.yml` directly. |
| CSRF cookie/header (`OpsSecurityConfig`) | — | cookie `OPS-XSRF-TOKEN`, header `X-OPS-XSRF-TOKEN` | — | `CookieCsrfTokenRepository` cookie name / header name, hardcoded in `OpsSecurityConfig` (not externalized as config). Renamed off Spring's `XSRF-TOKEN`/`X-XSRF-TOKEN` defaults to avoid the same one-origin cookie collision as the session cookie above — the gateway keeps `XSRF-TOKEN`/`X-XSRF-TOKEN` unchanged. |

**AUTO_SERVER note:** both sides open the settings DB with `;AUTO_SERVER=TRUE` — gateway (`SettingsDbConfig` **and** `SettingsEnvironmentPostProcessor`, which opens the DB early at boot) and ops (`OpsDbConfig`). H2 mixed mode lets two JVMs share one DB file: the first opener becomes the server, the other connects over TCP automatically. If either side lacked the flag, the second JVM would fail to open the locked file (the gateway's PostProcessor would then silently fall back to yaml defaults). The gateway side is a HikariCP pool (`minimumIdle=1`, `maxLifetime=0`) so at least one physical connection stays open for the process lifetime — this pins the H2 server role to the gateway JVM and eliminates the server-role handoff races that made the SPA's parallel login POSTs transiently fail (gateway 500 / ops spurious 401). Ops keeps short-lived connections (it must not own the DB while the gateway restarts) and instead retries a failed auth read once (`AdminAuthDao`). All three call sites build the URL through `SettingsDbUrl.autoServerSuffix(path)` (settings-core), which also sets `h2.bindAddress=127.0.0.1`: the H2 server-role JVM writes its own address into `.lock.db` for the peer to dial, and on a **multi-NIC host** it otherwise picks a LAN IP (e.g. `192.168.x.x`) the loopback-only peer can't reach — the sidecar then times out (~12s) on every DB read and ops login silently fails (auth lookup returns empty → 401, and the slow reads blow past the rate-limiter's 60s window so lockout never triggers). Binding loopback fixes it and keeps the settings DB off external interfaces. An operator-set `h2.bindAddress` is never overridden.

**Deploy note:** the systemd unit runs as user `qosid`, which needs permission to run `systemctl start/stop gateway-service` — add a polkit rule or a sudoers `NOPASSWD` entry scoped to **exactly those two commands**. If using the sudoers route, the configured commands must be prefixed with `sudo` (e.g. `OPS_GATEWAY_START_COMMAND=sudo /usr/bin/systemctl start gateway-service`), since the unit invokes `systemctl` directly. The unit expects the JAR at `/opt/services/qosid-flow/ops/target/qosid-ops.jar` (the ops pom sets `finalName=qosid-ops`). Since the Maven reactor (D), a root `mvn clean package` builds all three modules — settings-core, app (`app/target/qosid-flow-2.0.0.jar`), and ops — and CI attaches both the app and ops JARs to the GitHub Release. On first deploy the gateway must boot once before ops login works (the gateway creates the `admin_account` table).

**Nginx deploy note (C-faza, v1.13.0):** ops is API-only and has no page of its own to serve — in production a single nginx origin serves the SPA statically from `frontend/dist` and reverse-proxies `/qosid/` → gateway (:8090) and `/ops/api/` → this module (:8092), with buffering/caching disabled and a long read timeout on the `/ops/api/` location so the SSE log stream (`/ops/api/logs/stream`) isn't buffered or cut off. Reference config: `deploy/nginx-qosid.conf`. Apply the same SSE-safe proxy settings to any other reverse proxy fronting ops.

**v1.12.0 → v1.13.0 cutover checklist:**
1. `git pull` + build JARs (`mvn clean package -DskipTests`) — the frontend profile also builds `frontend/dist`.
2. Install/merge `deploy/nginx-qosid.conf` locations into the existing nginx server block; ensure the nginx user has read access to `/opt/services/qosid-flow/frontend/dist`.
3. `nginx -t && systemctl reload nginx`.
4. Restart the `gateway-service` and `qosid-ops` systemd units.
5. **WARNING:** skipping the nginx step leaves the UI fully unavailable — the gateway JAR no longer embeds the SPA, and the ops sidecar no longer serves an HTML page of its own. The CI-released JAR alone is no longer a complete deployment artifact; nginx + `frontend/dist` are required alongside it.

Recommended before exposing `/ops/api` through nginx: change the default admin password first, and put an nginx rate-limit and/or IP-allowlist on `/ops/api/login`. Since v1.14.0 the ops JVM also enforces the same application-level login rate-limit as the gateway (`LoginRateLimiter`: 5 failures / 60s per username, existing accounts only → `429 {"error":"rate-limited"}`), so the nginx layer is defense-in-depth, not the only guard.

**v1.13.0 → v1.14.0 cutover checklist (Maven reactor + stop-to-edit):**
1. `git pull` + root build (`mvn clean package -DskipTests`) — the reactor now builds `settings-core/target/qosid-settings-core-*.jar`, `app/target/qosid-flow-2.0.0.jar`, and `ops/target/qosid-ops.jar` in one pass.
2. **`gateway-service` unit:** update `ExecStart` — the app JAR moved from `target/qosid-flow-2.0.0.jar` to `app/target/qosid-flow-2.0.0.jar`.
3. **`qosid-ops` unit:** update `ExecStart` to `ops/target/qosid-ops.jar` (see `ops/qosid-ops.service`) and add `SETTINGS_ENCRYPTION_KEY` (same value as the gateway unit's) to the ops environment — without it secret settings can't be edited via stop-to-edit.
4. `systemctl daemon-reload`, then restart both units; redeploy `frontend/dist` (nginx layout unchanged from v1.13).
5. Behavior change to communicate: settings are now edited ONLY while the gateway is stopped (Ops page → stop → edit → start); all gateway-side settings PUTs answer `409 stop-required`; after an ops-driven start the SPA re-authenticates automatically (login-token handoff).

## Mandatory Environment Variables for Production

These **must** be set in production (no safe defaults):

| Env Var | Purpose |
|---------|---------|
| `KAFKA_BOOTSTRAP_SERVERS` | Production Kafka broker(s) |
| `REDIS_HOST` | Production Redis host |
| `SOURCE_USERNAME` | Production Biruni credentials |
| `SOURCE_PASSWORD` | Production Biruni credentials |
| `TELEGRAM_BOT_TOKEN` | Telegram bot for DLQ alerts |
| `TELEGRAM_CHAT_ID` | Telegram chat for DLQ alerts |
| `SETTINGS_ENCRYPTION_KEY` | AES-GCM master key for settings-DB secrets. Without it, any attempt to save a `secret=true` setting via the admin API throws `IllegalStateException` — plan to set this before storing OAuth2 client secrets etc. in the control-plane DB. |

> **Request-lifecycle endpoints (deploy note):** `gateway.source.base-url`, `gateway.source.request-pull-uri`, `gateway.source.response-save-uri`, `gateway.source.oauth2-provider-uri`, and `gateway.api.base-url` no longer ship business default values in yaml — they are **configured after upgrade via the Settings UI** (settings DB). Until `source.base-url`/`request-pull-uri` are set, the E1 pull cycle stays dormant (skips silently, warns once). The matching env vars (`SOURCE_BASE_URL`, `SOURCE_PULL_URI`, `SOURCE_SAVE_URI`, `SOURCE_OAUTH2_PROVIDER_URI`, `API_BASE_URL`) remain a transitional fallback; precedence is settings-DB row > env var > (empty) yaml.

Optional but recommended in production:

| Env Var | Purpose |
|---------|---------|
| `CALLBACK_ALLOWED_HOSTS` | Restrict X-Callback-Url to an explicit host allowlist; empty = any public host accepted (SSRF guard still blocks private/loopback/CGNAT ranges regardless). |
| `TRUSTED_PROXIES` | Peers whose X-Forwarded-For/X-Real-IP are trusted for client-IP extraction. Defaults to `127.0.0.1` — **must be set to the real nginx peer address in prod**, otherwise the trusted-proxy check silently never matches the actual reverse-proxy hop and forwarded-for headers are ignored. |
