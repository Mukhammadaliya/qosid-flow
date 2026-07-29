package uz.greenwhite.gateway.concurrency;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.config.KafkaProperties;

@Slf4j
@Component
public class ConcurrencyMetrics {

    private final MeterRegistry meterRegistry;
    private final DynamicConcurrencyManager concurrencyManager;
    private final ConcurrencyMonitorService monitorService;
    private final ThreadPoolTaskExecutor oracleHttpExecutor;
    private final ThreadPoolTaskExecutor apiHttpExecutor;
    private final KafkaProperties kafkaProperties;

    public ConcurrencyMetrics(
            MeterRegistry meterRegistry,
            DynamicConcurrencyManager concurrencyManager,
            ConcurrencyMonitorService monitorService,
            @Qualifier("oracleHttpExecutor") ThreadPoolTaskExecutor oracleHttpExecutor,
            @Qualifier("apiHttpExecutor") ThreadPoolTaskExecutor apiHttpExecutor,
            KafkaProperties kafkaProperties) {
        this.meterRegistry = meterRegistry;
        this.concurrencyManager = concurrencyManager;
        this.monitorService = monitorService;
        this.oracleHttpExecutor = oracleHttpExecutor;
        this.apiHttpExecutor = apiHttpExecutor;
        this.kafkaProperties = kafkaProperties;
    }

    @PostConstruct
    public void registerMetrics() {

        // ==================== ORACLE CONSUMER METRICS ====================

        registerConsumerMetrics("oracleRequestConsumer",
                kafkaProperties.getTopics().getOracleRequest(), "oracle-request");

        registerConsumerMetrics("oracleResponseConsumer",
                kafkaProperties.getTopics().getOracleResponse(), "oracle-response");

        // ==================== API CONSUMER METRICS ====================

        registerConsumerMetrics("apiRequestConsumer",
                kafkaProperties.getTopics().getApiRequest(), "api-request");

        registerConsumerMetrics("apiResponseConsumer",
                kafkaProperties.getTopics().getApiResponse(), "api-response");

        // ==================== HTTP THREAD POOL METRICS ====================

        registerPoolMetrics("oracle", oracleHttpExecutor);
        registerPoolMetrics("api", apiHttpExecutor);

        log.info("Concurrency metrics registered (oracle + api)");
    }

    private void registerConsumerMetrics(String listenerId, String topic, String topicTag) {
        Gauge.builder("gateway.kafka.consumer.concurrency",
                        () -> concurrencyManager.getCurrentConcurrency(listenerId))
                .description("Current Kafka consumer concurrency level")
                .tag("listener", listenerId)
                .register(meterRegistry);

        Gauge.builder("gateway.kafka.consumer.lag",
                        () -> monitorService.getLastKnownLag(topic))
                .description("Current Kafka consumer lag")
                .tag("topic", topicTag)
                .tag("listener", listenerId)
                .register(meterRegistry);
    }

    private void registerPoolMetrics(String flow, ThreadPoolTaskExecutor executor) {
        Gauge.builder("gateway.http.pool.active", executor::getActiveCount)
                .description("Active HTTP request threads")
                .tag("flow", flow)
                .register(meterRegistry);

        Gauge.builder("gateway.http.pool.size", executor::getPoolSize)
                .description("Current HTTP thread pool size")
                .tag("flow", flow)
                .register(meterRegistry);

        Gauge.builder("gateway.http.pool.queue", () -> executor.getThreadPoolExecutor().getQueue().size())
                .description("HTTP thread pool queue size")
                .tag("flow", flow)
                .register(meterRegistry);
    }
}
