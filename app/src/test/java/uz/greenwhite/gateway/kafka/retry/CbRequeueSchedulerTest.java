package uz.greenwhite.gateway.kafka.retry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A CB-blocked message must wait out the circuit window instead of spinning through Kafka.
 * Before this class, a 30-second window cost ~1500 round trips per message and the count cap
 * (50) turned 212 live production requests into DLQ records in about one second.
 */
class CbRequeueSchedulerTest {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private RetryProperties props(int maxPending, long maxPendingBytes) {
        RetryProperties p = new RetryProperties();
        p.setCbRequeueDelayMs(30);        // short delays keep the test fast
        p.setCbRequeueMaxDelayMs(60);
        p.setCbMaxPending(maxPending);
        p.setCbMaxPendingBytes(maxPendingBytes);
        return p;
    }

    private GatewayMetrics metrics() {
        GatewayMetrics m = mock(GatewayMetrics.class);
        Counter c = mock(Counter.class);
        when(m.getCbRequeueDelayed()).thenReturn(c);
        when(m.getCbRequeueRejected()).thenReturn(c);
        when(m.getRegistry()).thenReturn(new SimpleMeterRegistry());
        return m;
    }

    @Test
    void doesNotRequeueWhileTheCircuitIsStillOpen() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicBoolean requeued = new AtomicBoolean(false);
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> open.get());

        assertThat(scheduler.schedule("1:1", "cb-x", 100, () -> requeued.set(true))).isTrue();

        Thread.sleep(200);
        assertThat(requeued).as("circuit still open — the message must keep waiting").isFalse();
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    void requeuesAsSoonAsTheCircuitCloses() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicBoolean requeued = new AtomicBoolean(false);
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> open.get());
        scheduler.schedule("1:1", "cb-x", 100, () -> requeued.set(true));

        open.set(false);

        long deadline = System.currentTimeMillis() + 2000;
        while (!requeued.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(requeued).isTrue();
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(scheduler.pendingBytes()).isZero();
    }

    @Test
    void refusesWhenTheCountBoundIsReached() {
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(2, 1_000_000), metrics(), cb -> true);

        assertThat(scheduler.schedule("1:1", "cb-x", 10, () -> {})).isTrue();
        assertThat(scheduler.schedule("1:2", "cb-x", 10, () -> {})).isTrue();
        assertThat(scheduler.schedule("1:3", "cb-x", 10, () -> {}))
                .as("beyond the bound the caller must DLQ rather than the scheduler growing")
                .isFalse();
    }

    /** A single 20 MiB REST body must not be able to pin memory just because the count is low. */
    @Test
    void refusesWhenTheByteBoundIsReached() {
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(1000, 1000), metrics(), cb -> true);

        assertThat(scheduler.schedule("1:1", "cb-x", 900, () -> {})).isTrue();
        assertThat(scheduler.schedule("1:2", "cb-x", 200, () -> {})).isFalse();
    }

    /** Graceful shutdown must not sit on messages whose offsets are uncommitted. */
    @Test
    void shutdownDrainsPendingRequeuesImmediately() {
        List<String> done = new CopyOnWriteArrayList<>();
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> true);
        scheduler.schedule("1:1", "cb-x", 10, () -> done.add("1:1"));
        scheduler.schedule("1:2", "cb-x", 10, () -> done.add("1:2"));

        scheduler.shutdown();

        assertThat(done).containsExactlyInAnyOrder("1:1", "1:2");
        assertThat(scheduler.pendingCount()).isZero();
    }

    /**
     * A rebalance can redeliver a record whose previous incarnation is still parked here. The
     * fresh schedule must replace the stale entry (its Acknowledgment must never be used —
     * same rule as OrderedAckTracker.register), the byte accounting must follow the
     * replacement, and the stale runnable must never run.
     */
    @Test
    void reschedulingTheSameKeyReplacesTheStaleEntry() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        List<String> done = new CopyOnWriteArrayList<>();
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> open.get());

        scheduler.schedule("1:1", "cb-x", 300, () -> done.add("stale"));
        scheduler.schedule("1:1", "cb-x", 100, () -> done.add("fresh"));

        assertThat(scheduler.pendingCount()).isEqualTo(1);
        assertThat(scheduler.pendingBytes())
                .as("the replaced entry's bytes must not linger in the accounting")
                .isEqualTo(100);

        open.set(false);
        long deadline = System.currentTimeMillis() + 2000;
        while (done.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(150);   // give a stale timer, if one survived, the chance to misfire
        assertThat(done).containsExactly("fresh");
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(scheduler.pendingBytes()).isZero();
    }
}
