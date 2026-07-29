# Extension Guide

Step-by-step recipes for adding new features to the gateway. Follow these patterns exactly to maintain consistency with the existing codebase.

## Recipe 0: Add a Custom `AuthorizationProvider`

**When:** You want to gate the REST proxy (`POST/GET/... /qosid/api/v1/queue/**`) with a bespoke auth scheme — API-key header, JWT, IP allowlist, etc.

**How it works:** `ProxyController` calls `authorizationProvider.authorize(AuthContext)` on every inbound request. The default `NoOpAuthorizationProvider` allows everything. Your custom bean displaces it automatically because of `@ConditionalOnMissingBean(AuthorizationProvider.class)` on the default.

**File to create** — `auth/ApiKeyAuthorizationProvider.java` (name is up to you):

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthorizationProvider implements AuthorizationProvider {

    @Value("${gateway.auth.api-key:}")
    private String expectedApiKey;

    @Override
    public AuthResult authorize(AuthContext context) {
        String presented = context.getHeaders().get("X-Api-Key");
        if (presented == null || presented.isBlank()) {
            return AuthResult.denied("Missing X-Api-Key header", 401);
        }
        if (!expectedApiKey.equals(presented)) {
            return AuthResult.denied("Invalid API key", 403);
        }
        return AuthResult.allowed();
    }
}
```

**What you DON'T have to do:**

- Don't register a bean name — `@Component` on any `AuthorizationProvider` implementation is enough; `NoOpAuthorizationProvider` steps aside because of `@ConditionalOnMissingBean`.
- Don't touch `ProxyController` — it already throws `AuthorizationDeniedException(reason, httpStatus)` when `AuthResult.allowed == false`, and `GlobalExceptionHandler` translates that to the right HTTP status with a uniform `ErrorResponse` body.
- Don't wire Spring Security in. The framework is deliberately off the classpath; the project uses its own `AuthenticationException` (also mapped to 401 by the handler).

**What to pay attention to:**

- **Header case**: servlet headers are case-insensitive on lookup but the `AuthContext.headers` map preserves whatever case the client sent. Use `context.getHeaders().entrySet().stream().filter(...)` if you need case-insensitive matching.
- **Client IP**: `context.getClientIp()` already resolves `X-Forwarded-For` (leftmost) / `X-Real-IP` / remote addr — don't re-parse `X-Forwarded-For` yourself.
- **Status code**: pass a specific HTTP code on denial (`AuthResult.denied(reason, 401)`). Without it the default is 403, which is wrong for a missing-credential case.
- **Multiple implementations**: if you register more than one `AuthorizationProvider` bean Spring will fail with a "multiple beans" error — either `@Primary` one of them or combine the logic into a single composite provider.
- **Supplying a resolved tenant**: if your auth scheme can resolve the tenant itself (e.g. from an API-key → tenant lookup), return `AuthResult.allowed(tenantId, extraTenantId)` instead of the no-arg `allowed()` — both are `Long` (nullable); `ProxyController` uses them directly and only falls back to `gateway.tenant.default-rest-tenant` when `tenantId` is null.

---

## Recipe 1: Add a New Kafka Consumer

**When:** You need to consume messages from a new Kafka topic.

**Files to create/modify:**

1. **Create** `kafka/consumer/MyNewConsumer.java`:
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class MyNewConsumer {

    private final GatewayMetrics metrics;

    @KafkaListener(
        id = "myNewConsumer",
        topics = "${gateway.kafka.topics.my-new-topic}",
        containerFactory = "myNewConsumerFactory"  // create in KafkaConfig
    )
    public void consume(ConsumerRecord<String, MyMessage> record, Acknowledgment ack) {
        try {
            log.info("MyNew - Received: key={}", record.key());
            // processing logic
        } catch (Exception e) {
            log.error("MyNew - Processing failed: key={}, error={}", record.key(), e.getMessage(), e);
        } finally {
            ack.acknowledge();  // MANDATORY: always ack
        }
    }
}
```

2. **Modify** `config/KafkaConfig.java` — add a new consumer factory bean:
```java
@Bean("myNewConsumerFactory")
public ConcurrentKafkaListenerContainerFactory<String, Object> myNewListenerContainerFactory() {
    // Follow the pattern of oracleRequestConsumerFactory or apiRequestConsumerFactory
}
```

3. **Modify** `config/KafkaProperties.java` — add topic name:
```java
// In Topics inner class
private String myNewTopic;
```

4. **Modify** `application.yml`:
```yaml
gateway:
  kafka:
    topics:
      my-new-topic: ${KAFKA_TOPIC_MY_NEW:gateway.my.new.topic}
```

5. **Modify** `metrics/GatewayMetrics.java` — add consumer metrics.

6. **Modify** `config/KafkaConfig.java` — add `NewTopic` bean:
```java
@Bean
public NewTopic myNewTopic() {
    return TopicBuilder.name(kafkaProperties.getTopics().getMyNewTopic())
        .partitions(concurrencyProperties.getTopicPartitions())
        .replicas(1)
        .build();
}
```

**Critical:** Always use MANUAL ack mode. Never AUTO.

---

## Recipe 2: Add a New OAuth2 Provider

**When:** A new external API requires a different OAuth2 authentication flow.

**Existing implementations (check before creating a new one):**

| Type (`getName()`) | Class | Token request format | Use for |
|--------------------|-------|----------------------|---------|
| `biruni` | `JsonBodyOAuth2Client` | JSON body (posted to the token endpoint) | Biruni-style providers (type value `"biruni"` is frozen) |
| `standard` | `StandardOAuth2Client` | `application/x-www-form-urlencoded` (RFC 6749 `client_credentials`, no refresh token) | Spec-compliant providers, e.g. Regos (`auth.regos.uz`) |

**Files to create/modify:**

1. **Create** `oauth2/client/MyProviderOAuth2Client.java`:
```java
@Slf4j
@Component
@RequiredArgsConstructor
public class MyProviderOAuth2Client implements OAuth2Client {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private static final String NAME = "my-provider";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Token getAccessToken(ProviderProperties properties) {
        // Implement client_credentials flow
        // Return Token with accessToken, expiresIn, tokenType
    }

    @Override
    public Token refreshAccessToken(ProviderProperties properties, Token token) {
        // Implement refresh_token flow
        // Return new Token
    }
}
```

2. The new client is **auto-discovered** by Spring — `OAuth2ProviderService` collects all `OAuth2Client` beans via constructor injection into a `Map<String, OAuth2Client>` keyed by `getName()`.

3. **Configure** in Oracle/Biruni — the provider type returned by `oauth2ProviderUri` must match the `getName()` value ("my-provider").

**Redis cache key:** `oauth2:token:my-provider` (automatic via `OAuth2TokenRedisCache`).

**Refresh model:** Stale-while-revalidate — once a cached token crosses 80 % of its lifetime, the next caller silently fires an async refresh on `oauth2RefreshExecutor` while still being served the still-valid token. The synchronous lock+wait path only runs on a true cache miss (cold start, expired token, or repeated async-refresh failures). New providers automatically participate without any registration.

---

## Recipe 3: Add a New External API Integration

**When:** Requests need to be sent to a new external API that requires custom handling beyond what `HttpRequestService` provides.

**Option A: No custom logic needed (most cases)**

No code changes needed. The pipeline already handles any HTTP endpoint:
- Set `baseUrl`, `uri`, `method`, `headers` in the `RequestMessage` from Oracle
- If OAuth2 is needed, set `oauth2Provider` field
- Circuit Breaker is automatic per domain
- Per-endpoint timeout: add to `application.yml`:
```yaml
gateway:
  http:
    endpoint-timeouts:
      "slow-api.example.com": 60000  # 60s timeout for this domain
```

**Option B: Custom HTTP logic needed**

1. **Create** `http/MyCustomHttpService.java`:
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class MyCustomHttpService {

    private final WebClient webClient;
    private final HttpProperties httpProperties;

    public ResponseMessage sendCustomRequest(RequestMessage request) {
        // Custom logic
    }
}
```

2. **Modify** `OracleRequestConsumer.java` or `ApiRequestConsumer.java` — add routing logic to delegate to the custom service based on some request attribute.

**Warning:** Modifying request consumers is a Protected Zone operation. Follow the Required Checklist in `SAFETY-RULES.md`.

---

## Recipe 4: Add a New Metric

**When:** You need to track a new measurement for monitoring.

**Rule:** All metrics go in `GatewayMetrics.java`. Never create metrics anywhere else.

**Steps:**

1. **Modify** `metrics/GatewayMetrics.java`:
```java
// Add field
private final Counter myNewCounter;

// Initialize in constructor
this.myNewCounter = Counter.builder("gateway.mystage.myaction.total")
    .description("Description of what this counts")
    .tag("result", "success")
    .register(meterRegistry);

// Add accessor if needed
public Counter getMyNewCounter() { return myNewCounter; }
```

2. **Naming pattern:** `gateway.{stage}.{action}.{type}`
   - Stage: `oracle`, `kafka`, `http`, `consumer`, `dlq`
   - Action: `pull`, `produce`, `request`, `save`, `sent`
   - Type: `total` (Counter), `duration` (Timer)

3. **Update** `infrastructure/dashboard_grafana.json` — add a panel for the new metric.

4. **Optionally update** `infrastructure/prometheus/gateway-alerts.yml` — add alert rule if the metric needs alerting.

---

## Recipe 5: Add a New Configuration Group

**When:** A new feature needs its own configuration properties.

**Steps:**

1. **Create** `config/MyFeatureProperties.java`:
```java
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.my-feature")
public class MyFeatureProperties {

    private boolean enabled = true;
    private String someValue = "default";
    private int someNumber = 10;

    @PostConstruct
    public void validate() {
        if (someNumber <= 0) {
            throw new IllegalArgumentException("gateway.my-feature.some-number must be > 0");
        }
    }
}
```

2. **Add to** `application.yml`:
```yaml
gateway:
  my-feature:
    enabled: ${MY_FEATURE_ENABLED:true}
    some-value: ${MY_FEATURE_VALUE:default}
    some-number: ${MY_FEATURE_NUMBER:10}
```

3. **Inject** where needed:
```java
@RequiredArgsConstructor
public class MyService {
    private final MyFeatureProperties properties;
}
```

4. **Decide, explicitly, whether the key is operator-editable.** Adding a `SettingMeta` row to
   `SettingsRegistry` (settings-core) makes it appear in the ops UI and editable while the gateway
   is stopped; leaving it out makes it `.env`/yaml-only, applied on the next start. Omission is a
   legitimate choice, not an oversight — the `gateway.retry.cb-*` back-pressure keys are
   deliberately absent because they bound a memory-holding, ack-owning path where a typo is a
   message-loss risk rather than a tuning mistake. Either way, document the key (and which side of
   this line it falls on) in `ai-context/CONFIGURATION.md`.

---

## Recipe 6: Add a New Notification Channel

**When:** Alerts need to be sent to a new destination (e.g., Slack, email, webhook).

> **Before you write the send call, read SAFETY-RULES 41.** A DLQ alert channel is a rate-limited
> destination, not a log sink: an incident produces a *burst* of DLQ records, and one message per
> record is how the channel becomes useless exactly when it matters (2026-07-27: 212 records → 14
> delivered, 298 rate-limit rejections). Any new channel needs the same two properties as
> `TelegramNotificationService`:
>
> - **Coalesce** by (reason-class, host) inside a window — first alert immediate, the rest as one
>   summary, with the number of open groups itself bounded.
> - **Obey the destination's rate-limit signal** (`Retry-After` / `retry_after` / equivalent) by
>   staying silent until it passes, counting what was held back and reporting it on the next
>   message. **Never retry the rejection immediately.**
>
> Flush windows on a **dedicated** timer (`alertCoalesceTimer`), never `cbRequeueTimer` — a
> blocking send must not delay a parked request's requeue.

**Steps:**

1. **Create** `notification/SlackNotificationService.java`:
```java
@Slf4j
@Service
@ConditionalOnProperty(name = "gateway.slack.enabled", havingValue = "true")
public class SlackNotificationService implements NotificationService {

    private final SlackProperties properties;

    @Override
    public void sendDlqAlert(DlqMessage message) {
        // Format and send Slack message
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
```

2. **Create** `config/SlackProperties.java`:
```java
@Data
@Configuration
@ConfigurationProperties(prefix = "gateway.slack")
public class SlackProperties {
    private boolean enabled = false;
    private String webhookUrl;
    private String channel;
}
```

3. **Add to** `application.yml`:
```yaml
gateway:
  slack:
    enabled: ${SLACK_ENABLED:false}
    webhook-url: ${SLACK_WEBHOOK_URL:}
    channel: ${SLACK_CHANNEL:#alerts}
```

**Note:** Multiple `NotificationService` implementations can coexist. The `OracleDlqConsumer` and `ApiDlqConsumer` inject the active implementation. Currently `TelegramNotificationService` is the only implementation — it is **always** the active bean (no separate no-op fallback bean); when Telegram is disabled or unconfigured it resolves that per send (settings-DB → env fallback) and simply warn-logs instead of sending.

**Important:** If you want multiple active notification channels simultaneously, you need to modify the DLQ consumers to inject `List<NotificationService>` instead of a single bean.
