package uz.greenwhite.gateway.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-level façade over {@link RocksDBClient} for storing HTTP response bodies.
 *
 * <p>Bodies are retained for {@link RocksDBProperties#getResponseRetentionDays()} days
 * and the cleanup job runs on the cron defined by
 * {@link RocksDBProperties#getCleanupCron()}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResponseStorageService {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final RocksDBClient rocksDBClient;
    private final RocksDBProperties rocksDBProperties;

    /**
     * Persist a response body and its metadata.
     */
    public void saveResponse(String compositeId, byte[] body, int httpStatus, String contentType) {
        ResponseMetadata meta = ResponseMetadata.builder()
                .httpStatus(httpStatus)
                .contentType(contentType)
                .contentLength(body != null ? body.length : 0L)
                .savedAt(System.currentTimeMillis())
                .build();

        rocksDBClient.putResponse(compositeId, body);
        rocksDBClient.putResponseMetadata(compositeId, meta);

        log.debug("Response saved to RocksDB: {}", compositeId);
    }

    public byte[] getResponseBody(String compositeId) {
        return rocksDBClient.getResponse(compositeId);
    }

    public ResponseMetadata getResponseMetadata(String compositeId) {
        return rocksDBClient.getResponseMetadata(compositeId);
    }

    /**
     * Scheduled cleanup of responses older than the configured retention period.
     * Runs on the cron defined in {@code gateway.rocksdb.cleanup-cron}.
     */
    @Scheduled(cron = "#{rocksDBProperties.cleanupCron}")
    public void cleanupExpiredResponses() {
        long cutoffTime = System.currentTimeMillis()
                - (rocksDBProperties.getResponseRetentionDays() * MILLIS_PER_DAY);

        AtomicInteger deleted = new AtomicInteger();
        AtomicInteger kept = new AtomicInteger();

        rocksDBClient.iterateMetadata((compositeId, meta) -> {
            if (meta.getSavedAt() < cutoffTime) {
                rocksDBClient.deleteResponse(compositeId);
                deleted.incrementAndGet();
            } else {
                kept.incrementAndGet();
            }
        });

        log.info("RocksDB cleanup complete: deleted={}, retained={}, retentionDays={}",
                deleted.get(), kept.get(), rocksDBProperties.getResponseRetentionDays());
    }
}
