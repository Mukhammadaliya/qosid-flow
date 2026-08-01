package uz.greenwhite.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Back-pressure must not be reported as upstream failure.
 *
 * <p>An open circuit or a refused host permit returns a synthetic 503 that the upstream never
 * sent, and the message is parked and re-queued. Recording those as {@code error_5xx} let them
 * swamp the real numbers: on production 2026-08-01 the counter stood at 740 174 "5xx" against
 * 740 156 requeues, and the dashboard reported 26.8% success for a pipeline running at 99.68%.
 */
class GatewayMetricsTest {

    private MeterRegistry registry;
    private GatewayMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new GatewayMetrics(registry);
    }

    private double count(String result) {
        Counter c = registry.find("gateway.http.request.total").tag("result", result).counter();
        return c == null ? 0d : c.count();
    }

    @Test
    void deferredRequestIsNotCountedAsAServerError() {
        metrics.recordHttpResult(503, true);

        assertThat(count("deferred")).isEqualTo(1d);
        assertThat(count("error_5xx")).isZero();
        assertThat(count("success")).isZero();
    }

    @Test
    void genuine5xxIsStillCountedAsAServerError() {
        // The same status code, but this one came back from a server that was actually called.
        metrics.recordHttpResult(503, false);

        assertThat(count("error_5xx")).isEqualTo(1d);
        assertThat(count("deferred")).isZero();
    }

    @Test
    void statusCodeStillDecidesTheBucketWhenNotDeferred() {
        metrics.recordHttpResult(200, false);
        metrics.recordHttpResult(404, false);
        metrics.recordHttpResult(500, false);

        assertThat(count("success")).isEqualTo(1d);
        assertThat(count("error_4xx")).isEqualTo(1d);
        assertThat(count("error_5xx")).isEqualTo(1d);
    }

    @Test
    void theSingleArgumentOverloadKeepsItsOldMeaning() {
        // Retained for callers that never see a deferral; it must not silently start
        // classifying 503s as back-pressure.
        metrics.recordHttpResult(503);

        assertThat(count("error_5xx")).isEqualTo(1d);
        assertThat(count("deferred")).isZero();
    }

    @Test
    void deferralsAreSeparableFromRealTrafficInThePromQlSense() {
        // What the dashboard does: everything except result="deferred" is the denominator.
        metrics.recordHttpResult(200, false);
        metrics.recordHttpResult(500, false);
        for (int i = 0; i < 98; i++) {
            metrics.recordHttpResult(503, true);
        }

        double reachedAnUpstream = count("success") + count("error_4xx")
                + count("error_5xx") + count("timeout");
        assertThat(reachedAnUpstream).isEqualTo(2d);
        assertThat(count("deferred")).isEqualTo(98d);
        // 1 of 2 requests that were actually sent succeeded — not 1 of 100.
        assertThat(count("success") / reachedAnUpstream * 100).isEqualTo(50d);
    }
}
