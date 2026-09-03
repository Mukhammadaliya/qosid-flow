package uz.greenwhite.qosid.observability.stats;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StatPresetsTest {

    @Test
    void kpiIsInstantWithExpectedMetrics() {
        StatPreset p = StatPresets.get("kpi");
        assertThat(p.kind()).isEqualTo(StatPreset.Kind.INSTANT);
        assertThat(p.queries()).containsKeys("throughput", "successRate", "errorRate", "dlq", "e2eP95");
        assertThat(p.queries().get("throughput")).contains("gateway_kafka_produce_total");
        assertThat(p.queries().get("e2eP95")).contains("histogram_quantile(0.95");
    }

    @Test
    void funnelIsInstantWithFiveStages() {
        StatPreset p = StatPresets.get("funnel");
        assertThat(p.kind()).isEqualTo(StatPreset.Kind.INSTANT);
        assertThat(p.queries()).containsKeys("pull", "produce", "consume", "http", "save");
        assertThat(p.queries().get("consume")).contains("gateway_consumer_received_total");
    }

    @Test
    void throughputAndErrorsAndLatencyAreRange() {
        assertThat(StatPresets.get("throughput").kind()).isEqualTo(StatPreset.Kind.RANGE);
        assertThat(StatPresets.get("errors").kind()).isEqualTo(StatPreset.Kind.RANGE);
        assertThat(StatPresets.get("latency").kind()).isEqualTo(StatPreset.Kind.RANGE);
        assertThat(StatPresets.get("errors").queries()).containsKeys("4xx", "5xx", "timeout", "dlq");
        assertThat(StatPresets.get("latency").queries().get("httpP95")).contains("gateway_http_request_duration_seconds_bucket");
    }

    @Test
    void rangePresetsUseScalingWindowsAndInstantPresetsDoNot() {
        // Range presets must not hard-code a rate window: at 7d/30d resolution a fixed
        // [1m] samples a sliver of each step and the chart reports noise as trend.
        for (String name : new String[]{"throughput", "errors"}) {
            StatPresets.get(name).queries().forEach((series, promql) -> {
                assertThat(promql).as("%s/%s scales its rate window", name, series).contains("[${step}]");
                assertThat(promql).as("%s/%s has no fixed window", name, series).doesNotContain("[1m]");
            });
        }
        StatPresets.get("latency").queries().forEach((series, promql) -> {
            assertThat(promql).as("latency/%s scales its histogram window", series).contains("[${histWindow}]");
            assertThat(promql).as("latency/%s has no fixed window", series).doesNotContain("[5m]");
        });
        // Instant KPIs read "now" and are independent of the selected span, apart from
        // dlq which counts over it — those windows stay as they are.
        StatPresets.get("kpi").queries().forEach((series, promql) ->
            assertThat(promql).as("kpi/%s is not step-scaled", series).doesNotContain("${step}"));
    }

    @Test
    void unknownPresetReturnsNull() {
        assertThat(StatPresets.get("bogus")).isNull();
    }

    @Test
    void kpiRateDenominatorsAreNotClamped() {
        // Regression guard: clamp_min(...,1) on the denominator distorts percentages
        // at low traffic (<1 req/s) — 0.2 req/s all-success would show 20% instead of 100%.
        // Zero traffic yields NaN which parseScalar maps to null and the UI renders as "-".
        StatPreset p = StatPresets.get("kpi");
        assertThat(p.queries().get("successRate")).doesNotContain("clamp_min");
        assertThat(p.queries().get("errorRate")).doesNotContain("clamp_min");
        assertThat(p.queries().get("successRate"))
            .contains("/ sum(rate(gateway_http_request_total{result!=\"deferred\"}[5m])) * 100");
        assertThat(p.queries().get("errorRate"))
            .contains("/ sum(rate(gateway_http_request_total{result!=\"deferred\"}[5m])) * 100");
    }

    @Test
    void rateDenominatorsExcludeBackPressureDeferrals() {
        // A deferred request never reached an upstream — the circuit was open or the host
        // permit was refused, and the message went back on the queue. Counting it in the
        // denominator reports the gateway throttling itself as an upstream failure: on
        // production 2026-08-01 that read as 26.8% success for a pipeline running at 99.68%.
        StatPreset kpi = StatPresets.get("kpi");
        for (String key : new String[]{"successRate", "errorRate"}) {
            assertThat(kpi.queries().get(key))
                .as("kpi/%s excludes deferrals from the denominator", key)
                .contains("{result!=\"deferred\"}");
        }
        // The numerators must NOT pick deferrals up either, by name or by regex.
        assertThat(kpi.queries().get("successRate")).contains("{result=\"success\"}");
        assertThat(kpi.queries().get("errorRate")).doesNotContain("deferred|");
        StatPresets.get("errors").queries().forEach((series, promql) ->
            assertThat(promql).as("errors/%s is not a deferral count", series).doesNotContain("deferred"));
    }
}
