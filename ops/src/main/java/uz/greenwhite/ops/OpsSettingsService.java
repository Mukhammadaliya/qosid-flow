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
     * The UNION of every EDITABLE registry key and the DB rows, so the SPA's edit mode can
     * reach registry keys that have no DB row yet (their yaml/env value is live but invisible
     * to the ops sidecar, which cannot resolve the gateway's Environment). Each row carries a
     * {@code stored} flag = whether a DB row exists.
     * <ul>
     *   <li>Editable registry keys (LIVE + REQUIRES_RESTART) always appear: value = DB row
     *       value (masked when secret) if a row exists, else {@code ""} with {@code stored=false}.</li>
     *   <li>READ_ONLY registry keys appear ONLY when a DB row exists — ops cannot resolve their
     *       yaml/env value, so an empty read-only row is noise.</li>
     *   <li>DB rows NOT in the registry (db-managed source-auth/AI keys — {@link SettingsRegistry#isDbManaged})
     *       stay in the list ({@code stored=true}): the SPA's typed pages (SourceAuthPage/AiConfigPage)
     *       read their values back through this generic GET in stop-to-edit mode. Internal markers
     *       ({@link SettingsRegistry#isInternal}) are excluded.</li>
     * </ul>
     * Secret values never leave as plaintext ({@code displayValue} masks them). Duplication in
     * the SPA's General tab is prevented client-side (its {@code TYPED_FORM_KEYS} filter), so each
     * setting is still configured in exactly one place. Order: registry order first, then the
     * remaining DB rows.
     */
    public List<Map<String, Object>> list() {
        Map<String, SettingsRecord> dbRows = new LinkedHashMap<>();
        for (SettingsRecord r : repo.findAll()) {
            dbRows.put(r.getKey(), r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        // 1. Registry keys first, in registry order.
        for (SettingsRegistry.SettingMeta meta : SettingsRegistry.all()) {
            String key = meta.key();
            SettingsRecord r = dbRows.get(key);
            boolean editable = SettingsRegistry.isEditable(key);
            // READ_ONLY: ops can't resolve the yaml/env value, so only surface it if stored.
            if (!editable && r == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("value", r != null ? displayValue(r) : "");
            row.put("secret", r != null ? r.isSecret() : meta.secret());
            row.put("group", meta.group());
            row.put("label", meta.label());
            row.put("type", meta.type());
            row.put("editable", editable);
            row.put("stored", r != null);
            out.add(row);
        }
        // 2. DB rows not in the registry (db-managed source-auth/AI + any other non-internal row).
        for (SettingsRecord r : dbRows.values()) {
            if (SettingsRegistry.isKnown(r.getKey()) || SettingsRegistry.isInternal(r.getKey())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", r.getKey());
            row.put("value", displayValue(r));
            row.put("secret", r.isSecret());
            row.put("group", "Boshqa");
            row.put("label", r.getKey());
            row.put("type", "text");
            row.put("editable", isEditable(r.getKey()));
            row.put("stored", true);
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
