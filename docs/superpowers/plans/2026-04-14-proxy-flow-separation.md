# Proxy Flow Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split Oracle and API proxy flows into independent Kafka topics, consumers, and thread pools so one flow's failure cannot affect the other.

**Architecture:** Replace 3 shared topics with 6 source-specific topics, split RequestConsumer/ResponseConsumer/DlqConsumer into Oracle and API variants, create separate HTTP thread pools per flow.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Kafka, Resilience4j

---

### Task 1: Update KafkaProperties — 6 topic names

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/config/KafkaProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Replace Topics inner class**

Replace full content of `src/main/java/uz/greenwhite/gateway/config/KafkaProperties.java`:

```java
package uz.greenwhite.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.kafka")
public class KafkaProperties {

    private String bootstrapServers;
    private String groupId;
    private Topics topics = new Topics();

    @PostConstruct
    public void validate() {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("gateway.kafka.bootstrap-servers must be configured");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("gateway.kafka.group-id must be configured");
        }
        topics.validate();

        log.info("Kafka config: servers={}, groupId={}, oracle=[{}, {}, {}], api=[{}, {}, {}]",
                bootstrapServers, groupId,
                topics.oracleRequest, topics.oracleResponse, topics.oracleDlq,
                topics.apiRequest, topics.apiResponse, topics.apiDlq);
    }

    @Getter
    @Setter
    public static class Topics {
        private String oracleRequest;
        private String oracleResponse;
        private String oracleDlq;
        private String apiRequest;
        private String apiResponse;
        private String apiDlq;

        void validate() {
            requireNonBlank(oracleRequest, "gateway.kafka.topics.oracle-request");
            requireNonBlank(oracleResponse, "gateway.kafka.topics.oracle-response");
            requireNonBlank(oracleDlq, "gateway.kafka.topics.oracle-dlq");
            requireNonBlank(apiRequest, "gateway.kafka.topics.api-request");
            requireNonBlank(apiResponse, "gateway.kafka.topics.api-response");
            requireNonBlank(apiDlq, "gateway.kafka.topics.api-dlq");
        }

        private void requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must be configured");
            }
        }
    }
}
```

- [ ] **Step 2: Update application.yml topics section**

Replace the `gateway.kafka.topics` section in `src/main/resources/application.yml` (lines ~91-94):

Old:
```yaml
    topics:
      request-new: ${KAFKA_TOPIC_REQUEST:gateway.request.new}
      request-response: ${KAFKA_TOPIC_RESPONSE:gateway.request.response}
      request-dlq: ${KAFKA_TOPIC_DLQ:gateway.request.dlq}
```

New:
```yaml
    topics:
      oracle-request: ${KAFKA_TOPIC_ORACLE_REQUEST:gateway.oracle.request}
      oracle-response: ${KAFKA_TOPIC_ORACLE_RESPONSE:gateway.oracle.response}
      oracle-dlq: ${KAFKA_TOPIC_ORACLE_DLQ:gateway.oracle.dlq}
      api-request: ${KAFKA_TOPIC_API_REQUEST:gateway.api.request}
      api-response: ${KAFKA_TOPIC_API_RESPONSE:gateway.api.response}
      api-dlq: ${KAFKA_TOPIC_API_DLQ:gateway.api.dlq}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: FAIL — other files still reference old topic names. This is expected at this stage.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/config/KafkaProperties.java src/main/resources/application.yml
git commit -m "refactor: split Kafka topics into oracle and api namespaces

Replace 3 shared topics (gateway.request.*) with 6 source-specific
topics: gateway.oracle.{request,response,dlq} and
gateway.api.{request,response,dlq}."
```

---

### Task 2: Update ConcurrencyProperties — oracle/api separate config

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/config/ConcurrencyProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Replace ConcurrencyProperties**

Replace full content of `src/main/java/uz/greenwhite/gateway/config/ConcurrencyProperties.java`:

```java
package uz.greenwhite.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.concurrency")
public class ConcurrencyProperties {

    // Oracle flow
    private int oracleMin = 5;
    private int oracleMax = 10;

    // API flow
    private int apiMin = 5;
    private int apiMax = 10;

    // Shared settings
    private long monitorIntervalMs = 10_000;
    private long scaleUpThreshold = 50;
    private long scaleDownThreshold = 10;
    private int scaleStep = 2;
    private long scaleCooldownMs = 30_000;
    private int topicPartitions = 10;
    private int threadPoolMultiplier = 2;
    private int threadPoolQueueCapacity = 50;

    @PostConstruct
    public void validate() {
        validateRange("oracle", oracleMin, oracleMax);
        validateRange("api", apiMin, apiMax);

        if (scaleUpThreshold <= scaleDownThreshold) {
            throw new IllegalArgumentException("scaleUpThreshold must be > scaleDownThreshold");
        }

        log.info("Concurrency config: oracle[min={}, max={}], api[min={}, max={}], " +
                        "partitions={}, poolMultiplier={}, poolQueue={}",
                oracleMin, oracleMax, apiMin, apiMax,
                topicPartitions, threadPoolMultiplier, threadPoolQueueCapacity);
    }

    private void validateRange(String name, int min, int max) {
        if (min < 1) throw new IllegalArgumentException(name + "Min must be >= 1");
        if (max < min) throw new IllegalArgumentException(name + "Max must be >= " + name + "Min");
        if (max > topicPartitions) {
            log.warn("{}Max ({}) exceeds topicPartitions ({}), capping", name, max, topicPartitions);
        }
    }
}
```

- [ ] **Step 2: Update application.yml concurrency section**

Replace the `gateway.concurrency` section in `src/main/resources/application.yml`:

Old:
```yaml
  concurrency:
    min-concurrency: ${CONCURRENCY_MIN:10}
    max-concurrency: ${CONCURRENCY_MAX:15}
    ...
```

New:
```yaml
  concurrency:
    oracle-min: ${ORACLE_CONCURRENCY_MIN:5}
    oracle-max: ${ORACLE_CONCURRENCY_MAX:10}
    api-min: ${API_CONCURRENCY_MIN:5}
    api-max: ${API_CONCURRENCY_MAX:10}
    monitor-interval-ms: ${CONCURRENCY_MONITOR_INTERVAL:10000}
    scale-up-threshold: ${CONCURRENCY_SCALE_UP:50}
    scale-down-threshold: ${CONCURRENCY_SCALE_DOWN:10}
    scale-step: ${CONCURRENCY_SCALE_STEP:2}
    scale-cooldown-ms: ${CONCURRENCY_COOLDOWN:30000}
    topic-partitions: ${KAFKA_PARTITIONS:10}
    thread-pool-multiplier: ${THREAD_POOL_MULTIPLIER:2}
    thread-pool-queue-capacity: ${THREAD_POOL_QUEUE:50}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/config/ConcurrencyProperties.java src/main/resources/application.yml
git commit -m "refactor: split concurrency config into oracle and api

Separate min/max concurrency for Oracle and API flows.
Each flow gets independent thread pool sizing."
```

---

### Task 3: Update KafkaConfig — 6 topic beans, 5 consumer factories

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/config/KafkaConfig.java`

- [ ] **Step 1: Replace full KafkaConfig**

Replace full content of `src/main/java/uz/greenwhite/gateway/config/KafkaConfig.java`:

```java
package uz.greenwhite.gateway.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    private final KafkaProperties kafkaProperties;
    private final ConcurrencyProperties concurrencyProperties;

    // ==================== ADMIN CLIENT ====================

    @Bean
    public AdminClient kafkaAdminClient() {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        return AdminClient.create(props);
    }

    // ==================== TOPICS ====================

    @Bean
    public NewTopic oracleRequestTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getOracleRequest())
                .partitions(concurrencyProperties.getTopicPartitions())
                .replicas(1).build();
    }

    @Bean
    public NewTopic oracleResponseTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getOracleResponse())
                .partitions(concurrencyProperties.getTopicPartitions())
                .replicas(1).build();
    }

    @Bean
    public NewTopic oracleDlqTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getOracleDlq())
                .partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic apiRequestTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getApiRequest())
                .partitions(concurrencyProperties.getTopicPartitions())
                .replicas(1).build();
    }

    @Bean
    public NewTopic apiResponseTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getApiResponse())
                .partitions(concurrencyProperties.getTopicPartitions())
                .replicas(1).build();
    }

    @Bean
    public NewTopic apiDlqTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getApiDlq())
                .partitions(3).replicas(1).build();
    }

    // ==================== ERROR HANDLER ====================

    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, exception) -> {
                    log.error("Kafka message processing failed permanently — skipping. " +
                                    "topic={}, partition={}, offset={}, key={}, error={}",
                            record.topic(), record.partition(), record.offset(),
                            record.key(), exception.getMessage(), exception);
                },
                new FixedBackOff(1000L, 2L)
        );
        errorHandler.addNotRetryableExceptions(
                org.apache.kafka.common.errors.SerializationException.class,
                org.springframework.kafka.support.serializer.DeserializationException.class
        );
        return errorHandler;
    }

    // ==================== CONSUMER FACTORY ====================

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaProperties.getGroupId());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "uz.greenwhite.gateway.*");
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    // ==================== ORACLE CONTAINER FACTORIES ====================

    @Bean("oracleRequestConsumerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> oracleRequestConsumerFactory() {
        return buildFactory(concurrencyProperties.getOracleMin(), "Oracle request");
    }

    @Bean("oracleResponseConsumerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> oracleResponseConsumerFactory() {
        return buildFactory(concurrencyProperties.getOracleMin(), "Oracle response");
    }

    // ==================== API CONTAINER FACTORIES ====================

    @Bean("apiRequestConsumerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> apiRequestConsumerFactory() {
        return buildFactory(concurrencyProperties.getApiMin(), "API request");
    }

    @Bean("apiResponseConsumerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> apiResponseConsumerFactory() {
        return buildFactory(concurrencyProperties.getApiMin(), "API response");
    }

    // ==================== DLQ CONTAINER FACTORY ====================

    @Bean("dlqConsumerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> dlqListenerContainerFactory() {
        return buildFactory(1, "DLQ");
    }

    // ==================== HELPER ====================

    private ConcurrentKafkaListenerContainerFactory<String, Object> buildFactory(int concurrency, String label) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setIdleBetweenPolls(100);
        factory.setCommonErrorHandler(kafkaErrorHandler());
        log.info("{} consumer factory created with concurrency: {}", label, concurrency);
        return factory;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/config/KafkaConfig.java
git commit -m "refactor: split KafkaConfig into oracle and api factories

6 topic beans, 5 consumer factories (oracle request/response,
api request/response, shared DLQ). Each uses source-specific
concurrency from ConcurrencyProperties."
```

---

### Task 4: Update ThreadPoolConfig — 2 separate executors

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/config/ThreadPoolConfig.java`

- [ ] **Step 1: Replace ThreadPoolConfig with 2 executors**

Replace full content of `src/main/java/uz/greenwhite/gateway/config/ThreadPoolConfig.java`:

```java
package uz.greenwhite.gateway.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ThreadPoolConfig {

    private final ConcurrencyProperties props;

    @Bean("oracleHttpExecutor")
    public ThreadPoolTaskExecutor oracleHttpExecutor() {
        return buildExecutor(props.getOracleMin(), props.getOracleMax(), "oracle-http-");
    }

    @Bean("apiHttpExecutor")
    public ThreadPoolTaskExecutor apiHttpExecutor() {
        return buildExecutor(props.getApiMin(), props.getApiMax(), "api-http-");
    }

    private ThreadPoolTaskExecutor buildExecutor(int min, int max, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(min);
        executor.setMaxPoolSize(max * props.getThreadPoolMultiplier());
        executor.setQueueCapacity(props.getThreadPoolQueueCapacity());
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new RejectWithLogging());
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();

        log.info("ThreadPool [{}] created: core={}, max={}, queue={}",
                prefix, executor.getCorePoolSize(), executor.getMaxPoolSize(),
                props.getThreadPoolQueueCapacity());
        return executor;
    }

    static class RejectWithLogging implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("HTTP thread pool exhausted! Task rejected. queue={}, active={}, pool={}",
                    executor.getQueue().size(), executor.getActiveCount(), executor.getPoolSize());
            throw new RejectedExecutionException("HTTP thread pool exhausted — task will be re-queued");
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/config/ThreadPoolConfig.java
git commit -m "refactor: separate HTTP thread pools for oracle and api

oracleHttpExecutor and apiHttpExecutor with independent sizing.
One flow's pool exhaustion cannot affect the other."
```

---

### Task 5: Update RequestProducer — source-specific methods

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/producer/RequestProducer.java`

- [ ] **Step 1: Replace RequestProducer**

Replace full content of `src/main/java/uz/greenwhite/gateway/kafka/producer/RequestProducer.java`:

```java
package uz.greenwhite.gateway.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequestProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaProperties kafkaProperties;

    // ==================== ORACLE ====================

    public CompletableFuture<SendResult<String, Object>> sendOracleRequest(RequestMessage message) {
        return send(kafkaProperties.getTopics().getOracleRequest(), message.getCompositeId(), message, "Oracle request");
    }

    public CompletableFuture<SendResult<String, Object>> sendOracleResponse(ResponseMessage message) {
        return send(kafkaProperties.getTopics().getOracleResponse(), message.getCompositeId(), message, "Oracle response");
    }

    public CompletableFuture<SendResult<String, Object>> sendToOracleDlq(DlqMessage message) {
        log.warn("Sending to Oracle DLQ: {} - reason: {}", message.getCompositeId(), message.getFailureReason());
        return send(kafkaProperties.getTopics().getOracleDlq(), message.getCompositeId(), message, "Oracle DLQ");
    }

    // ==================== API ====================

    public CompletableFuture<SendResult<String, Object>> sendApiRequest(RequestMessage message) {
        return send(kafkaProperties.getTopics().getApiRequest(), message.getCompositeId(), message, "API request");
    }

    public CompletableFuture<SendResult<String, Object>> sendApiResponse(ResponseMessage message) {
        return send(kafkaProperties.getTopics().getApiResponse(), message.getCompositeId(), message, "API response");
    }

    public CompletableFuture<SendResult<String, Object>> sendToApiDlq(DlqMessage message) {
        log.warn("Sending to API DLQ: {} - reason: {}", message.getCompositeId(), message.getFailureReason());
        return send(kafkaProperties.getTopics().getApiDlq(), message.getCompositeId(), message, "API DLQ");
    }

    // ==================== HELPER ====================

    private CompletableFuture<SendResult<String, Object>> send(String topic, String key, Object message, String label) {
        log.debug("Sending {} to Kafka: {}", label, key);
        return kafkaTemplate.send(topic, key, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send {} {}: {}", label, key, ex.getMessage());
                    } else {
                        log.info("{} sent successfully: {} [partition={}, offset={}]",
                                label, key,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/kafka/producer/RequestProducer.java
git commit -m "refactor: source-specific producer methods

Replace sendRequest/sendResponse/sendToDlq with oracle and api
variants: sendOracleRequest, sendApiRequest, etc."
```

---

### Task 6: Create Oracle consumers

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java`
- Create: `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleResponseConsumer.java`
- Create: `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleDlqConsumer.java`

- [ ] **Step 1: Create OracleRequestConsumer**

Create `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java`:

```java
package uz.greenwhite.gateway.kafka.consumer.oracle;

import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.http.HttpRequestService;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.enums.ErrorSource;
import uz.greenwhite.gateway.model.enums.RequestStatus;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.state.RequestStateService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class OracleRequestConsumer {

    private final HttpRequestService httpRequestService;
    private final RequestStateService requestStateService;
    private final RequestProducer requestProducer;
    private final RetryProperties retryProperties;
    private final ThreadPoolTaskExecutor httpExecutor;
    private final GatewayMetrics metrics;
    private final KafkaProperties kafkaProperties;

    public OracleRequestConsumer(
            HttpRequestService httpRequestService,
            RequestStateService requestStateService,
            RequestProducer requestProducer,
            RetryProperties retryProperties,
            @Qualifier("oracleHttpExecutor") ThreadPoolTaskExecutor httpExecutor,
            GatewayMetrics metrics, KafkaProperties kafkaProperties) {
        this.httpRequestService = httpRequestService;
        this.requestStateService = requestStateService;
        this.requestProducer = requestProducer;
        this.retryProperties = retryProperties;
        this.httpExecutor = httpExecutor;
        this.metrics = metrics;
        this.kafkaProperties = kafkaProperties;
    }

    @KafkaListener(
            id = "oracleRequestConsumer",
            topics = "${gateway.kafka.topics.oracle-request}",
            groupId = "${gateway.kafka.group-id}",
            containerFactory = "oracleRequestConsumerFactory"
    )
    public void consumeRequest(ConsumerRecord<String, RequestMessage> record, Acknowledgment ack) {
        String key = record.key();
        RequestMessage message = record.value();

        log.info("[ORACLE] Received request: {} [partition={}, offset={}]",
                key, record.partition(), record.offset());

        metrics.getConsumerReceived().increment();

        try {
            if (requestStateService.isCompleted(key)) {
                log.warn("[ORACLE] Request already completed, skipping: {}", key);
                metrics.getConsumerSkippedDuplicate().increment();
                ack.acknowledge();
                return;
            }

            if (!requestStateService.tryLock(key)) {
                log.warn("[ORACLE] Request is being processed by another instance: {}", key);
                metrics.getConsumerLockFailed().increment();
                ack.acknowledge();
                return;
            }

            CompletableFuture.runAsync(() -> processRequest(key, message, ack), httpExecutor)
                    .exceptionally(throwable -> {
                        log.error("[ORACLE] Unexpected error for {}: {}", key, throwable.getMessage(), throwable);
                        requestStateService.releaseLock(key);
                        ack.acknowledge();
                        return null;
                    });

        } catch (RejectedExecutionException e) {
            log.warn("[ORACLE] Pool exhausted, re-queuing: {}", key);
            metrics.getPoolRejection().increment();
            requestStateService.releaseLock(key);
            requestProducer.sendOracleRequest(message);
            ack.acknowledge();

        } catch (Exception e) {
            log.error("[ORACLE] Error submitting request {}: {}", key, e.getMessage(), e);
            requestStateService.releaseLock(key);
            ack.acknowledge();
        }
    }

    private void processRequest(String key, RequestMessage message, Acknowledgment ack) {
        if (isMessageExpired(message)) {
            long ageMs = Duration.between(message.getCreatedAt(), LocalDateTime.now()).toMillis();
            log.warn("[ORACLE] Message expired: {} (age={}ms)", key, ageMs);
            handlePermanentFailure(key, message, 0, "Message expired after " + ageMs + "ms", ErrorSource.SYSTEM);
            requestStateService.releaseLock(key);
            ack.acknowledge();
            return;
        }

        requestStateService.createInitialState(key);
        requestStateService.updateStatus(key, RequestStatus.SENT);

        Timer.Sample httpSample = Timer.start(metrics.getRegistry());

        httpRequestService.sendRequest(message)
                .timeout(Duration.ofMillis(
                        retryProperties.getIntervalMs() * retryProperties.getMaxAttempts() + 60_000))
                .subscribe(
                        response -> {
                            try {
                                httpSample.stop(metrics.getHttpRequestTimer());
                                if (response != null && response.isSuccess()) {
                                    metrics.getHttpSuccess().increment();
                                    handleSuccess(key, message, response);
                                } else {
                                    int httpStatus = response != null ? response.getHttpStatus() : 0;
                                    metrics.recordHttpResult(httpStatus);
                                    handleFailedResponse(key, message, response);
                                }
                            } catch (Exception e) {
                                log.error("[ORACLE] Error handling response for {}: {}", key, e.getMessage(), e);
                            } finally {
                                requestStateService.releaseLock(key);
                                ack.acknowledge();
                            }
                        },
                        error -> {
                            try {
                                httpSample.stop(metrics.getHttpRequestTimer());
                                if (isTimeoutException(error)) {
                                    metrics.recordHttpTimeout();
                                    log.error("[ORACLE] HTTP timeout for {}: {}", key, error.getMessage());
                                } else {
                                    metrics.getHttpError5xx().increment();
                                    log.error("[ORACLE] HTTP exception for {}: {}", key, error.getMessage());
                                }
                                handleFailedProcessing(key, message, error);
                            } catch (Exception e) {
                                log.error("[ORACLE] Error handling failure for {}: {}", key, e.getMessage(), e);
                            } finally {
                                requestStateService.releaseLock(key);
                                ack.acknowledge();
                            }
                        }
                );
    }

    private void handleSuccess(String key, RequestMessage request, ResponseMessage response) {
        try {
            requestProducer.sendOracleResponse(response).get(10, java.util.concurrent.TimeUnit.SECONDS);
            requestStateService.updateStatus(key, RequestStatus.COMPLETED);
            log.info("[ORACLE] Request processed successfully: {}", key);
        } catch (Exception e) {
            log.error("[ORACLE] Failed to send response to Kafka for {}: {}", key, e.getMessage());
            DlqMessage dlqMessage = DlqMessage.builder()
                    .companyId(response.getCompanyId())
                    .requestId(response.getRequestId())
                    .originalTopic(kafkaProperties.getTopics().getOracleResponse())
                    .failureReason("Response too large for Kafka: " + e.getMessage())
                    .errorSource("KAFKA")
                    .httpStatus(response.getHttpStatus())
                    .attemptCount(1)
                    .url(request.getMethod() + " " + request.getBaseUrl()
                            + (request.getUri() != null ? request.getUri() : ""))
                    .failedAt(LocalDateTime.now())
                    .build();
            requestProducer.sendToOracleDlq(dlqMessage);
            requestStateService.updateStatus(key, RequestStatus.FAILED);
        }
    }

    private void handleFailedResponse(String key, RequestMessage message, ResponseMessage response) {
        int attemptCount = requestStateService.incrementAttempt(key);
        int httpStatus = response != null ? response.getHttpStatus() : 0;
        String errorMessage = response != null ? response.getErrorMessage() : "Unknown error";

        String errorSourceStr = response != null ? response.getErrorSource() : null;
        boolean isNonRetryableSource = "CIRCUIT_BREAKER".equals(errorSourceStr)
                || "OAUTH2".equals(errorSourceStr);

        boolean isRetryable = retryProperties.isRetryable(httpStatus) && !isNonRetryableSource;
        boolean hasAttemptsLeft = attemptCount < retryProperties.getMaxAttempts();

        if (isRetryable && hasAttemptsLeft) {
            metrics.getHttpRetry().increment();
            log.warn("[ORACLE] Retryable error for {}: status={}, attempt {}/{}",
                    key, httpStatus, attemptCount, retryProperties.getMaxAttempts());
            requestProducer.sendOracleRequest(message);
        } else {
            if (isNonRetryableSource) {
                log.warn("[ORACLE] Non-retryable error source for {}: source={}", key, errorSourceStr);
            }
            ErrorSource source = ErrorSource.fromString(errorSourceStr);
            handlePermanentFailure(key, message, httpStatus, errorMessage, source);
        }
    }

    private void handleFailedProcessing(String key, RequestMessage message, Throwable e) {
        int attemptCount = requestStateService.incrementAttempt(key);
        if (attemptCount < retryProperties.getMaxAttempts()) {
            metrics.getHttpRetry().increment();
            log.warn("[ORACLE] Processing error for {}, attempt {}/{}, re-sending",
                    key, attemptCount, retryProperties.getMaxAttempts());
            requestProducer.sendOracleRequest(message);
        } else {
            handlePermanentFailure(key, message, 0, e.getMessage(), ErrorSource.SYSTEM);
        }
    }

    private void handlePermanentFailure(String key, RequestMessage message,
            int httpStatus, String errorMessage, ErrorSource source) {
        log.error("[ORACLE] Request failed permanently: {} - status={}, error={}, source={}",
                key, httpStatus, errorMessage, source);

        ResponseMessage errorResponse = ResponseMessage.builder()
                .companyId(message.getCompanyId())
                .requestId(message.getRequestId())
                .httpStatus(httpStatus)
                .errorMessage(errorMessage)
                .processedAt(LocalDateTime.now())
                .build();
        requestProducer.sendOracleResponse(errorResponse);

        int attemptCount = requestStateService.getAttemptCount(key);
        DlqMessage dlqMessage = DlqMessage.from(
                message, errorMessage, source.name(), httpStatus, attemptCount,
                kafkaProperties.getTopics().getOracleRequest()
        );
        requestProducer.sendToOracleDlq(dlqMessage);

        metrics.getDlqSent().increment();
        requestStateService.updateStatus(key, RequestStatus.FAILED);
    }

    private boolean isMessageExpired(RequestMessage message) {
        if (message.getCreatedAt() == null) return false;
        long ageMs = Duration.between(message.getCreatedAt(), LocalDateTime.now()).toMillis();
        return ageMs > retryProperties.getMaxMessageAgeMs();
    }

    private boolean isTimeoutException(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return e instanceof java.util.concurrent.TimeoutException
                || e.getCause() instanceof java.util.concurrent.TimeoutException
                || e.getCause() instanceof java.net.SocketTimeoutException
                || msg.contains("timeout") || msg.contains("timed out");
    }
}
```

- [ ] **Step 2: Create OracleResponseConsumer**

Create `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleResponseConsumer.java`:

```java
package uz.greenwhite.gateway.kafka.consumer.oracle;

import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.enums.RequestStatus;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.model.ResponseSaveRequest;
import uz.greenwhite.gateway.source.ResponseSinkClient;
import uz.greenwhite.gateway.state.RequestStateService;
import uz.greenwhite.gateway.storage.ResponseStorageService;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class OracleResponseConsumer {

    private final ResponseSinkClient responseSinkClient;
    private final RequestStateService requestStateService;
    private final RetryProperties retryProperties;
    private final GatewayMetrics metrics;
    private final ResponseStorageService responseStorageService;

    public OracleResponseConsumer(
            ResponseSinkClient responseSinkClient,
            RequestStateService requestStateService,
            RetryProperties retryProperties,
            GatewayMetrics metrics,
            ResponseStorageService responseStorageService) {
        this.responseSinkClient = responseSinkClient;
        this.requestStateService = requestStateService;
        this.retryProperties = retryProperties;
        this.metrics = metrics;
        this.responseStorageService = responseStorageService;
    }

    @KafkaListener(
            id = "oracleResponseConsumer",
            topics = "${gateway.kafka.topics.oracle-response}",
            groupId = "${gateway.kafka.group-id}",
            containerFactory = "oracleResponseConsumerFactory"
    )
    public void consumeResponse(ConsumerRecord<String, ResponseMessage> record, Acknowledgment ack) {
        String key = record.key();
        ResponseMessage message = record.value();

        log.info("[ORACLE] Received response: {} [partition={}, offset={}]",
                key, record.partition(), record.offset());

        try {
            // RocksDB — always save if body present
            if (message.getBody() != null) {
                responseStorageService.saveResponse(
                        message.getCompositeId(), message.getBody(),
                        message.getHttpStatus(), message.getContentType());
                requestStateService.updateHasResult(message.getCompositeId(), true);
            }

            // E5: Save to Oracle/Biruni with retry
            Timer.Sample saveSample = Timer.start(metrics.getRegistry());
            boolean saved = saveWithRetry(message);
            saveSample.stop(metrics.getOracleSaveTimer());

            if (saved) {
                metrics.getOracleSaveSuccess().increment();
                requestStateService.updateStatus(key, RequestStatus.COMPLETED);
                log.info("[ORACLE] Response saved successfully: {}", key);
            } else {
                metrics.getOracleSaveError().increment();
                saveErrorResponse(message, "Failed to save response after " +
                        retryProperties.getMaxAttempts() + " attempts");
                requestStateService.updateStatus(key, RequestStatus.FAILED);
                log.error("[ORACLE] Failed to save response after retries: {}", key);
            }

            ack.acknowledge();

        } catch (Exception e) {
            log.error("[ORACLE] Error processing response {}: {}", key, e.getMessage(), e);
            metrics.getOracleSaveError().increment();
            saveErrorResponse(message, e.getMessage());
            requestStateService.updateStatus(key, RequestStatus.FAILED);
            ack.acknowledge();
        }
    }

    private boolean saveWithRetry(ResponseMessage message) {
        String key = message.getCompositeId();
        for (int attempt = 1; attempt <= retryProperties.getMaxAttempts(); attempt++) {
            try {
                var saveRequest = buildSaveRequest(message);
                boolean saved = responseSinkClient.saveResponse(saveRequest);
                if (saved) return true;
                log.warn("[ORACLE] Save returned false for {}: attempt {}/{}", key, attempt, retryProperties.getMaxAttempts());
            } catch (Exception e) {
                log.warn("[ORACLE] Save failed for {}: attempt {}/{} - {}", key, attempt, retryProperties.getMaxAttempts(), e.getMessage());
            }
            if (attempt < retryProperties.getMaxAttempts()) {
                metrics.getOracleSaveRetry().increment();
                sleepBeforeRetry();
            }
        }
        return false;
    }

    private void saveErrorResponse(ResponseMessage message, String errorMessage) {
        try {
            var errorRequest = ResponseSaveRequest.builder()
                    .companyId(message.getCompanyId())
                    .requestId(message.getRequestId())
                    .response(null)
                    .errorMessage(errorMessage)
                    .build();
            responseSinkClient.saveResponse(errorRequest);
        } catch (Exception e) {
            log.error("[ORACLE] Failed to save error response: {} - {}", message.getCompositeId(), e.getMessage());
        }
    }

    private ResponseSaveRequest buildSaveRequest(ResponseMessage message) {
        ResponseSaveRequest.ResponseData responseData;
        if (message.isSuccess()) {
            byte[] body = message.getBody();
            String bodyAsString = body != null ? new String(body, StandardCharsets.UTF_8) : null;
            responseData = ResponseSaveRequest.ResponseData.builder()
                    .status(message.getHttpStatus()).body(bodyAsString).build();
        } else {
            responseData = ResponseSaveRequest.ResponseData.builder()
                    .status(message.getHttpStatus()).body(message.getErrorMessage()).build();
        }
        return ResponseSaveRequest.builder()
                .companyId(message.getCompanyId())
                .requestId(message.getRequestId())
                .response(responseData)
                .errorMessage(message.getErrorMessage())
                .build();
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(retryProperties.getIntervalMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 3: Create OracleDlqConsumer**

Create `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleDlqConsumer.java`:

```java
package uz.greenwhite.gateway.kafka.consumer.oracle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.notification.NotificationService;

@Slf4j
@Service
@RequiredArgsConstructor
public class OracleDlqConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            id = "oracleDlqConsumer",
            topics = "${gateway.kafka.topics.oracle-dlq}",
            groupId = "${gateway.kafka.group-id}",
            containerFactory = "dlqConsumerFactory"
    )
    public void consumeDlq(ConsumerRecord<String, DlqMessage> record, Acknowledgment ack) {
        String key = record.key();
        DlqMessage message = record.value();

        log.info("[ORACLE-DLQ] Message received: {} [partition={}, offset={}]", key, record.partition(), record.offset());

        try {
            notificationService.sendDlqAlert(message);
        } catch (Exception e) {
            log.error("[ORACLE-DLQ] Failed to process message {}: {}", key, e.getMessage());
        }

        ack.acknowledge();
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/
git commit -m "feat: create Oracle-specific Kafka consumers

OracleRequestConsumer, OracleResponseConsumer, OracleDlqConsumer —
independent from API flow with dedicated thread pool and topics."
```

---

### Task 7: Create API consumers

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java`
- Create: `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiResponseConsumer.java`
- Create: `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiDlqConsumer.java`

- [ ] **Step 1: Create ApiRequestConsumer**

Create `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java`:

```java
package uz.greenwhite.gateway.kafka.consumer.api;

import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.http.HttpRequestService;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.enums.ErrorSource;
import uz.greenwhite.gateway.model.enums.RequestStatus;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.state.RequestStateService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class ApiRequestConsumer {

    private final HttpRequestService httpRequestService;
    private final RequestStateService requestStateService;
    private final RequestProducer requestProducer;
    private final RetryProperties retryProperties;
    private final ThreadPoolTaskExecutor httpExecutor;
    private final GatewayMetrics metrics;
    private final KafkaProperties kafkaProperties;

    public ApiRequestConsumer(
            HttpRequestService httpRequestService,
            RequestStateService requestStateService,
            RequestProducer requestProducer,
            RetryProperties retryProperties,
            @Qualifier("apiHttpExecutor") ThreadPoolTaskExecutor httpExecutor,
            GatewayMetrics metrics, KafkaProperties kafkaProperties) {
        this.httpRequestService = httpRequestService;
        this.requestStateService = requestStateService;
        this.requestProducer = requestProducer;
        this.retryProperties = retryProperties;
        this.httpExecutor = httpExecutor;
        this.metrics = metrics;
        this.kafkaProperties = kafkaProperties;
    }

    @KafkaListener(
            id = "apiRequestConsumer",
            topics = "${gateway.kafka.topics.api-request}",
            groupId = "${gateway.kafka.group-id}",
            containerFactory = "apiRequestConsumerFactory"
    )
    public void consumeRequest(ConsumerRecord<String, RequestMessage> record, Acknowledgment ack) {
        String key = record.key();
        RequestMessage message = record.value();

        log.info("[API] Received request: {} [partition={}, offset={}]",
                key, record.partition(), record.offset());

        metrics.getConsumerReceived().increment();

        try {
            if (requestStateService.isCompleted(key)) {
                log.warn("[API] Request already completed, skipping: {}", key);
                metrics.getConsumerSkippedDuplicate().increment();
                ack.acknowledge();
                return;
            }

            if (!requestStateService.tryLock(key)) {
                log.warn("[API] Request is being processed by another instance: {}", key);
                metrics.getConsumerLockFailed().increment();
                ack.acknowledge();
                return;
            }

            CompletableFuture.runAsync(() -> processRequest(key, message, ack), httpExecutor)
                    .exceptionally(throwable -> {
                        log.error("[API] Unexpected error for {}: {}", key, throwable.getMessage(), throwable);
                        requestStateService.releaseLock(key);
                        ack.acknowledge();
                        return null;
                    });

        } catch (RejectedExecutionException e) {
            log.warn("[API] Pool exhausted, re-queuing: {}", key);
            metrics.getPoolRejection().increment();
            requestStateService.releaseLock(key);
            requestProducer.sendApiRequest(message);
            ack.acknowledge();

        } catch (Exception e) {
            log.error("[API] Error submitting request {}: {}", key, e.getMessage(), e);
            requestStateService.releaseLock(key);
            ack.acknowledge();
        }
    }

    private void processRequest(String key, RequestMessage message, Acknowledgment ack) {
        if (isMessageExpired(message)) {
            long ageMs = Duration.between(message.getCreatedAt(), LocalDateTime.now()).toMillis();
            log.warn("[API] Message expired: {} (age={}ms)", key, ageMs);
            handlePermanentFailure(key, message, 0, "Message expired after " + ageMs + "ms", ErrorSource.SYSTEM);
            requestStateService.releaseLock(key);
            ack.acknowledge();
            return;
        }

        requestStateService.createInitialState(key);
        requestStateService.updateStatus(key, RequestStatus.SENT);

        Timer.Sample httpSample = Timer.start(metrics.getRegistry());

        httpRequestService.sendRequest(message)
                .timeout(Duration.ofMillis(
                        retryProperties.getIntervalMs() * retryProperties.getMaxAttempts() + 60_000))
                .subscribe(
                        response -> {
                            try {
                                httpSample.stop(metrics.getHttpRequestTimer());
                                if (response != null && response.isSuccess()) {
                                    metrics.getHttpSuccess().increment();
                                    handleSuccess(key, message, response);
                                } else {
                                    int httpStatus = response != null ? response.getHttpStatus() : 0;
                                    metrics.recordHttpResult(httpStatus);
                                    handleFailedResponse(key, message, response);
                                }
                            } catch (Exception e) {
                                log.error("[API] Error handling response for {}: {}", key, e.getMessage(), e);
                            } finally {
                                requestStateService.releaseLock(key);
                                ack.acknowledge();
                            }
                        },
                        error -> {
                            try {
                                httpSample.stop(metrics.getHttpRequestTimer());
                                if (isTimeoutException(error)) {
                                    metrics.recordHttpTimeout();
                                    log.error("[API] HTTP timeout for {}: {}", key, error.getMessage());
                                } else {
                                    metrics.getHttpError5xx().increment();
                                    log.error("[API] HTTP exception for {}: {}", key, error.getMessage());
                                }
                                handleFailedProcessing(key, message, error);
                            } catch (Exception e) {
                                log.error("[API] Error handling failure for {}: {}", key, e.getMessage(), e);
                            } finally {
                                requestStateService.releaseLock(key);
                                ack.acknowledge();
                            }
                        }
                );
    }

    private void handleSuccess(String key, RequestMessage request, ResponseMessage response) {
        try {
            requestProducer.sendApiResponse(response).get(10, java.util.concurrent.TimeUnit.SECONDS);
            requestStateService.updateStatus(key, RequestStatus.COMPLETED);
            log.info("[API] Request processed successfully: {}", key);
        } catch (Exception e) {
            log.error("[API] Failed to send response to Kafka for {}: {}", key, e.getMessage());
            DlqMessage dlqMessage = DlqMessage.builder()
                    .companyId(response.getCompanyId())
                    .requestId(response.getRequestId())
                    .originalTopic(kafkaProperties.getTopics().getApiResponse())
                    .failureReason("Response too large for Kafka: " + e.getMessage())
                    .errorSource("KAFKA")
                    .httpStatus(response.getHttpStatus())
                    .attemptCount(1)
                    .url(request.getMethod() + " " + request.getBaseUrl()
                            + (request.getUri() != null ? request.getUri() : ""))
                    .failedAt(LocalDateTime.now())
                    .build();
            requestProducer.sendToApiDlq(dlqMessage);
            requestStateService.updateStatus(key, RequestStatus.FAILED);
        }
    }

    private void handleFailedResponse(String key, RequestMessage message, ResponseMessage response) {
        int attemptCount = requestStateService.incrementAttempt(key);
        int httpStatus = response != null ? response.getHttpStatus() : 0;
        String errorMessage = response != null ? response.getErrorMessage() : "Unknown error";

        String errorSourceStr = response != null ? response.getErrorSource() : null;
        boolean isNonRetryableSource = "CIRCUIT_BREAKER".equals(errorSourceStr)
                || "OAUTH2".equals(errorSourceStr);

        boolean isRetryable = retryProperties.isRetryable(httpStatus) && !isNonRetryableSource;
        boolean hasAttemptsLeft = attemptCount < retryProperties.getMaxAttempts();

        if (isRetryable && hasAttemptsLeft) {
            metrics.getHttpRetry().increment();
            log.warn("[API] Retryable error for {}: status={}, attempt {}/{}",
                    key, httpStatus, attemptCount, retryProperties.getMaxAttempts());
            requestProducer.sendApiRequest(message);
        } else {
            if (isNonRetryableSource) {
                log.warn("[API] Non-retryable error source for {}: source={}", key, errorSourceStr);
            }
            ErrorSource source = ErrorSource.fromString(errorSourceStr);
            handlePermanentFailure(key, message, httpStatus, errorMessage, source);
        }
    }

    private void handleFailedProcessing(String key, RequestMessage message, Throwable e) {
        int attemptCount = requestStateService.incrementAttempt(key);
        if (attemptCount < retryProperties.getMaxAttempts()) {
            metrics.getHttpRetry().increment();
            log.warn("[API] Processing error for {}, attempt {}/{}, re-sending",
                    key, attemptCount, retryProperties.getMaxAttempts());
            requestProducer.sendApiRequest(message);
        } else {
            handlePermanentFailure(key, message, 0, e.getMessage(), ErrorSource.SYSTEM);
        }
    }

    private void handlePermanentFailure(String key, RequestMessage message,
            int httpStatus, String errorMessage, ErrorSource source) {
        log.error("[API] Request failed permanently: {} - status={}, error={}, source={}",
                key, httpStatus, errorMessage, source);

        ResponseMessage errorResponse = ResponseMessage.builder()
                .companyId(message.getCompanyId())
                .requestId(message.getRequestId())
                .httpStatus(httpStatus)
                .errorMessage(errorMessage)
                .processedAt(LocalDateTime.now())
                .build();
        requestProducer.sendApiResponse(errorResponse);

        int attemptCount = requestStateService.getAttemptCount(key);
        DlqMessage dlqMessage = DlqMessage.from(
                message, errorMessage, source.name(), httpStatus, attemptCount,
                kafkaProperties.getTopics().getApiRequest()
        );
        requestProducer.sendToApiDlq(dlqMessage);

        metrics.getDlqSent().increment();
        requestStateService.updateStatus(key, RequestStatus.FAILED);
    }

    private boolean isMessageExpired(RequestMessage message) {
        if (message.getCreatedAt() == null) return false;
        long ageMs = Duration.between(message.getCreatedAt(), LocalDateTime.now()).toMillis();
        return ageMs > retryProperties.getMaxMessageAgeMs();
    }

    private boolean isTimeoutException(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return e instanceof java.util.concurrent.TimeoutException
                || e.getCause() instanceof java.util.concurrent.TimeoutException
                || e.getCause() instanceof java.net.SocketTimeoutException
                || msg.contains("timeout") || msg.contains("timed out");
    }
}
```

- [ ] **Step 2: Create ApiResponseConsumer**

Create `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiResponseConsumer.java`:

```java
package uz.greenwhite.gateway.kafka.consumer.api;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.callback.CallbackService;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.enums.RequestStatus;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.state.RequestStateService;
import uz.greenwhite.gateway.storage.ResponseStorageService;

@Slf4j
@Service
public class ApiResponseConsumer {

    private final RequestStateService requestStateService;
    private final GatewayMetrics metrics;
    private final ResponseStorageService responseStorageService;
    private final CallbackService callbackService;

    public ApiResponseConsumer(
            RequestStateService requestStateService,
            GatewayMetrics metrics,
            ResponseStorageService responseStorageService,
            CallbackService callbackService) {
        this.requestStateService = requestStateService;
        this.metrics = metrics;
        this.responseStorageService = responseStorageService;
        this.callbackService = callbackService;
    }

    @KafkaListener(
            id = "apiResponseConsumer",
            topics = "${gateway.kafka.topics.api-response}",
            groupId = "${gateway.kafka.group-id}",
            containerFactory = "apiResponseConsumerFactory"
    )
    public void consumeResponse(ConsumerRecord<String, ResponseMessage> record, Acknowledgment ack) {
        String key = record.key();
        ResponseMessage message = record.value();

        log.info("[API] Received response: {} [partition={}, offset={}]",
                key, record.partition(), record.offset());

        try {
            // RocksDB — always save if body present
            if (message.getBody() != null) {
                responseStorageService.saveResponse(
                        message.getCompositeId(), message.getBody(),
                        message.getHttpStatus(), message.getContentType());
                requestStateService.updateHasResult(message.getCompositeId(), true);
            }

            // Callback trigger
            callbackService.sendCallbackIfNeeded(message);

            // API source — RocksDB + Redis + callback only, no Oracle save
            RequestStatus status = message.isSuccess() ? RequestStatus.COMPLETED : RequestStatus.FAILED;
            requestStateService.updateStatus(key, status);
            log.info("[API] Response processed: {} -> {}", key, status);

            ack.acknowledge();

        } catch (Exception e) {
            log.error("[API] Error processing response {}: {}", key, e.getMessage(), e);
            requestStateService.updateStatus(key, RequestStatus.FAILED);
            ack.acknowledge();
        }
    }
}
```

- [ ] **Step 3: Create ApiDlqConsumer**

Create `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiDlqConsumer.java`:

```java
package uz.greenwhite.gateway.kafka.consumer.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.notification.NotificationService;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiDlqConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            id = "apiDlqConsumer",
            topics = "${gateway.kafka.topics.api-dlq}",
            groupId = "${gateway.kafka.group-id}",
            containerFactory = "dlqConsumerFactory"
    )
    public void consumeDlq(ConsumerRecord<String, DlqMessage> record, Acknowledgment ack) {
        String key = record.key();
        DlqMessage message = record.value();

        log.info("[API-DLQ] Message received: {} [partition={}, offset={}]", key, record.partition(), record.offset());

        try {
            notificationService.sendDlqAlert(message);
        } catch (Exception e) {
            log.error("[API-DLQ] Failed to process message {}: {}", key, e.getMessage());
        }

        ack.acknowledge();
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/kafka/consumer/api/
git commit -m "feat: create API-specific Kafka consumers

ApiRequestConsumer, ApiResponseConsumer, ApiDlqConsumer —
independent from Oracle flow. ApiResponseConsumer has no Oracle
save, only RocksDB + Redis + callback."
```

---

### Task 8: Update callers and delete old consumers

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/api/ProxyController.java`
- Modify: `src/main/java/uz/greenwhite/gateway/oracle/RequestPuller.java`
- Modify: `src/main/java/uz/greenwhite/gateway/controller/TestController.java`
- Delete: `src/main/java/uz/greenwhite/gateway/kafka/consumer/RequestConsumer.java`
- Delete: `src/main/java/uz/greenwhite/gateway/kafka/consumer/ResponseConsumer.java`
- Delete: `src/main/java/uz/greenwhite/gateway/kafka/consumer/DlqConsumer.java`

- [ ] **Step 1: Update ProxyController**

In `src/main/java/uz/greenwhite/gateway/api/ProxyController.java`, change line 129:

Old: `requestProducer.sendRequest(message).get(10, TimeUnit.SECONDS);`
New: `requestProducer.sendApiRequest(message).get(10, TimeUnit.SECONDS);`

- [ ] **Step 2: Update RequestPuller**

In `src/main/java/uz/greenwhite/gateway/oracle/RequestPuller.java`:

Line 121 — Old: `requestProducer.sendRequest(request).get(10, TimeUnit.SECONDS);`
New: `requestProducer.sendOracleRequest(request).get(10, TimeUnit.SECONDS);`

Line 160 — Old: `kafkaProperties.getTopics().getRequestNew()`
New: `kafkaProperties.getTopics().getOracleRequest()`

Line 162 — Old: `requestProducer.sendToDlq(dlqMessage);`
New: `requestProducer.sendToOracleDlq(dlqMessage);`

- [ ] **Step 3: Update TestController**

In `src/main/java/uz/greenwhite/gateway/controller/TestController.java`, update all `requestProducer.sendRequest()` calls to use the appropriate source method. Oracle test requests use `sendOracleRequest()`, API test requests use `sendApiRequest()`.

- [ ] **Step 4: Delete old consumers**

Delete the following files:
- `src/main/java/uz/greenwhite/gateway/kafka/consumer/RequestConsumer.java`
- `src/main/java/uz/greenwhite/gateway/kafka/consumer/ResponseConsumer.java`
- `src/main/java/uz/greenwhite/gateway/kafka/consumer/DlqConsumer.java`

- [ ] **Step 5: Verify full build**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: wire up source-specific producers, delete shared consumers

ProxyController → sendApiRequest, RequestPuller → sendOracleRequest.
Delete shared RequestConsumer, ResponseConsumer, DlqConsumer —
replaced by oracle/ and api/ specific consumers."
```

---

### Task 9: Manual verification

- [ ] **Step 1: Restart infrastructure (new topics)**

```bash
docker-compose restart redpanda
```

Wait for RedPanda to be healthy, then start the app.

- [ ] **Step 2: Verify health**

```bash
curl http://localhost:8091/actuator/health
```
Expected: `{"status":"UP",...}`

- [ ] **Step 3: Test API proxy flow**

```bash
curl -X POST http://localhost:8090/api/test/proxy
```
Expected: `{"compositeId":"0:...","status":"sent","source":"API",...}`

Wait 10s, then:
```bash
curl "http://localhost:8090/qosid/api/v1/requests/0:{ID}/status"
```
Expected: `{"requestId":"0:...","status":"COMPLETED",...}`

- [ ] **Step 4: Verify separate topics in RedPanda Console**

Open http://localhost:8082 — should see 6 topics:
- `gateway.oracle.request`, `gateway.oracle.response`, `gateway.oracle.dlq`
- `gateway.api.request`, `gateway.api.response`, `gateway.api.dlq`

- [ ] **Step 5: Verify logs show [API] prefix**

App logs should show `[API] Received request:` and `[API] Response processed:` — not generic.

- [ ] **Step 6: Test callback end-to-end**

Start callback listener, then:
```bash
curl -X POST http://localhost:8090/api/test/proxy -H "X-Callback-Url: http://localhost:9100/hook"
```
Expected: Callback received on port 9100.
