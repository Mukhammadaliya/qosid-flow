package uz.greenwhite.gateway.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the RocksDB-backed response body storage.
 * Bound to {@code gateway.rocksdb.*} in application.yml.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.rocksdb")
public class RocksDBProperties {

    /**
     * Filesystem path where the RocksDB database lives.
     * Created automatically if missing.
     */
    private String path = "./data/rocksdb";

    /**
     * Number of days to retain a stored response before the cleanup job evicts it.
     */
    private int responseRetentionDays = 7;

    /**
     * Spring cron expression for the cleanup job.
     * Default: midnight every day.
     */
    private String cleanupCron = "0 0 0 * * *";
}
