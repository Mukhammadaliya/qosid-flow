package uz.greenwhite.gateway.concurrency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.config.ConcurrencyProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicConcurrencyManager {

    private final KafkaListenerEndpointRegistry registry;
    private final ConcurrencyProperties properties;

    /**
     * Separate state tracking for each listener
     */
    private final Map<String, AtomicInteger> concurrencyMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastScaleTimeMap = new ConcurrentHashMap<>();

    /**
     * Adjust concurrency for a specific listener based on current consumer lag.
     *
     * @param listenerId the @KafkaListener id value
     * @param currentLag current consumer lag (sum across all partitions)
     */
    public void adjustConcurrency(String listenerId, long currentLag) {
        MessageListenerContainer container = registry.getListenerContainer(listenerId);

        if (container == null) {
            log.warn("Listener container not found: {}", listenerId);
            return;
        }

        if (!(container instanceof ConcurrentMessageListenerContainer<?, ?> concurrent)) {
            log.warn("Container is not ConcurrentMessageListenerContainer: {}", listenerId);
            return;
        }

        // Get or create state for this listener
        AtomicInteger currentConcurrency = concurrencyMap.computeIfAbsent(
                listenerId, k -> new AtomicInteger(0));
        AtomicLong lastScaleTime = lastScaleTimeMap.computeIfAbsent(
                listenerId, k -> new AtomicLong(0));

        int actual = concurrent.getConcurrency();
        if (currentConcurrency.compareAndSet(0, actual)) {
            log.info("Initial concurrency detected for [{}]: {}", listenerId, actual);
        }

        int minConcurrency = resolveMin(listenerId);
        int maxConcurrency = resolveMax(listenerId);
        int desired = calculateDesiredConcurrency(currentLag, minConcurrency, maxConcurrency);

        // -1 means no change needed (lag is in neutral zone)
        if (desired == -1) {
            log.debug("Lag {} is in neutral zone, no scaling needed for [{}]", currentLag, listenerId);
            return;
        }

        int current = currentConcurrency.get();

        // Already at desired level
        if (desired == current) {
            log.debug("Concurrency already at desired level {} for [{}]", current, listenerId);
            return;
        }

        // Cooldown check — prevent too frequent scaling
        long now = System.currentTimeMillis();
        long lastScale = lastScaleTime.get();
        if (now - lastScale < properties.getScaleCooldownMs()) {
            log.debug("Scaling cooldown active for [{}]. Last scale: {}ms ago",
                    listenerId, now - lastScale);
            return;
        }

        // Step-based scaling (gradual increase/decrease)
        int newConcurrency;
        if (desired > current) {
            newConcurrency = Math.min(current + properties.getScaleStep(), desired);
        } else {
            newConcurrency = Math.max(current - properties.getScaleStep(), desired);
        }

        // Enforce min/max bounds
        newConcurrency = Math.max(minConcurrency, newConcurrency);
        newConcurrency = Math.min(maxConcurrency, newConcurrency);

        if (newConcurrency == current) {
            return;
        }

        // Apply new concurrency level
        try {
            concurrent.setConcurrency(newConcurrency);

            // Instead of stop/start — only applied on next rebalance.
            // This does not halt existing threads, just sets the new target.
            if (newConcurrency > current) {
                // Scale UP would need a restart to spawn new consumer threads, but a
                // restart rebalances the whole consumer group and every in-flight
                // ack that lands after stop() is a no-op — those offsets stay
                // uncommitted and get redelivered, i.e. messages are lost exactly
                // when load is highest (SAFETY-RULES 32). Off by default; the new
                // target still applies on the next natural rebalance or restart.
                if (properties.isAllowRestartOnScaleUp()) {
                    concurrent.stop();
                    concurrent.start();
                } else {
                    log.info("Scale-up target set for [{}] but restart is disabled — "
                                    + "concurrency {} applies on next rebalance/restart",
                            listenerId, newConcurrency);
                }
            }
            // Scale DOWN — no stop/start needed, threads reduce on next rebalance

            currentConcurrency.set(newConcurrency);
            lastScaleTime.set(now);

            String direction = newConcurrency > current ? "⬆ SCALED UP" : "⬇ SCALED DOWN";
            log.info("{} [{}]: {} → {} (lag: {})",
                    direction, listenerId, current, newConcurrency, currentLag);

        } catch (Exception e) {
            log.error("Failed to adjust concurrency for [{}]: {}", listenerId, e.getMessage(), e);
        }
    }

    private int resolveMin(String listenerId) {
        return listenerId.startsWith("api") ? properties.getApiMin() : properties.getOracleMin();
    }

    private int resolveMax(String listenerId) {
        return listenerId.startsWith("api") ? properties.getApiMax() : properties.getOracleMax();
    }

    private int calculateDesiredConcurrency(long currentLag, int min, int max) {
        if (currentLag <= properties.getScaleDownThreshold()) return min;
        if (currentLag > properties.getScaleUpThreshold()) {
            double ratio = Math.min(1.0,
                    (double) (currentLag - properties.getScaleUpThreshold()) / (properties.getScaleUpThreshold() * 3));
            int desired = min + (int) Math.ceil((max - min) * ratio);
            return Math.min(desired, max);
        }
        return -1; // neutral zone
    }

    /**
     * Get the current concurrency level for a specific listener.
     */
    public int getCurrentConcurrency(String listenerId) {
        AtomicInteger val = concurrencyMap.get(listenerId);
        return val != null ? val.get() : 0;
    }

    /**
     * Get the last scaling timestamp for a specific listener.
     */
    public long getLastScaleTime(String listenerId) {
        AtomicLong val = lastScaleTimeMap.get(listenerId);
        return val != null ? val.get() : 0;
    }
}