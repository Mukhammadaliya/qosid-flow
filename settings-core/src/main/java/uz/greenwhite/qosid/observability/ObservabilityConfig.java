package uz.greenwhite.qosid.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import uz.greenwhite.qosid.observability.logs.LogQueryService;
import uz.greenwhite.qosid.observability.logs.LokiResponseParser;
import uz.greenwhite.qosid.observability.stats.PrometheusResponseParser;
import uz.greenwhite.qosid.observability.stats.StatsQueryService;

/**
 * Wiring for the read-only Loki/Prometheus proxies, shared by both JVMs.
 *
 * <p>settings-core is never component-scanned (the same convention as
 * {@code SettingsRepository} and friends) — each application {@code @Import}s this
 * class and supplies a {@link SettingsResolver}. Both proxies degrade to an empty
 * result plus an error string when their backend is unreachable, so importing this
 * never adds a way for the console to answer 500.
 */
@Configuration
public class ObservabilityConfig {

    /** Bare client — the absolute Loki URI is rebuilt per request from settings. */
    @Bean
    public RestClient lokiRestClient() {
        return RestClient.builder().build();
    }

    /** Bare client — the absolute Prometheus URI is rebuilt per request from settings. */
    @Bean
    public RestClient prometheusRestClient() {
        return RestClient.builder().build();
    }

    @Bean
    public LokiResponseParser lokiResponseParser(ObjectMapper objectMapper) {
        return new LokiResponseParser(objectMapper);
    }

    @Bean
    public PrometheusResponseParser prometheusResponseParser(ObjectMapper objectMapper) {
        return new PrometheusResponseParser(objectMapper);
    }

    @Bean
    public LogQueryService logQueryService(LokiResponseParser lokiResponseParser, SettingsResolver settingsResolver,
                                           @Value("${gateway.loki.url:http://localhost:3100}") String lokiUrl) {
        return new LogQueryService(lokiRestClient(), lokiResponseParser, settingsResolver, lokiUrl);
    }

    @Bean
    public StatsQueryService statsQueryService(PrometheusResponseParser prometheusResponseParser,
                                              SettingsResolver settingsResolver,
                                              @Value("${gateway.prometheus.url:http://localhost:9090}") String prometheusUrl) {
        return new StatsQueryService(prometheusRestClient(), prometheusResponseParser, settingsResolver, prometheusUrl);
    }
}
