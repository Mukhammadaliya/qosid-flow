package uz.greenwhite.gateway.stats;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only proxy over the Prometheus HTTP API. Maps a named preset to fixed PromQL
 * (browser never sends PromQL), runs instant (/query) or range (/query_range) calls,
 * and normalizes into a StatsResult. Prometheus unreachable / bad preset degrade to
 * an empty result + error string (never 500).
 *
 * <p>Presets carry tokens this service expands: {@code ${window}} (instant — the full
 * selected span) and {@code ${step}} / {@code ${histWindow}} (range — rate windows that
 * scale with the resolution, so a 7d or 30d chart samples a representative window).
 */
@Slf4j
@Service
public class StatsQueryService {

    /** Prometheus refuses a range query that would yield more than this many points per series. */
    private static final int MAX_RANGE_POINTS = 11_000;

    private final RestClient prometheusRestClient;
    private final PrometheusResponseParser parser;
    private final SettingsService settings;
    private final String prometheusUrl;

    public StatsQueryService(RestClient prometheusRestClient, PrometheusResponseParser parser,
                             SettingsService settings,
                             @Value("${gateway.prometheus.url:http://localhost:9090}") String prometheusUrl) {
        this.prometheusRestClient = prometheusRestClient;
        this.parser = parser;
        this.settings = settings;
        this.prometheusUrl = prometheusUrl;
    }

    public StatsResult query(String presetName, Long fromMs, Long toMs, Integer stepSec) {
        StatPreset preset = StatPresets.get(presetName);
        if (preset == null) {
            return new StatsResult(List.of(), Map.of(), "unknown preset: " + presetName);
        }
        long end = (toMs != null) ? toMs : System.currentTimeMillis();
        long start = (fromMs != null) ? fromMs : end - 3_600_000L;
        int step = (stepSec == null) ? 30 : Math.max(stepSec, 1);
        long windowSec = Math.min(Math.max((end - start) / 1000L, 60L), 2_592_000L); // cap 30d
        try {
            if (preset.kind() == StatPreset.Kind.INSTANT) {
                Map<String, Double> kpis = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : preset.queries().entrySet()) {
                    String promql = e.getValue().replace("${window}", windowSec + "s");
                    String uri = UriComponentsBuilder.fromUriString(baseUrl() + "/api/v1/query")
                            .queryParam("query", "{promql}")
                            .queryParam("time", end / 1000L)
                            .build(promql).toString();
                    Double v = parser.parseScalar(get(uri));
                    kpis.put(e.getKey(), v);
                }
                return new StatsResult(List.of(), kpis, null);
            } else {
                // A fixed step blows past MAX_RANGE_POINTS on the long ranges (30s over 7d is
                // 20160 points), which fails the whole panel with a 400. Raise the step instead.
                int effectiveStep = Math.max(step, (int) Math.ceil(windowSec / (double) MAX_RANGE_POINTS));
                // Rate windows scale with the step. Sampling a 1m rate every 34 minutes — what a
                // 7d view would do — reports one minute in every thirty-four and calls it a trend.
                // The floors keep the short ranges byte-identical to the old fixed [1m] / [5m].
                String rateWindow = Math.max(effectiveStep, 60) + "s";
                String histWindow = Math.max(effectiveStep, 300) + "s";
                List<StatSeries> series = new ArrayList<>();
                for (Map.Entry<String, String> e : preset.queries().entrySet()) {
                    String promql = e.getValue()
                            .replace("${step}", rateWindow)
                            .replace("${histWindow}", histWindow);
                    String uri = UriComponentsBuilder.fromUriString(baseUrl() + "/api/v1/query_range")
                            .queryParam("query", "{promql}")
                            .queryParam("start", start / 1000L)
                            .queryParam("end", end / 1000L)
                            .queryParam("step", effectiveStep)
                            .build(promql).toString();
                    series.add(new StatSeries(e.getKey(), parser.parseRange(get(uri))));
                }
                return new StatsResult(series, Map.of(), null);
            }
        } catch (Exception ex) {
            log.warn("Prometheus query failed: {}", ex.getMessage());
            return new StatsResult(List.of(), Map.of(), "Prometheus unreachable: " + ex.getMessage());
        }
    }

    private String get(String uri) {
        return prometheusRestClient.get().uri(java.net.URI.create(uri)).retrieve().body(String.class);
    }

    private String baseUrl() {
        return settings.get("gateway.prometheus.url", prometheusUrl);
    }
}
