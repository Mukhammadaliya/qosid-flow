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
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;
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
    private final OAuth2ProviderService oAuth2ProviderService;

    public ApiRequestConsumer(
            HttpRequestService httpRequestService,
            RequestStateService requestStateService,
            RequestProducer requestProducer,
            RetryProperties retryProperties,
            @Qualifier("apiHttpExecutor") ThreadPoolTaskExecutor httpExecutor,
            GatewayMetrics metrics,
            KafkaProperties kafkaProperties,
            OAuth2ProviderService oAuth2ProviderService) {
        this.httpRequestService = httpRequestService;
        this.requestStateService = requestStateService;
        this.requestProducer = requestProducer;
        this.retryProperties = retryProperties;
        this.httpExecutor = httpExecutor;
        this.metrics = metrics;
        this.kafkaProperties = kafkaProperties;
        this.oAuth2ProviderService = oAuth2ProviderService;
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

        // Idempotency gate. Redis failure here must NOT ack — nack and let Kafka
        // redeliver once Redis is back (see SAFETY-RULES: no silent drops).
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
        } catch (Exception e) {
            log.error("[API] State store unavailable for {} — nack, will redeliver: {}",
                    key, e.getMessage());
            ack.nack(Duration.ofSeconds(5));
            return;
        }

        try {
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

        if (requestStateService.getState(key).isEmpty()) {
            requestStateService.createInitialState(key);
        }
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
        int httpStatus = response != null ? response.getHttpStatus() : 0;
        String errorMessage = response != null ? response.getErrorMessage() : "Unknown error";
        String errorSourceStr = response != null ? response.getErrorSource() : null;

        if ("CIRCUIT_BREAKER".equals(errorSourceStr)) {
            metrics.getHttpRetry().increment();
            log.warn("[API] CB-blocked, requeueing without attempt increment: {}", key);
            requestProducer.sendApiRequest(message);
            return;
        }

        if (httpStatus == 401 && message.getOauth2Provider() != null
                && !requestStateService.isTokenEvicted(key)) {
            metrics.getHttpRetry().increment();
            log.warn("[API] 401 from API with OAuth2 provider — evicting token and requeuing: {} (provider={})",
                    key, message.getOauth2Provider());
            oAuth2ProviderService.evictToken(message.getOauth2Provider());
            requestStateService.markTokenEvicted(key);
            requestProducer.sendApiRequest(message);
            return;
        }

        int attemptCount = requestStateService.incrementAttempt(key);
        boolean isOAuth2 = "OAUTH2".equals(errorSourceStr);
        boolean isRetryable = retryProperties.isRetryable(httpStatus) && !isOAuth2;
        boolean hasAttemptsLeft = attemptCount < retryProperties.getMaxAttempts();

        if (isRetryable && hasAttemptsLeft) {
            metrics.getHttpRetry().increment();
            log.warn("[API] Retryable error for {}: status={}, attempt {}/{}",
                    key, httpStatus, attemptCount, retryProperties.getMaxAttempts());
            requestProducer.sendApiRequest(message);
        } else {
            if (isOAuth2) {
                log.warn("[API] Non-retryable OAuth2 error for {}", key);
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
