package uz.greenwhite.gateway.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Mono;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.http.HttpRequestService;
import uz.greenwhite.gateway.kafka.consumer.api.ApiRequestConsumer;
import uz.greenwhite.gateway.kafka.consumer.oracle.OracleRequestConsumer;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
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

import static org.assertj.core.api.Assertions.assertThat;
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
                .method("POST").baseUrl("https://main-app.example.com")
                .uri("/b/trade/api/sm24:order_export")
                .body("{\"modified_id\":\"3035052873\"}")
                .createdAt(createdAt).build();
        return new ConsumerRecord<>(TOPIC, 0, 0L, KEY, msg);
    }

    /** Fresh, unlocked, CB-blocked, circuit reported open — the shape both flows are tested on. */
    private void stubCbBlockedPath() {
        when(state.isCompleted(KEY)).thenReturn(false);
        when(state.tryLock(KEY)).thenReturn(true);
        when(state.getState(anyString())).thenReturn(Optional.empty());
        when(state.incrementCbRequeue(KEY)).thenReturn(1);
        when(http.sendRequest(any())).thenReturn(Mono.just(cbBlocked()));
        when(http.circuitBreakerName(any())).thenReturn("cb-main-app.example.com");
        when(http.isCircuitOpen("cb-main-app.example.com")).thenAnswer(inv -> circuitOpen.get());
        when(producer.sendOracleRequest(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(producer.sendApiRequest(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private ApiRequestConsumer consumer(RetryProperties props) {
        return consumer(props, metrics());
    }

    private ApiRequestConsumer consumer(RetryProperties props, GatewayMetrics metrics) {
        stubCbBlockedPath();
        return new ApiRequestConsumer(http, state, producer, props, inlineExecutor(),
                metrics, mock(KafkaProperties.class, RETURNS_DEEP_STUBS),
                mock(OAuth2ProviderService.class), timer);
    }

    /**
     * The Oracle flow's consumer. Deliberately a separate consumer with its OWN
     * CbRequeueScheduler instance (SAFETY-RULES 5) — the two flows are never merged, so the
     * cases below are duplicated rather than parameterised over a shared abstraction.
     */
    private OracleRequestConsumer oracleConsumer(RetryProperties props) {
        return oracleConsumer(props, metrics());
    }

    private OracleRequestConsumer oracleConsumer(RetryProperties props, GatewayMetrics metrics) {
        stubCbBlockedPath();
        return new OracleRequestConsumer(http, state, producer, props, inlineExecutor(),
                metrics, mock(KafkaProperties.class, RETURNS_DEEP_STUBS),
                mock(OAuth2ProviderService.class), timer);
    }

    // ==================== API ====================

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

    /**
     * Exercises the OUTER expiry gate at the top of processRequest: the record is already
     * 6 minutes old when consumed, so it is DLQ'd before sendRequest is ever invoked. The
     * in-branch (CB) expiry check is pinned separately by
     * {@link #messageThatExpiresWhileWaitingOnTheCircuitGoesToDlqNotBackToPark()}.
     */
    @Test
    void expiredMessageGoesToDlqInsteadOfBeingParkedForever() {
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer(retryProps(100)).consumeRequest(record(LocalDateTime.now().minusMinutes(6)), ack);

        verify(producer).sendToApiDlq(any());
        verify(producer, never()).sendApiRequest(any());
        verify(ack).acknowledge();
    }

    /**
     * Exercises the IN-BRANCH expiry check inside the CIRCUIT_BREAKER branch — the give-up-by-
     * TIME property this whole change exists to introduce (the 2026-07-27 incident was a
     * give-up-by-COUNT). The message is fresh when the outer gate at the top of processRequest
     * runs, and expired by the time the CB-blocked response reaches the callback — the real
     * production shape, where age accrues while a request waits out circuit windows across
     * park→requeue→re-block cycles. Deleting the in-branch isMessageExpired block makes this
     * test fail: the message would be parked (no DLQ, no ack) instead of giving up.
     *
     * <p>Time passing is simulated deterministically: the mocked sendRequest back-dates
     * {@code createdAt} at emission time (RequestMessage is mutable), so no real sleeping and
     * no clock seam is needed. The outer gate reads the fresh value, the callback the expired
     * one.
     */
    @Test
    void messageThatExpiresWhileWaitingOnTheCircuitGoesToDlqNotBackToPark() {
        Acknowledgment ack = mock(Acknowledgment.class);
        ApiRequestConsumer consumer = consumer(retryProps(100));
        when(http.sendRequest(any())).thenAnswer(inv -> {
            RequestMessage sent = inv.getArgument(0);
            return Mono.fromSupplier(() -> {
                // Wall-clock time elapsing while the request waited on the circuit.
                sent.setCreatedAt(LocalDateTime.now().minusMinutes(6));
                return cbBlocked();
            });
        });

        consumer.consumeRequest(record(LocalDateTime.now()), ack);

        ArgumentCaptor<DlqMessage> dlq = ArgumentCaptor.forClass(DlqMessage.class);
        verify(producer).sendToApiDlq(dlq.capture());
        // The in-branch reason, not the outer gate's "Message expired after ...".
        assertThat(dlq.getValue().getFailureReason()).startsWith("Circuit breaker still open after");
        verify(producer, never()).sendApiRequest(any());
        verify(ack).acknowledge();
        verify(state).releaseLock(KEY);
    }

    /**
     * The headline property of the whole change: a message that ages out WHILE PARKED is given
     * up by the scheduler itself — one DLQ record with the CB reason, `...requeue.exhausted`
     * moves, the record is completed, and the message is never produced back to Kafka. The
     * circuit stays open for the entire test, so passing proves the give-up does not depend on
     * the circuit ever closing (the pre-5c behaviour: the parked entry would sit forever and
     * this test would time out on the DLQ verify with zero acks).
     *
     * <p>The message is fresh at consume time (so it parks instead of hitting the in-branch
     * expiry) and is back-dated after the park — RequestMessage is mutable and the scheduler's
     * expiry supplier closes over it, so the next timer fire (20–40 ms delays here) reads the
     * expired age. Deterministic: no dependency on which fire sees it, only that one does.
     */
    @Test
    void messageThatExpiresWhileParkedIsDlqdByTheSchedulerNotHandedBack() {
        Acknowledgment ack = mock(Acknowledgment.class);
        GatewayMetrics m = metrics();
        Counter exhausted = mock(Counter.class);
        when(m.getCbRequeueExhausted()).thenReturn(exhausted);
        ConsumerRecord<String, RequestMessage> rec = record(LocalDateTime.now());

        consumer(retryProps(100), m).consumeRequest(rec, ack);
        // Parked now (circuit open, record uncommitted). Age it out while it waits:
        rec.value().setCreatedAt(LocalDateTime.now().minusMinutes(6));

        ArgumentCaptor<DlqMessage> dlq = ArgumentCaptor.forClass(DlqMessage.class);
        verify(producer, timeout(2000)).sendToApiDlq(dlq.capture());
        assertThat(dlq.getValue().getFailureReason()).startsWith("Circuit breaker still open after");
        verify(exhausted, timeout(2000)).increment();
        verify(ack, timeout(2000)).acknowledge();
        verify(producer, never()).sendApiRequest(any());
        // Twice: once by the park path's finally (a parked message must not hold the lock),
        // once by the give-up callback itself (which must not lean on that finally). Deleting
        // the give-up's releaseLock leaves exactly one call and fails this verify.
        verify(state, timeout(2000).times(2)).releaseLock(KEY);
    }

    @Test
    void pendingBoundFullFallsBackToDlq() {
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer(retryProps(0)).consumeRequest(record(LocalDateTime.now()), ack);

        verify(producer).sendToApiDlq(any());
        verify(ack).acknowledge();
    }

    // ==================== ORACLE ====================
    // The same five properties, pinned separately for the Oracle flow: its consumer and its
    // CbRequeueScheduler are its own (SAFETY-RULES 5), so a shared/parameterised test would
    // let one flow's regression hide behind the other's passing assertions.

    @Test
    void oracleCbBlockedResponseIsParkedAndTheRecordStaysUncommitted() {
        Acknowledgment ack = mock(Acknowledgment.class);

        oracleConsumer(retryProps(100)).consumeRequest(record(LocalDateTime.now()), ack);

        verify(ack, never()).acknowledge();
        verify(producer, never()).sendOracleRequest(any());
        verify(state).releaseLock(KEY);   // the lock must not be held for the whole wait
    }

    @Test
    void oracleParkedMessageIsRequeuedAndCompletedOnceTheCircuitCloses() {
        Acknowledgment ack = mock(Acknowledgment.class);
        oracleConsumer(retryProps(100)).consumeRequest(record(LocalDateTime.now()), ack);

        circuitOpen.set(false);

        verify(producer, timeout(2000)).sendOracleRequest(any());
        verify(ack, timeout(2000)).acknowledge();
    }

    /**
     * Exercises the OUTER expiry gate at the top of processRequest (see the API twin). The
     * in-branch (CB) expiry check is pinned separately by
     * {@link #oracleMessageThatExpiresWhileWaitingOnTheCircuitGoesToDlqNotBackToPark()}.
     */
    @Test
    void oracleExpiredMessageGoesToDlqInsteadOfBeingParkedForever() {
        Acknowledgment ack = mock(Acknowledgment.class);

        oracleConsumer(retryProps(100)).consumeRequest(record(LocalDateTime.now().minusMinutes(6)), ack);

        verify(producer).sendToOracleDlq(any());
        verify(producer, never()).sendOracleRequest(any());
        verify(ack).acknowledge();
    }

    /**
     * Exercises the IN-BRANCH expiry check inside the Oracle CIRCUIT_BREAKER branch — the
     * give-up-by-TIME property this change exists to introduce (the 2026-07-27 incident was a
     * give-up-by-COUNT). Same deterministic technique as the API twin: the mocked sendRequest
     * back-dates {@code createdAt} at emission time, so the outer gate reads a fresh message and
     * the CB-branch check in the callback reads an expired one — no real sleeping, no clock seam.
     * Deleting the in-branch isMessageExpired block makes this test fail: the message would be
     * parked (no DLQ, no ack) instead of giving up.
     */
    @Test
    void oracleMessageThatExpiresWhileWaitingOnTheCircuitGoesToDlqNotBackToPark() {
        Acknowledgment ack = mock(Acknowledgment.class);
        OracleRequestConsumer consumer = oracleConsumer(retryProps(100));
        when(http.sendRequest(any())).thenAnswer(inv -> {
            RequestMessage sent = inv.getArgument(0);
            return Mono.fromSupplier(() -> {
                // Wall-clock time elapsing while the request waited on the circuit.
                sent.setCreatedAt(LocalDateTime.now().minusMinutes(6));
                return cbBlocked();
            });
        });

        consumer.consumeRequest(record(LocalDateTime.now()), ack);

        ArgumentCaptor<DlqMessage> dlq = ArgumentCaptor.forClass(DlqMessage.class);
        verify(producer).sendToOracleDlq(dlq.capture());
        // The in-branch reason, not the outer gate's "Message expired after ...".
        assertThat(dlq.getValue().getFailureReason()).startsWith("Circuit breaker still open after");
        verify(producer, never()).sendOracleRequest(any());
        verify(ack).acknowledge();
        verify(state).releaseLock(KEY);
    }

    /**
     * The Oracle twin of {@link #messageThatExpiresWhileParkedIsDlqdByTheSchedulerNotHandedBack}:
     * the give-up wiring is duplicated per flow (SAFETY-RULES 5), so it is pinned per flow —
     * a regression in the Oracle give-up callback must not hide behind the API test passing.
     */
    @Test
    void oracleMessageThatExpiresWhileParkedIsDlqdByTheSchedulerNotHandedBack() {
        Acknowledgment ack = mock(Acknowledgment.class);
        GatewayMetrics m = metrics();
        Counter exhausted = mock(Counter.class);
        when(m.getCbRequeueExhausted()).thenReturn(exhausted);
        ConsumerRecord<String, RequestMessage> rec = record(LocalDateTime.now());

        oracleConsumer(retryProps(100), m).consumeRequest(rec, ack);
        // Parked now (circuit open, record uncommitted). Age it out while it waits:
        rec.value().setCreatedAt(LocalDateTime.now().minusMinutes(6));

        ArgumentCaptor<DlqMessage> dlq = ArgumentCaptor.forClass(DlqMessage.class);
        verify(producer, timeout(2000)).sendToOracleDlq(dlq.capture());
        assertThat(dlq.getValue().getFailureReason()).startsWith("Circuit breaker still open after");
        verify(exhausted, timeout(2000)).increment();
        verify(ack, timeout(2000)).acknowledge();
        verify(producer, never()).sendOracleRequest(any());
        // Twice: once by the park path's finally (a parked message must not hold the lock),
        // once by the give-up callback itself (which must not lean on that finally). Deleting
        // the give-up's releaseLock leaves exactly one call and fails this verify.
        verify(state, timeout(2000).times(2)).releaseLock(KEY);
    }

    @Test
    void oraclePendingBoundFullFallsBackToDlq() {
        Acknowledgment ack = mock(Acknowledgment.class);

        oracleConsumer(retryProps(0)).consumeRequest(record(LocalDateTime.now()), ack);

        verify(producer).sendToOracleDlq(any());
        verify(ack).acknowledge();
    }

    // ==================== FLOW ISOLATION (SAFETY-RULES 5) ====================

    /**
     * The reason the CB-parking code is duplicated instead of shared: one flow's stuck circuit must
     * never spend the other flow's parking budget. Every other case here builds a single consumer,
     * so making {@code cbRequeueScheduler} static — or hoisting it to a shared bean — would leave
     * them all green while silently coupling the flows.
     *
     * <p>Both consumers are built against ONE {@code GatewayMetrics} (and therefore one real
     * {@link SimpleMeterRegistry}, exactly as in production) with room for exactly ONE parked
     * message. Oracle parks first and fills its own budget; the API message must still park. With a
     * shared pending map the API message would find the bound full and be DLQ'd instead.
     *
     * <p>The same shared registry pins the second half of the invariant: two scheduler instances
     * coexisting in one registry must each register their own gauge. A tag collision does not throw
     * in Micrometer — the first registration silently wins — so a lost {@code flow=ORACLE} gauge
     * would otherwise be invisible.
     */
    @Test
    void theTwoFlowsNeitherShareTheirParkingBudgetNorTheirGauges() {
        GatewayMetrics shared = metrics();
        MeterRegistry registry = shared.getRegistry();
        RetryProperties props = retryProps(1);   // room for exactly one parked message PER FLOW

        Acknowledgment oracleAck = mock(Acknowledgment.class);
        oracleConsumer(props, shared).consumeRequest(record(LocalDateTime.now()), oracleAck);

        Acknowledgment apiAck = mock(Acknowledgment.class);
        consumer(props, shared).consumeRequest(record(LocalDateTime.now()), apiAck);

        // Both parked: neither flow saw a full bound, so neither gave up.
        verify(producer, never()).sendToApiDlq(any());
        verify(producer, never()).sendToOracleDlq(any());
        verify(apiAck, never()).acknowledge();
        verify(oracleAck, never()).acknowledge();

        assertThat(registry.find("gateway.http.circuitbreaker.requeue.pending")
                .tag("flow", "API").gauge())
                .as("API flow gauge must survive the ORACLE registration")
                .isNotNull();
        assertThat(registry.find("gateway.http.circuitbreaker.requeue.pending")
                .tag("flow", "ORACLE").gauge())
                .as("ORACLE flow gauge must survive the API registration")
                .isNotNull();
    }
}
