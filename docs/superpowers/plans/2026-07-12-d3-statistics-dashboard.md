# D3 Statistics Dashboard (Prometheus proxy + Smartup charts) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A message-flow statistics dashboard in the qosid SPA — KPI cards + throughput/funnel/error/latency charts — fed by a read-only admin proxy that runs preset PromQL against the existing Prometheus.

**Architecture:** Prometheus already scrapes the JAR's `/actuator/prometheus` (GatewayMetrics for E1–E5 + DLQ + E2E + API). A read-only admin proxy (`/qosid/admin/api/stats`) maps named presets to fixed PromQL and calls Prometheus's `/api/v1/query` (instant) / `/api/v1/query_range` (range) server-side; Prometheus stays internal. The SPA renders the normalized results with @ant-design/plots. Only additive backend (config + proxy); the E1–E5 pipeline is untouched.

**Tech Stack:** Java 21 / Spring Boot 3.3.0 (RestClient, Micrometer/Prometheus), React 18 + TS + Vite + Ant Design + @ant-design/plots, Prometheus (Docker).

## Global Constraints

- Backend package `uz.greenwhite.gateway`; admin API base `/qosid/admin/api` (existing session-auth + CSRF chain; GET is auth-gated, 401 unauthenticated).
- Prometheus stays internal: bind `127.0.0.1:9090` (not 0.0.0.0); the host-side JAR reaches it via `localhost:9090`.
- Config `gateway.prometheus.url`: resolve per-request `settings.get("gateway.prometheus.url", <yaml @Value>)` where the yaml value is `${PROMETHEUS_URL:http://localhost:9090}`. (Reuses the D2 fix pattern — do NOT hardcode a constant as the only default; the yaml/env value MUST be the live fallback.)
- Prometheus unreachable / any query error MUST degrade to `{series: [], kpis: {}, error: "..."}` HTTP 200 — never a 500. Unknown preset likewise → 200 with error (do not throw out of the controller).
- The browser sends only a preset NAME, never raw PromQL. Presets map to FIXED PromQL (no user string interpolated into a query). The only computed substitution is a service-computed `${window}` (an integer + "s", derived from `to-from`) in instant presets.
- Exact Prometheus metric names (verified live from `/actuator/prometheus`): counters `gateway_api_request_received_total`, `gateway_api_request_total{result}`, `gateway_kafka_produce_total{result}`, `gateway_consumer_received_total`, `gateway_http_request_total{result=success|error_4xx|error_5xx|timeout}`, `gateway_oracle_pull_total{result}`, `gateway_oracle_save_total{result}`, `gateway_dlq_sent_total`; timers `gateway_request_e2e_duration_seconds_*`, `gateway_http_request_duration_seconds_*`. Counters already carry a single `_total`.
- p95 requires micrometer percentile-histograms enabled for `gateway.request.e2e.duration` and `gateway.http.request.duration` (they are plain summaries otherwise — no `_bucket`, so `histogram_quantile` returns empty).
- Charts use @ant-design/plots themed to the Smartup palette; all UI copy via i18next `t('key')`.
- Existing suites stay green (backend + frontend); the SPA still builds into the single JAR (D0+D1 `frontend` profile). SPA fallback for `/statistics` deep-links must work (extend `SpaForwardController`).
- Windows Maven: `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"; & "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" <args>` (`-P '!frontend'` for backend-only). Frontend: from `frontend/`, `npm test -- <pattern>`.

---

### Task 1: Config — percentile-histograms + Prometheus URL + loopback bind

**Files:**
- Modify: `src/main/resources/application.yml` (add `management.metrics.distribution.percentiles-histogram` + `gateway.prometheus.url`), `docker-compose.yml` (Prometheus port → loopback)

**Interfaces:**
- Produces: the `gateway.prometheus.url` property (default `http://localhost:9090`) and `_bucket` histogram series for the two latency timers, both consumed by Task 2. No code depends on this at compile time.

- [ ] **Step 1: Enable percentile-histograms for the two latency timers**

In `src/main/resources/application.yml`, under the existing `management:` block (which currently has `server.port` and `endpoints.web.exposure`), add a `metrics` subtree:
```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        gateway.request.e2e.duration: true
        gateway.http.request.duration: true
```
(Merge into the existing `management:` map — do not duplicate the key. Keep `management.server.port` and `management.endpoints` as they are.)

- [ ] **Step 2: Add the Prometheus URL property**

In `src/main/resources/application.yml`, under the `gateway:` tree (next to the `gateway.loki.url` added in D2), add:
```yaml
  prometheus:
    url: ${PROMETHEUS_URL:http://localhost:9090}
```

- [ ] **Step 3: Bind Prometheus to loopback**

In `docker-compose.yml`, change the `prometheus` service `ports:` entry from `"9090:9090"` to `"127.0.0.1:9090:9090"` (the host JAR reaches it via 127.0.0.1:9090; external access closed — same posture as the D2 Loki fix). Leave everything else.

- [ ] **Step 4: Verify**

Run: `docker compose config -q` → exit 0 (validates the compose change). If Docker is unavailable: `python -c "import yaml; yaml.safe_load(open('docker-compose.yml')); yaml.safe_load(open('src/main/resources/application.yml')); print('ok')"` → `ok`.
Then verify the histogram config takes effect: boot the JAR (`$env:JAVA_HOME=...; java -jar target/qosid-flow-1.0.0-SNAPSHOT.jar` with `POLLING_ENABLED=false SETTINGS_ENCRYPTION_KEY=x SETTINGS_DB_PATH=./target/t1 ROCKSDB_PATH=./target/t1r`), then `curl -s http://localhost:8091/actuator/prometheus | grep gateway_request_e2e_duration_seconds_bucket | head` → expect at least one `..._bucket{...,le="..."}` line (proves p95 will work). Stop the JAR.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yml docker-compose.yml
git commit -m "feat(stats): percentile-histograms for p95 + prometheus.url + loopback bind"
```

---

### Task 2: Stats proxy backend (preset PromQL API)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/stats/StatPoint.java`, `StatSeries.java`, `StatsResult.java`, `StatPreset.java`, `StatPresets.java`, `PrometheusResponseParser.java`, `StatsQueryService.java`, `PrometheusClientConfig.java`, `src/main/java/uz/greenwhite/gateway/admin/StatsController.java`
- Test: `src/test/java/uz/greenwhite/gateway/stats/StatPresetsTest.java`, `PrometheusResponseParserTest.java`, `StatsQueryServiceTest.java`, `src/test/java/uz/greenwhite/gateway/admin/StatsControllerTest.java`

**Interfaces:**
- Consumes: `SettingsService.get(String,String)`; Spring `ObjectMapper`.
- Produces:
  - `record StatPoint(double t, double v)`; `record StatSeries(String name, java.util.List<StatPoint> points)`; `record StatsResult(java.util.List<StatSeries> series, java.util.Map<String,Double> kpis, String error)`.
  - `enum StatPreset.Kind { INSTANT, RANGE }`; `record StatPreset(Kind kind, java.util.LinkedHashMap<String,String> queries)`; `StatPresets.get(String name) -> StatPreset` (null if unknown), with the 5 presets below.
  - `PrometheusResponseParser.parseScalar(String json) -> Double` (instant single value, null if none) and `.parseRange(String json) -> java.util.List<StatPoint>`.
  - `StatsQueryService.query(String preset, Long fromMs, Long toMs, Integer stepSec) -> StatsResult`.
  - `StatsController` at `/qosid/admin/api/stats`.

- [ ] **Step 1: Write the failing preset test**

`src/test/java/uz/greenwhite/gateway/stats/StatPresetsTest.java`:
```java
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -P '!frontend' -Dtest=StatPresetsTest test`
Expected: FAIL — classes not found.

- [ ] **Step 3: Implement the records + preset registry**

`src/main/java/uz/greenwhite/gateway/stats/StatPoint.java`:
```java
package uz.greenwhite.gateway.stats;

public record StatPoint(double t, double v) {}
```

`src/main/java/uz/greenwhite/gateway/stats/StatSeries.java`:
```java
package uz.greenwhite.gateway.stats;

import java.util.List;

public record StatSeries(String name, List<StatPoint> points) {}
```

`src/main/java/uz/greenwhite/gateway/stats/StatsResult.java`:
```java
package uz.greenwhite.gateway.stats;

import java.util.List;
import java.util.Map;

public record StatsResult(List<StatSeries> series, Map<String, Double> kpis, String error) {}
```

`src/main/java/uz/greenwhite/gateway/stats/StatPreset.java`:
```java
package uz.greenwhite.gateway.stats;

import java.util.LinkedHashMap;

/** A named dashboard query: a set of (seriesName -> fixed PromQL) run as instant or range. */
public record StatPreset(Kind kind, LinkedHashMap<String, String> queries) {
    public enum Kind { INSTANT, RANGE }
}
```

`src/main/java/uz/greenwhite/gateway/stats/StatPresets.java`:
```java
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
            "successRate", "sum(rate(gateway_http_request_total{result=\"success\"}[5m])) / clamp_min(sum(rate(gateway_http_request_total[5m])),1) * 100",
            "errorRate", "sum(rate(gateway_http_request_total{result=~\"error_4xx|error_5xx|timeout\"}[5m])) / clamp_min(sum(rate(gateway_http_request_total[5m])),1) * 100",
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
```

- [ ] **Step 4: Run to verify the preset test passes**

Run: `mvn -P '!frontend' -Dtest=StatPresetsTest test`
Expected: PASS (4).

- [ ] **Step 5: Write the failing parser test**

`src/test/java/uz/greenwhite/gateway/stats/PrometheusResponseParserTest.java`:
```java
package uz.greenwhite.gateway.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusResponseParserTest {

    private final PrometheusResponseParser parser = new PrometheusResponseParser(new ObjectMapper());

    @Test
    void parseScalarReadsInstantVectorValue() {
        // Prometheus /query "vector" result: data.result[0].value = [ <ts>, "<value>" ]
        String json = "{\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{},\"value\":[1700000000,\"42.5\"]}]}}";
        assertThat(parser.parseScalar(json)).isEqualTo(42.5);
    }

    @Test
    void parseScalarReturnsNullWhenEmpty() {
        assertThat(parser.parseScalar("{\"data\":{\"resultType\":\"vector\",\"result\":[]}}")).isNull();
    }

    @Test
    void parseRangeReadsMatrixValues() {
        String json = "{\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{},"
                + "\"values\":[[1700000000,\"1\"],[1700000060,\"2\"]]}]}}";
        List<StatPoint> pts = parser.parseRange(json);
        assertThat(pts).hasSize(2);
        assertThat(pts.get(0).t()).isEqualTo(1700000000.0);
        assertThat(pts.get(1).v()).isEqualTo(2.0);
    }
}
```

- [ ] **Step 6: Implement the parser**

`src/main/java/uz/greenwhite/gateway/stats/PrometheusResponseParser.java`:
```java
package uz.greenwhite.gateway.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Parses Prometheus /api/v1/query (vector) and /query_range (matrix) JSON. */
@RequiredArgsConstructor
public class PrometheusResponseParser {

    private final ObjectMapper objectMapper;

    /** Instant vector -> the first sample's value, or null if empty/NaN/unparseable. */
    public Double parseScalar(String json) {
        try {
            JsonNode result = objectMapper.readTree(json).path("data").path("result");
            if (!result.isArray() || result.isEmpty()) return null;
            JsonNode value = result.get(0).path("value");
            if (!value.isArray() || value.size() < 2) return null;
            double d = Double.parseDouble(value.get(1).asText());
            return Double.isNaN(d) ? null : d;
        } catch (Exception e) {
            return null;
        }
    }

    /** Range matrix -> the first series' [t, v] points (empty on error/absent). */
    public List<StatPoint> parseRange(String json) {
        List<StatPoint> out = new ArrayList<>();
        try {
            JsonNode result = objectMapper.readTree(json).path("data").path("result");
            if (!result.isArray() || result.isEmpty()) return out;
            for (JsonNode value : result.get(0).path("values")) {
                double t = value.get(0).asDouble();
                double v = Double.parseDouble(value.get(1).asText());
                if (!Double.isNaN(v)) out.add(new StatPoint(t, v));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }
}
```

- [ ] **Step 7: Run to verify the parser test passes**

Run: `mvn -P '!frontend' -Dtest=PrometheusResponseParserTest test`
Expected: PASS (3).

- [ ] **Step 8: Write the failing service test**

`src/test/java/uz/greenwhite/gateway/stats/StatsQueryServiceTest.java`:
```java
package uz.greenwhite.gateway.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.settings.SettingsService;
import org.hamcrest.Matchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StatsQueryServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SettingsService settings = mock(SettingsService.class);

    private StatsQueryService service(MockRestServiceServer[] holder) {
        RestClient.Builder b = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(b).build();
        when(settings.get("gateway.prometheus.url", "http://localhost:9090")).thenReturn("http://localhost:9090");
        return new StatsQueryService(b.build(), new PrometheusResponseParser(mapper), settings, "http://localhost:9090");
    }

    @Test
    void instantPresetPopulatesKpis() {
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        StatsQueryService s = service(h);
        h[0].expect(manyTimes(), requestTo(Matchers.containsString("/api/v1/query?")))
                .andRespond(withSuccess("{\"data\":{\"result\":[{\"value\":[1,\"7\"]}]}}", MediaType.APPLICATION_JSON));
        StatsResult r = s.query("kpi", null, null, null);
        assertThat(r.error()).isNull();
        assertThat(r.kpis()).containsKeys("throughput", "successRate", "errorRate", "dlq", "e2eP95");
        assertThat(r.kpis().get("throughput")).isEqualTo(7.0);
    }

    @Test
    void rangePresetPopulatesSeries() {
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        StatsQueryService s = service(h);
        h[0].expect(manyTimes(), requestTo(Matchers.containsString("/api/v1/query_range?")))
                .andRespond(withSuccess("{\"data\":{\"result\":[{\"values\":[[1,\"3\"]]}]}}", MediaType.APPLICATION_JSON));
        StatsResult r = s.query("throughput", null, null, null);
        assertThat(r.series()).extracting(StatSeries::name).contains("produced", "httpSuccess", "errors");
        assertThat(r.series().get(0).points()).hasSize(1);
    }

    @Test
    void prometheusDownDegradesToError() {
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        StatsQueryService s = service(h);
        h[0].expect(manyTimes(), requestTo(Matchers.containsString("/api/v1/query")))
                .andRespond(withServerError());
        StatsResult r = s.query("kpi", null, null, null);
        assertThat(r.error()).isNotNull();
        assertThat(r.kpis()).isEmpty();
    }

    @Test
    void unknownPresetReturnsErrorNotException() {
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        StatsQueryService s = service(h);
        StatsResult r = s.query("bogus", null, null, null);
        assertThat(r.error()).isNotNull();
        assertThat(r.series()).isEmpty();
        assertThat(r.kpis()).isEmpty();
    }
}
```

- [ ] **Step 9: Implement the service + RestClient bean**

`src/main/java/uz/greenwhite/gateway/stats/PrometheusClientConfig.java`:
```java
package uz.greenwhite.gateway.stats;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class PrometheusClientConfig {

    /** Bare RestClient — the absolute Prometheus URI is built per-request from settings. */
    @Bean
    public RestClient prometheusRestClient() {
        return RestClient.builder().build();
    }

    /** Parser bean (plain class, injected into StatsQueryService). */
    @Bean
    public PrometheusResponseParser prometheusResponseParser(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new PrometheusResponseParser(mapper);
    }
}
```

`src/main/java/uz/greenwhite/gateway/stats/StatsQueryService.java`:
```java
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
 */
@Slf4j
@Service
public class StatsQueryService {

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
        long windowSec = Math.max((end - start) / 1000L, 60L);
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
                List<StatSeries> series = new ArrayList<>();
                for (Map.Entry<String, String> e : preset.queries().entrySet()) {
                    String uri = UriComponentsBuilder.fromUriString(baseUrl() + "/api/v1/query_range")
                            .queryParam("query", "{promql}")
                            .queryParam("start", start / 1000L)
                            .queryParam("end", end / 1000L)
                            .queryParam("step", step)
                            .build(e.getValue()).toString();
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
```
Note: `queryParam("query", "{promql}")` + `.build(promql)` uses UriComponentsBuilder's strict variable expansion, which percent-encodes the PromQL (braces, quotes) exactly like the D2 Loki fix — do NOT use `.build().toUriString()` with the raw PromQL inlined (that re-expands `{...}` as templates and fails).

- [ ] **Step 10: Run to verify the service test passes**

Run: `mvn -P '!frontend' -Dtest=StatsQueryServiceTest test`
Expected: PASS (4).

- [ ] **Step 11: Write the failing controller test**

`src/test/java/uz/greenwhite/gateway/admin/StatsControllerTest.java`:
```java
package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import uz.greenwhite.gateway.stats.StatsQueryService;
import uz.greenwhite.gateway.stats.StatsResult;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StatsControllerTest {

    @Autowired MockMvc mvc;
    @MockBean StatsQueryService statsQueryService;

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/qosid/admin/api/stats?preset=kpi")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void returnsKpisForAuthenticatedAdmin() throws Exception {
        when(statsQueryService.query(eq("kpi"), any(), any(), any()))
                .thenReturn(new StatsResult(List.of(), Map.of("throughput", 7.0), null));
        mvc.perform(get("/qosid/admin/api/stats?preset=kpi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.throughput").value(7.0))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}
```

- [ ] **Step 12: Implement the controller**

`src/main/java/uz/greenwhite/gateway/admin/StatsController.java`:
```java
package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.greenwhite.gateway.stats.StatsQueryService;
import uz.greenwhite.gateway.stats.StatsResult;

/**
 * Read-only admin proxy over Prometheus. Under the /qosid/admin/api/** chain,
 * so session-authenticated + CSRF-protected like the rest of the control plane.
 */
@RestController
@RequestMapping("/qosid/admin/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsQueryService statsQueryService;

    @GetMapping
    public ResponseEntity<StatsResult> query(
            @RequestParam String preset,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) Integer step) {
        return ResponseEntity.ok(statsQueryService.query(preset, from, to, step));
    }
}
```

- [ ] **Step 13: Run the controller test + full backend suite**

Run: `mvn -P '!frontend' -Dtest=StatsControllerTest test` → Expected: PASS (2).
Run: `mvn -P '!frontend' test` → Expected: all green (prior 123 + 13 new = 136), 0 failures.

- [ ] **Step 14: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/stats/ src/main/java/uz/greenwhite/gateway/admin/StatsController.java src/test/java/uz/greenwhite/gateway/stats/ src/test/java/uz/greenwhite/gateway/admin/StatsControllerTest.java
git commit -m "feat(stats): read-only Prometheus proxy API (/qosid/admin/api/stats)"
```

---

### Task 3: Frontend — stats API, Statistics nav/route, KPI cards + throughput chart

**Files:**
- Create: `frontend/src/api/stats.ts`, `frontend/src/pages/stats/StatisticsPage.tsx`, `frontend/src/pages/stats/statChartTheme.ts`
- Modify: `frontend/package.json` (add `@ant-design/plots`), `frontend/src/layout/AppLayout.tsx` (enable Statistics nav), `frontend/src/App.tsx` (route `/statistics`), `frontend/src/config/SpaForwardController`? (no — backend; handled below), `src/main/java/uz/greenwhite/gateway/config/SpaForwardController.java` (add `/statistics`), `frontend/src/locales/uz.json`
- Test: `frontend/src/pages/stats/StatisticsPage.test.tsx`, and add a case to `src/test/java/uz/greenwhite/gateway/config/SpaForwardControllerTest.java`

**Interfaces:**
- Consumes: `api` (`api/client.ts`), `useTranslation`, `smartupTheme`.
- Produces: `api/stats.ts` (`StatPoint`, `StatSeries`, `StatsResult`, `queryStats(preset, params)`); `StatisticsPage` (KPI cards + time-range/refresh controls + throughput Line chart). Task 4 adds the other 3 charts to this page.

- [ ] **Step 1: Add the @ant-design/plots dependency**

In `frontend/package.json` add to `dependencies`: `"@ant-design/plots": "^2.2.1"`. Then run (from `frontend/`) `npm install`.

- [ ] **Step 2: Write the failing StatisticsPage test**

`frontend/src/pages/stats/StatisticsPage.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { StatisticsPage } from './StatisticsPage';
import * as statsApi from '../../api/stats';

// @ant-design/plots renders to canvas/DOM that jsdom can't fully draw — stub the charts.
vi.mock('@ant-design/plots', () => ({
  Line: () => <div data-testid="chart-line" />,
  Column: () => <div data-testid="chart-column" />,
  Funnel: () => <div data-testid="chart-funnel" />,
}));

beforeEach(() => vi.restoreAllMocks());

describe('StatisticsPage', () => {
  it('renders KPI values from the kpi preset', async () => {
    vi.spyOn(statsApi, 'queryStats').mockImplementation((preset: string) => {
      if (preset === 'kpi') return Promise.resolve({ series: [], kpis: { throughput: 7, successRate: 99, errorRate: 1, dlq: 3, e2eP95: 250 } });
      return Promise.resolve({ series: [], kpis: {} });
    });
    render(<StatisticsPage />);
    await waitFor(() => expect(screen.getByText('7')).toBeInTheDocument()); // throughput KPI
    expect(screen.getByText('3')).toBeInTheDocument(); // dlq KPI
  });

  it('shows the Prometheus error banner', async () => {
    vi.spyOn(statsApi, 'queryStats').mockResolvedValue({ series: [], kpis: {}, error: 'Prometheus unreachable' });
    render(<StatisticsPage />);
    await waitFor(() => expect(screen.getByText(/Prometheus unreachable/)).toBeInTheDocument());
  });
});
```

- [ ] **Step 3: Run to verify it fails**

Run (from `frontend/`): `npm test -- StatisticsPage`
Expected: FAIL — `./StatisticsPage`, `../../api/stats` not found.

- [ ] **Step 4: Implement the stats API wrapper + chart theme**

`frontend/src/api/stats.ts`:
```ts
import { api } from './client';

const BASE = '/qosid/admin/api/stats';

// Backend serializes the record StatPoint(double t, double v) as {"t":..,"v":..}
export interface StatPoint { t: number; v: number } // t = epoch SECONDS, v = value
export interface StatSeries { name: string; points: StatPoint[] }
export interface StatsResult { series: StatSeries[]; kpis: Record<string, number | null>; error?: string }

export interface StatsParams { from?: number; to?: number; step?: number }

export const queryStats = (preset: string, params: StatsParams = {}) =>
  api.get<StatsResult>(BASE, { params: { preset, ...params } }).then(r => r.data);
```

`frontend/src/pages/stats/statChartTheme.ts`:
```ts
// Smartup-palette color sequence for @ant-design/plots.
export const SMARTUP_COLORS = ['#0095D9', '#2EC27E', '#F5A623', '#E5484D', '#A2228D', '#6B7689'];
```

- [ ] **Step 5: Implement the StatisticsPage (KPI cards + controls + throughput chart)**

`frontend/src/pages/stats/StatisticsPage.tsx`:
```tsx
import { useCallback, useEffect, useState } from 'react';
import { Card, Row, Col, Statistic, Alert, Space, Select, Switch } from 'antd';
import { Line } from '@ant-design/plots';
import { useTranslation } from 'react-i18next';
import { queryStats, StatsResult, StatSeries } from '../../api/stats';
import { SMARTUP_COLORS } from './statChartTheme';

const RANGES: Record<string, number> = { '15m': 900_000, '1h': 3_600_000, '6h': 21_600_000, '24h': 86_400_000 };

function toChartData(series: StatSeries[]) {
  return series.flatMap((s) =>
    s.points.map((p) => ({ time: new Date(p.t * 1000).toLocaleTimeString(), series: s.name, value: p.v })));
}

export function StatisticsPage() {
  const { t } = useTranslation();
  const [rangeKey, setRangeKey] = useState('1h');
  const [auto, setAuto] = useState(true);
  const [kpi, setKpi] = useState<StatsResult>({ series: [], kpis: {} });
  const [throughput, setThroughput] = useState<StatsResult>({ series: [], kpis: {} });
  const [error, setError] = useState<string | undefined>();

  const load = useCallback(() => {
    const to = Date.now();
    const from = to - RANGES[rangeKey];
    const params = { from, to, step: 30 };
    Promise.all([queryStats('kpi', params), queryStats('throughput', params)])
      .then(([k, tp]) => { setKpi(k); setThroughput(tp); setError(k.error ?? tp.error); })
      .catch(() => setError(t('stats.loadFailed')));
  }, [rangeKey, t]);

  useEffect(() => {
    load();
    if (!auto) return;
    const id = setInterval(load, 30_000);
    return () => clearInterval(id);
  }, [load, auto]);

  const n = (key: string, digits = 0) => {
    const v = kpi.kpis[key];
    return v == null ? '-' : Number(v).toFixed(digits);
  };

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Select value={rangeKey} onChange={setRangeKey} style={{ width: 100 }}
          options={Object.keys(RANGES).map((k) => ({ value: k, label: k }))} />
        <Switch checked={auto} onChange={setAuto} checkedChildren={t('stats.autoOn')} unCheckedChildren={t('stats.autoOff')} />
      </Space>

      {error && <Alert type="error" style={{ marginBottom: 16 }} message={error} />}

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={5}><Card><Statistic title={t('stats.throughput')} value={n('throughput', 2)} suffix="req/s" /></Card></Col>
        <Col span={5}><Card><Statistic title={t('stats.successRate')} value={n('successRate', 1)} suffix="%" /></Card></Col>
        <Col span={4}><Card><Statistic title={t('stats.errorRate')} value={n('errorRate', 1)} suffix="%" /></Card></Col>
        <Col span={5}><Card><Statistic title={t('stats.dlq')} value={n('dlq')} /></Card></Col>
        <Col span={5}><Card><Statistic title={t('stats.e2eP95')} value={n('e2eP95')} suffix="ms" /></Card></Col>
      </Row>

      <Card title={t('stats.throughputChart')}>
        <Line data={toChartData(throughput.series)} xField="time" yField="value" seriesField="series"
          colorField="series" scale={{ color: { range: SMARTUP_COLORS } }} height={240} />
      </Card>
    </div>
  );
}
```
Note: `@ant-design/plots` v2 uses `colorField` + `scale.color.range` for series colors; if the installed minor differs, the implementer adapts the color prop to the installed API (verify against `node_modules/@ant-design/plots`). The `toChartData` flattening is reused by Task 4.

- [ ] **Step 6: Enable Statistics nav + route + SPA fallback + i18n**

In `frontend/src/layout/AppLayout.tsx`, change the disabled `Statistics` nav item to enabled routing to `/statistics`:
```tsx
    { key: '/statistics', icon: <BarChartOutlined />, label: t('nav.statistics') },
```
(Keep `Logs` disabled.) Extend the nav `onClick` so it navigates for `/statistics`:
```tsx
            onClick={({ key }) => { if (key === '/settings' || key === '/statistics' || key.startsWith('/errors/')) navigate(key); }}
```

In `frontend/src/App.tsx`, add the route inside the authed `<Routes>`:
```tsx
                    <Route path="statistics" element={<StatisticsPage />} />
```
and import it: `import { StatisticsPage } from './pages/stats/StatisticsPage';`

In `src/main/java/uz/greenwhite/gateway/config/SpaForwardController.java`, add `/statistics` to the mapping:
```java
    @GetMapping({"/login", "/settings", "/settings/**", "/errors", "/errors/**", "/statistics"})
```
And add a test case to `src/test/java/uz/greenwhite/gateway/config/SpaForwardControllerTest.java`:
```java
    @Test
    void statisticsDeepLinkForwardsToIndex() throws Exception {
        mvc.perform(get("/statistics")).andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }
```

Add to `frontend/src/locales/uz.json`:
```json
  "stats.throughput": "O'tkazuvchanlik",
  "stats.successRate": "Muvaffaqiyat",
  "stats.errorRate": "Xato",
  "stats.dlq": "DLQ",
  "stats.e2eP95": "E2E p95",
  "stats.throughputChart": "O'tkazuvchanlik (vaqt bo'yicha)",
  "stats.funnelChart": "Pipeline oqimi",
  "stats.errorsChart": "Xatolar breakdown",
  "stats.latencyChart": "Kechikish (p95)",
  "stats.autoOn": "Avto",
  "stats.autoOff": "Qo'lda",
  "stats.loadFailed": "Yuklab bo'lmadi"
```

- [ ] **Step 7: Run the tests + build**

Run (from `frontend/`): `npm test -- StatisticsPage` → Expected: PASS (2).
Run: `mvn -P '!frontend' -Dtest=SpaForwardControllerTest test` → Expected: PASS (5).
Run (from `frontend/`): `npm run build` → Expected: succeeds.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/stats.ts frontend/src/pages/stats/ frontend/package.json frontend/package-lock.json frontend/src/layout/AppLayout.tsx frontend/src/App.tsx frontend/src/locales/uz.json src/main/java/uz/greenwhite/gateway/config/SpaForwardController.java src/test/java/uz/greenwhite/gateway/config/SpaForwardControllerTest.java
git commit -m "feat(ui): statistics dashboard shell — KPI cards + throughput chart"
```

---

### Task 4: Frontend — funnel, errors, latency charts

**Files:**
- Modify: `frontend/src/pages/stats/StatisticsPage.tsx` (add the 3 charts + their fetches)
- Test: `frontend/src/pages/stats/StatisticsPage.charts.test.tsx`

**Interfaces:**
- Consumes: `queryStats` + `toChartData` pattern from Task 3; the mocked `@ant-design/plots` `Funnel`/`Column`/`Line`.
- Produces: the completed dashboard (funnel from the `funnel` preset kpis; errors Column from the `errors` preset series; latency Line from the `latency` preset series).

- [ ] **Step 1: Write the failing charts test**

`frontend/src/pages/stats/StatisticsPage.charts.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { StatisticsPage } from './StatisticsPage';
import * as statsApi from '../../api/stats';

vi.mock('@ant-design/plots', () => ({
  Line: () => <div data-testid="chart-line" />,
  Column: () => <div data-testid="chart-column" />,
  Funnel: () => <div data-testid="chart-funnel" />,
}));

beforeEach(() => vi.restoreAllMocks());

describe('StatisticsPage charts', () => {
  it('renders funnel, errors, and latency charts once their presets load', async () => {
    vi.spyOn(statsApi, 'queryStats').mockImplementation((preset: string) => {
      if (preset === 'funnel') return Promise.resolve({ series: [], kpis: { pull: 0, produce: 100, consume: 98, http: 95, save: 90 } });
      if (preset === 'errors') return Promise.resolve({ series: [{ name: '4xx', points: [{ t: 1, v: 2 }] }], kpis: {} });
      if (preset === 'latency') return Promise.resolve({ series: [{ name: 'e2eP95', points: [{ t: 1, v: 250 }] }], kpis: {} });
      return Promise.resolve({ series: [], kpis: {} });
    });
    render(<StatisticsPage />);
    await waitFor(() => expect(screen.getByTestId('chart-funnel')).toBeInTheDocument());
    expect(screen.getByTestId('chart-column')).toBeInTheDocument();
    // two Line charts now (throughput + latency)
    expect(screen.getAllByTestId('chart-line').length).toBeGreaterThanOrEqual(2);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run (from `frontend/`): `npm test -- StatisticsPage.charts`
Expected: FAIL — the funnel/column charts aren't rendered yet.

- [ ] **Step 3: Add the three charts to StatisticsPage**

Update `frontend/src/pages/stats/StatisticsPage.tsx`: add imports `Column, Funnel` from `@ant-design/plots`; add state + fetches for `funnel`, `errors`, `latency`; render them in a 2×2 grid below the throughput card. Replace the single throughput `<Card>` block with:
```tsx
      <Row gutter={16}>
        <Col span={12}>
          <Card title={t('stats.throughputChart')} style={{ marginBottom: 16 }}>
            <Line data={toChartData(throughput.series)} xField="time" yField="value" seriesField="series"
              colorField="series" scale={{ color: { range: SMARTUP_COLORS } }} height={240} />
          </Card>
        </Col>
        <Col span={12}>
          <Card title={t('stats.funnelChart')} style={{ marginBottom: 16 }}>
            <Funnel data={funnelData} xField="stage" yField="count" height={240}
              scale={{ color: { range: SMARTUP_COLORS } }} />
          </Card>
        </Col>
        <Col span={12}>
          <Card title={t('stats.errorsChart')}>
            <Column data={toChartData(errors.series)} xField="time" yField="value" stack colorField="series"
              scale={{ color: { range: SMARTUP_COLORS } }} height={240} />
          </Card>
        </Col>
        <Col span={12}>
          <Card title={t('stats.latencyChart')}>
            <Line data={toChartData(latency.series)} xField="time" yField="value" seriesField="series"
              colorField="series" scale={{ color: { range: SMARTUP_COLORS } }} height={240} />
          </Card>
        </Col>
      </Row>
```
Add the state + include the extra presets in the `Promise.all`:
```tsx
  const [funnel, setFunnel] = useState<StatsResult>({ series: [], kpis: {} });
  const [errors, setErrors] = useState<StatsResult>({ series: [], kpis: {} });
  const [latency, setLatency] = useState<StatsResult>({ series: [], kpis: {} });
```
```tsx
    Promise.all([
      queryStats('kpi', params), queryStats('throughput', params),
      queryStats('funnel', params), queryStats('errors', params), queryStats('latency', params),
    ])
      .then(([k, tp, fn, er, lt]) => {
        setKpi(k); setThroughput(tp); setFunnel(fn); setErrors(er); setLatency(lt);
        setError(k.error ?? tp.error ?? fn.error ?? er.error ?? lt.error);
      })
      .catch(() => setError(t('stats.loadFailed')));
```
And derive the funnel chart data (ordered stages) from the funnel preset kpis:
```tsx
  const FUNNEL_STAGES = ['pull', 'produce', 'consume', 'http', 'save'];
  const funnelData = FUNNEL_STAGES.map((stage) => ({ stage, count: funnel.kpis[stage] ?? 0 }));
```

- [ ] **Step 4: Run the tests + full frontend suite + build**

Run (from `frontend/`): `npm test -- StatisticsPage` → Expected: PASS (all StatisticsPage + charts tests).
Run (from `frontend/`): `npm test` → Expected: all green (prior 27 + StatisticsPage 2 + charts 1 = 30).
Run (from `frontend/`): `npm run build` → Expected: succeeds.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/stats/StatisticsPage.tsx frontend/src/pages/stats/StatisticsPage.charts.test.tsx
git commit -m "feat(ui): funnel + errors + latency charts on the statistics dashboard"
```

---

### Task 5: E2E + full build + local Prometheus smoke

**Files:**
- Create: `frontend/e2e/statistics.spec.ts`

**Interfaces:**
- Consumes: the built SPA (vite preview) with the admin API mocked via `page.route`, like `settings.spec.ts`/`errors.spec.ts`.
- Produces: an E2E proving login → `/statistics` renders a KPI value from a mocked stats response.

- [ ] **Step 1: Write the failing E2E**

`frontend/e2e/statistics.spec.ts`:
```ts
import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/qosid/admin/api/health', (r) => r.fulfill({ status: 200, body: '{}', headers: { 'content-type': 'application/json' } }));
  let loggedIn = false;
  await page.route('**/qosid/admin/api/me', (r) =>
    r.fulfill({ status: loggedIn ? 200 : 401, body: JSON.stringify({ username: 'admin' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/login', (r) => { loggedIn = true; return r.fulfill({ status: 200, body: JSON.stringify({ username: 'admin', status: 'AUTHENTICATED' }), headers: { 'content-type': 'application/json' } }); });
  await page.route('**/qosid/admin/api/stats?*', (r) => {
    const preset = new URL(r.request().url()).searchParams.get('preset');
    const body = preset === 'kpi'
      ? { series: [], kpis: { throughput: 7, successRate: 99, errorRate: 1, dlq: 3, e2eP95: 250 } }
      : { series: [], kpis: {} };
    return r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
  });
});

test('login then view statistics KPIs', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Foydalanuvchi').fill('admin');
  await page.getByLabel('Parol').fill('admin');
  await page.getByRole('button', { name: 'Kirish' }).click();
  await expect(page.getByText('Sozlamalar')).toBeVisible();
  await page.goto('/statistics');
  await expect(page.getByText('req/s')).toBeVisible();
});
```

- [ ] **Step 2: Run the E2E**

Run (from `frontend/`): `npm run e2e`
Expected: 3 passed (settings + errors + statistics). If chromium missing: `npx playwright install chromium` then retry.

- [ ] **Step 3: Full single-JAR build + backend regression**

Run: `mvn package -DskipTests` (frontend ON) → Expected: BUILD SUCCESS; `jar tf target/qosid-flow-1.0.0-SNAPSHOT.jar | grep static/index.html` present.
Run: `mvn -P '!frontend' test` → Expected: 136 backend tests green.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/statistics.spec.ts
git commit -m "test(ui): E2E for the statistics dashboard over mocked stats API"
```

---

## Phase-end verification (controller runs after all tasks)

1. `mvn -P '!frontend' test` — 136 backend tests green.
2. `cd frontend && npm test` — 30 frontend tests green.
3. `cd frontend && npm run e2e` — Playwright green (3 specs).
4. `mvn package -DskipTests` — single JAR with `static/index.html`.
5. **Local smoke:** `docker compose up -d prometheus` (loopback-bound), boot the JAR (`MANAGEMENT_PORT` reachable by Prometheus's `host.docker.internal:8091` scrape), open `/statistics` after login → KPI cards + charts populate (drive some pipeline traffic or accept zeros on a fresh instance). Confirm graceful degradation: stop `prometheus`, reload → error banner, not a 500. Confirm `/statistics` deep-link serves index.html. Screenshot.

## Self-Review notes (author)

- **Spec coverage:** histogram + prometheus.url + loopback bind → Task 1; preset PromQL proxy + Prometheus-down + never-500 + settings-fallback → Task 2; @ant-design/plots + nav/route + KPI cards + throughput → Task 3; funnel/errors/latency charts → Task 4; E2E/smoke → Task 5. All spec §3–§8 map to a task.
- **Config-pattern reuse:** Task 2's `StatsQueryService` uses the exact D2 `@Value` + `settings.get(key, yamlValue)` fallback + strict URI variable expansion (`{promql}` build var) — the two D2 blockers (dead config, brace re-expansion) are pre-avoided by construction.
- **Never-500:** unknown preset and Prometheus-down both return `{series:[], kpis:{}, error}` HTTP 200; controller always `ResponseEntity.ok`.
- **SPA fallback:** `/statistics` added to `SpaForwardController` + a test (D2 shipped with this exact gap caught only at smoke — not repeating it).
- **Type consistency:** backend `StatPoint(t,v)` serializes as `{t,v}`; frontend `StatPoint` interface is `{t,v}` (Step 4 note corrects the initial array form). `StatsResult{series,kpis,error}` matches across backend record, `api/stats.ts`, page, and E2E mock.
- **Stub sequencing:** Task 3 renders the throughput chart; Task 4 extends the SAME page with 3 more charts (no stub files needed — the page is additively extended, its test updated).
