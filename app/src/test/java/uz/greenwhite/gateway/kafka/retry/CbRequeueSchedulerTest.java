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

    /** Never-expiring supplier for the cases that pin behaviour other than the deadline. */
    private static final java.util.function.BooleanSupplier FRESH = () -> false;

    @Test
    void doesNotRequeueWhileTheCircuitIsStillOpen() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicBoolean requeued = new AtomicBoolean(false);
        AtomicBoolean gaveUp = new AtomicBoolean(false);
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> open.get());

        assertThat(scheduler.schedule("1:1", "cb-x", 100, FRESH,
                () -> requeued.set(true), () -> gaveUp.set(true))).isTrue();

        Thread.sleep(200);
        assertThat(requeued).as("circuit still open — the message must keep waiting").isFalse();
        assertThat(gaveUp).as("not expired — the message must not give up").isFalse();
        assertThat(scheduler.pendingCount()).as("an unexpired entry must still be re-arming").isEqualTo(1);
    }

    @Test
    void requeuesAsSoonAsTheCircuitCloses() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicBoolean requeued = new AtomicBoolean(false);
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> open.get());
        scheduler.schedule("1:1", "cb-x", 100, FRESH, () -> requeued.set(true), () -> {});

        open.set(false);

        long deadline = System.currentTimeMillis() + 2000;
        while (!requeued.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(requeued).isTrue();
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(scheduler.pendingBytes()).isZero();
    }

    /**
     * The give-up-by-TIME property lives HERE, not on the next delivery: a parked message whose
     * deadline has passed must run the give-up callback on its next timer fire even though the
     * circuit predicate still says open — otherwise a circuit that never flips (e.g.
     * automatic-transition-from-open-to-half-open-enabled turned off, no other traffic) holds
     * the message and its uncommitted offset forever.
     */
    @Test
    void expiredEntryGivesUpEvenThoughTheCircuitIsStillOpen() throws Exception {
        AtomicBoolean requeued = new AtomicBoolean(false);
        AtomicBoolean gaveUp = new AtomicBoolean(false);
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> true);

        scheduler.schedule("1:1", "cb-x", 100, () -> true,
                () -> requeued.set(true), () -> gaveUp.set(true));

        long deadline = System.currentTimeMillis() + 2000;
        while (!gaveUp.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(gaveUp).as("expired while parked — must give up without waiting on the circuit").isTrue();
        assertThat(requeued).as("a given-up message must never also be requeued").isFalse();
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(scheduler.pendingBytes()).isZero();
    }

    /**
     * The deadline must be re-evaluated on EVERY fire, not once at park time: age accrues while
     * the message waits, so an entry parked fresh must still age out if the circuit outlives it.
     */
    @Test
    void entryThatExpiresAfterParkingGivesUpOnALaterFire() throws Exception {
        AtomicBoolean expired = new AtomicBoolean(false);
        AtomicBoolean requeued = new AtomicBoolean(false);
        AtomicBoolean gaveUp = new AtomicBoolean(false);
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> true);

        scheduler.schedule("1:1", "cb-x", 100, expired::get,
                () -> requeued.set(true), () -> gaveUp.set(true));

        Thread.sleep(100);   // let at least one fresh fire re-arm first
        expired.set(true);

        long deadline = System.currentTimeMillis() + 2000;
        while (!gaveUp.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(gaveUp).as("a re-armed entry must notice a deadline that passed while parked").isTrue();
        assertThat(requeued).isFalse();
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(scheduler.pendingBytes()).isZero();
    }

    @Test
    void refusesWhenTheCountBoundIsReached() {
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(2, 1_000_000), metrics(), cb -> true);

        assertThat(scheduler.schedule("1:1", "cb-x", 10, FRESH, () -> {}, () -> {})).isTrue();
        assertThat(scheduler.schedule("1:2", "cb-x", 10, FRESH, () -> {}, () -> {})).isTrue();
        assertThat(scheduler.schedule("1:3", "cb-x", 10, FRESH, () -> {}, () -> {}))
                .as("beyond the bound the caller must DLQ rather than the scheduler growing")
                .isFalse();
    }

    /** A single 20 MiB REST body must not be able to pin memory just because the count is low. */
    @Test
    void refusesWhenTheByteBoundIsReached() {
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(1000, 1000), metrics(), cb -> true);

        assertThat(scheduler.schedule("1:1", "cb-x", 900, FRESH, () -> {}, () -> {})).isTrue();
        assertThat(scheduler.schedule("1:2", "cb-x", 200, FRESH, () -> {}, () -> {})).isFalse();
    }

    /** Graceful shutdown must not sit on messages whose offsets are uncommitted. */
    @Test
    void shutdownDrainsPendingRequeuesImmediately() {
        List<String> done = new CopyOnWriteArrayList<>();
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> true);
        scheduler.schedule("1:1", "cb-x", 10, FRESH, () -> done.add("1:1"), () -> {});
        scheduler.schedule("1:2", "cb-x", 10, FRESH, () -> done.add("1:2"), () -> {});

        scheduler.shutdown();

        assertThat(done).containsExactlyInAnyOrder("1:1", "1:2");
        assertThat(scheduler.pendingCount()).isZero();
    }

    /**
     * The drain deliberately requeues even an already-expired entry: on shutdown a produced
     * hand-back beats a DLQ decision taken with no timer left to own it, and the outer
     * processRequest gate DLQs the expired message on redelivery anyway. The long delay keeps
     * the timer from firing first, so the drain is the only thread that can act.
     */
    @Test
    void shutdownDrainRequeuesRatherThanGivesUpAnExpiredEntry() {
        RetryProperties slow = props(100, 1_000_000);
        slow.setCbRequeueDelayMs(60_000);
        AtomicBoolean requeued = new AtomicBoolean(false);
        AtomicBoolean gaveUp = new AtomicBoolean(false);
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, slow, metrics(), cb -> true);
        scheduler.schedule("1:1", "cb-x", 10, () -> true,
                () -> requeued.set(true), () -> gaveUp.set(true));

        scheduler.shutdown();

        assertThat(requeued).as("drain hands the message back — redelivery ages it out").isTrue();
        assertThat(gaveUp).as("exactly one outcome per entry: the drain already took it").isFalse();
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

        scheduler.schedule("1:1", "cb-x", 300, FRESH, () -> done.add("stale"), () -> {});
        scheduler.schedule("1:1", "cb-x", 100, FRESH, () -> done.add("fresh"), () -> {});

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
