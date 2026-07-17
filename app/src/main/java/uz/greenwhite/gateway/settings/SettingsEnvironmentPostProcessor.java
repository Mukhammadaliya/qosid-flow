package uz.greenwhite.gateway.settings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import uz.greenwhite.qosid.settings.SettingsRegistry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Boot-time layer: reads the H2 settings DB and adds its non-secret rows to the
 * Environment as a property source ranked below systemEnvironment but above
 * application.yml. Every @ConfigurationProperties consumer (ApiProperties,
 * GatewayProperties, retry/http/polling) thus picks up DB overrides on boot,
 * WITHOUT per-consumer changes — this is what makes "requires-restart" settings apply.
 *
 * ALLOWLIST: only EDITABLE registry keys (SettingsRegistry with effect-class LIVE or
 * REQUIRES_RESTART) are injected. READ_ONLY keys (ports, Kafka, Redis, db-path) and
 * unknown keys are never injected, even if a row somehow lands in the DB — so the
 * settings DB can never repoint infra. (SettingsRegistry is pure static, no Spring —
 * safe to call before the context exists.)
 *
 * Secrets are NOT loaded here (they are read at runtime via SettingsService.getSecret).
 * Any failure (first boot, no table, no file) is swallowed: yaml defaults then apply.
 * Runs after ConfigData so gateway.settings.db-path is already resolved.
 */
public class SettingsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "settings-db";

    @Override
    public int getOrder() {
        // After config-data (yaml loaded) so db-path resolves; still early enough to bind properties.
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String dbPath = environment.getProperty("gateway.settings.db-path", "./data/settings");
        // AUTO_SERVER matches SettingsDbConfig so the ops sidecar can co-access the same
        // H2 file; without it this boot-time open would fail (and be silently swallowed →
        // yaml defaults) once the sidecar holds the embedded lock. H2 rejects AUTO_SERVER
        // for in-memory DBs, so mem: paths (tests) are exempt.
        String autoServer = dbPath.startsWith("mem:") ? "" : ";AUTO_SERVER=TRUE";
        String url = "jdbc:h2:" + dbPath + ";MODE=LEGACY;IFEXISTS=TRUE" + autoServer;
        Map<String, Object> props = new HashMap<>();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT skey, svalue, secret FROM settings")) {
            while (rs.next()) {
                if (rs.getBoolean("secret")) continue;            // secrets stay out of the environment
                String key = rs.getString("skey");
                // Allowlist: only editable registry keys (LIVE/REQUIRES_RESTART) are injected.
                // Covers the old gateway.settings.* / gateway._meta.* skips AND blocks
                // read-only/unknown keys (e.g. spring.kafka.bootstrap-servers) outright.
                if (!SettingsRegistry.isEditable(key)) continue;
                String value = rs.getString("svalue");
                if (value != null) props.put(key, value);
            }
        } catch (Exception e) {
            // First boot / no DB file (IFEXISTS=TRUE) / no table → never-fail; yaml defaults apply.
            return;
        }
        if (props.isEmpty()) return;
        MutablePropertySources sources = environment.getPropertySources();
        MapPropertySource source = new MapPropertySource(SOURCE_NAME, props);
        // Rank below systemEnvironment, above application.yml. Read-only infra is protected
        // by the isEditable allowlist above, not by ranking. For editable keys the DB value
        // outranks yaml — including yaml's ${ENV:default} placeholders, so once a key has a
        // DB row its systemd env var no longer takes effect (DB/UI is the source of truth).
        // Config-data appended the yaml sources below systemEnvironment, so inserting
        // immediately after systemEnvironment puts settings-db above yaml.
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addFirst(source); // e.g. MockEnvironment (no systemEnvironment) — presence is enough
        }
    }
}
