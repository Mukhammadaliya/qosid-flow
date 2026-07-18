package uz.greenwhite.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import uz.greenwhite.qosid.settings.SettingsCipher;
import uz.greenwhite.qosid.settings.SettingsRecord;
import uz.greenwhite.qosid.settings.SettingsRegistry;
import uz.greenwhite.qosid.settings.SettingsRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stop-to-edit settings logic for the ops sidecar: settings are only writable
 * while the gateway JVM is DOWN (it snapshots the DB into its Environment at
 * boot — editing a running service would silently diverge DB from runtime).
 * Secrets are masked on read and AES-encrypted at rest with the SAME key the
 * gateway uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsSettingsService {

    static final String MASK = "***";
    private static final String AUTHOR = "admin@ops";

    private final SettingsRepository repo;
    private final SettingsCipher cipher;
    private final GatewayStatusService status;

    /**
     * All DB rows enriched with registry metadata; secret values never leave as plaintext.
     * Internal markers ({@link SettingsRegistry#isInternal}) and db-managed keys
     * ({@link SettingsRegistry#isDbManaged} — source-auth/AI, which have dedicated typed
     * pages) are hidden so every setting is listed in exactly one place. Db-managed keys
     * stay WRITABLE via {@link #put} — only the raw list hides them.
     */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SettingsRecord r : repo.findAll()) {
            if (SettingsRegistry.isInternal(r.getKey()) || SettingsRegistry.isDbManaged(r.getKey())) {
                continue;
            }
            Optional<SettingsRegistry.SettingMeta> meta = SettingsRegistry.all().stream()
                    .filter(m -> m.key().equals(r.getKey()))
                    .findFirst();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", r.getKey());
            row.put("value", displayValue(r));
            row.put("secret", r.isSecret());
            row.put("group", meta.map(SettingsRegistry.SettingMeta::group).orElse("Boshqa"));
            row.put("label", meta.map(SettingsRegistry.SettingMeta::label).orElse(r.getKey()));
            row.put("type", meta.map(SettingsRegistry.SettingMeta::type).orElse("text"));
            row.put("editable", isEditable(r.getKey()));
            out.add(row);
        }
        return out;
    }

    public ResponseEntity<Map<String, String>> put(String key, String value) {
        if (status.isUp()) {
            return ResponseEntity.status(409).body(Map.of("error", "service-running"));
        }
        // Internal markers (admin.*, gateway._meta.*, migration flag) are bookkeeping the
        // gateway writes for itself — never editable through this generic PUT.
        if (SettingsRegistry.isInternal(key)) {
            return bad("not-editable");
        }
        Optional<SettingsRecord> existing = repo.find(key);
        // No row yet: allow first-time creation for registry-known keys AND for the
        // db-managed (source-auth/AI) keys — on a fresh install most of those have no
        // row until first save, and while the gateway is down this API is the only
        // way to configure them. Anything else is truly unknown.
        if (existing.isEmpty() && !SettingsRegistry.isKnown(key) && !SettingsRegistry.isDbManaged(key)) {
            return bad("unknown-key");
        }
        if (!isEditable(key)) {
            return bad("not-editable");
        }
        // Existing row: trust its stored secret flag. First-time creation: db-managed
        // keys carry their declared flag (registry keys are all non-secret today, and
        // isDbManagedSecret is false for them, so this covers both cases).
        boolean secret = existing.map(SettingsRecord::isSecret)
                .orElse(SettingsRegistry.isDbManagedSecret(key));
        String stored = value;
        if (secret) {
            if (!cipher.isEnabled()) {
                return bad("encryption-unavailable");
            }
            stored = cipher.encrypt(value);
        }
        repo.insertAudit(key,
                existing.map(r -> secret ? MASK : r.getValue()).orElse(null),
                secret ? MASK : value,
                AUTHOR);
        repo.upsert(key, stored, secret, AUTHOR);
        log.info("Setting '{}' updated via ops (stop-to-edit){}", key, secret ? " [secret]" : "");
        return ResponseEntity.ok(Map.of("key", key, "status", "SAVED"));
    }

    /**
     * Registry keys follow their effect class (READ_ONLY → not editable); keys that
     * exist in the DB but are NOT in the registry (source-auth secrets, AI config —
     * managed by dedicated gateway pages) stay editable here: while the gateway is
     * down its dedicated pages are unreachable, and this API is the only way in.
     */
    private static boolean isEditable(String key) {
        return SettingsRegistry.isKnown(key) ? SettingsRegistry.isEditable(key) : true;
    }

    private static String displayValue(SettingsRecord r) {
        if (r.isSecret()) {
            return (r.getValue() == null || r.getValue().isEmpty()) ? "" : MASK;
        }
        return r.getValue() == null ? "" : r.getValue();
    }

    private static ResponseEntity<Map<String, String>> bad(String error) {
        return ResponseEntity.badRequest().body(Map.of("error", error));
    }
}
