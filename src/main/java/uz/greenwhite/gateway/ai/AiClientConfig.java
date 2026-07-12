package uz.greenwhite.gateway.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiClientConfig {

    /**
     * RestClient with bounded timeouts (connect 5s, read 30s) so a hung LLM endpoint
     * cannot pin a servlet thread forever. The absolute LLM URI + bearer header are
     * set per-request.
     */
    @Bean
    public RestClient aiRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        return RestClient.builder().requestFactory(factory).build();
    }
}
