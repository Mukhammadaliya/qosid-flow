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
    void settingsOverrideUrlWinsOverYamlFallback() {
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        StatsQueryService s = service(h);
        // H2 override (last-stub-wins over the helper's default): must beat the @Value yaml fallback.
        when(settings.get("gateway.prometheus.url", "http://localhost:9090")).thenReturn("http://other:9090");
        h[0].expect(manyTimes(), requestTo(Matchers.containsString("other:9090")))
                .andRespond(withSuccess("{\"data\":{\"result\":[{\"value\":[1,\"1\"]}]}}", MediaType.APPLICATION_JSON));
        StatsResult r = s.query("kpi", null, null, null);
        assertThat(r.error()).isNull();
        h[0].verify(); // enforce that the request actually hit the overridden host
    }

    @Test
    void shortRangeKeepsTheHistoricFixedRateWindows() {
        // The token expansion must not change what the 15m/1h views ask for: the floors
        // are set so a small step still yields the [1m] / [5m] windows used before.
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        StatsQueryService s = service(h);
        h[0].expect(manyTimes(), requestTo(Matchers.containsString("60s")))
                .andRespond(withSuccess("{\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));
        long end = 1_700_000_000_000L;
        s.query("throughput", end - 3_600_000L, end, 15);
        h[0].verify();

        MockRestServiceServer[] h2 = new MockRestServiceServer[1];
        StatsQueryService s2 = service(h2);
        h2[0].expect(manyTimes(), requestTo(Matchers.containsString("300s")))
                .andRespond(withSuccess("{\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));
        s2.query("latency", end - 3_600_000L, end, 15);
        h2[0].verify();
    }

    @Test
    void longRangeScalesTheRateWindowWithTheStep() {
        // 7d at a 2016s step: a [1m] window would sample one minute in thirty-four.
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        StatsQueryService s = service(h);
        h[0].expect(manyTimes(), requestTo(Matchers.containsString("2016s")))
                .andRespond(withSuccess("{\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));
        long end = 1_700_000_000_000L;
        s.query("throughput", end - 604_800_000L, end, 2016);
        h[0].verify();
    }

    @Test
    void tooSmallAStepIsRaisedBelowPrometheusPointCeiling() {
        // 30s over 7d is 20160 points per series — Prometheus rejects the query outright.
        // The service raises the step (>= 55s here) rather than fail the whole panel.
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        StatsQueryService s = service(h);
        h[0].expect(manyTimes(), requestTo(Matchers.containsString("step=55")))
                .andRespond(withSuccess("{\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));
        long end = 1_700_000_000_000L;
        StatsResult r = s.query("throughput", end - 604_800_000L, end, 30);
        assertThat(r.error()).isNull();
        h[0].verify();
    }

    @Test
    void noRangePresetLeavesAnUnexpandedToken() {
        // A token that survives into the URL reaches Prometheus as literal text and the
        // panel silently returns nothing — cheaper to catch here than on the dashboard.
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        StatsQueryService s = service(h);
        h[0].expect(manyTimes(), requestTo(Matchers.not(Matchers.containsString("$"))))
                .andRespond(withSuccess("{\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));
        long end = 1_700_000_000_000L;
        for (String preset : new String[]{"throughput", "errors", "latency"}) {
            s.query(preset, end - 86_400_000L, end, 288);
        }
        h[0].verify();
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
