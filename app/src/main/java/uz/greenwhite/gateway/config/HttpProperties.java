package uz.greenwhite.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.http")
public class HttpProperties {

    private int connectTimeoutMs;
    private int readTimeoutMs;
    private int writeTimeoutMs;

    /**
     * Maximum number of concurrent connections in the Reactor Netty pool.
     */
    private int maxConnections = 500;

    /**
     * Maximum idle time (ms) before an idle connection is evicted.
     */
    private long maxIdleTimeMs = 60_000;

    /**
     * Maximum total lifetime (ms) of a connection regardless of usage.
     */
    private long maxLifeTimeMs = 300_000;

    /**
     * Maximum time (ms) a request will wait for a free connection from the pool.
     */
    private long pendingAcquireTimeoutMs = 45_000;

    @PostConstruct
    public void validate() {
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException("gateway.http.connect-timeout-ms must be > 0");
        }
        if (readTimeoutMs <= 0) {
            throw new IllegalArgumentException("gateway.http.read-timeout-ms must be > 0");
        }
        if (writeTimeoutMs <= 0) {
            throw new IllegalArgumentException("gateway.http.write-timeout-ms must be > 0");
        }

        log.info("HTTP client config: connect={}ms, read={}ms, write={}ms, " +
                        "pool[maxConn={}, idle={}ms, life={}ms, acquire={}ms]",
                connectTimeoutMs, readTimeoutMs, writeTimeoutMs,
                maxConnections, maxIdleTimeMs, maxLifeTimeMs, pendingAcquireTimeoutMs);
    }

    /**
     * Per-endpoint timeout overrides in milliseconds.
     * Key: domain or host (e.g., "api.example.com")
     * Value: read timeout in ms
     * If not found, falls back to readTimeoutMs.
     *
     * Example config:
     *   gateway.http.endpoint-timeouts:
     *     api.slow-service.com: 60000
     *     api.fast-service.com: 5000
     */
    private Map<String, Integer> endpointTimeouts = new HashMap<>();

    /**
     * Get timeout for specific URL. Falls back to global readTimeoutMs.
     */
    public int getTimeoutForUrl(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host != null && endpointTimeouts.containsKey(host)) {
                return endpointTimeouts.get(host);
            }
        } catch (Exception ignored) {}
        return readTimeoutMs;
    }
}