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
