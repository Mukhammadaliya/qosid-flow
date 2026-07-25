package uz.greenwhite.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.retry")
public class RetryProperties {

    /**
     * Maximum retry attempts
     * Default: 3
     */
    private int maxAttempts = 3;

    /**
     * Interval between retries in milliseconds
     * Default: 3000 (3 seconds)
     */
    private long intervalMs = 3000;

    /**
     * Comma-separated HTTP status codes that are retryable
     * Default: 408,429,500,502,503,504
     */
    private String retryableStatuses = "408,429,500,502,503,504";

    /**
     * Maximum age of a message in milliseconds before it is considered expired.
     * Messages older than this are immediately failed (prevents infinite re-queue loops).
     * Default: 300000 (5 minutes)
     */
    private long maxMessageAgeMs = 300_000;

    /**
     * Maximum number of CB-blocked re-queues for one message before it is sent to the DLQ.
     * SAFETY-RULES 23 keeps attemptCount untouched on that path, so this separate cap is
     * what bounds the loop and stops the Kafka write amplification a long CB outage causes.
     * Default: 50
     */
    private int cbMaxRequeues = 50;

    /**
     * Cached set of retryable status codes, parsed once at startup
     */
    private Set<Integer> retryableStatusSet;

    @PostConstruct
    public void init() {
        this.retryableStatusSet = Collections.unmodifiableSet(
                Arrays.stream(retryableStatuses.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Integer::parseInt)
                        .collect(Collectors.toSet())
        );
    }

    /**
     * Check if HTTP status is retryable
     */
    public boolean isRetryable(int httpStatus) {
        return retryableStatusSet.contains(httpStatus);
    }
}