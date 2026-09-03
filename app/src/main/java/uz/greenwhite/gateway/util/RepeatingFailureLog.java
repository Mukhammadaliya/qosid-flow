package uz.greenwhite.gateway.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reporting policy for a failure that repeats on every tick of a timer, retry, or poll.
 *
 * <p>SAFETY-RULES 42 in one object. The gateway has three such paths (consumer-lag monitoring,
 * the source pull leg, DLQ alerting) and each one that logs per occurrence turns a single
 * unreachable dependency into megabytes an hour: a downed broker produced 4 257 identical ERROR
 * entries in a day, and a misconfigured pull URI wrote its upstream's 404 page every five
 * seconds. Deciding the policy here means a fourth such path cannot get it wrong differently.
 *
 * <p>The policy: report the first failure, restate every {@code repeatEvery}-th afterwards so a
 * long outage still leaves a trail, and report recovery once with the length of the outage.
 * Callers do their own logging — the logger must belong to the class that failed, not to this one.
 *
 * <p>Thread-safe. The counter is atomic because a poll path may be driven by more than one
 * scheduler thread; the worst a race can do is shift which occurrence gets reported.
 */
public final class RepeatingFailureLog {

    private final int repeatEvery;
    private final AtomicInteger consecutive = new AtomicInteger();

    /** @param repeatEvery restate the failure every Nth consecutive occurrence; must be positive. */
    public RepeatingFailureLog(int repeatEvery) {
        if (repeatEvery <= 0) {
            throw new IllegalArgumentException("repeatEvery must be positive, got " + repeatEvery);
        }
        this.repeatEvery = repeatEvery;
    }

    /**
     * Records a failure.
     *
     * @return whether this occurrence should be logged at operator level (first, then every Nth)
     */
    public boolean noteFailure() {
        int n = consecutive.incrementAndGet();
        return n == 1 || n % repeatEvery == 0;
    }

    /**
     * Records a success, closing any outage.
     *
     * @return how many consecutive failures just ended, or {@code 0} when nothing was broken —
     *         so a healthy path does not log a recovery line on every tick
     */
    public int noteSuccess() {
        return consecutive.getAndSet(0);
    }

    /** Consecutive failures so far; for the log message's "(N consecutive)" detail. */
    public int consecutive() {
        return consecutive.get();
    }
}
