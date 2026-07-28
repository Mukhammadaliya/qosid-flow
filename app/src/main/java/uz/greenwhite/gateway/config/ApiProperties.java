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

    /**
     * Upper bound on concurrent in-flight HTTP calls per upstream host, enforced by
     * {@code HostConcurrencyLimiter} in the E4 send path (both Oracle and API flows).
     *
     * <p>Why 25: in the 2026-07-27 incident a restart backlog was dispatched at ~67 requests/second
     * against one host, which opened its circuit (≥5 of 10 sliding-window calls failed). 25
     * concurrent calls at a ~200 ms upstream latency sustain ~125 req/s — comfortably above that
     * arrival rate, so a backlog still drains quickly, while never presenting the upstream with
     * hundreds of simultaneous connections.</p>
     *
     * <p>A refused permit is NOT a failure: the send path answers with the same CB-blocked-shaped
     * response ({@code errorSource=CIRCUIT_BREAKER}, 503), so the message takes Phase 1's
     * park-and-recheck path and returns shortly. {@code <= 0} disables the limit entirely —
     * operator escape hatch, same convention as {@code gateway.proxy.max-body-size}.</p>
     */
    private int maxConcurrentPerHost = 25;

    @PostConstruct
    public void init() {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("gateway.api.base-url is not configured — API proxy requests will fail");
        } else {
            log.info("API proxy target: {}", baseUrl);
        }
    }
}
