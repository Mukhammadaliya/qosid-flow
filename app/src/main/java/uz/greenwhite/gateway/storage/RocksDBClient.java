package uz.greenwhite.gateway.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Low-level wrapper around the singleton {@link RocksDB} instance.
 * Stores response bodies under {@code response:<compositeId>} and metadata
 * under {@code response_meta:<compositeId>}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RocksDBClient {

    private static final String RESPONSE_PREFIX = "response:";
    private static final String RESPONSE_META_PREFIX = "response_meta:";

    private final RocksDB rocksDB;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== RESPONSE BODY ====================

    public void putResponse(String compositeId, byte[] body) {
        byte[] key = bodyKey(compositeId);
        try {
            rocksDB.put(key, body);
        } catch (RocksDBException e) {
            log.error("RocksDB putResponse failed for {}: {}", compositeId, e.getMessage(), e);
            throw new StorageWriteException("RocksDB body write failed for " + compositeId, e);
        }
    }

    public byte[] getResponse(String compositeId) {
        byte[] key = bodyKey(compositeId);
        try {
            return rocksDB.get(key);
        } catch (RocksDBException e) {
            log.error("RocksDB getResponse failed for {}: {}", compositeId, e.getMessage(), e);
            return null;
        }
    }

    // ==================== RESPONSE METADATA ====================

    public void putResponseMetadata(String compositeId, ResponseMetadata meta) {
        byte[] key = metaKey(compositeId);
        try {
            byte[] value = objectMapper.writeValueAsBytes(meta);
            rocksDB.put(key, value);
        } catch (RocksDBException e) {
            log.error("RocksDB putResponseMetadata failed for {}: {}", compositeId, e.getMessage(), e);
            throw new StorageWriteException("RocksDB metadata write failed for " + compositeId, e);
        } catch (Exception e) {
            log.error("Failed to serialize ResponseMetadata for {}: {}", compositeId, e.getMessage(), e);
            throw new StorageWriteException("ResponseMetadata serialization failed for " + compositeId, e);
        }
    }

    public ResponseMetadata getResponseMetadata(String compositeId) {
        byte[] key = metaKey(compositeId);
        try {
            byte[] value = rocksDB.get(key);
            if (value == null) {
                return null;
            }
            return objectMapper.readValue(value, ResponseMetadata.class);
        } catch (RocksDBException e) {
            log.error("RocksDB getResponseMetadata failed for {}: {}", compositeId, e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("Failed to deserialize ResponseMetadata for {}: {}", compositeId, e.getMessage(), e);
            return null;
        }
    }

    // ==================== DELETE ====================

    public void deleteResponse(String compositeId) {
        try {
            rocksDB.delete(bodyKey(compositeId));
            rocksDB.delete(metaKey(compositeId));
        } catch (RocksDBException e) {
            log.error("RocksDB deleteResponse failed for {}: {}", compositeId, e.getMessage(), e);
        }
    }

    // ==================== ITERATION ====================

    /**
     * Walk every metadata entry, calling the consumer with (compositeId, metadata).
     * Used by the cleanup job to evict expired responses.
     */
    public void iterateMetadata(BiConsumer<String, ResponseMetadata> consumer) {
        byte[] prefixBytes = RESPONSE_META_PREFIX.getBytes(StandardCharsets.UTF_8);
        try (RocksIterator it = rocksDB.newIterator()) {
            it.seek(prefixBytes);
            while (it.isValid()) {
                byte[] keyBytes = it.key();
                String fullKey = new String(keyBytes, StandardCharsets.UTF_8);
                if (!fullKey.startsWith(RESPONSE_META_PREFIX)) {
                    break; // left the prefix range
                }
                String compositeId = fullKey.substring(RESPONSE_META_PREFIX.length());
                try {
                    ResponseMetadata meta = objectMapper.readValue(it.value(), ResponseMetadata.class);
                    consumer.accept(compositeId, meta);
                } catch (Exception e) {
                    log.warn("Skipping unreadable metadata for key {}: {}", fullKey, e.getMessage());
                }
                it.next();
            }
        }
    }

    // ==================== HELPERS ====================

    private static byte[] bodyKey(String compositeId) {
        return (RESPONSE_PREFIX + compositeId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] metaKey(String compositeId) {
        return (RESPONSE_META_PREFIX + compositeId).getBytes(StandardCharsets.UTF_8);
    }
}
