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
    void unknownPresetReturnsErrorNotException() {
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        StatsQueryService s = service(h);
        StatsResult r = s.query("bogus", null, null, null);
        assertThat(r.error()).isNotNull();
        assertThat(r.series()).isEmpty();
        assertThat(r.kpis()).isEmpty();
    }
}
