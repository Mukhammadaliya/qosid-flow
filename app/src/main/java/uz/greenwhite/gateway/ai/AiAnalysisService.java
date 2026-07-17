package uz.greenwhite.gateway.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.settings.SettingsService;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Opt-in AI error analysis. Dormant until base-url + api-key + model are all set.
 * Redacts obvious secrets, builds a chat prompt, and calls a configurable
 * OpenAI-compatible /chat/completions endpoint. Any failure (unconfigured,
 * unreachable, bad key) degrades to {analysis:null, error} — never throws out.
 */
@Slf4j
@Service
public class AiAnalysisService {

    private static final String SYSTEM_PROMPT =
            "You are an SRE assistant for the 'qosid' message-gateway pipeline "
          + "(Oracle/REST ingress -> Kafka -> HTTP with CircuitBreaker+OAuth2 -> RocksDB/callback, DLQ on failure). "
          + "Given ONE error or log entry, reply with a concise root-cause hypothesis and a concrete suggested fix. "
          + "Answer in the same language as the log (Uzbek or English), as short markdown. Do not invent details.";

    private final RestClient aiRestClient;
    private final SettingsService settings;
    private final ObjectMapper objectMapper;

    public AiAnalysisService(RestClient aiRestClient, SettingsService settings, ObjectMapper objectMapper) {
        this.aiRestClient = aiRestClient;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return !settings.get("gateway.ai.base-url", "").isBlank()
            && !settings.get("gateway.ai.api-key", "").isBlank()
            && !settings.get("gateway.ai.model", "").isBlank();
    }

    public AiAnalysisResult analyze(AiAnalyzeRequest req) {
        if (!enabled()) {
            return new AiAnalysisResult(null, "AI not configured");
        }
        String baseUrl = settings.get("gateway.ai.base-url", "").replaceAll("/+$", "");
        String apiKey = settings.getSecret("gateway.ai.api-key", "");
        String model = settings.get("gateway.ai.model", "");
        String context = AiRedactor.redact(buildContext(req));
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.2,
                "max_tokens", 800,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", context)));
        try {
            String resp = aiRestClient.post()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            String content = extractContent(resp);
            return content.isBlank()
                    ? new AiAnalysisResult(null, "AI returned no content")
                    : new AiAnalysisResult(content, null);
        } catch (Exception e) {
            log.warn("AI analyze failed: {}", e.getMessage());
            return new AiAnalysisResult(null, "AI request failed: " + e.getMessage());
        }
    }

    private String buildContext(AiAnalyzeRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.level() != null) sb.append("Level: ").append(req.level()).append('\n');
        if (req.labels() != null && !req.labels().isEmpty()) sb.append("Labels: ").append(req.labels()).append('\n');
        if (req.parsed() != null && !req.parsed().isNull()) {
            sb.append("Details:\n").append(req.parsed().toPrettyString());
        } else if (req.line() != null) {
            sb.append("Log line:\n").append(req.line());
        }
        return sb.toString();
    }

    private String extractContent(String resp) {
        try {
            JsonNode choices = objectMapper.readTree(resp).path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("message").path("content").asText("");
            }
        } catch (Exception e) {
            log.warn("AI response parse failed: {}", e.getMessage());
        }
        return "";
    }
}
