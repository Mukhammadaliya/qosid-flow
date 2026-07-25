package uz.greenwhite.gateway.concurrency;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import uz.greenwhite.gateway.config.ConcurrencyProperties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A scale-up must not stop()/start() a live container by default.
 *
 * <p>The restart triggers a consumer-group rebalance; any in-flight
 * {@code ack.acknowledge()} that lands after the container stopped is a no-op, so
 * those offsets stay uncommitted and get redelivered — message loss triggered
 * precisely by high load. See SAFETY-RULES 32.
 */
class NoRestartOnScaleUpTest {

    private static final String LISTENER = "apiRequestConsumer";

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ConcurrentMessageListenerContainer<String, Object> containerAt(int concurrency) {
        ConcurrentMessageListenerContainer container = mock(ConcurrentMessageListenerContainer.class);
        when(container.getConcurrency()).thenReturn(concurrency);
        return container;
    }

    private DynamicConcurrencyManager manager(ConcurrentMessageListenerContainer<String, Object> c,
                                              ConcurrencyProperties props) {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainer(LISTENER)).thenReturn(c);
        return new DynamicConcurrencyManager(registry, props);
    }

    @Test
    void restartOnScaleUpIsDisabledByDefault() {
        assertFalse(new ConcurrencyProperties().isAllowRestartOnScaleUp());
    }

    @Test
    void scaleUpSetsConcurrencyButDoesNotRestartContainer() {
        ConcurrentMessageListenerContainer<String, Object> c = containerAt(5);
        // Lag well above scale-up-threshold (50) forces the scale-up branch.
        manager(c, new ConcurrencyProperties()).adjustConcurrency(LISTENER, 500);

        verify(c, atLeastOnce()).setConcurrency(anyInt());
        verify(c, never()).stop();
        verify(c, never()).start();
    }

    @Test
    void scaleUpRestartsOnlyWhenOperatorOptsIn() {
        ConcurrencyProperties props = new ConcurrencyProperties();
        props.setAllowRestartOnScaleUp(true);
        ConcurrentMessageListenerContainer<String, Object> c = containerAt(5);

        manager(c, props).adjustConcurrency(LISTENER, 500);

        verify(c).stop();
        verify(c).start();
    }
}
