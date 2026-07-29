package uz.greenwhite.gateway.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class LokiClientConfig {

    /** Bare RestClient — the absolute Loki URI is built per-request from settings. */
    @Bean
    public RestClient lokiRestClient() {
        return RestClient.builder().build();
    }

    /** Plain class (unit-testable without Spring); exposed as a bean for {@link LogQueryService}. */
    @Bean
    public LokiResponseParser lokiResponseParser(ObjectMapper objectMapper) {
        return new LokiResponseParser(objectMapper);
    }
}
