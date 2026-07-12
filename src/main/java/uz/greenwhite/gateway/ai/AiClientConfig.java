package uz.greenwhite.gateway.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AiClientConfig {

    /** Bare RestClient — the absolute LLM URI + bearer header are set per-request. */
    @Bean
    public RestClient aiRestClient() {
        return RestClient.builder().build();
    }
}
