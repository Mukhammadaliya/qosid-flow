# Extension Guide

Step-by-step recipes for adding new features to the gateway. Follow these patterns exactly to maintain consistency with the existing codebase.

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
    // Follow the pattern of requestConsumerFactory or responseConsumerFactory
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

2. **Modify** `RequestConsumer.java` — add routing logic to delegate to the custom service based on some request attribute.

**Warning:** Modifying `RequestConsumer.java` is a Protected Zone operation. Follow the Required Checklist in `SAFETY-RULES.md`.

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

---

## Recipe 6: Add a New Notification Channel

**When:** Alerts need to be sent to a new destination (e.g., Slack, email, webhook).

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

**Note:** Multiple `NotificationService` implementations can coexist. The `DlqConsumer` injects all available implementations. Currently, `NoOpNotificationService` is the fallback when `TelegramNotificationService` is disabled.

**Important:** If you want multiple active notification channels simultaneously, you need to modify `DlqConsumer` to inject `List<NotificationService>` instead of a single bean.
