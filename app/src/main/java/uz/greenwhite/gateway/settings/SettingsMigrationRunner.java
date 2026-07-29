package uz.greenwhite.gateway.settings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import uz.greenwhite.qosid.settings.SettingsRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On first boot (settings table empty), copies migratable yaml values into the
 * settings DB once. Bootstrap values (Kafka/Redis/ports/H2 path) are NOT migrated —
 * they stay in yaml. Idempotent: runs only when the table is empty.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettingsMigrationRunner implements ApplicationRunner {

    /** Marker key written LAST after a successful migration — makes the run resumable. */
    private static final String MIGRATION_MARKER = "gateway.settings.migration-completed";

    /** settings key -> yaml property name. Non-secret runtime settings only. */
    static final Map<String, String> MIGRATABLE_KEYS = new LinkedHashMap<>() {{
        put("gateway.api.base-url", "gateway.api.base-url");
        put("gateway.retry.max-attempts", "gateway.retry.max-attempts");
        put("gateway.retry.interval-ms", "gateway.retry.interval-ms");
        put("gateway.retry.retryable-statuses", "gateway.retry.retryable-statuses");
        put("gateway.retry.max-message-age-ms", "gateway.retry.max-message-age-ms");
        put("gateway.http.connect-timeout-ms", "gateway.http.connect-timeout-ms");
        put("gateway.http.read-timeout-ms", "gateway.http.read-timeout-ms");
        put("gateway.response.threshold-size", "gateway.response.threshold-size");
        put("gateway.polling.enabled", "gateway.polling.enabled");
        put("gateway.polling.interval-ms", "gateway.polling.interval-ms");
        put("gateway.tenant.field-name", "gateway.tenant.field-name");
        put("gateway.tenant.extra-field-name", "gateway.tenant.extra-field-name");
        put("gateway.tenant.default-rest-tenant", "gateway.tenant.default-rest-tenant");
    }};

    private final SettingsRepository repository;
    private final SettingsService settingsService;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        // Always runs, regardless of the marker: an UPGRADED deployment (marker already
        // present from before B3 existed) must still pick up the source basic credentials
        // on first boot after upgrade. Idempotent — only writes rows that are still absent,
        // so it never overwrites an admin edit made via the settings UI.
        ensureSourceBasicCreds();

        if (repository.find(MIGRATION_MARKER).isPresent()) {
            log.info("Settings migration already completed — skipping");
            return;
        }
        int migrated = 0;
        for (Map.Entry<String, String> e : MIGRATABLE_KEYS.entrySet()) {
            String value = environment.getProperty(e.getValue());
            if (value != null) {
                settingsService.set(e.getKey(), value, false, "migration");
                migrated++;
            }
        }

        // Write the marker LAST — a partial failure leaves it absent so the next boot re-runs
        // (idempotent upsert-by-key re-applies cleanly). Resumable.
        settingsService.set(MIGRATION_MARKER, "true", false, "migration");
        log.info("yaml→DB settings migration complete: {} keys migrated", migrated);
    }

    /**
     * Migrates the B3 source-channel basic credentials outside the one-shot marker gate.
     * Runs on every boot but only writes a key when its row is still absent, so it's safe
     * both on a fresh deploy and on an already-migrated (upgraded) deployment where the
     * generic {@link #MIGRATION_MARKER} was written before B3 existed.
     */
    private void ensureSourceBasicCreds() {
        if (repository.find("gateway.source.auth.basic.username").isEmpty()) {
            String u = environment.getProperty("gateway.source.username");
            if (u != null && !u.isBlank()) {
                settingsService.set("gateway.source.auth.basic.username", u, false, "migration");
            }
        }
        if (repository.find("gateway.source.auth.basic.password").isEmpty()) {
            String p = environment.getProperty("gateway.source.password");
            if (p != null && !p.isBlank()) {
                try {
                    settingsService.set("gateway.source.auth.basic.password", p, true, "migration");
                } catch (Exception e) {
                    // Guarded: if encryption is disabled, settingsService.set(..., true, ...) throws —
                    // degrade to a WARN so a blank encryption key never fails startup (yaml fallback
                    // in SourceAuthService still applies in that case).
                    log.warn("Source basic-password migration skipped (encryption disabled?): {} — yaml fallback applies", e.getMessage());
                }
            }
        }
    }
}
