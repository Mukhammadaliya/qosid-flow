package uz.greenwhite.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.api")
public class ApiProperties {

    /**
     * Base URL of the main application.
     * API proxy requests are forwarded to this URL + stripped URI path.
     * Example: https://smartup.online/b
     */
    private String baseUrl;

    @PostConstruct
    public void init() {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("gateway.api.base-url is not configured — API proxy requests will fail");
        } else {
            log.info("API proxy target: {}", baseUrl);
        }
    }
}
