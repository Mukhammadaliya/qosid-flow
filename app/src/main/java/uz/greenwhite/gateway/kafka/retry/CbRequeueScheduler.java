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
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Parks a circuit-breaker-blocked message until its circuit will accept traffic again, then
 * hands it back to the caller to be re-queued — or, if the message ages past its deadline
 * while parked, gives it up to the caller's DLQ path.
 *
 * <p><b>Why this exists.</b> The CB-blocked branch used to re-queue immediately, so a message
 * bounced through Kafka every ~20 ms for as long as the circuit stayed open (30 s by
 * configuration). The count cap that bounded that loop ({@code cb-max-requeues}, then 50) was
 * therefore spent in about one second: on 2026-07-27 a single circuit window turned 212 live
 * production requests into DLQ records that the upstream never saw. Waiting is free; spinning
 * is not.
 *
 * <p><b>The deadline is checked on every fire, before the circuit.</b> Give-up is by TIME, and
 * the scheduler itself enforces it: each timer fire first asks the caller-supplied {@code
 * expired} predicate (the consumer's own {@code isMessageExpired}, so the registry-managed
 * {@code max-message-age-ms} is read live and never duplicated here) and only then the circuit
 * state. Without that order a circuit that never flips — {@code
 * automatic-transition-from-open-to-half-open-enabled} turned off and no unrelated traffic to
 * drive the transition, since {@code isCircuitOpen} is a deliberately passive read — would hold
 * a parked backlog and its uncommitted offsets forever. An expired message therefore leaves as
 * one DLQ record from right here, via {@code giveUpAndComplete}, even if its circuit is closed
 * by the time the timer fires: the message is already too late to be worth producing again.
 *
 * <p><b>Ack contract (SAFETY-RULES 39, 40).</b> Exactly one of {@code requeueAndComplete} and
 * {@code giveUpAndComplete} completes the Kafka record, and only after its work (the requeue
 * produce, or the DLQ hand-off) is done. Until then the offset stays uncommitted, so a crash
 * redelivers the message rather than losing it — and the idempotency gate absorbs the
 * duplicate. Commits for that partition stall for the wait; that shows up as consumer lag,
 * which is the honest signal for "we are waiting on an upstream".
 *
 * <p><b>Exactly-once outcome.</b> A parked entry has three possible exits — timer requeue,
 * timer give-up, shutdown drain — and running any two of them would double-produce or
 * double-complete, so every exit races through the pending map first: whichever thread removes
 * the entry (value-conditionally on the timer paths, atomically in {@link #shutdown()}) runs
 * its runnable, and the loser walks away. The timer's two exits cannot race each other — they
 * are exclusive branches of one fire. The byte accounting follows the same rule — adjusted only
 * by the thread that performed the map transition — so {@link #pendingBytes()} can never drift
 * from the map. A rebalance can redeliver a record whose previous incarnation is still parked;
 * the fresh {@link #schedule} replaces the stale entry and the stale runnables are deliberately
 * abandoned — their {@code Acknowledgment} must never be used (same rule as
 * {@code OrderedAckTracker#register}).
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
     * A parked message: what it costs in memory, when it is too late to bother, and how to end
     * the park — hand it back, or give it up. Each {@link #schedule} call closes over fresh
     * lambdas, so record equality is effectively instance identity — which is what the
     * conditional map removes rely on.
     */
    private record Parked(long bytes, BooleanSupplier expired,
                          Runnable requeueAndComplete, Runnable giveUpAndComplete) {}

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
     * Park a CB-blocked message until {@code circuitOpen} stops being true for {@code cbName},
     * or until {@code expired} says the message's deadline has passed — whichever a timer fire
     * sees first. Never blocks the caller.
     *
     * @param key                one message, one entry — re-scheduling the same key (redelivery
     *                           after a rebalance) replaces the stale entry
     * @param bodyBytes          held payload size, for the byte bound
     * @param expired            the CONSUMER'S own deadline check (a closure over its
     *                           {@code isMessageExpired(message)}) — never reimplemented here,
     *                           so the live-read {@code max-message-age-ms} cannot drift between
     *                           the two; re-evaluated on every fire, checked before the circuit
     * @param requeueAndComplete produce the requeue AND complete the Kafka record, in that order
     * @param giveUpAndComplete  DLQ the message (the consumer's {@code handlePermanentFailure}
     *                           path, so the DLQ record shape and metrics stay consistent) AND
     *                           complete the Kafka record, in that order
     * @return false when the pending bound is full — the caller must DLQ the message
     */
    public boolean schedule(String key, String cbName, long bodyBytes, BooleanSupplier expired,
                            Runnable requeueAndComplete, Runnable giveUpAndComplete) {
        if (pending.size() >= retryProperties.getCbMaxPending()
                || pendingBytes.get() + bodyBytes > retryProperties.getCbMaxPendingBytes()) {
            metrics.getCbRequeueRejected().increment();
            log.error("[{}] CB requeue pending bound full ({} msgs / {} bytes) — DLQ: {}",
                    flowLabel, pending.size(), pendingBytes.get(), key);
            return false;
        }
        Parked parked = new Parked(bodyBytes, expired, requeueAndComplete, giveUpAndComplete);
        Parked replaced = pending.put(key, parked);
        pendingBytes.addAndGet(bodyBytes);
        if (replaced != null) {
            // Redelivered while still parked: absorb the stale entry. Its runnables hold a
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
            // Deadline BEFORE circuit: an expired message must leave even if its circuit never
            // flips, and must not be produced again even if the circuit just closed — it is
            // already too late for the upstream to be worth another round trip.
            if (isExpired(parked, key)) {
                if (!pending.remove(key, parked)) {
                    return;     // shutdown() won the race and already ran the requeue drain
                }
                pendingBytes.addAndGet(-parked.bytes());
                log.error("[{}] parked message aged out while circuit {} was open — giving up: {}",
                        flowLabel, cbName, key);
                try {
                    parked.giveUpAndComplete().run();
                } catch (Exception e) {
                    // Never swallow silently: this thread owned an uncommitted Kafka offset.
                    // The record will be redelivered and the outer processRequest gate DLQs it.
                    log.error("[{}] give-up failed for {} — the record stays uncommitted and "
                            + "will be redelivered: {}", flowLabel, key, e.getMessage(), e);
                }
                return;
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
     * A deadline-check failure must not DLQ a live message (irreversible) and must not abandon
     * it either, so it reads as "not expired" and the entry keeps waiting — the mirror of
     * {@link #stillOpen}, which fails toward waiting for the same reason. The supplier is a
     * pure age comparison in practice, so a persistent failure here is not a real mode.
     */
    private boolean isExpired(Parked parked, String key) {
        try {
            return parked.expired().getAsBoolean();
        } catch (Exception e) {
            log.warn("[{}] expiry check failed for {} — treating as not expired: {}",
                    flowLabel, key, e.getMessage());
            return false;
        }
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
     * handover. Deliberately requeues even an entry that is already past its deadline — a
     * hand-back is reversible where a DLQ decision is not, and the outer processRequest gate
     * DLQs the expired message on redelivery. Failures are logged and swallowed — the offset is
     * still uncommitted, so the worst case is redelivery, and throwing here would abandon the
     * remaining parked messages.
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
