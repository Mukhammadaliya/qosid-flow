package uz.greenwhite.gateway.kafka.ack;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps Kafka offset commits from running ahead of work that is still in flight.
 *
 * <p><b>The problem.</b> Both request consumers hand the record to an executor and
 * acknowledge later, from the completion callback — in completion order, not offset order.
 * With {@code AckMode.MANUAL} Spring commits the <b>highest</b> acknowledged offset per
 * partition, so acknowledging offset 105 while 100–104 are still being processed commits
 * past them. A crash or rebalance inside that window loses those records silently: they are
 * never redelivered and never reach the DLQ. Spring's own remedy, {@code asyncAcks=true},
 * cannot be used here — it rejects {@code nack()} outright
 * ({@code "nack() is not supported with out-of-order commits"}, verified in the
 * spring-kafka bytecode), and both consumers depend on {@code nack()} for back-pressure
 * (SAFETY-RULES 25 and 33).
 *
 * <p><b>The rule.</b> An {@link Acknowledgment} is handed to Spring only once no earlier
 * registered record on the same partition is still in flight. Because Spring keeps the
 * maximum, acknowledging the top of a completed run commits the whole run — so a single
 * {@code acknowledge()} on the highest contiguously-completed offset is both sufficient and
 * the cheapest option. "Contiguous" means <i>nothing earlier is pending</i>, never
 * "offset + 1": offsets are not necessarily consecutive (transaction markers, compaction).
 *
 * <p><b>Failure direction.</b> If a registered record never completes, later offsets on that
 * partition stop being committed. Processing continues; on the next restart Kafka redelivers
 * from the stuck offset and the idempotency gate absorbs the duplicates. That is
 * at-least-once — the safe direction, and the opposite of the silent loss this replaces.
 *
 * <p><b>Isolation.</b> Each consumer owns its own instance. The Oracle and API flows must
 * never share ack state (SAFETY-RULES 5) — one flow's stuck partition must not be able to
 * hold back the other's commits.
 *
 * <p>Thread-safe: registrations arrive on the listener thread, completions on executor and
 * Netty threads.
 */
@Slf4j
public class OrderedAckTracker {

    private final Map<TopicPartition, PartitionAcks> partitions = new ConcurrentHashMap<>();

    /**
     * Record that a message is in flight. Called on the listener thread, before the work is
     * handed off. Re-registering an offset replaces the previous {@link Acknowledgment}: a
     * redelivered record carries a fresh one and the stale object must never be used.
     */
    public void register(TopicPartition partition, long offset, Acknowledgment ack) {
        partitions.computeIfAbsent(partition, p -> new PartitionAcks()).register(offset, ack);
    }

    /**
     * Mark the work for this offset finished and commit as far as the contiguous run now
     * reaches. Safe to call more than once for the same offset, and safe to call for a
     * partition that has since been revoked (it is ignored).
     */
    public void complete(TopicPartition partition, long offset) {
        PartitionAcks state = partitions.get(partition);
        if (state == null) {
            // Revoked between hand-off and completion: the offset belongs to whoever owns
            // the partition now, and committing it from here would be wrong.
            log.debug("Completion for unowned partition {} offset {} ignored", partition, offset);
            return;
        }
        state.complete(offset);
    }

    /**
     * Acknowledge a record that had no asynchronous work at all (duplicate skip, pool
     * rejection, submit failure). It still has to queue behind earlier in-flight records —
     * acking it directly is the same gap-committing bug in a quieter disguise.
     */
    public void acknowledgeInOrder(TopicPartition partition, long offset, Acknowledgment ack) {
        register(partition, offset, ack);
        complete(partition, offset);
    }

    /**
     * Drop all state for partitions this consumer no longer owns. Without this, revoked
     * partitions leak entries and a stuck entry could block a partition we get back later.
     */
    public void revoke(Collection<TopicPartition> revoked) {
        for (TopicPartition partition : revoked) {
            PartitionAcks removed = partitions.remove(partition);
            if (removed != null) {
                log.debug("Dropped ack state for revoked partition {}", partition);
            }
        }
    }

    /** In-flight (registered but not yet committed) records across all partitions. */
    public int pendingCount() {
        int total = 0;
        for (PartitionAcks state : partitions.values()) {
            total += state.size();
        }
        return total;
    }

    /**
     * Per-partition ack state. A partition is owned by exactly one consumer thread, but
     * completions arrive from arbitrary threads, so every access is guarded.
     */
    private static final class PartitionAcks {

        private final TreeMap<Long, Entry> entries = new TreeMap<>();

        synchronized void register(long offset, Acknowledgment ack) {
            entries.put(offset, new Entry(ack));
        }

        synchronized int size() {
            return entries.size();
        }

        /**
         * The acknowledge call stays inside the lock on purpose: it keeps "committed offset"
         * and "completed set" a single atomic step, which is what makes the invariant
         * testable and true. Under {@code AckMode.MANUAL} {@code acknowledge()} only queues
         * the offset for the next poll cycle, so this holds the lock for a bounded,
         * non-blocking moment.
         */
        synchronized void complete(long offset) {
            Entry entry = entries.get(offset);
            if (entry == null) {
                return; // already committed, or never registered
            }
            entry.done = true;

            Acknowledgment highestContiguous = null;
            Iterator<Map.Entry<Long, Entry>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry candidate = iterator.next().getValue();
                if (!candidate.done) {
                    break; // an earlier record is still in flight — commit stops here
                }
                highestContiguous = candidate.ack;
                iterator.remove();
            }

            if (highestContiguous != null) {
                highestContiguous.acknowledge();
            }
        }
    }

    private static final class Entry {
        private final Acknowledgment ack;
        private boolean done;

        private Entry(Acknowledgment ack) {
            this.ack = ack;
        }
    }
}
