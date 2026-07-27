package uz.greenwhite.gateway.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Mono;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.http.HttpRequestService;
import uz.greenwhite.gateway.kafka.consumer.api.ApiRequestConsumer;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;
import uz.greenwhite.gateway.state.RequestStateService;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A CB-blocked message must be parked, not re-queued at Kafka speed, and its Kafka record must
 * NOT be completed while it is parked — otherwise the offset commits while the requeue is still
 * outstanding, which is the silent-loss shape SAFETY-RULES 39 exists to prevent.
 *
 * <p>On 2026-07-27 the old immediate-requeue path spent its 50-round-trip budget in about one
 * second and wrote 212 live production requests to the DLQ during a single 30-second circuit
 * window. These tests pin the replacement.
 */
class CbBackpressureTest {

    private static final String KEY = "100:15073141";
    private static final String TOPIC = "gateway.request.new";

    private final RequestProducer producer = mock(RequestProducer.class);
    private final RequestStateService state = mock(RequestStateService.class);
    private final HttpRequestService http = mock(HttpRequestService.class);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean circuitOpen = new AtomicBoolean(true);

    @AfterEach
    void stopTimer() {
        timer.shutdownNow();
    }

    /** Runs submitted work inline so the reactive callback executes during consumeRequest(). */
    private ThreadPoolTaskExecutor inlineExecutor() {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        return executor;
    }

    private GatewayMetrics metrics() {
        GatewayMetrics m = mock(GatewayMetrics.class);
        Counter c = mock(Counter.class);
        when(m.getConsumerReceived()).thenReturn(c);
        when(m.getHttpRetry()).thenReturn(c);
        when(m.getCbRequeueExhausted()).thenReturn(c);
        when(m.getCbRequeueDelayed()).thenReturn(c);
        when(m.getCbRequeueRejected()).thenReturn(c);
        when(m.getDlqSent()).thenReturn(c);
        when(m.getHttpRequestTimer()).thenReturn(mock(Timer.class));
        when(m.getRegistry()).thenReturn(new SimpleMeterRegistry());
        return m;
    }

    private RetryProperties retryProps(int maxPending) {
        RetryProperties p = new RetryProperties();
        p.setCbRequeueDelayMs(20);
        p.setCbRequeueMaxDelayMs(40);
        p.setCbMaxPending(maxPending);
        p.setCbMaxPendingBytes(1_000_000);
        p.setCbMaxRequeues(1000);
        p.setMaxMessageAgeMs(300_000);
        return p;
    }

    /** The synthetic 503 that HttpRequestService.buildCircuitBreakerResponse produces. */
    private ResponseMessage cbBlocked() {
        return ResponseMessage.builder()
                .tenantId(100L).requestId(15073141L)
                .httpStatus(503).errorSource("CIRCUIT_BREAKER")
                .errorMessage("Circuit breaker open").build();
    }

    private ConsumerRecord<String, RequestMessage> record(LocalDateTime createdAt) {
        RequestMessage msg = RequestMessage.builder()
                .tenantId(100L).requestId(15073141L).source("ORACLE")
                .method("POST").baseUrl("https://smartup.online")
                .uri("/b/trade/api/sm24:order_export")
                .body("{\"modified_id\":\"3035052873\"}")
                .createdAt(createdAt).build();
        return new ConsumerRecord<>(TOPIC, 0, 0L, KEY, msg);
    }

    private ApiRequestConsumer consumer(RetryProperties props) {
        when(state.isCompleted(KEY)).thenReturn(false);
        when(state.tryLock(KEY)).thenReturn(true);
        when(state.getState(anyString())).thenReturn(Optional.empty());
        when(state.incrementCbRequeue(KEY)).thenReturn(1);
        when(http.sendRequest(any())).thenReturn(Mono.just(cbBlocked()));
        when(http.circuitBreakerName(any())).thenReturn("cb-smartup.online");
        when(http.isCircuitOpen("cb-smartup.online")).thenAnswer(inv -> circuitOpen.get());
        when(producer.sendOracleRequest(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(producer.sendApiRequest(any())).thenReturn(CompletableFuture.completedFuture(null));
        return new ApiRequestConsumer(http, state, producer, props, inlineExecutor(),
                metrics(), mock(KafkaProperties.class, RETURNS_DEEP_STUBS),
                mock(OAuth2ProviderService.class), timer);
    }

    @Test
    void cbBlockedResponseIsParkedAndTheRecordStaysUncommitted() {
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer(retryProps(100)).consumeRequest(record(LocalDateTime.now()), ack);

        verify(ack, never()).acknowledge();
        verify(producer, never()).sendApiRequest(any());
        verify(state).releaseLock(KEY);   // the lock must not be held for the whole wait
    }

    @Test
    void parkedMessageIsRequeuedAndCompletedOnceTheCircuitCloses() {
        Acknowledgment ack = mock(Acknowledgment.class);
        consumer(retryProps(100)).consumeRequest(record(LocalDateTime.now()), ack);

        circuitOpen.set(false);

        verify(producer, timeout(2000)).sendApiRequest(any());
        verify(ack, timeout(2000)).acknowledge();
    }

    @Test
    void expiredMessageGoesToDlqInsteadOfBeingParkedForever() {
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer(retryProps(100)).consumeRequest(record(LocalDateTime.now().minusMinutes(6)), ack);

        verify(producer).sendToApiDlq(any());
        verify(producer, never()).sendApiRequest(any());
        verify(ack).acknowledge();
    }

    @Test
    void pendingBoundFullFallsBackToDlq() {
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer(retryProps(0)).consumeRequest(record(LocalDateTime.now()), ack);

        verify(producer).sendToApiDlq(any());
        verify(ack).acknowledge();
    }
}
