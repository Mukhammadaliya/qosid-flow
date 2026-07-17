package uz.greenwhite.gateway.settings;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.greenwhite.qosid.settings.SettingsCipher;
import uz.greenwhite.qosid.settings.SettingsRecord;
import uz.greenwhite.qosid.settings.SettingsRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single entry point for runtime settings. Cache-backed (one instance, no
 * distributed invalidation). Secret values are stored AES-encrypted; the cache
 * holds the encrypted form and decrypts on {@link #getSecret}. Audit masks secrets.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final String MASK = "***";

    private final SettingsRepository repository;
    private final SettingsCipher cipher;

    /** key -> record (secret records hold encrypted value). */
    private final ConcurrentHashMap<String, SettingsRecord> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void reload() {
        cache.clear();
        for (SettingsRecord r : repository.findAll()) {
            cache.put(r.getKey(), r);
        }
        log.info("SettingsService cache loaded: {} entries", cache.size());
    }

    public String get(String key, String defaultValue) {
        SettingsRecord r = cache.get(key);
        return r != null && r.getValue() != null ? r.getValue() : defaultValue;
    }

    public String getSecret(String key, String defaultValue) {
        SettingsRecord r = cache.get(key);
        if (r == null || r.getValue() == null) return defaultValue;
        if (!r.isSecret()) return r.getValue();
        if (!cipher.isEnabled()) {
            log.warn("getSecret({}) but cipher disabled — returning default", key);
            return defaultValue;
        }
        return cipher.decrypt(r.getValue());
    }

    public void set(String key, String value, boolean secret, String changedBy) {
        SettingsRecord prev = cache.get(key);
        String stored = value;
        if (secret) {
            if (!cipher.isEnabled()) {
                throw new IllegalStateException("Cannot store secret '" + key + "': encryption disabled");
            }
            stored = cipher.encrypt(value);
        }
        repository.upsert(key, stored, secret, changedBy);
        String oldForAudit = prev == null ? null : (prev.isSecret() ? MASK : prev.getValue());
        String newForAudit = secret ? MASK : value;
        repository.insertAudit(key, oldForAudit, newForAudit, changedBy);
        cache.put(key, SettingsRecord.builder()
                .key(key).value(stored).secret(secret).updatedBy(changedBy).build());
    }

    public List<SettingsRecord> getAllMasked() {
        List<SettingsRecord> out = new ArrayList<>();
        for (SettingsRecord r : cache.values()) {
            out.add(SettingsRecord.builder()
                    .key(r.getKey())
                    .value(r.isSecret() ? MASK : r.getValue())
                    .secret(r.isSecret())
                    .updatedAt(r.getUpdatedAt())
                    .updatedBy(r.getUpdatedBy())
                    .build());
        }
        out.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        return out;
    }
}
