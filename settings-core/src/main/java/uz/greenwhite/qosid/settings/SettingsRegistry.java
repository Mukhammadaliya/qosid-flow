package uz.greenwhite.qosid.settings;

import java.util.List;

/**
 * Static metadata for known gateway settings: group, human label, input type,
 * and effect-class (how a change takes effect). Drives the settings UI color-coding
 * and backend validation. Keys not listed are NOT editable ({@link #isEditable}) —
 * the generic PUT rejects them and the boot-time injection skips them; effectClassOf
 * still reports REQUIRES_RESTART for unknown keys (display-only safe assumption).
 * Secrets and source-auth/AI keys are managed by their dedicated pages, not here.
 */
public final class SettingsRegistry {

    private SettingsRegistry() {}

    public enum EffectClass { LIVE, REQUIRES_RESTART, READ_ONLY }

    public record SettingMeta(String key, String group, String label, String type,
                              EffectClass effectClass, boolean secret) {}

    private static final List<SettingMeta> SETTINGS = List.of(
        // 🟢 LIVE — endpoints & simple runtime values (read per-use via SettingsService)
        new SettingMeta("gateway.api.base-url", "Endpointlar", "API proxy manzili", "url", EffectClass.LIVE, false),
        new SettingMeta("gateway.source.base-url", "Endpointlar", "Source base-url", "url", EffectClass.LIVE, false),
        new SettingMeta("gateway.source.request-pull-uri", "Endpointlar", "Source pull URI", "text", EffectClass.LIVE, false),
        new SettingMeta("gateway.source.response-save-uri", "Endpointlar", "Source save URI", "text", EffectClass.LIVE, false),
        new SettingMeta("gateway.loki.url", "Observability", "Loki URL", "url", EffectClass.LIVE, false),
        new SettingMeta("gateway.prometheus.url", "Observability", "Prometheus URL", "url", EffectClass.LIVE, false),
        new SettingMeta("gateway.tenant.field-name", "Tenant", "Tenant field name", "text", EffectClass.LIVE, false),
        new SettingMeta("gateway.tenant.extra-field-name", "Tenant", "Extra tenant field name", "text", EffectClass.LIVE, false),
        new SettingMeta("gateway.tenant.default-rest-tenant", "Tenant", "Default REST tenant", "number", EffectClass.LIVE, false),
        new SettingMeta("gateway.tenant.trust-extra-tenant-header", "Tenant", "Trust X-Extra-Tenant-Id header", "boolean", EffectClass.LIVE, false),
        // 🟡 REQUIRES_RESTART — bound into beans at startup; persisted, applied on next boot
        // (threshold-size is @Value-bound in StatusController/CallbackService — boot-time, not live)
        new SettingMeta("gateway.response.threshold-size", "Umumiy", "Inline javob chegarasi (bayt)", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.retry.max-attempts", "Retry/HTTP", "Max attempts", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.retry.interval-ms", "Retry/HTTP", "Retry interval (ms)", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.retry.retryable-statuses", "Retry/HTTP", "Retryable statuses", "text", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.retry.max-message-age-ms", "Retry/HTTP", "Max message age (ms)", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.http.connect-timeout-ms", "Retry/HTTP", "Connect timeout (ms)", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.http.read-timeout-ms", "Retry/HTTP", "Read timeout (ms)", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.polling.enabled", "Polling", "Polling yoqilgan", "boolean", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.polling.interval-ms", "Polling", "Polling interval (ms)", "number", EffectClass.REQUIRES_RESTART, false),
        // ⚪ READ_ONLY — bootstrap/infra, shown greyed, never editable (encryption key intentionally excluded)
        new SettingMeta("gateway.settings.db-path", "Infratuzilma", "Settings DB yo'li", "text", EffectClass.READ_ONLY, false),
        new SettingMeta("server.port", "Infratuzilma", "App port", "number", EffectClass.READ_ONLY, false),
        new SettingMeta("management.server.port", "Infratuzilma", "Management port", "number", EffectClass.READ_ONLY, false),
        new SettingMeta("spring.kafka.bootstrap-servers", "Infratuzilma", "Kafka bootstrap", "text", EffectClass.READ_ONLY, false),
        new SettingMeta("spring.data.redis.host", "Infratuzilma", "Redis host", "text", EffectClass.READ_ONLY, false),
        new SettingMeta("spring.data.redis.port", "Infratuzilma", "Redis port", "number", EffectClass.READ_ONLY, false),
        new SettingMeta("spring.profiles.active", "Infratuzilma", "Spring profil", "text", EffectClass.READ_ONLY, false)
    );

    public static List<SettingMeta> all() {
        return SETTINGS;
    }

    public static EffectClass effectClassOf(String key) {
        return SETTINGS.stream()
                .filter(m -> m.key().equals(key))
                .map(SettingMeta::effectClass)
                .findFirst()
                .orElse(EffectClass.REQUIRES_RESTART);
    }

    public static boolean isReadOnly(String key) {
        return effectClassOf(key) == EffectClass.READ_ONLY;
    }

    /**
     * A key is editable only if it is EXPLICITLY listed in the registry with a
     * non-READ_ONLY effect class. Unknown keys are NOT editable — this is the
     * allowlist that keeps the generic settings PUT and the boot-time Environment
     * injection (SettingsEnvironmentPostProcessor) from touching infra keys
     * (Kafka, Redis, ports) or arbitrary properties.
     */
    public static boolean isEditable(String key) {
        return SETTINGS.stream()
                .anyMatch(m -> m.key().equals(key) && m.effectClass() != EffectClass.READ_ONLY);
    }
}
