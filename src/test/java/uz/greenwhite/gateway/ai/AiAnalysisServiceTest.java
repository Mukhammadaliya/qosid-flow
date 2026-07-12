package uz.greenwhite.gateway.ai;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiAnalysisServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SettingsService settings = mock(SettingsService.class);

    private AiAnalysisService service(MockRestServiceServer[] holder) {
        RestClient.Builder b = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(b).build();
        return new AiAnalysisService(b.build(), settings, mapper);
    }

    private void configure(boolean on) {
        when(settings.get("gateway.ai.base-url", "")).thenReturn(on ? "https://llm.example/v1" : "");
        when(settings.get("gateway.ai.api-key", "")).thenReturn(on ? "stored" : "");
        when(settings.get("gateway.ai.model", "")).thenReturn(on ? "gpt-4o-mini" : "");
        when(settings.getSecret("gateway.ai.api-key", "")).thenReturn(on ? "sk-real" : "");
    }

    @Test
    void disabledWhenConfigBlank() {
        configure(false);
        AiAnalysisService s = service(new MockRestServiceServer[1]);
        assertThat(s.enabled()).isFalse();
        AiAnalysisResult r = s.analyze(new AiAnalyzeRequest("boom", "ERROR", java.util.Map.of(), null));
        assertThat(r.analysis()).isNull();
        assertThat(r.error()).isNotNull();
    }

    @Test
    void returnsAnalysisFromChatCompletion() {
        configure(true);
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        AiAnalysisService s = service(h);
        h[0].expect(requestTo(Matchers.containsString("/chat/completions")))
                // Decrypted key (getSecret) must be sent, not the masked/stored value.
                .andExpect(header("Authorization", "Bearer sk-real"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Root cause: timeout. Fix: raise the client timeout.\"}}]}",
                        MediaType.APPLICATION_JSON));
        AiAnalysisResult r = s.analyze(new AiAnalyzeRequest("connection timeout", "ERROR", java.util.Map.of(), null));
        assertThat(r.error()).isNull();
        assertThat(r.analysis()).contains("Root cause");
    }

    @Test
    void llmErrorDegradesGracefully() {
        configure(true);
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        AiAnalysisService s = service(h);
        h[0].expect(requestTo(Matchers.containsString("/chat/completions"))).andRespond(withServerError());
        AiAnalysisResult r = s.analyze(new AiAnalyzeRequest("x", "ERROR", java.util.Map.of(), null));
        assertThat(r.analysis()).isNull();
        assertThat(r.error()).isNotNull();
    }
}
