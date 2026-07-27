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
     * Runaway guard, NOT a retry policy. Since the requeue is time-delayed (see
     * {@link #cbRequeueDelayMs}) a message needs at most
     * {@code maxMessageAgeMs / cbRequeueDelayMs} requeues (~150 at the defaults) to wait out any
     * realistic outage, so this only fires if the delay logic itself breaks. It was 50 until
     * 2026-07-27, when a 30 s circuit window DLQ'd 212 live production requests in one second —
     * see SAFETY-RULES 23.
     */
    private int cbMaxRequeues = 1_000;

    /**
     * First wait before re-checking a blocked circuit, in ms. Doubles on each re-check up to
     * {@link #cbRequeueMaxDelayMs}. This is what replaced the old spin-requeue: at Kafka
     * round-trip speed (~20 ms) a 30 s circuit window cost ~1500 pointless round trips per
     * message, and the count cap turned that into false DLQ records after ~1 s.
     */
    private long cbRequeueDelayMs = 2_000;

    /**
     * Upper bound for the doubling wait, in ms. Defaults to one
     * {@code resilience4j.circuitbreaker.*.wait-duration-in-open-state} (30 s) — waiting longer
     * than the window cannot help, and {@link #maxMessageAgeMs} is what ultimately gives up.
     */
    private long cbRequeueMaxDelayMs = 30_000;

    /** Max messages held in memory waiting for a circuit, per flow. Beyond this: DLQ. */
    private int cbMaxPending = 5_000;

    /**
     * Max total held body bytes per flow. A REST body can be 20 MiB
     * ({@code gateway.proxy.max-body-size}), so a count bound alone does not bound memory.
     */
    private long cbMaxPendingBytes = 268_435_456;   // 256 MiB

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