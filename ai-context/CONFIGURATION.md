# Configuration Reference

Complete reference for all configuration parameters. Every property has a default value and can be overridden via environment variables.

## gateway.source.* — Oracle/Biruni Data Source

**Properties class:** `GatewayProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.source.base-url` | String | `https://app3.greenwhite.uz/x24` | `SOURCE_BASE_URL` | Biruni API base URL |
| `gateway.source.username` | String | `admin@head` | `SOURCE_USERNAME` | Basic Auth username |
| `gateway.source.password` | String | `greenwhite` | `SOURCE_PASSWORD` | Basic Auth password |
| `gateway.source.request-pull-uri` | String | `/b/biruni/bmb/requests$pull` | `SOURCE_PULL_URI` | URI for pulling requests |
| `gateway.source.response-save-uri` | String | `/b/biruni/bmb/requests$save` | `SOURCE_SAVE_URI` | URI for saving responses |
| `gateway.source.oauth2-provider-uri` | String | `/b/biruni/bmb/requests$get_oauth_provider_data` | `SOURCE_OAUTH2_PROVIDER_URI` | URI for OAuth2 provider config |
| `gateway.source.connection-timeout` | int | `60` | — | Connection timeout in seconds |

## gateway.kafka.* — Kafka Settings

**Properties class:** `KafkaProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.kafka.bootstrap-servers` | String | `localhost:19092` | `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address |
| `gateway.kafka.group-id` | String | `gateway-service-group` | `KAFKA_GROUP_ID` | Consumer group ID |
| `gateway.kafka.topics.request-new` | String | `gateway.request.new` | `KAFKA_TOPIC_REQUEST` | Topic for new requests |
| `gateway.kafka.topics.request-response` | String | `gateway.request.response` | `KAFKA_TOPIC_RESPONSE` | Topic for responses |
| `gateway.kafka.topics.request-dlq` | String | `gateway.request.dlq` | `KAFKA_TOPIC_DLQ` | Dead Letter Queue topic |

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

## gateway.concurrency.* — Dynamic Scaling

**Properties class:** `ConcurrencyProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.concurrency.min-concurrency` | int | `3` | `CONCURRENCY_MIN` | Minimum consumer threads |
| `gateway.concurrency.max-concurrency` | int | `15` | `CONCURRENCY_MAX` | Maximum consumer threads |
| `gateway.concurrency.monitor-interval-ms` | long | `10000` | `CONCURRENCY_MONITOR_INTERVAL` | Lag check interval (ms) |
| `gateway.concurrency.scale-up-threshold` | long | `50` | `CONCURRENCY_SCALE_UP` | Scale up when lag exceeds this |
| `gateway.concurrency.scale-down-threshold` | long | `10` | `CONCURRENCY_SCALE_DOWN` | Scale down when lag below this |
| `gateway.concurrency.scale-step` | int | `2` | — | Threads to add/remove per step |
| `gateway.concurrency.scale-cooldown-ms` | long | `30000` | — | Minimum time between scaling events |
| `gateway.concurrency.topic-partitions` | int | `10` | `KAFKA_PARTITIONS` | Kafka partitions per topic |
| `gateway.concurrency.thread-pool-multiplier` | int | `2` | `THREAD_POOL_MULTIPLIER` | HTTP thread pool max = `maxConcurrency * multiplier` |
| `gateway.concurrency.thread-pool-queue-capacity` | int | `50` | `THREAD_POOL_QUEUE` | HTTP thread pool queue size before rejection |

## gateway.telegram.* — Telegram Notifications

**Properties class:** `TelegramProperties.java`

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.telegram.enabled` | boolean | `true` | `TELEGRAM_ENABLED` | Enable Telegram DLQ alerts |
| `gateway.telegram.bot-token` | String | — | `TELEGRAM_BOT_TOKEN` | Telegram Bot API token |
| `gateway.telegram.chat-id` | Long | — | `TELEGRAM_CHAT_ID` | Target chat ID |
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

## gateway.target.* — Upstream Base URL

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.target.base-url` | String | `http://localhost:8081/b` | `TARGET_BASE_URL` | Base URL used by API-source requests when no explicit upstream is supplied. |

## gateway.proxy.* — REST Proxy Layout

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `gateway.proxy.prefix` | String | `/qosid/queue` | URI prefix stripped when forwarding the proxied request. |
| `gateway.proxy.context-path` | String | `/qosid` | Application context path (informational). |

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

## gateway.s3.* — File Upload Backends

**Properties class:** `FileProperties.java`

### MinIO (`gateway.s3.minio.*`)

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.s3.minio.enabled` | boolean | `false` | `MINIO_ENABLED` | Gate: client bean registered only when true. |
| `gateway.s3.minio.endpoint` | String | — | `MINIO_ENDPOINT` | MinIO server URL. |
| `gateway.s3.minio.access-key` | String | — | `MINIO_ACCESS_KEY` | |
| `gateway.s3.minio.secret-key` | String | — | `MINIO_SECRET_KEY` | |
| `gateway.s3.minio.bucket` | String | `gateway-files` | `MINIO_BUCKET` | Target bucket (must exist at startup). |

### Cloudflare R2 (`gateway.s3.cloudflare-r2.*`)

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `gateway.s3.cloudflare-r2.enabled` | boolean | `false` | `R2_ENABLED` | Gate: client bean registered only when true. |
| `gateway.s3.cloudflare-r2.endpoint` | String | — | `R2_ENDPOINT` | R2 endpoint URL. |
| `gateway.s3.cloudflare-r2.access-key` | String | — | `R2_ACCESS_KEY` | |
| `gateway.s3.cloudflare-r2.secret-key` | String | — | `R2_SECRET_KEY` | |
| `gateway.s3.cloudflare-r2.temp-bucket` | String | — | `R2_TEMP_BUCKET` | Temp-upload bucket. |
| `gateway.s3.cloudflare-r2.permanent-bucket` | String | — | `R2_PERMANENT_BUCKET` | Permanent-upload bucket. |
| `gateway.s3.cloudflare-r2.link-expiration-hours` | int | `24` | `R2_LINK_EXPIRY` | Presigned GET URL lifetime (hours). |

## gateway.security.* — Content-Type Allowlist

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `gateway.security.content-types` | List\<String\> | JSON/text/image/audio/video MIME list (see `application.yml`) | Whitelist enforced by `ContentTypeValidator`. Requests / uploads with other types are rejected with 400. |

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
