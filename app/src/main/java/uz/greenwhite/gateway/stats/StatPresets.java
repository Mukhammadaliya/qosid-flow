package uz.greenwhite.gateway.stats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed PromQL for each dashboard preset. The browser sends only a preset name;
 * no user input is ever interpolated into a query. Instant presets may contain the
 * service-computed ${window} token (see StatsQueryService) for increase() windows.
 * Metric names verified live from /actuator/prometheus.
 */
public final class StatPresets {

    private StatPresets() {}

    private static final Map<String, StatPreset> PRESETS = new LinkedHashMap<>();

    private static LinkedHashMap<String, String> q(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static {
        PRESETS.put("kpi", new StatPreset(StatPreset.Kind.INSTANT, q(
            "throughput", "sum(rate(gateway_kafka_produce_total{result=\"success\"}[5m]))",
            "successRate", "sum(rate(gateway_http_request_total{result=\"success\"}[5m])) / sum(rate(gateway_http_request_total[5m])) * 100",
            "errorRate", "sum(rate(gateway_http_request_total{result=~\"error_4xx|error_5xx|timeout\"}[5m])) / sum(rate(gateway_http_request_total[5m])) * 100",
            "dlq", "sum(increase(gateway_dlq_sent_total[${window}]))",
            "e2eP95", "histogram_quantile(0.95, sum(rate(gateway_request_e2e_duration_seconds_bucket[5m])) by (le)) * 1000"
        )));
        PRESETS.put("funnel", new StatPreset(StatPreset.Kind.INSTANT, q(
            "pull", "sum(increase(gateway_oracle_pull_total{result=\"success\"}[${window}]))",
            "produce", "sum(increase(gateway_kafka_produce_total{result=\"success\"}[${window}]))",
            "consume", "sum(increase(gateway_consumer_received_total[${window}]))",
            "http", "sum(increase(gateway_http_request_total{result=\"success\"}[${window}]))",
            "save", "sum(increase(gateway_oracle_save_total{result=\"success\"}[${window}]))"
        )));
        PRESETS.put("throughput", new StatPreset(StatPreset.Kind.RANGE, q(
            "produced", "sum(rate(gateway_kafka_produce_total{result=\"success\"}[1m]))",
            "httpSuccess", "sum(rate(gateway_http_request_total{result=\"success\"}[1m]))",
            "errors", "sum(rate(gateway_http_request_total{result=~\"error_4xx|error_5xx|timeout\"}[1m]))"
        )));
        PRESETS.put("errors", new StatPreset(StatPreset.Kind.RANGE, q(
            "4xx", "sum(rate(gateway_http_request_total{result=\"error_4xx\"}[1m]))",
            "5xx", "sum(rate(gateway_http_request_total{result=\"error_5xx\"}[1m]))",
            "timeout", "sum(rate(gateway_http_request_total{result=\"timeout\"}[1m]))",
            "dlq", "sum(rate(gateway_dlq_sent_total[1m]))"
        )));
        PRESETS.put("latency", new StatPreset(StatPreset.Kind.RANGE, q(
            "e2eP95", "histogram_quantile(0.95, sum(rate(gateway_request_e2e_duration_seconds_bucket[5m])) by (le)) * 1000",
            "httpP95", "histogram_quantile(0.95, sum(rate(gateway_http_request_duration_seconds_bucket[5m])) by (le)) * 1000"
        )));
    }

    public static StatPreset get(String name) {
        return PRESETS.get(name);
    }
}
