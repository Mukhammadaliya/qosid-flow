package uz.greenwhite.gateway.http;

import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.config.ApiProperties;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-host bound on concurrent in-flight HTTP calls (Phase 2 of the adaptive CB backpressure
 * plan). On 2026-07-27 a restart backlog of ~1000 requests was dispatched at ~67 req/s against
 * one upstream host; enough of those calls failed to open its circuit. Bounding OUR concurrency
 * per host lets a backlog drain at a rate the upstream can absorb, so the circuit stops opening
 * in the first place.
 *
 * <p><b>Contract, load-bearing for {@code HttpRequestService} (a protected zone):</b></p>
 * <ul>
 *   <li>{@link #tryAcquire(String)} is non-blocking — a refused permit is answered with the same
 *       CB-blocked-shaped response an open circuit produces, so Phase 1's park-and-recheck path
 *       absorbs it with no consumer changes;</li>
 *   <li>{@link #release(String)} must run exactly once per successful acquire, on every
 *       termination of the reactive chain ({@code doFinally}: complete, error, cancel). A leaked
 *       permit throttles the host forever and is invisible until saturation — hence the guards:
 *       releasing a host that was never acquired is a no-op, and an unmatched release can neither
 *       drive {@link #inFlight(String)} negative nor mint an extra permit above the limit;</li>
 *   <li>keys are circuit-breaker names ({@code cb-<host>}), i.e. derived by the same single
 *       naming rule as the circuit itself, so the limiter and the breaker always agree on what
 *       "one host" means. The map grows one entry per distinct upstream host — the same bounded
 *       cardinality the CB registry already carries.</li>
 * </ul>
 *
 * <p>DELIBERATELY shared between the Oracle and API flows (weighed against SAFETY-RULES 5): the
 * limit protects the <i>upstream host</i>, which is physically one resource no matter which flow
 * calls it — two per-flow limiters would let 2× the bound hit one host, defeating the purpose.
 * Unlike ack trackers or park budgets, this state cannot couple the flows' failure modes: every
 * permit is released when its call terminates (the HTTP timeout bounds how long that takes), so
 * one flow can slow the other's calls <i>to the same saturated host</i> — which is the intended
 * behaviour — but never stall it, hold its offsets, or leak state into it.</p>
 *
 * <p>The limit is {@code gateway.api.max-concurrent-per-host} (default 25 — rationale on the
 * property's javadoc), read once at startup; {@code <= 0} disables the bound (acquires always
 * succeed, in-flight is still counted).</p>
 */
@Component
public class HostConcurrencyLimiter {

    /**
     * Permits + an exact in-flight count per host. The {@link Semaphore} answers "is there
     * room?"; the {@link AtomicInteger} is the release guard: a CAS-decrement that refuses to go
     * below zero makes an unmatched {@code release} a no-op <i>atomically</i>, which a bare
     * {@code Semaphore} cannot do ({@code Semaphore.release()} happily mints permits above the
     * initial count, silently raising the host's capacity).
     */
    private record Slot(Semaphore permits, AtomicInteger inFlight) {
        static Slot withCapacity(int capacity) {
            return new Slot(new Semaphore(Math.max(capacity, 0)), new AtomicInteger());
        }
    }

    private final int limit;
    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    public HostConcurrencyLimiter(ApiProperties apiProperties) {
        this.limit = apiProperties.getMaxConcurrentPerHost();
    }

    /**
     * Non-blocking permit for one HTTP call to {@code host}.
     *
     * @return {@code true} and one in-flight slot taken — the caller now OWES exactly one
     *         {@link #release(String)}; {@code false} and nothing taken — the caller must not
     *         release.
     */
    public boolean tryAcquire(String host) {
        Slot slot = slots.computeIfAbsent(host, h -> Slot.withCapacity(limit));
        if (limit > 0 && !slot.permits().tryAcquire()) {
            return false;
        }
        slot.inFlight().incrementAndGet();
        return true;
    }

    /**
     * Returns one permit for {@code host}. Exactly one call per successful
     * {@link #tryAcquire(String)}; an unmatched call (unknown host, or more releases than
     * acquires) is a guarded no-op — see the class javadoc.
     */
    public void release(String host) {
        Slot slot = slots.get(host);
        if (slot == null) {
            return; // never acquired — nothing to return
        }
        while (true) {
            int current = slot.inFlight().get();
            if (current <= 0) {
                return; // unmatched release — refuse to go negative or mint a permit
            }
            if (slot.inFlight().compareAndSet(current, current - 1)) {
                if (limit > 0) {
                    slot.permits().release();
                }
                return;
            }
        }
    }

    /** Calls currently in flight toward {@code host}; {@code 0} for a host never seen. */
    public int inFlight(String host) {
        Slot slot = slots.get(host);
        return slot == null ? 0 : slot.inFlight().get();
    }
}
