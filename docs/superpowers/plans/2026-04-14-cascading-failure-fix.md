# Cascading Failure Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 3 root causes of cascading failure: CB retry loop, `.block()` thread exhaustion, and CallerRunsPolicy consumer blocking.

**Architecture:** Add CB/OAuth2 to ErrorSource enum, make handleFailedResponse CB-aware, replace `.block()` with `.subscribe()`, replace CallerRunsPolicy with reject+re-queue, add message age limit.

**Tech Stack:** Java 21, Spring Boot 3.3, Project Reactor, Resilience4j, Spring Kafka

---

### Task 1: Add CIRCUIT_BREAKER and OAUTH2 to ErrorSource enum

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/model/enums/ErrorSource.java`

- [ ] **Step 1: Add new enum values**

Open `src/main/java/uz/greenwhite/gateway/model/enums/ErrorSource.java` and add two new values after SYSTEM:

```java
package uz.greenwhite.gateway.model.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorSource {

    HTTP("HTTP", "External API error"),
    SYSTEM("SYSTEM", "Internal system error"),
    CIRCUIT_BREAKER("CIRCUIT_BREAKER", "Circuit breaker rejected"),
    OAUTH2("OAUTH2", "OAuth2 token error");

    private final String code;
    private final String description;

    /**
     * Parse errorSource string to enum safely.
     * Returns HTTP as default for null or unknown values.
     */
    public static ErrorSource fromString(String errorSource) {
        if (errorSource == null) return HTTP;
        try {
            return ErrorSource.valueOf(errorSource);
        } catch (IllegalArgumentException e) {
            return HTTP;
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/model/enums/ErrorSource.java
git commit -m "feat: add CIRCUIT_BREAKER and OAUTH2 to ErrorSource enum

Add new enum values to distinguish circuit breaker and OAuth2 errors
from regular HTTP errors. Add fromString() for safe parsing from
ResponseMessage.errorSource string field."
```

---

### Task 2: Add maxMessageAgeMs to RetryProperties

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/config/RetryProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add maxMessageAgeMs field to RetryProperties**

Open `src/main/java/uz/greenwhite/gateway/config/RetryProperties.java` and add the new field after `retryableStatuses`:

```java
package uz.greenwhite.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.retry")
public class RetryProperties {

    /**
     * Maximum retry attempts
     * Default: 3
     */
    private int maxAttempts = 3;

    /**
     * Interval between retries in milliseconds
     * Default: 3000 (3 seconds)
     */
    private long intervalMs = 3000;

    /**
     * Comma-separated HTTP status codes that are retryable
     * Default: 408,429,500,502,503,504
     */
    private String retryableStatuses = "408,429,500,502,503,504";

    /**
     * Maximum age of a message in milliseconds before it is considered expired.
     * Messages older than this are immediately failed (prevents infinite re-queue loops).
     * Default: 300000 (5 minutes)
     */
    private long maxMessageAgeMs = 300_000;

    /**
     * Cached set of retryable status codes, parsed once at startup
     */
    private Set<Integer> retryableStatusSet;

    @PostConstruct
    public void init() {
        this.retryableStatusSet = Collections.unmodifiableSet(
                Arrays.stream(retryableStatuses.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Integer::parseInt)
                        .collect(Collectors.toSet())
        );
    }

    /**
     * Check if HTTP status is retryable
     */
    public boolean isRetryable(int httpStatus) {
        return retryableStatusSet.contains(httpStatus);
    }
}
```

- [ ] **Step 2: Add config to application.yml**

Open `src/main/resources/application.yml`, in the `gateway.retry` section (lines 123-127), add `max-message-age-ms`:

```yaml
  # --- Retry ---
  retry:
    max-attempts: ${RETRY_MAX_ATTEMPTS:3}
    interval-ms: ${RETRY_INTERVAL_MS:3000}
    retryable-statuses: ${RETRY_STATUSES:408,429,500,502,503,504}
    max-message-age-ms: ${RETRY_MAX_MESSAGE_AGE:300000}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/config/RetryProperties.java src/main/resources/application.yml
git commit -m "feat: add max-message-age-ms config for message expiry

Messages older than this threshold (default 5 min) are immediately
failed instead of retried. Prevents messages from circulating
indefinitely in Kafka re-queue loops."
```

---

### Task 3: Add pool rejection metric to GatewayMetrics

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/metrics/GatewayMetrics.java`

- [ ] **Step 1: Add poolRejection counter**

Open `src/main/java/uz/greenwhite/gateway/metrics/GatewayMetrics.java`.

Add the field declaration after `httpCircuitBreakerOpen` (after line 53):

```java
    // ==================== E4: HTTP Request ====================
    private final Timer httpRequestTimer;
    private final Counter httpSuccess;
    private final Counter httpError4xx;
    private final Counter httpError5xx;
    private final Counter httpTimeout;
    private final Counter httpRetry;
    private final Counter httpCircuitBreakerOpen;
    private final Counter poolRejection;
```

Add the counter registration in the constructor, after the `httpCircuitBreakerOpen` registration (after line 194):

```java
        this.httpCircuitBreakerOpen = Counter.builder("gateway.http.circuitbreaker.rejected")
                .description("Requests rejected by circuit breaker")
                .tag("stage", "http_request")
                .register(registry);

        this.poolRejection = Counter.builder("gateway.consumer.pool.rejection")
                .description("Tasks rejected by HTTP thread pool (pool exhausted)")
                .tag("stage", "consumer_process")
                .register(registry);
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/metrics/GatewayMetrics.java
git commit -m "feat: add pool rejection metric counter

Tracks how often HTTP thread pool rejects tasks due to exhaustion.
Key indicator for backpressure monitoring."
```

---

### Task 4: Replace CallerRunsPolicy with reject policy in ThreadPoolConfig

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/config/ThreadPoolConfig.java`

- [ ] **Step 1: Replace CallerRunsWithLogging with RejectWithLogging**

Replace the full file content of `src/main/java/uz/greenwhite/gateway/config/ThreadPoolConfig.java`:

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

    private final ConcurrencyProperties concurrencyProperties;

    /**
     * Dedicated thread pool for HTTP requests.
     *
     * Kafka consumer threads only receive messages and
     * delegate HTTP work to this pool.
     * Result: consumer threads stay free to accept new messages.
     *
     * IMPORTANT: Uses RejectWithLogging instead of CallerRunsPolicy.
     * CallerRunsPolicy was blocking Kafka consumer threads when pool
     * was exhausted, causing complete pipeline stall during cascading failures.
     * RejectWithLogging throws RejectedExecutionException which RequestConsumer
     * catches and re-queues the message to Kafka for later processing.
     */
    @Bean("httpRequestExecutor")
    public ThreadPoolTaskExecutor httpRequestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Core — always alive
        executor.setCorePoolSize(concurrencyProperties.getMinConcurrency());

        // Max — scales up under load
        executor.setMaxPoolSize(concurrencyProperties.getMaxConcurrency()
                * concurrencyProperties.getThreadPoolMultiplier());

        // Queue — tasks wait here when max pool is full
        executor.setQueueCapacity(concurrencyProperties.getThreadPoolQueueCapacity());

        // Idle threads are terminated after 60s
        executor.setKeepAliveSeconds(60);

        // Thread name prefix — for debugging and monitoring
        executor.setThreadNamePrefix("http-req-");

        // Rejection policy — reject task so consumer thread stays free
        executor.setRejectedExecutionHandler(new RejectWithLogging());

        // Allow core threads to timeout (saves resources)
        executor.setAllowCoreThreadTimeOut(true);

        executor.initialize();

        log.info("HTTP Request ThreadPool created: core={}, max={}, queue={}",
                executor.getCorePoolSize(),
                executor.getMaxPoolSize(),
                concurrencyProperties.getThreadPoolQueueCapacity());

        return executor;
    }

    /**
     * Custom rejection handler:
     * When queue is full — reject the task with RejectedExecutionException.
     * RequestConsumer catches this and re-queues the message to Kafka.
     * Consumer thread stays free to process new messages.
     *
     * Previous CallerRunsPolicy was running the task on the Kafka consumer thread,
     * blocking it for up to 69 seconds and causing pipeline stall.
     */
    static class RejectWithLogging implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("HTTP thread pool exhausted! Task rejected. queue={}, active={}, pool={}",
                    executor.getQueue().size(),
                    executor.getActiveCount(),
                    executor.getPoolSize());

            throw new RejectedExecutionException("HTTP thread pool exhausted — task will be re-queued");
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/config/ThreadPoolConfig.java
git commit -m "fix: replace CallerRunsPolicy with RejectWithLogging

CallerRunsPolicy was blocking Kafka consumer threads when the HTTP
thread pool was exhausted, causing complete pipeline stall during
cascading failures. RejectWithLogging throws RejectedExecutionException
which RequestConsumer catches and re-queues the message to Kafka."
```

---

### Task 5: Refactor RequestConsumer — CB-aware retry, remove .block(), handle pool rejection, message expiry

This is the core task. All 4 fixes converge in RequestConsumer.

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/consumer/RequestConsumer.java`

- [ ] **Step 1: Replace the full RequestConsumer implementation**

Replace the full file content of `src/main/java/uz/greenwhite/gateway/kafka/consumer/RequestConsumer.java`:

```java
package uz.greenwhite.gateway.kafka.consumer;

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
public class RequestConsumer {

    private final HttpRequestService httpRequestService;
    private final RequestStateService requestStateService;
    private final RequestProducer requestProducer;
    private final RetryProperties retryProperties;
    private final ThreadPoolTaskExecutor httpExecutor;
    private final GatewayMetrics metrics;
    private final KafkaProperties kafkaProperties;

    public RequestConsumer(
            HttpRequestService httpRequestService,
            RequestStateService requestStateService,
            RequestProducer requestProducer,
            RetryProperties retryProperties,
            @Qualifier("httpRequestExecutor") ThreadPoolTaskExecutor httpExecutor,
            GatewayMetrics metrics, KafkaProperties kafkaProperties) {
        this.httpRequestService = httpRequestService;
        this.requestStateService = requestStateService;
        this.requestProducer = requestProducer;
        this.retryProperties = retryProperties;
        this.httpExecutor = httpExecutor;
        this.metrics = metrics;
        this.kafkaProperties = kafkaProperties;
    }

    @KafkaListener(id = "requestConsumer", topics = "${gateway.kafka.topics.request-new}", groupId = "${spring.kafka.consumer.group-id}", containerFactory = "requestConsumerFactory")
    public void consumeRequest(ConsumerRecord<String, RequestMessage> record, Acknowledgment ack) {
        String key = record.key();
        RequestMessage message = record.value();

        log.info("Received request: {} [partition={}, offset={}]",
                key, record.partition(), record.offset());

        // ===== E3: Consumer received =====
        metrics.getConsumerReceived().increment();

        try {
            // 1. Idempotency check
            if (requestStateService.isCompleted(key)) {
                log.warn("Request already completed, skipping: {}", key);
                metrics.getConsumerSkippedDuplicate().increment();
                ack.acknowledge();
                return;
            }

            // 2. Concurrency lock
            if (!requestStateService.tryLock(key)) {
                log.warn("Request is being processed by another instance: {}", key);
                metrics.getConsumerLockFailed().increment();
                ack.acknowledge();
                return;
            }

            // 3. Delegate HTTP work to a separate thread pool
            CompletableFuture.runAsync(() -> processRequest(key, message, ack), httpExecutor)
                    .exceptionally(throwable -> {
                        log.error("Unexpected error in async submission for {}: {}",
                                key, throwable.getMessage(), throwable);
                        requestStateService.releaseLock(key);
                        ack.acknowledge();
                        return null;
                    });

        } catch (RejectedExecutionException e) {
            // Pool exhausted — re-queue message to Kafka, don't block consumer thread
            log.warn("Pool exhausted, re-queuing: {}", key);
            metrics.getPoolRejection().increment();
            requestStateService.releaseLock(key);
            requestProducer.sendRequest(message);
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Error submitting request {}: {}", key, e.getMessage(), e);
            requestStateService.releaseLock(key);
            ack.acknowledge();
        }
    }

    /**
     * Process single request (runs on httpExecutor thread).
     * Uses non-blocking .subscribe() instead of .block() to free the thread immediately.
     */
    private void processRequest(String key, RequestMessage message, Acknowledgment ack) {
        // 0. Message expiry check — prevent messages from circulating indefinitely
        if (isMessageExpired(message)) {
            long ageMs = Duration.between(message.getCreatedAt(), LocalDateTime.now()).toMillis();
            log.warn("Message expired: {} (age={}ms, limit={}ms)", key, ageMs, retryProperties.getMaxMessageAgeMs());
            handlePermanentFailure(key, message, 0, "Message expired after " + ageMs + "ms", ErrorSource.SYSTEM);
            requestStateService.releaseLock(key);
            ack.acknowledge();
            return;
        }

        // 1. Create initial state
        requestStateService.createInitialState(key);

        // 2. Update status to SENT
        requestStateService.updateStatus(key, RequestStatus.SENT);

        // ===== E4: HTTP Request with Timer =====
        Timer.Sample httpSample = Timer.start(metrics.getRegistry());

        // 3. Non-blocking HTTP request — thread is freed immediately after subscribe()
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
                                log.error("Error handling response for {}: {}", key, e.getMessage(), e);
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
                                    log.error("E4: HTTP timeout for {}: {}", key, error.getMessage());
                                } else {
                                    metrics.getHttpError5xx().increment();
                                    log.error("E4: HTTP exception for {}: {}", key, error.getMessage());
                                }

                                handleFailedProcessing(key, message, error);
                            } catch (Exception e) {
                                log.error("Error handling failure for {}: {}", key, e.getMessage(), e);
                            } finally {
                                requestStateService.releaseLock(key);
                                ack.acknowledge();
                            }
                        }
                );
    }

    private void handleSuccess(String key, RequestMessage request, ResponseMessage response) {
        try {
            requestProducer.sendResponse(response).get(10, java.util.concurrent.TimeUnit.SECONDS);
            requestStateService.updateStatus(key, RequestStatus.COMPLETED);
            log.info("Request processed successfully: {}", key);
        } catch (Exception e) {
            log.error("E4: Failed to send response to Kafka for {}: {}", key, e.getMessage());

            DlqMessage dlqMessage = DlqMessage.builder()
                    .companyId(response.getCompanyId())
                    .requestId(response.getRequestId())
                    .originalTopic(kafkaProperties.getTopics().getRequestResponse())
                    .failureReason("Response too large for Kafka: " + e.getMessage())
                    .errorSource("KAFKA")
                    .httpStatus(response.getHttpStatus())
                    .attemptCount(1)
                    .url(request.getMethod() + " " + request.getBaseUrl()
                            + (request.getUri() != null ? request.getUri() : ""))
                    .failedAt(java.time.LocalDateTime.now())
                    .build();

            requestProducer.sendToDlq(dlqMessage);
            requestStateService.updateStatus(key, RequestStatus.FAILED);
        }
    }

    /**
     * Handle failed HTTP response.
     * CB and OAuth2 errors are NOT retried — they indicate infrastructure issues,
     * not transient external API errors. Retrying them causes infinite Kafka re-queue loops.
     */
    private void handleFailedResponse(String key, RequestMessage message, ResponseMessage response) {
        int attemptCount = requestStateService.incrementAttempt(key);
        int httpStatus = response != null ? response.getHttpStatus() : 0;
        String errorMessage = response != null ? response.getErrorMessage() : "Unknown error";

        // CB and OAuth2 errors — do NOT retry, fail immediately
        String errorSourceStr = response != null ? response.getErrorSource() : null;
        boolean isNonRetryableSource = "CIRCUIT_BREAKER".equals(errorSourceStr)
                || "OAUTH2".equals(errorSourceStr);

        boolean isRetryable = retryProperties.isRetryable(httpStatus) && !isNonRetryableSource;
        boolean hasAttemptsLeft = attemptCount < retryProperties.getMaxAttempts();

        if (isRetryable && hasAttemptsLeft) {
            // ===== E4: Retry metric =====
            metrics.getHttpRetry().increment();
            log.warn("E4: Retryable error for {}: status={}, attempt {}/{}",
                    key, httpStatus, attemptCount, retryProperties.getMaxAttempts());
            requestProducer.sendRequest(message);
        } else {
            if (isNonRetryableSource) {
                log.warn("E4: Non-retryable error source for {}: source={}, status={}",
                        key, errorSourceStr, httpStatus);
            }
            ErrorSource source = ErrorSource.fromString(errorSourceStr);
            handlePermanentFailure(key, message, httpStatus, errorMessage, source);
        }
    }

    private void handleFailedProcessing(String key, RequestMessage message, Throwable e) {
        int attemptCount = requestStateService.incrementAttempt(key);

        if (attemptCount < retryProperties.getMaxAttempts()) {
            metrics.getHttpRetry().increment();
            log.warn("E4: Processing error for {}, attempt {}/{}, re-sending",
                    key, attemptCount, retryProperties.getMaxAttempts());
            requestProducer.sendRequest(message);
        } else {
            handlePermanentFailure(key, message, 0, e.getMessage(), ErrorSource.SYSTEM);
        }
    }

    private void handlePermanentFailure(String key, RequestMessage message,
            int httpStatus, String errorMessage, ErrorSource source) {
        log.error("E4: Request failed permanently: {} - status={}, error={}, source={}, body={}",
                key, httpStatus, errorMessage, source, message.getBody());

        ResponseMessage errorResponse = ResponseMessage.builder()
                .companyId(message.getCompanyId())
                .requestId(message.getRequestId())
                .httpStatus(httpStatus)
                .errorMessage(errorMessage)
                .processedAt(LocalDateTime.now())
                .build();
        requestProducer.sendResponse(errorResponse);

        int attemptCount = requestStateService.getAttemptCount(key);
        DlqMessage dlqMessage = DlqMessage.from(
                message, errorMessage, source.name(), httpStatus, attemptCount,
                kafkaProperties.getTopics().getRequestNew()
        );
        requestProducer.sendToDlq(dlqMessage);

        metrics.getDlqSent().increment();
        requestStateService.updateStatus(key, RequestStatus.FAILED);
    }

    /**
     * Check if message has exceeded maximum age.
     * Returns false if createdAt is null (backwards compatibility with old messages).
     */
    private boolean isMessageExpired(RequestMessage message) {
        if (message.getCreatedAt() == null) return false;
        long ageMs = Duration.between(message.getCreatedAt(), LocalDateTime.now()).toMillis();
        return ageMs > retryProperties.getMaxMessageAgeMs();
    }

    /**
     * Check if exception is timeout-related
     */
    private boolean isTimeoutException(Throwable e) {
        if (e == null) return false;

        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return e instanceof java.util.concurrent.TimeoutException
                || e.getCause() instanceof java.util.concurrent.TimeoutException
                || e.getCause() instanceof java.net.SocketTimeoutException
                || msg.contains("timeout")
                || msg.contains("timed out");
    }
}
```

**Key changes summary:**
1. **Fix 1 (CB retry loop):** `handleFailedResponse()` lines 180-207 — checks `response.getErrorSource()` for "CIRCUIT_BREAKER" and "OAUTH2", skips retry for these.
2. **Fix 2 (.block() removed):** `processRequest()` lines 118-159 — uses `.timeout().subscribe()` with onSuccess/onError callbacks instead of `.block()`. Thread is freed immediately.
3. **Fix 3 (Pool rejection):** `consumeRequest()` lines 96-101 — catches `RejectedExecutionException`, re-queues to Kafka without incrementing attempt count.
4. **Fix 4 (Message expiry):** `processRequest()` lines 109-116 — checks `isMessageExpired()` before processing.
5. **Lock/ack safety:** `releaseLock()` and `ack.acknowledge()` are called in `finally` blocks inside subscribe callbacks.

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Verify full build**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/kafka/consumer/RequestConsumer.java
git commit -m "fix: resolve cascading failure — CB retry loop, .block(), CallerRunsPolicy

1. CB-aware retry: Circuit Breaker and OAuth2 errors are no longer
   retried via Kafka, preventing infinite retry loops (was 4.5M retries).

2. Remove .block(): Replace blocking .block(69s) with non-blocking
   .subscribe() — threads are freed immediately after dispatching
   the HTTP request, preventing thread pool exhaustion.

3. Pool rejection handling: Catch RejectedExecutionException from
   ThreadPoolConfig's new RejectWithLogging policy. Re-queue message
   to Kafka without incrementing attempt count (backpressure without
   blocking consumer threads).

4. Message expiry: Messages older than max-message-age-ms (default
   5 min) are immediately failed, preventing infinite circulation."
```

---

### Task 6: Manual verification — start the application and test

**Files:** None (runtime verification)

- [ ] **Step 1: Start infrastructure**

Run: `docker-compose up -d`
Expected: All containers running

- [ ] **Step 2: Start S3 mock**

In a new terminal:
```bash
cd tools/s3-mock && node s3-mock.js
```
Expected: `S3 mock server running` on port 9000

- [ ] **Step 3: Start the application with dev profile**

Start from IntelliJ with the environment variables from the test plan (POLLING_ENABLED=false, TELEGRAM_ENABLED=false, MINIO/R2 settings).

Check startup log for:
```
HTTP Request ThreadPool created: core=10, max=30, queue=50
```

- [ ] **Step 4: Verify health**

Run: `curl http://localhost:8091/actuator/health`
Expected: `{"status":"UP",...}`

- [ ] **Step 5: Test normal flow still works**

Run:
```bash
curl -X POST http://localhost:8090/api/test/proxy
```
Expected: `{"compositeId":"0:...","status":"sent","source":"API",...}`

Wait 10 seconds, then check status:
```bash
curl "http://localhost:8090/qosid/api/v1/requests/0:{ID}/status"
```
Expected: `{"requestId":"0:...","status":"COMPLETED",...}`

- [ ] **Step 6: Verify new metrics registered**

Run:
```bash
curl http://localhost:8091/actuator/prometheus | grep pool_rejection
```
Expected: `gateway_consumer_pool_rejection_total 0.0`

- [ ] **Step 7: Verify pool rejection policy in log**

Check application startup logs — there should be NO `CallerRunsWithLogging` references, and the ThreadPool log should show `core=10, max=30, queue=50`.
