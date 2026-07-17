package uz.greenwhite.gateway.storage;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring configuration that owns the singleton {@link RocksDB} instance
 * used as response-body storage. The instance is closed on application shutdown.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RocksDBConfig {

    private final RocksDBProperties properties;

    private RocksDB rocksDB;

    @Bean
    public RocksDB rocksDB() throws RocksDBException, IOException {
        RocksDB.loadLibrary();

        Path dbPath = Paths.get(properties.getPath());
        if (!Files.exists(dbPath)) {
            Files.createDirectories(dbPath);
            log.info("RocksDB path created: {}", dbPath.toAbsolutePath());
        }

        Options options = new Options().setCreateIfMissing(true);
        rocksDB = RocksDB.open(options, dbPath.toString());

        log.info("RocksDB opened at: {}", dbPath.toAbsolutePath());
        return rocksDB;
    }

    @PreDestroy
    public void close() {
        if (rocksDB != null) {
            try {
                rocksDB.close();
                log.info("RocksDB closed cleanly");
            } catch (Exception e) {
                log.error("Error while closing RocksDB: {}", e.getMessage(), e);
            }
        }
    }
}
