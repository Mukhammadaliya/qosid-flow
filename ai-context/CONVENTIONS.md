# Code Conventions

Rules and patterns that all code in this project must follow. AI tools must generate code that matches these conventions.

## Package Structure

Base package: `uz.greenwhite.gateway`

| Package | Purpose | When to Add Here |
|---------|---------|-----------------|
| `config/` | Configuration classes, bean definitions | New `*Properties.java` or `*Config.java` |
| `controller/` | REST endpoints (dev only) | New test/dev endpoints |
| `health/` | Health indicators | New health checks |
| `http/` | HTTP client logic | Custom HTTP handling beyond `HttpRequestService` |
| `kafka/consumer/` | Kafka consumers | New Kafka consumer classes |
| `kafka/producer/` | Kafka producers | New producer methods or classes |
| `kafka/ack/` | Acknowledgement ordering (`OrderedAckTracker`) | Rarely — changes here decide whether offsets can commit past in-flight work (SAFETY-RULES 39) |
| `kafka/retry/` | Deferred re-delivery of messages the pipeline is not ready to send (`CbRequeueScheduler`) | A new hold-and-retry-later mechanism; anything that owns a record's completion must follow SAFETY-RULES 40 |
| `metrics/` | Metrics definitions | **Never create new files here** — all metrics go in `GatewayMetrics.java` |
| `model/` | Domain models | New domain objects |
| `model/kafka/` | Kafka message models | New `*Message.java` classes |
| `model/enums/` | Enumerations | New enums |
| `notification/` | Alert/notification services | New notification channels |
| `oauth2/` | OAuth2 token management | OAuth2 related logic |
| `oauth2/client/` | OAuth2 client implementations | New provider type implementations |
| `oauth2/model/` | OAuth2 data models | New OAuth2 related models |
| `oracle/` | Oracle/Biruni data source | Biruni API related logic |
| `source/` | Data source interfaces | New data source abstractions |
| `state/` | Redis state management | State/lock related logic |
| `concurrency/` | Concurrency management | Scaling related logic |
| `validation/` | Validation logic | New validators |

**Rule:** Add new classes to existing packages. Create a new package only when introducing an entirely new domain that does not fit any existing package.

## Naming Conventions

| Type | Pattern | Examples |
|------|---------|----------|
| Service class | `*Service.java` | `HttpRequestService`, `RequestStateService`, `OAuth2ProviderService` |
| Consumer | `*Consumer.java` | `OracleRequestConsumer`, `ApiRequestConsumer`, `OracleResponseConsumer`, `ApiResponseConsumer` |
| Producer | `*Producer.java` | `RequestProducer` |
| Configuration | `*Config.java` | `KafkaConfig`, `RedisConfig`, `HttpClientConfig` |
| Properties | `*Properties.java` | `KafkaProperties`, `HttpProperties`, `RetryProperties` |
| Domain model | Plain name | `Request`, `Response`, `Token` |
| Kafka message | `*Message.java` | `RequestMessage`, `ResponseMessage`, `DlqMessage` |
| Enum | Singular | `RequestStatus`, `ErrorSource`, `GrantType` |
| Interface | Descriptive name | `OAuth2Client`, `NotificationService`, `RequestSourceClient` |
| Exception | `*Exception.java` | `OAuth2TokenException` |
| Health indicator | `*HealthIndicator.java` | `SystemHealthIndicator` |
| Validator | `*Validator.java` | `RequestValidator` |

## Class Annotations Pattern

**Service/Component classes:**
```java
@Slf4j
@Service  // or @Component
@RequiredArgsConstructor
public class MyService {
    private final SomeDependency dependency;
    // ...
}
```

**Properties classes:**
```java
@Getter
@Setter
@Configuration  // or @Component
@ConfigurationProperties(prefix = "gateway.my-feature")
public class MyFeatureProperties {
    private String someValue = "default";
    private int someNumber = 10;

    @PostConstruct
    public void validate() {
        // validate required fields
    }
}
```

**Kafka consumer classes:**
```java
@Slf4j
@Service
public class MyConsumer {
    // Constructor injection (no @RequiredArgsConstructor due to @Qualifier usage)
    private final SomeDependency dependency;

    @KafkaListener(
        id = "myConsumer",
        topics = "${gateway.kafka.topics.my-topic}",
        containerFactory = "myConsumerFactory"
    )
    public void consume(ConsumerRecord<String, MyMessage> record, Acknowledgment ack) {
        try {
            // process
        } finally {
            ack.acknowledge();  // MANDATORY: always ack
        }
    }
}
```

## Dependency Injection

**DO:**
```java
@RequiredArgsConstructor
public class MyService {
    private final DependencyA depA;
    private final DependencyB depB;
}
```

**DO NOT:**
```java
public class MyService {
    @Autowired  // NEVER use field injection
    private DependencyA depA;
}
```

**Exception:** When `@Qualifier` is needed (e.g., consumers with `oracleHttpExecutor`/`apiHttpExecutor`), use explicit constructor:
```java
public class MyService {
    private final ThreadPoolTaskExecutor executor;

    public MyService(@Qualifier("apiHttpExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }
}
```

## Logging

**Annotation:** Always use `@Slf4j` (Lombok)

**Format:** `"Stage - Action: detail, key={}", value`

```java
// Good
log.info("E1 - Pull completed: requests={}, duration={}ms", count, duration);
log.warn("E3 - Skipped duplicate: key={}", compositeId);
log.error("E4 - HTTP request failed: url={}, status={}, error={}", url, status, e.getMessage());

// Bad
log.info("Processed " + count + " requests");  // String concatenation
log.info("Done");  // No context
```

**Log levels:**
- `DEBUG` — detailed processing steps, state transitions
- `INFO` — stage completions, summaries, configuration loaded
- `WARN` — skipped duplicates, lock failures, retries, circuit breaker state changes
- `ERROR` — unrecoverable failures, DLQ sends, exception details

## Error Handling

1. **Try-catch only when recovery is possible:**
```java
// Good — recovery by sending to DLQ
try {
    sendToKafka(request);
} catch (Exception e) {
    sendToDlq(request, e.getMessage());
}

// Bad — catch just to log and rethrow
try {
    process();
} catch (Exception e) {
    log.error("Error", e);
    throw e;  // pointless wrapping
}
```

2. **Domain-specific exceptions:** Create specific exception types (e.g., `OAuth2TokenException`)

3. **Catch-all only in top-level consumers:** `Oracle/ApiRequestConsumer` and `Oracle/ApiResponseConsumer` catch `Exception` at the top level to ensure Kafka ack and prevent consumer death

## Metrics

**All metrics are centralized in `GatewayMetrics.java`.** Never create metrics in any other file.

**Naming pattern:** `gateway.{stage}.{action}.{type}`

Examples:
- `gateway.oracle.pull.duration` (Timer)
- `gateway.http.request.total` (Counter with tags)
- `gateway.kafka.consumer.lag` (Gauge)

**To add a new metric:**
1. Add field in `GatewayMetrics.java`
2. Initialize in constructor with `meterRegistry`
3. Create accessor method if needed
4. Update Grafana dashboard in `infrastructure/dashboard_grafana.json`

## Configuration Pattern

Every new configuration group follows this pattern:

1. **Properties class** with `@ConfigurationProperties`:
```java
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.my-feature")
public class MyFeatureProperties {
    private String value = "default";
}
```

2. **application.yml** with env var override:
```yaml
gateway:
  my-feature:
    value: ${MY_FEATURE_VALUE:default}
```

3. **Inject via constructor** where needed:
```java
@RequiredArgsConstructor
public class MyService {
    private final MyFeatureProperties properties;
}
```

## Model Classes

Use Lombok annotations consistently:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyModel {
    private Long id;
    private String name;
}
```

For Kafka messages, use `@JsonProperty` for snake_case JSON mapping:
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyMessage {
    @JsonProperty("device_id")
    private Long deviceId;
}
```
Exception: `RequestMessage.tenantId`/`extraTenantId` intentionally have NO `@JsonProperty`
binding to a wire name — the Biruni wire field (default `company_id`/`filial_id`,
configurable) is translated at the boundary by `TenantMappingService`, not by Jackson
annotations on the internal model. See `ai-context/SAFETY-RULES.md` rule 30.

## Anti-Patterns (Never Do)

| Anti-Pattern | Correct Approach |
|-------------|-----------------|
| `@Autowired` field injection | Constructor injection via `@RequiredArgsConstructor` |
| Static utility methods | Instance methods in `@Component` classes |
| God classes (>300 lines) | Split by responsibility into separate classes |
| Metrics outside `GatewayMetrics` | Always add to `GatewayMetrics.java` |
| Hardcoded config values | Use `*Properties.java` + `application.yml` |
| `AUTO` Kafka ack mode | Always `MANUAL` — acknowledge after processing |
| Swallowing exceptions silently | Log or handle; never empty catch blocks |
