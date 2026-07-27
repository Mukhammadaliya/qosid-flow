package uz.greenwhite.gateway.kafka.retry;

import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Parks a circuit-breaker-blocked message until its circuit will accept traffic again, then
 * hands it back to the caller to be re-queued.
 *
 * <p><b>Why this exists.</b> The CB-blocked branch used to re-queue immediately, so a message
 * bounced through Kafka every ~20 ms for as long as the circuit stayed open (30 s by
 * configuration). The count cap that bounded that loop ({@code cb-max-requeues}, then 50) was
 * therefore spent in about one second: on 2026-07-27 a single circuit window turned 212 live
 * production requests into DLQ records that the upstream never saw. Waiting is free; spinning
 * is not.
 *
 * <p><b>Ack contract (SAFETY-RULES 39, 40).</b> The {@code requeueAndComplete} runnable is the
 * only place the Kafka record is completed, and it runs only after the requeue is produced.
 * Until then the offset stays uncommitted, so a crash redelivers the message rather than losing
 * it — and the idempotency gate absorbs the duplicate. Commits for that partition stall for the
 * wait; that shows up as consumer lag, which is the honest signal for "we are waiting on an
 * upstream".
 *
 * <p><b>Exactly-once hand-back.</b> Running a parked runnable twice would produce the message
 * twice and complete the record twice, so every hand-back races through the pending map first:
 * whichever thread (timer or {@link #shutdown()}) removes the entry runs the runnable, and the
 * loser walks away. The byte accounting follows the same rule — adjusted only by the thread
 * that performed the map transition — so {@link #pendingBytes()} can never drift from the map.
 * A rebalance can redeliver a record whose previous incarnation is still parked; the fresh
 * {@link #schedule} replaces the stale entry and the stale runnable is deliberately abandoned —
 * its {@code Acknowledgment} must never be used (same rule as {@code OrderedAckTracker#register}).
 *
 * <p><b>Bounded.</b> Held messages cost memory, so the pending set is bounded by both count and
 * bytes; past either bound {@link #schedule} returns {@code false} and the caller must fall back
 * to DLQ. One instance per flow — Oracle and API never share this state (SAFETY-RULES 5).
 */
@Slf4j
public class CbRequeueScheduler {

    private final String flowLabel;
    private final ScheduledExecutorService scheduler;
    private final RetryProperties retryProperties;
    private final GatewayMetrics metrics;
    private final Predicate<String> circuitOpen;

    /**
     * A parked message: what it costs in memory, and how to hand it back when the wait is over.
     * Each {@link #schedule} call closes over a fresh {@code Runnable}, so record equality is
     * effectively instance identity — which is what the conditional map removes rely on.
     */
    private record Parked(long bytes, Runnable requeueAndComplete) {}

    private final Map<String, Parked> pending = new ConcurrentHashMap<>();
    private final AtomicLong pendingBytes = new AtomicLong();

    public CbRequeueScheduler(String flowLabel,
                              ScheduledExecutorService scheduler,
                              RetryProperties retryProperties,
                              GatewayMetrics metrics,
                              Predicate<String> circuitOpen) {
        this.flowLabel = flowLabel;
        this.scheduler = scheduler;
        this.retryProperties = retryProperties;
        this.metrics = metrics;
        this.circuitOpen = circuitOpen;
        List<Tag> tags = List.of(Tag.of("flow", flowLabel));
        metrics.getRegistry().gauge("gateway.http.circuitbreaker.requeue.pending",
                tags, this, CbRequeueScheduler::pendingCount);
        metrics.getRegistry().gauge("gateway.http.circuitbreaker.requeue.pending.bytes",
                tags, this, CbRequeueScheduler::pendingBytes);
    }

    /**
     * Park a CB-blocked message until {@code circuitOpen} stops being true for {@code cbName}.
     * Never blocks the caller.
     *
     * @param key                one message, one entry — re-scheduling the same key (redelivery
     *                           after a rebalance) replaces the stale entry
     * @param bodyBytes          held payload size, for the byte bound
     * @param requeueAndComplete produce the requeue AND complete the Kafka record, in that order
     * @return false when the pending bound is full — the caller must DLQ the message
     */
    public boolean schedule(String key, String cbName, long bodyBytes, Runnable requeueAndComplete) {
        if (pending.size() >= retryProperties.getCbMaxPending()
                || pendingBytes.get() + bodyBytes > retryProperties.getCbMaxPendingBytes()) {
            metrics.getCbRequeueRejected().increment();
            log.error("[{}] CB requeue pending bound full ({} msgs / {} bytes) — DLQ: {}",
                    flowLabel, pending.size(), pendingBytes.get(), key);
            return false;
        }
        Parked parked = new Parked(bodyBytes, requeueAndComplete);
        Parked replaced = pending.put(key, parked);
        pendingBytes.addAndGet(bodyBytes);
        if (replaced != null) {
            // Redelivered while still parked: absorb the stale entry. Its runnable holds a
            // stale Acknowledgment and must never run; its timer chain will find the map
            // pointing at the fresh entry and stop.
            pendingBytes.addAndGet(-replaced.bytes());
            log.warn("[{}] re-parked {} — replacing a stale parked entry (redelivery)",
                    flowLabel, key);
        }
        metrics.getCbRequeueDelayed().increment();
        arm(key, parked, cbName, retryProperties.getCbRequeueDelayMs());
        return true;
    }

    private void arm(String key, Parked parked, String cbName, long delayMs) {
        // Jitter so a burst parked in the same window does not stampede the upstream in
        // lockstep the moment the circuit closes.
        long jittered = delayMs + ThreadLocalRandom.current().nextLong(250);
        scheduler.schedule(() -> {
            if (pending.get(key) != parked) {
                return;     // drained by shutdown or replaced by a redelivery — chain ends here
            }
            if (stillOpen(cbName)) {
                long next = Math.min(delayMs * 2, retryProperties.getCbRequeueMaxDelayMs());
                log.debug("[{}] circuit {} still open, re-checking in {}ms: {}",
                        flowLabel, cbName, next, key);
                arm(key, parked, cbName, next);
                return;
            }
            if (!pending.remove(key, parked)) {
                return;     // shutdown() won the race and already ran the runnable
            }
            pendingBytes.addAndGet(-parked.bytes());
            try {
                parked.requeueAndComplete().run();
            } catch (Exception e) {
                // Never swallow silently: this thread owned an uncommitted Kafka offset.
                log.error("[{}] delayed requeue failed for {} — the record stays uncommitted and "
                        + "will be redelivered: {}", flowLabel, key, e.getMessage(), e);
            }
        }, jittered, TimeUnit.MILLISECONDS);
    }

    /**
     * A predicate failure must neither fire the requeue early (the circuit may well still be
     * open) nor abandon the message, so it reads as "still open" and the re-check simply
     * happens again after the next delay.
     */
    private boolean stillOpen(String cbName) {
        try {
            return circuitOpen.test(cbName);
        } catch (Exception e) {
            log.warn("[{}] circuit state check failed for {} — treating as still open: {}",
                    flowLabel, cbName, e.getMessage());
            return true;
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    public long pendingBytes() {
        return pendingBytes.get();
    }

    /**
     * Runs every parked requeue immediately. Called on shutdown: a parked message holds an
     * uncommitted offset, and producing it now turns a would-be redelivery into a clean
     * handover. Failures are logged and swallowed — the offset is still uncommitted, so the
     * worst case is redelivery, and throwing here would abandon the remaining parked messages.
     */
    public void shutdown() {
        log.info("[{}] draining {} parked CB requeues on shutdown", flowLabel, pending.size());
        for (String key : pending.keySet().toArray(String[]::new)) {
            Parked parked = pending.remove(key);
            if (parked == null) {
                continue;   // its timer fired first
            }
            pendingBytes.addAndGet(-parked.bytes());
            try {
                parked.requeueAndComplete().run();
            } catch (Exception e) {
                log.error("[{}] drain failed for {} — will be redelivered: {}",
                        flowLabel, key, e.getMessage());
            }
        }
    }
}
