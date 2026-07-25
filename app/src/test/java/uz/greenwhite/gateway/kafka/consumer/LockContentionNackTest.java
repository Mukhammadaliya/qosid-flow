package uz.greenwhite.gateway.kafka.consumer;

import io.micrometer.core.instrument.Counter;
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

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A held Redis lock must NOT acknowledge the message.
 *
 * <p>"Another instance is processing it" only holds in a multi-instance steady state.
 * After a restart or SIGKILL the dead instance's locks stay orphaned until the lock TTL
 * (300s) expires, so acking during that window silently discards every redelivered
 * message — no DLQ, no alert. SAFETY-RULES 25 forbids acking when the Redis gate
 * *errors*; this is the same reasoning applied to lock *contention*.
 */
class LockContentionNackTest {

    private static final String KEY = "1:2";

    private GatewayMetrics metricsWithCounters() {
        GatewayMetrics metrics = mock(GatewayMetrics.class);
        Counter counter = mock(Counter.class);
        when(metrics.getConsumerReceived()).thenReturn(counter);
        when(metrics.getConsumerLockFailed()).thenReturn(counter);
        return metrics;
    }

    private RequestStateService stateWithLockHeld() {
        RequestStateService state = mock(RequestStateService.class);
        when(state.isCompleted(KEY)).thenReturn(false);
        when(state.tryLock(KEY)).thenReturn(false);
        return state;
    }

    private ConsumerRecord<String, RequestMessage> record() {
        RequestMessage msg = RequestMessage.builder().tenantId(1L).requestId(2L).build();
        return new ConsumerRecord<>("topic", 0, 0L, KEY, msg);
    }

    @Test
    void apiConsumerNacksOnLockContention() {
        RequestStateService state = stateWithLockHeld();
        RequestProducer producer = mock(RequestProducer.class);
        Acknowledgment ack = mock(Acknowledgment.class);

        new ApiRequestConsumer(
                mock(HttpRequestService.class), state, producer, mock(RetryProperties.class),
                mock(ThreadPoolTaskExecutor.class), metricsWithCounters(),
                mock(KafkaProperties.class), mock(OAuth2ProviderService.class))
                .consumeRequest(record(), ack);

        verify(ack).nack(any(Duration.class));
        verify(ack, never()).acknowledge();
        verify(producer, never()).sendApiRequest(any());
    }

    @Test
    void oracleConsumerNacksOnLockContention() {
        RequestStateService state = stateWithLockHeld();
        RequestProducer producer = mock(RequestProducer.class);
        Acknowledgment ack = mock(Acknowledgment.class);

        new OracleRequestConsumer(
                mock(HttpRequestService.class), state, producer, mock(RetryProperties.class),
                mock(ThreadPoolTaskExecutor.class), metricsWithCounters(),
                mock(KafkaProperties.class), mock(OAuth2ProviderService.class))
                .consumeRequest(record(), ack);

        verify(ack).nack(any(Duration.class));
        verify(ack, never()).acknowledge();
        verify(producer, never()).sendOracleRequest(any());
    }
}
