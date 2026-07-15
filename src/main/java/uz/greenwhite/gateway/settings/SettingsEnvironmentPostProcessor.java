package uz.greenwhite.gateway.settings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

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
        String url = "jdbc:h2:" + dbPath + ";MODE=LEGACY;IFEXISTS=TRUE";
        Map<String, Object> props = new HashMap<>();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT skey, svalue, secret FROM settings")) {
            while (rs.next()) {
                if (rs.getBoolean("secret")) continue;            // secrets stay out of the environment
                String key = rs.getString("skey");
                if (key.startsWith("gateway.settings.")) continue; // never re-inject bootstrap keys
                if (key.startsWith("gateway._meta.")) continue;    // internal flags are not properties
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
        // Rank below systemEnvironment (env vars / -D win → read-only infra protected),
        // above application.yml. Config-data appended the yaml sources below systemEnvironment,
        // so inserting immediately after systemEnvironment puts settings-db above yaml.
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addFirst(source); // e.g. MockEnvironment (no systemEnvironment) — presence is enough
        }
    }
}
