package uz.greenwhite.gateway.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.concurrency.ConcurrencyMonitorService;
import uz.greenwhite.gateway.concurrency.DynamicConcurrencyManager;
import uz.greenwhite.gateway.config.KafkaProperties;

@Slf4j
@Component("gateway")
public class SystemHealthIndicator implements HealthIndicator {

    private final ConcurrencyMonitorService monitorService;
    private final DynamicConcurrencyManager concurrencyManager;
    private final ThreadPoolTaskExecutor oracleHttpExecutor;
    private final ThreadPoolTaskExecutor apiHttpExecutor;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final KafkaProperties kafkaProperties;

    public SystemHealthIndicator(
            ConcurrencyMonitorService monitorService,
            DynamicConcurrencyManager concurrencyManager,
            @Qualifier("oracleHttpExecutor") ThreadPoolTaskExecutor oracleHttpExecutor,
            @Qualifier("apiHttpExecutor") ThreadPoolTaskExecutor apiHttpExecutor,
            CircuitBreakerRegistry circuitBreakerRegistry,
            KafkaProperties kafkaProperties) {
        this.monitorService = monitorService;
        this.concurrencyManager = concurrencyManager;
        this.oracleHttpExecutor = oracleHttpExecutor;
        this.apiHttpExecutor = apiHttpExecutor;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public Health health() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("externalApi");
        CircuitBreaker.Metrics cbMetrics = cb.getMetrics();

        boolean circuitBreakerOpen = cb.getState() == CircuitBreaker.State.OPEN;

        Health.Builder builder = circuitBreakerOpen ? Health.down() : Health.up();

        return builder
                // Oracle Kafka Consumer
                .withDetail("kafka.oracleRequestConsumer.concurrency",
                        concurrencyManager.getCurrentConcurrency("oracleRequestConsumer"))
                .withDetail("kafka.oracleRequestConsumer.lag",
                        monitorService.getLastKnownLag(kafkaProperties.getTopics().getOracleRequest()))
                .withDetail("kafka.oracleResponseConsumer.concurrency",
                        concurrencyManager.getCurrentConcurrency("oracleResponseConsumer"))
                .withDetail("kafka.oracleResponseConsumer.lag",
                        monitorService.getLastKnownLag(kafkaProperties.getTopics().getOracleResponse()))
                // API Kafka Consumer
                .withDetail("kafka.apiRequestConsumer.concurrency",
                        concurrencyManager.getCurrentConcurrency("apiRequestConsumer"))
                .withDetail("kafka.apiRequestConsumer.lag",
                        monitorService.getLastKnownLag(kafkaProperties.getTopics().getApiRequest()))
                .withDetail("kafka.apiResponseConsumer.concurrency",
                        concurrencyManager.getCurrentConcurrency("apiResponseConsumer"))
                .withDetail("kafka.apiResponseConsumer.lag",
                        monitorService.getLastKnownLag(kafkaProperties.getTopics().getApiResponse()))
                // Oracle HTTP Thread Pool
                .withDetail("http.oracle.pool.active", oracleHttpExecutor.getActiveCount())
                .withDetail("http.oracle.pool.size", oracleHttpExecutor.getThreadPoolExecutor().getPoolSize())
                .withDetail("http.oracle.pool.queue", oracleHttpExecutor.getThreadPoolExecutor().getQueue().size())
                // API HTTP Thread Pool
                .withDetail("http.api.pool.active", apiHttpExecutor.getActiveCount())
                .withDetail("http.api.pool.size", apiHttpExecutor.getThreadPoolExecutor().getPoolSize())
                .withDetail("http.api.pool.queue", apiHttpExecutor.getThreadPoolExecutor().getQueue().size())
                // Circuit Breaker
                .withDetail("circuitBreaker.state", cb.getState().name())
                .withDetail("circuitBreaker.failureRate", cbMetrics.getFailureRate() + "%")
                .withDetail("circuitBreaker.slowCallRate", cbMetrics.getSlowCallRate() + "%")
                .withDetail("circuitBreaker.bufferedCalls", cbMetrics.getNumberOfBufferedCalls())
                .withDetail("circuitBreaker.failedCalls", cbMetrics.getNumberOfFailedCalls())
                .build();
    }
}
