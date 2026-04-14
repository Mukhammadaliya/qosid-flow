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
