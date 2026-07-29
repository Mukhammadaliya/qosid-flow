package uz.greenwhite.gateway.kafka.ack;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Both request consumers hand the record to an executor and acknowledge later, from the
 * completion callback — i.e. in <b>completion</b> order, not offset order. With
 * {@code AckMode.MANUAL} Spring commits the <b>highest</b> acknowledged offset per
 * partition, so acking offset 105 while 100–104 are still in flight commits past them:
 * a crash or rebalance in that window loses those records silently — no redelivery, no DLQ.
 *
 * <p>Spring's own remedy ({@code asyncAcks=true}) is unusable here because it rejects
 * {@code nack()}, which both consumers need for back-pressure (SAFETY-RULES 25 and 33).
 * This tracker is the alternative: an acknowledgement is only ever passed to Spring when
 * <b>no earlier registered record on that partition is still in flight</b>, so the
 * committed offset can never run ahead of completed work.</p>
 */
class OrderedAckTrackerTest {

    private static final TopicPartition P0 = new TopicPartition("gateway.request.new", 0);
    private static final TopicPartition P1 = new TopicPartition("gateway.request.new", 1);

    @Test
    void completingALaterOffsetFirstDoesNotAcknowledgeAnything() {
        OrderedAckTracker tracker = new OrderedAckTracker();
        Acknowledgment a100 = mock(Acknowledgment.class);
        Acknowledgment a101 = mock(Acknowledgment.class);
        Acknowledgment a102 = mock(Acknowledgment.class);
        tracker.register(P0, 100L, a100);
        tracker.register(P0, 101L, a101);
        tracker.register(P0, 102L, a102);

        tracker.complete(P0, 102L);

        verify(a100, never()).acknowledge();
        verify(a101, never()).acknowledge();
        verify(a102, never()).acknowledge();
    }

    @Test
    void acknowledgesTheHighestContiguouslyCompletedOffset() {
        OrderedAckTracker tracker = new OrderedAckTracker();
        Acknowledgment a100 = mock(Acknowledgment.class);
        Acknowledgment a101 = mock(Acknowledgment.class);
        Acknowledgment a102 = mock(Acknowledgment.class);
        tracker.register(P0, 100L, a100);
        tracker.register(P0, 101L, a101);
        tracker.register(P0, 102L, a102);

        tracker.complete(P0, 102L);
        tracker.complete(P0, 100L);
        // 101 is still in flight, so the commit must stop at 100 even though 102 is done.
        verify(a100).acknowledge();
        verify(a102, never()).acknowledge();

        tracker.complete(P0, 101L);
        // The 101→102 run is now contiguous; acking the top commits both (Spring keeps the max).
        verify(a102).acknowledge();
    }

    /**
     * Offsets are not necessarily consecutive (transaction markers, compaction), so
     * "contiguous" must mean "nothing earlier is still pending" — never "offset + 1".
     */
    @Test
    void nonConsecutiveOffsetsStillFormAContiguousRun() {
        OrderedAckTracker tracker = new OrderedAckTracker();
        Acknowledgment a10 = mock(Acknowledgment.class);
        Acknowledgment a17 = mock(Acknowledgment.class);
        tracker.register(P0, 10L, a10);
        tracker.register(P0, 17L, a17);

        tracker.complete(P0, 10L);
        tracker.complete(P0, 17L);

        verify(a17).acknowledge();
    }

    @Test
    void partitionsDoNotBlockEachOther() {
        OrderedAckTracker tracker = new OrderedAckTracker();
        Acknowledgment stuckOnP0 = mock(Acknowledgment.class);
        Acknowledgment doneOnP1 = mock(Acknowledgment.class);
        tracker.register(P0, 1L, stuckOnP0);
        tracker.register(P1, 1L, doneOnP1);

        tracker.complete(P1, 1L);

        verify(doneOnP1).acknowledge();
        verify(stuckOnP0, never()).acknowledge();
    }

    /** The synchronous paths (duplicate skip, pool rejection) ack without any async work. */
    @Test
    void acknowledgeInOrderIsImmediateWhenNothingEarlierIsPending() {
        OrderedAckTracker tracker = new OrderedAckTracker();
        Acknowledgment ack = mock(Acknowledgment.class);

        tracker.acknowledgeInOrder(P0, 5L, ack);

        verify(ack).acknowledge();
    }

    /** ...but even those must queue behind an earlier in-flight record. */
    @Test
    void acknowledgeInOrderWaitsForAnEarlierInFlightRecord() {
        OrderedAckTracker tracker = new OrderedAckTracker();
        Acknowledgment inFlight = mock(Acknowledgment.class);
        Acknowledgment skipped = mock(Acknowledgment.class);
        tracker.register(P0, 5L, inFlight);

        tracker.acknowledgeInOrder(P0, 6L, skipped);
        verify(skipped, never()).acknowledge();

        tracker.complete(P0, 5L);
        verify(skipped).acknowledge();
    }

    @Test
    void revokedPartitionStateIsDroppedAndLaterCompletionsAreIgnored() {
        OrderedAckTracker tracker = new OrderedAckTracker();
        Acknowledgment ack = mock(Acknowledgment.class);
        tracker.register(P0, 1L, ack);

        tracker.revoke(Collections.singletonList(P0));
        tracker.complete(P0, 1L);

        // The partition belongs to another consumer now — acking it would commit
        // an offset we no longer own.
        verify(ack, never()).acknowledge();
        assertThat(tracker.pendingCount()).isZero();
    }

    /** A redelivered record arrives with a fresh Acknowledgment; the stale one must not win. */
    @Test
    void reRegisteringAnOffsetReplacesTheStaleAcknowledgment() {
        OrderedAckTracker tracker = new OrderedAckTracker();
        Acknowledgment stale = mock(Acknowledgment.class);
        Acknowledgment fresh = mock(Acknowledgment.class);
        tracker.register(P0, 7L, stale);
        tracker.register(P0, 7L, fresh);

        tracker.complete(P0, 7L);

        verify(fresh).acknowledge();
        verify(stale, never()).acknowledge();
    }

    @Test
    void completedEntriesAreReleasedSoTheTrackerDoesNotGrow() {
        OrderedAckTracker tracker = new OrderedAckTracker();
        for (long offset = 0; offset < 50; offset++) {
            tracker.register(P0, offset, mock(Acknowledgment.class));
        }
        for (long offset = 0; offset < 50; offset++) {
            tracker.complete(P0, offset);
        }

        assertThat(tracker.pendingCount()).isZero();
    }

    /**
     * The invariant under concurrency: an acknowledged offset must never exceed the
     * highest offset whose predecessors have ALL completed. Completions are driven from
     * many threads in an arbitrary order, which is exactly the production shape.
     */
    @Test
    void neverAcknowledgesAheadOfCompletedWorkUnderConcurrency() throws Exception {
        OrderedAckTracker tracker = new OrderedAckTracker();
        int count = 200;
        boolean[] completed = new boolean[count];
        List<Long> acked = Collections.synchronizedList(new ArrayList<>());

        for (long offset = 0; offset < count; offset++) {
            long captured = offset;
            tracker.register(P0, offset, () -> {
                // Snapshot the contiguous-completed frontier at ack time and assert we
                // are not past it. Reading `completed` here is safe: the tracker holds
                // the partition lock, so no other completion is mid-flight.
                int frontier = -1;
                while (frontier + 1 < count && completed[frontier + 1]) {
                    frontier++;
                }
                assertThat(captured)
                        .as("acked offset must not run ahead of contiguously completed work")
                        .isLessThanOrEqualTo((long) frontier);
                acked.add(captured);
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> shuffled = new ArrayList<>();
        for (long offset = 0; offset < count; offset++) {
            shuffled.add(offset);
        }
        // Deterministic "disorder": interleave the two halves, so later offsets finish first.
        List<Long> order = new ArrayList<>();
        for (int i = 0; i < count / 2; i++) {
            order.add(shuffled.get(count / 2 + i));
            order.add(shuffled.get(i));
        }
        for (long offset : order) {
            pool.submit(() -> {
                start.await();
                completed[(int) offset] = true;
                tracker.complete(P0, offset);
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(acked).as("the final offset must be acknowledged once everything completes")
                .contains((long) (count - 1));
        assertThat(tracker.pendingCount()).isZero();
    }
}
