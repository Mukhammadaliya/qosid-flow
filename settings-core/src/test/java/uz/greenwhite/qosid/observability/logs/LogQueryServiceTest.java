package uz.greenwhite.qosid.observability.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.greenwhite.qosid.observability.SettingsResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.MediaType;
import org.hamcrest.Matchers;

class LogQueryServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SettingsResolver settings = mock(SettingsResolver.class);

    private static final String YAML_URL = "http://localhost:3100";

    private LogQueryService serviceBackedBy(MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        when(settings.get("gateway.loki.url", YAML_URL)).thenReturn(YAML_URL);
        return new LogQueryService(builder.build(), new LokiResponseParser(mapper), settings, YAML_URL);
    }

    @Test
    void happyPathReturnsEntriesAndNoError() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        LogQueryService service = serviceBackedBy(holder);
        holder[0].expect(requestTo(Matchers.containsString("/loki/api/v1/query_range")))
                .andRespond(withSuccess(
                        "{\"data\":{\"result\":[{\"stream\":{\"level\":\"ERROR\"},\"values\":[[\"1700000002000000000\",\"boom\"]]}]}}",
                        MediaType.APPLICATION_JSON));
        LogQueryResult result = service.query("jar", null, null, null, null, "ERROR", 200);
        assertThat(result.error()).isNull();
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).line()).isEqualTo("boom");
    }

    @Test
    void lokiDownReturnsEmptyWithErrorNotException() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        LogQueryService service = serviceBackedBy(holder);
        holder[0].expect(requestTo(Matchers.containsString("/loki/api/v1/query_range")))
                .andRespond(withServerError());
        LogQueryResult result = service.query("jar", null, null, null, null, "ERROR", 200);
        assertThat(result.entries()).isEmpty();
        assertThat(result.error()).isNotNull();
    }

    @Test
    void settingsOverrideUrlWinsOverYamlFallback() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        LogQueryService service = serviceBackedBy(holder);
        when(settings.get("gateway.loki.url", YAML_URL)).thenReturn("http://other:3100"); // H2 override
        holder[0].expect(requestTo(Matchers.containsString("other:3100")))
                .andRespond(withSuccess("{\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));
        LogQueryResult result = service.query("jar", null, null, null, null, "ERROR", 200);
        assertThat(result.error()).isNull();
        holder[0].verify();
    }

    @Test
    void unknownSourceDegradesToErrorResultNotException() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        LogQueryService service = serviceBackedBy(holder);
        // no server expectation: Loki must not be called for a bad source
        LogQueryResult result = service.query("bogus", null, null, null, null, null, 200);
        assertThat(result.entries()).isEmpty();
        assertThat(result.error()).isNotNull();
    }

    @Test
    void labelValuesRejectsInvalidLabelNameWithoutCallingLoki() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        LogQueryService service = serviceBackedBy(holder);
        // no server expectation set: any HTTP call would fail this test
        assertThat(service.labelValues("bad/name")).isEmpty();
        assertThat(service.labelValues("bad?name={x}")).isEmpty();
        assertThat(service.labelValues(null)).isEmpty();
    }
}
