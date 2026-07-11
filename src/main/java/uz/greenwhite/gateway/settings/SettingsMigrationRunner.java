package uz.greenwhite.gateway.settings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

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
        if (!repository.findAll().isEmpty()) {
            log.info("Settings DB already populated — skipping yaml→DB migration");
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
        log.info("yaml→DB settings migration complete: {} keys migrated", migrated);
    }
}
