package uz.greenwhite.gateway.kafka.consumer.api;

import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.http.HttpRequestService;
import uz.greenwhite.gateway.kafka.ack.OrderedAckTracker;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.kafka.retry.CbRequeueScheduler;
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
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Service
public class ApiRequestConsumer implements ConsumerSeekAware {

    /**
     * Acks are issued from completion callbacks, so they arrive out of offset order; this
     * keeps the committed offset from running ahead of work still in flight (SAFETY-RULES 39).
     * Its own instance — ack state is never shared with the Oracle flow (SAFETY-RULES 5), so
     * a partition stuck on one flow cannot hold back the other's commits.
     */
    private final OrderedAckTracker ackTracker = new OrderedAckTracker();

    private final HttpRequestService httpRequestService;
    private final RequestStateService requestStateService;
    private final RequestProducer requestProducer;
    private final RetryProperties retryProperties;
    private final ThreadPoolTaskExecutor httpExecutor;
    private final GatewayMetrics metrics;
    private final KafkaProperties kafkaProperties;
    private final OAuth2ProviderService oAuth2ProviderService;

    /**
     * Parks CB-blocked messages until the circuit accepts traffic again, instead of
     * re-queuing them at Kafka round-trip speed (SAFETY-RULES 23). Its own instance —
     * pending state is never shared with the Oracle flow (SAFETY-RULES 5); only the
     * underlying timer threads are shared.
     */
    private final CbRequeueScheduler cbRequeueScheduler;

    public ApiRequestConsumer(
            HttpRequestService httpRequestService,
            RequestStateService requestStateService,
            RequestProducer requestProducer,
            RetryProperties retryProperties,
            @Qualifier("apiHttpExecutor") ThreadPoolTaskExecutor httpExecutor,
            GatewayMetrics metrics,
            KafkaProperties kafkaProperties,
            OAuth2ProviderService oAuth2ProviderService,
            @Qualifier("cbRequeueTimer") ScheduledExecutorService cbRequeueTimer) {
        this.httpRequestService = httpRequestService;
        this.requestStateService = requestStateService;
        this.requestProducer = requestProducer;
        this.retryProperties = retryProperties;
        this.httpExecutor = httpExecutor;
        this.metrics = metrics;
        this.kafkaProperties = kafkaProperties;
        this.oAuth2ProviderService = oAuth2ProviderService;
        this.cbRequeueScheduler = new CbRequeueScheduler(
                "API", cbRequeueTimer, retryProperties, metrics, httpRequestService::isCircuitOpen);
    }

    /**
     * A parked message holds an uncommitted offset; draining on shutdown turns a would-be
     * redelivery into a clean handover. Runs before the {@code cbRequeueTimer} bean's own
     * shutdown (this bean depends on it) and after the Kafka listener containers have been
     * stopped ({@code SmartLifecycle} stop precedes bean destruction), so no new parkings
     * can race with the drain.
     */
    @PreDestroy
    public void shutdown() {
        cbRequeueScheduler.shutdown();
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
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        long offset = record.offset();

        log.info("[API] Received request: {} [partition={}, offset={}]",
                key, record.partition(), record.offset());

        metrics.getConsumerReceived().increment();

        // Idempotency gate. Redis failure here must NOT ack — nack and let Kafka
        // redeliver once Redis is back (see SAFETY-RULES: no silent drops).
        try {
            if (requestStateService.isCompleted(key)) {
                log.warn("[API] Request already completed, skipping: {}", key);
                metrics.getConsumerSkippedDuplicate().increment();
                // Through the tracker, not ack.acknowledge(): a duplicate is instant, but an
                // earlier record on this partition may still be in flight.
                ackTracker.acknowledgeInOrder(partition, offset, ack);
                return;
            }

            if (!requestStateService.tryLock(key)) {
                // A held lock does NOT always mean "another instance is working on it":
                // after a restart or SIGKILL the dead instance's locks stay orphaned
                // until the lock TTL (300s) expires. Acking here would silently discard
                // every message redelivered during that window (SAFETY-RULES 33), so we
                // nack and let Kafka bring it back once the holder finishes or the TTL
                // reaps the lock.
                log.warn("[API] Lock held for {} — nack, will redeliver", key);
                metrics.getConsumerLockFailed().increment();
                ack.nack(Duration.ofSeconds(10));
                return;
            }
        } catch (Exception e) {
            log.error("[API] State store unavailable for {} — nack, will redeliver: {}",
                    key, e.getMessage());
            ack.nack(Duration.ofSeconds(5));
            return;
        }

        // Registered before the hand-off, while still on the listener thread and therefore
        // still in offset order. Every ack from here on goes through the tracker.
        ackTracker.register(partition, offset, ack);

        try {
            CompletableFuture.runAsync(() -> processRequest(key, message, partition, offset), httpExecutor)
                    .exceptionally(throwable -> {
                        log.error("[API] Unexpected error for {}: {}", key, throwable.getMessage(), throwable);
                        requestStateService.releaseLock(key);
                        ackTracker.complete(partition, offset);
                        return null;
                    });

        } catch (RejectedExecutionException e) {
            log.warn("[API] Pool exhausted, re-queuing: {}", key);
            metrics.getPoolRejection().increment();
            requestStateService.releaseLock(key);
            requestProducer.sendApiRequest(message);
            ackTracker.complete(partition, offset);

        } catch (Exception e) {
            log.error("[API] Error submitting request {}: {}", key, e.getMessage(), e);
            requestStateService.releaseLock(key);
            ackTracker.complete(partition, offset);
        }
    }

    /**
     * A revoked partition's offsets belong to another consumer now: committing them from here
     * would be wrong, and keeping the entries would leak (SAFETY-RULES 39).
     */
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        ackTracker.revoke(partitions);
    }

    private void processRequest(String key, RequestMessage message, TopicPartition partition, long offset) {
        if (isMessageExpired(message)) {
            long ageMs = Duration.between(message.getCreatedAt(), LocalDateTime.now()).toMillis();
            log.warn("[API] Message expired: {} (age={}ms)", key, ageMs);
            handlePermanentFailure(key, message, 0, "Message expired after " + ageMs + "ms", ErrorSource.SYSTEM);
            requestStateService.releaseLock(key);
            ackTracker.complete(partition, offset);
            return;
        }

        if (requestStateService.getState(key).isEmpty()) {
            requestStateService.createInitialState(key, message.getSource());
        }
        requestStateService.updateStatus(key, RequestStatus.SENT);

        Timer.Sample httpSample = Timer.start(metrics.getRegistry());

        httpRequestService.sendRequest(message)
                .timeout(Duration.ofMillis(
                        retryProperties.getIntervalMs() * retryProperties.getMaxAttempts() + 60_000))
                .subscribe(
                        response -> {
                            boolean parked = false;
                            try {
                                httpSample.stop(metrics.getHttpRequestTimer());
                                if (response != null && response.isSuccess()) {
                                    metrics.getHttpSuccess().increment();
                                    handleSuccess(key, message, response);
                                } else {
                                    int httpStatus = response != null ? response.getHttpStatus() : 0;
                                    metrics.recordHttpResult(httpStatus);
                                    parked = handleFailedResponse(key, message, response, partition, offset);
                                }
                            } catch (Exception e) {
                                log.error("[API] Error handling response for {}: {}", key, e.getMessage(), e);
                            } finally {
                                // The lock is released even when parked: a parked message is not
                                // being processed, and the 300 s lock TTL must not be held for
                                // the whole wait.
                                requestStateService.releaseLock(key);
                                // A parked message's record is completed by CbRequeueScheduler once
                                // the requeue is produced — completing it here would commit the
                                // offset while the work is still outstanding (SAFETY-RULES 39).
                                if (!parked) {
                                    ackTracker.complete(partition, offset);
                                }
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
                                ackTracker.complete(partition, offset);
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
                    .tenantId(response.getTenantId())
                    .requestId(response.getRequestId())
                    .originalTopic(kafkaProperties.getTopics().getApiResponse())
                    .failureReason("Failed to publish response to Kafka: " + e.getMessage())
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

    /**
     * @return {@code true} when the message was parked by {@link CbRequeueScheduler} — the
     *         caller must then NOT complete the Kafka record; the scheduler's runnable owns it
     */
    private boolean handleFailedResponse(String key, RequestMessage message, ResponseMessage response,
            TopicPartition partition, long offset) {
        int httpStatus = response != null ? response.getHttpStatus() : 0;
        String errorMessage = response != null ? response.getErrorMessage() : "Unknown error";
        String errorSourceStr = response != null ? response.getErrorSource() : null;

        if ("CIRCUIT_BREAKER".equals(errorSourceStr)) {
            // SAFETY-RULES 23: CB-blocked is transient back-pressure — attemptCount stays
            // untouched and CIRCUIT_BREAKER is never added to isNonRetryableSource.
            //
            // Give-up is by TIME, not by round-trip count: the message is parked until the
            // circuit accepts traffic again, and isMessageExpired (max-message-age-ms, 5 min)
            // is what eventually turns a genuinely stuck message into one DLQ record. The
            // count cap below is only a runaway guard for a broken delay path.
            if (isMessageExpired(message)) {
                long ageMs = Duration.between(message.getCreatedAt(), LocalDateTime.now()).toMillis();
                metrics.getCbRequeueExhausted().increment();
                log.error("[API] CB still open and message expired (age={}ms), sending to DLQ: {}",
                        ageMs, key);
                handlePermanentFailure(key, message, httpStatus,
                        "Circuit breaker still open after " + ageMs + "ms", ErrorSource.SYSTEM);
                return false;
            }

            int requeues = requestStateService.incrementCbRequeue(key);
            if (requeues > retryProperties.getCbMaxRequeues()) {
                metrics.getCbRequeueExhausted().increment();
                log.error("[API] CB requeue runaway guard tripped ({} > {}), sending to DLQ: {}",
                        requeues, retryProperties.getCbMaxRequeues(), key);
                handlePermanentFailure(key, message, httpStatus,
                        "Circuit breaker requeue guard tripped after " + requeues + " requeues",
                        ErrorSource.SYSTEM);
                return false;
            }

            metrics.getHttpRetry().increment();
            boolean parked;
            String cbName = null;
            try {
                cbName = httpRequestService.circuitBreakerName(message);
                long bodyBytes = message.getBody() == null ? 0 : message.getBody().length();
                parked = cbRequeueScheduler.schedule(key, cbName, bodyBytes, () -> {
                    requestProducer.sendApiRequest(message);
                    ackTracker.complete(partition, offset);
                });
            } catch (Exception e) {
                // Belt-and-braces, both branches should be unreachable: circuitBreakerName
                // throws NPE only for a null baseUrl (impossible on this path — the CB
                // response is built AFTER the send path resolved a circuit from that very
                // baseUrl), and schedule() throws RejectedExecutionException only if the
                // timer is already shut down (impossible while consuming — listener
                // containers stop before bean destruction begins). Letting either escape
                // to the callback's generic catch would complete the record with the
                // message neither requeued nor DLQ'd — a silent drop, the one outcome
                // that is never acceptable. DLQ instead.
                log.error("[API] Could not park CB-blocked message {} — falling back to DLQ: {}",
                        key, e.getMessage(), e);
                parked = false;
            }
            if (parked) {
                // Deliberately NOT completing the record here: the scheduler's runnable owns it.
                log.warn("[API] CB-blocked, parked until {} accepts traffic ({}/{}): {}",
                        cbName, requeues, retryProperties.getCbMaxRequeues(), key);
                return true;
            }
            log.error("[API] CB pending bound full, sending to DLQ: {}", key);
            handlePermanentFailure(key, message, httpStatus,
                    "Circuit breaker open and requeue buffer full", ErrorSource.SYSTEM);
            return false;
        }

        if (httpStatus == 401 && message.getOauth2Provider() != null
                && !requestStateService.isTokenEvicted(key)) {
            metrics.getHttpRetry().increment();
            log.warn("[API] 401 from API with OAuth2 provider — evicting token and requeuing: {} (provider={})",
                    key, message.getOauth2Provider());
            oAuth2ProviderService.evictToken(message.getOauth2Provider());
            requestStateService.markTokenEvicted(key);
            requestProducer.sendApiRequest(message);
            return false;
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
        return false;
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
                .tenantId(message.getTenantId())
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
