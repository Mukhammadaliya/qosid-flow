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

## Server & Management

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `server.port` | int | `8090` | `SERVER_PORT` | Application HTTP port |
| `management.server.port` | int | `8091` | `MANAGEMENT_PORT` | Actuator/metrics port |
| `spring.lifecycle.timeout-per-shutdown-phase` | Duration | `30s` | — | Graceful shutdown timeout |

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
