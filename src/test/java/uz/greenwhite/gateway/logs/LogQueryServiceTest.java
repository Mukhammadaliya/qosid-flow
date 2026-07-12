package uz.greenwhite.gateway.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.settings.SettingsService;

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
    private final SettingsService settings = mock(SettingsService.class);

    private LogQueryService serviceBackedBy(MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        when(settings.get("gateway.loki.url", "http://loki:3100")).thenReturn("http://loki:3100");
        return new LogQueryService(builder.build(), new LokiResponseParser(mapper), settings);
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
}
