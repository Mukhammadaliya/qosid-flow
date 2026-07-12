package uz.greenwhite.gateway.logs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.List;

/**
 * Read-only proxy over Loki's HTTP API. Resolves the Loki base URL per call
 * (settings override, yaml fallback), runs a LogQL query_range, and normalizes
 * the result. Loki being unreachable degrades to an empty result + error string
 * (never a 500) so the Errors UI stays usable when logging infra is down.
 */
@Slf4j
@Service
public class LogQueryService {

    private static final String DEFAULT_URL = "http://localhost:3100";
    private static final int MAX_LIMIT = 1000;

    private final RestClient lokiRestClient;
    private final LokiResponseParser parser;
    private final SettingsService settings;

    public LogQueryService(RestClient lokiRestClient, LokiResponseParser parser, SettingsService settings) {
        this.lokiRestClient = lokiRestClient;
        this.parser = parser;
        this.settings = settings;
    }

    public LogQueryResult query(String source, Long fromMs, Long toMs, String q,
                                String container, String level, Integer limit) {
        long end = (toMs != null) ? toMs : System.currentTimeMillis();
        long start = (fromMs != null) ? fromMs : end - 3_600_000L; // default 1h
        int lim = (limit == null) ? 200 : Math.min(Math.max(limit, 1), MAX_LIMIT);
        String logql = LogQlBuilder.build(source, q, container, level);
        // LogQL is passed as a URI variable ({logql}) and expanded via build(...): this strictly
        // encodes it AND stops RestClient.uri(String) from treating the LogQL braces {label="v"}
        // as URI template placeholders.
        java.net.URI uri = UriComponentsBuilder.fromUriString(baseUrl() + "/loki/api/v1/query_range")
                .queryParam("query", "{logql}")
                .queryParam("start", start * 1_000_000L)   // ms -> ns
                .queryParam("end", end * 1_000_000L)
                .queryParam("limit", lim)
                .queryParam("direction", "backward")
                .build(logql);
        try {
            String body = lokiRestClient.get().uri(uri).retrieve().body(String.class);
            return new LogQueryResult(parser.parse(body), null);
        } catch (Exception e) {
            log.warn("Loki query failed: {}", e.getMessage());
            return new LogQueryResult(List.of(), "Loki unreachable: " + e.getMessage());
        }
    }

    public List<String> labelValues(String name) {
        String uri = baseUrl() + "/loki/api/v1/label/" + name + "/values";
        try {
            String body = lokiRestClient.get().uri(uri).retrieve().body(String.class);
            return parser.parseLabelValues(body);
        } catch (Exception e) {
            log.warn("Loki label query failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String baseUrl() {
        return settings.get("gateway.loki.url", DEFAULT_URL);
    }
}
