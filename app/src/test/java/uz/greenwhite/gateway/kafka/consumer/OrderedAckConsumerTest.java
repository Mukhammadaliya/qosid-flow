package uz.greenwhite.gateway.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.http.HttpRequestService;
import uz.greenwhite.gateway.kafka.consumer.api.ApiRequestConsumer;
import uz.greenwhite.gateway.kafka.consumer.oracle.OracleRequestConsumer;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;
import uz.greenwhite.gateway.state.RequestStateService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End of the out-of-order commit hole, checked at the consumer rather than on the tracker
 * alone: a record that finishes early must not have its offset committed while an EARLIER
 * record on the same partition is still in flight. With {@code AckMode.MANUAL} Spring commits
 * the highest acknowledged offset, so such an ack silently marks the unfinished records
 * consumed — a crash in that window loses them with no redelivery and no DLQ.
 *
 * <p>The duplicate-skip path is used as the "finishes early" case because it acknowledges
 * with no asynchronous work at all — the quietest version of the bug, and the one most
 * likely to be reintroduced by someone who assumes "nothing ran, so acking is free".</p>
 *
 * <p>Both flows are covered separately on purpose: their consumers must never be merged
 * (SAFETY-RULES 5), so the guard cannot live in one shared test either.</p>
 */
class OrderedAckConsumerTest {

    private static final String TOPIC = "gateway.request.new";
    private static final String IN_FLIGHT_KEY = "1:100";
    private static final String DUPLICATE_KEY = "1:101";

    /** Captures the submitted work instead of running it, so offset 0 stays in flight. */
    private static final class DeferredExecutor {
        private final List<Runnable> submitted = new ArrayList<>();

        ThreadPoolTaskExecutor asMock() {
            ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
            doAnswer(invocation -> {
                submitted.add(invocation.getArgument(0));
                return null;
            }).when(executor).execute(any(Runnable.class));
            return executor;
        }

        void runAll() {
            List<Runnable> copy = new ArrayList<>(submitted);
            submitted.clear();
            copy.forEach(Runnable::run);
        }
    }

    private GatewayMetrics metrics() {
        GatewayMetrics metrics = mock(GatewayMetrics.class);
        Counter counter = mock(Counter.class);
        when(metrics.getConsumerReceived()).thenReturn(counter);
        when(metrics.getConsumerSkippedDuplicate()).thenReturn(counter);
        when(metrics.getRegistry()).thenReturn(new SimpleMeterRegistry());
        when(metrics.getHttpRequestTimer()).thenReturn(mock(Timer.class));
        return metrics;
    }

    /** First record processes, second is a duplicate. */
    private RequestStateService state() {
        RequestStateService state = mock(RequestStateService.class);
        when(state.isCompleted(IN_FLIGHT_KEY)).thenReturn(false);
        when(state.tryLock(IN_FLIGHT_KEY)).thenReturn(true);
        when(state.isCompleted(DUPLICATE_KEY)).thenReturn(true);
        when(state.getState(anyString())).thenReturn(Optional.empty());
        return state;
    }

    private ConsumerRecord<String, RequestMessage> record(String key, long offset) {
        RequestMessage message = RequestMessage.builder().tenantId(1L).requestId(offset).build();
        return new ConsumerRecord<>(TOPIC, 0, offset, key, message);
    }

    // ==================== API ====================

    @Test
    void apiConsumerDoesNotCommitPastAnInFlightRecord() {
        DeferredExecutor executor = new DeferredExecutor();
        ApiRequestConsumer consumer = new ApiRequestConsumer(
                mock(HttpRequestService.class), state(), mock(RequestProducer.class),
                mock(RetryProperties.class), executor.asMock(), metrics(),
                mock(KafkaProperties.class), mock(OAuth2ProviderService.class));

        Acknowledgment inFlightAck = mock(Acknowledgment.class);
        Acknowledgment duplicateAck = mock(Acknowledgment.class);

        consumer.consumeRequest(record(IN_FLIGHT_KEY, 0L), inFlightAck);   // stays in flight
        consumer.consumeRequest(record(DUPLICATE_KEY, 1L), duplicateAck);  // finishes instantly

        verify(duplicateAck, never()).acknowledge();
        verify(inFlightAck, never()).acknowledge();

        // Offset 0 finishes (mocked HttpRequestService returns null → the async task throws →
        // the consumer's exceptionally() branch completes it). Now the run 0→1 is contiguous
        // and the top of it is committed, which commits both.
        executor.runAll();

        verify(duplicateAck).acknowledge();
    }

    // ==================== ORACLE ====================

    @Test
    void oracleConsumerDoesNotCommitPastAnInFlightRecord() {
        DeferredExecutor executor = new DeferredExecutor();
        OracleRequestConsumer consumer = new OracleRequestConsumer(
                mock(HttpRequestService.class), state(), mock(RequestProducer.class),
                mock(RetryProperties.class), executor.asMock(), metrics(),
                mock(KafkaProperties.class), mock(OAuth2ProviderService.class));

        Acknowledgment inFlightAck = mock(Acknowledgment.class);
        Acknowledgment duplicateAck = mock(Acknowledgment.class);

        consumer.consumeRequest(record(IN_FLIGHT_KEY, 0L), inFlightAck);
        consumer.consumeRequest(record(DUPLICATE_KEY, 1L), duplicateAck);

        verify(duplicateAck, never()).acknowledge();
        verify(inFlightAck, never()).acknowledge();

        executor.runAll();

        verify(duplicateAck).acknowledge();
    }

    // ==================== REBALANCE ====================

    @Test
    void revokedPartitionsStopBeingAcknowledged() {
        DeferredExecutor executor = new DeferredExecutor();
        ApiRequestConsumer consumer = new ApiRequestConsumer(
                mock(HttpRequestService.class), state(), mock(RequestProducer.class),
                mock(RetryProperties.class), executor.asMock(), metrics(),
                mock(KafkaProperties.class), mock(OAuth2ProviderService.class));

        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.consumeRequest(record(IN_FLIGHT_KEY, 0L), ack);

        consumer.onPartitionsRevoked(
                List.of(new org.apache.kafka.common.TopicPartition(TOPIC, 0)));
        executor.runAll();

        // The partition belongs to another consumer now; committing its offset from here
        // would commit work that consumer may not have done.
        verify(ack, never()).acknowledge();
    }
}
