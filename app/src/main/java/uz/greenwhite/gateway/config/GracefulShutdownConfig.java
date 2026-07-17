package uz.greenwhite.gateway.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class GracefulShutdownConfig {

    private final KafkaListenerEndpointRegistry kafkaRegistry;
    private final ThreadPoolTaskExecutor oracleHttpExecutor;
    private final ThreadPoolTaskExecutor apiHttpExecutor;

    public GracefulShutdownConfig(
            KafkaListenerEndpointRegistry kafkaRegistry,
            @Qualifier("oracleHttpExecutor") ThreadPoolTaskExecutor oracleHttpExecutor,
            @Qualifier("apiHttpExecutor") ThreadPoolTaskExecutor apiHttpExecutor) {
        this.kafkaRegistry = kafkaRegistry;
        this.oracleHttpExecutor = oracleHttpExecutor;
        this.apiHttpExecutor = apiHttpExecutor;
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Graceful shutdown started...");

        // 1. Stop Kafka consumers — no new messages
        stopKafkaConsumers();

        // 2. Shutdown both HTTP executors — wait for in-flight requests
        shutdownExecutor("oracle", oracleHttpExecutor);
        shutdownExecutor("api", apiHttpExecutor);

        log.info("Graceful shutdown completed");
    }

    private void stopKafkaConsumers() {
        log.info("Stopping Kafka consumers...");
        try {
            kafkaRegistry.getListenerContainers().forEach(container -> {
                log.info("Stopping Kafka listener: {}", container.getListenerId());
                container.stop();
            });
            log.info("All Kafka consumers stopped");
        } catch (Exception e) {
            log.error("Error stopping Kafka consumers: {}", e.getMessage(), e);
        }
    }

    private void shutdownExecutor(String name, ThreadPoolTaskExecutor executor) {
        log.info("Shutting down {} HTTP executor. Active: {}, Queue: {}",
                name, executor.getActiveCount(),
                executor.getThreadPoolExecutor().getQueue().size());

        executor.getThreadPoolExecutor().shutdown();

        try {
            if (!executor.getThreadPoolExecutor().awaitTermination(25, TimeUnit.SECONDS)) {
                log.warn("{} HTTP executor did not terminate in 25s, forcing shutdown. Active: {}",
                        name, executor.getActiveCount());
                executor.getThreadPoolExecutor().shutdownNow();
            } else {
                log.info("{} HTTP executor terminated gracefully", name);
            }
        } catch (InterruptedException e) {
            log.warn("{} shutdown interrupted, forcing...", name);
            executor.getThreadPoolExecutor().shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
