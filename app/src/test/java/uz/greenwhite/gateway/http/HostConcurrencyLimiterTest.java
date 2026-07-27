package uz.greenwhite.gateway.http;

import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.config.ApiProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounds OUR concurrency toward one upstream host (Phase 2 of the adaptive CB backpressure
 * plan): the 2026-07-27 incident opened {@code cb-smartup.online} because a restart backlog was
 * dispatched at ~67 req/s against a single host. The limiter's contract is deliberately small —
 * non-blocking acquire, idempotent-safe release, observable in-flight count — because
 * {@code HttpRequestService} wires it into a protected zone and every behaviour here is
 * load-bearing there.
 */
class HostConcurrencyLimiterTest {

    private HostConcurrencyLimiter limiterWithLimit(int limit) {
        ApiProperties properties = new ApiProperties();
        properties.setMaxConcurrentPerHost(limit);
        return new HostConcurrencyLimiter(properties);
    }

    @Test
    void permitsAreExhaustedAtTheConfiguredLimit() {
        HostConcurrencyLimiter limiter = limiterWithLimit(2);

        assertThat(limiter.tryAcquire("cb-smartup.online")).isTrue();
        assertThat(limiter.tryAcquire("cb-smartup.online")).isTrue();
        assertThat(limiter.tryAcquire("cb-smartup.online")).isFalse();
        assertThat(limiter.inFlight("cb-smartup.online")).isEqualTo(2);
    }

    /** One saturated host must not throttle a healthy one — the limit is per host, not global. */
    @Test
    void hostsAreExhaustedIndependently() {
        HostConcurrencyLimiter limiter = limiterWithLimit(1);

        assertThat(limiter.tryAcquire("cb-smartup.online")).isTrue();
        assertThat(limiter.tryAcquire("cb-smartup.online")).isFalse();

        assertThat(limiter.tryAcquire("cb-regos.uz")).isTrue();
        assertThat(limiter.inFlight("cb-smartup.online")).isEqualTo(1);
        assertThat(limiter.inFlight("cb-regos.uz")).isEqualTo(1);
    }

    @Test
    void releaseReturnsThePermit() {
        HostConcurrencyLimiter limiter = limiterWithLimit(1);

        assertThat(limiter.tryAcquire("cb-smartup.online")).isTrue();
        assertThat(limiter.tryAcquire("cb-smartup.online")).isFalse();

        limiter.release("cb-smartup.online");

        assertThat(limiter.inFlight("cb-smartup.online")).isZero();
        assertThat(limiter.tryAcquire("cb-smartup.online")).isTrue();
    }

    /**
     * A release with no matching acquire must be a harmless no-op. A negative count — or worse,
     * an extra permit — would silently raise the host's capacity above the configured bound,
     * which is exactly the failure mode the limiter exists to prevent.
     */
    @Test
    void releasingANeverAcquiredHostIsANoOp() {
        HostConcurrencyLimiter limiter = limiterWithLimit(1);

        limiter.release("cb-never-acquired");

        assertThat(limiter.inFlight("cb-never-acquired")).isZero();
        // Capacity is unchanged: still exactly one permit.
        assertThat(limiter.tryAcquire("cb-never-acquired")).isTrue();
        assertThat(limiter.tryAcquire("cb-never-acquired")).isFalse();
    }

    @Test
    void overReleasingDoesNotInflateCapacityOrGoNegative() {
        HostConcurrencyLimiter limiter = limiterWithLimit(1);

        assertThat(limiter.tryAcquire("cb-smartup.online")).isTrue();
        limiter.release("cb-smartup.online");
        limiter.release("cb-smartup.online"); // double release — must be swallowed

        assertThat(limiter.inFlight("cb-smartup.online")).isZero();
        assertThat(limiter.tryAcquire("cb-smartup.online")).isTrue();
        assertThat(limiter.tryAcquire("cb-smartup.online")).isFalse(); // still ONE permit, not two
    }

    /** {@code <= 0} = unlimited — the operator escape hatch, same convention as gateway.proxy.max-body-size. */
    @Test
    void nonPositiveLimitDisablesTheBound() {
        HostConcurrencyLimiter limiter = limiterWithLimit(0);

        for (int i = 0; i < 200; i++) {
            assertThat(limiter.tryAcquire("cb-smartup.online")).isTrue();
        }
        assertThat(limiter.inFlight("cb-smartup.online")).isEqualTo(200);

        limiter.release("cb-smartup.online");
        assertThat(limiter.inFlight("cb-smartup.online")).isEqualTo(199);
    }

    @Test
    void inFlightOfAnUnknownHostIsZero() {
        assertThat(limiterWithLimit(5).inFlight("cb-never-seen")).isZero();
    }
}
