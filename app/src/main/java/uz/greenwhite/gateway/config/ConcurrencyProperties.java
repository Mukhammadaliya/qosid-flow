package uz.greenwhite.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.concurrency")
public class ConcurrencyProperties {

    // Oracle flow
    private int oracleMin = 5;
    private int oracleMax = 10;

    // API flow
    private int apiMin = 5;
    private int apiMax = 10;

    // Shared settings
    private long monitorIntervalMs = 10_000;
    private long scaleUpThreshold = 50;
    private long scaleDownThreshold = 10;
    private int scaleStep = 2;
    private long scaleCooldownMs = 30_000;
    private int topicPartitions = 10;
    private int threadPoolMultiplier = 2;
    private int threadPoolQueueCapacity = 50;

    @PostConstruct
    public void validate() {
        validateRange("oracle", oracleMin, oracleMax);
        validateRange("api", apiMin, apiMax);

        if (scaleUpThreshold <= scaleDownThreshold) {
            throw new IllegalArgumentException("scaleUpThreshold must be > scaleDownThreshold");
        }

        log.info("Concurrency config: oracle[min={}, max={}], api[min={}, max={}], " +
                        "partitions={}, poolMultiplier={}, poolQueue={}",
                oracleMin, oracleMax, apiMin, apiMax,
                topicPartitions, threadPoolMultiplier, threadPoolQueueCapacity);
    }

    private void validateRange(String name, int min, int max) {
        if (min < 1) throw new IllegalArgumentException(name + "Min must be >= 1");
        if (max < min) throw new IllegalArgumentException(name + "Max must be >= " + name + "Min");
        if (max > topicPartitions) {
            log.warn("{}Max ({}) exceeds topicPartitions ({}), capping", name, max, topicPartitions);
        }
    }
}
