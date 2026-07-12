package uz.greenwhite.gateway.stats;

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
    void unknownPresetReturnsNull() {
        assertThat(StatPresets.get("bogus")).isNull();
    }
}
