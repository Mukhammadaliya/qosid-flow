package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class SettingsEnvironmentPostProcessorTest {

    private final SettingsEnvironmentPostProcessor processor = new SettingsEnvironmentPostProcessor();

    @Test
    void loadsNonSecretRowsAsProperties(@TempDir Path dir) throws Exception {
        String dbPath = dir.resolve("settings").toString();
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + dbPath + ";MODE=LEGACY", "sa", "");
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE settings (skey VARCHAR(200) PRIMARY KEY, svalue CLOB, secret BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMP, updated_by VARCHAR(100))");
            s.execute("INSERT INTO settings (skey, svalue, secret) VALUES ('gateway.api.base-url', 'https://db.example.com', FALSE)");
            s.execute("INSERT INTO settings (skey, svalue, secret) VALUES ('gateway.source.auth.basic.password', 'ENCRYPTED', TRUE)");
        }
        MockEnvironment env = new MockEnvironment();
        env.setProperty("gateway.settings.db-path", dbPath);

        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("https://db.example.com", env.getProperty("gateway.api.base-url"));
        // secret row is NOT injected into the environment
        assertNull(env.getProperty("gateway.source.auth.basic.password"));
    }

    @Test
    void readOnlyAndUnknownKeysAreNeverInjected(@TempDir Path dir) throws Exception {
        // F1a allowlist: only EDITABLE registry keys (LIVE/REQUIRES_RESTART) enter the
        // Environment. A read-only infra key or an unknown key in the DB (however it got
        // there) must NOT be able to repoint ports/Kafka on next boot.
        String dbPath = dir.resolve("settings").toString();
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + dbPath + ";MODE=LEGACY", "sa", "");
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE settings (skey VARCHAR(200) PRIMARY KEY, svalue CLOB, secret BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMP, updated_by VARCHAR(100))");
            s.execute("INSERT INTO settings (skey, svalue, secret) VALUES ('server.port', '1', FALSE)");                              // READ_ONLY
            s.execute("INSERT INTO settings (skey, svalue, secret) VALUES ('gateway.kafka.bootstrap-servers', 'evil:9092', FALSE)");  // unknown
            s.execute("INSERT INTO settings (skey, svalue, secret) VALUES ('gateway.api.base-url', 'https://db.example.com', FALSE)"); // LIVE
        }
        MockEnvironment env = new MockEnvironment();
        env.setProperty("gateway.settings.db-path", dbPath);

        processor.postProcessEnvironment(env, new SpringApplication());

        assertNull(env.getProperty("server.port"));
        assertNull(env.getProperty("gateway.kafka.bootstrap-servers"));
        assertEquals("https://db.example.com", env.getProperty("gateway.api.base-url"));
    }

    @Test
    void missingDatabaseIsSilentlyIgnored(@TempDir Path dir) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("gateway.settings.db-path", dir.resolve("does-not-exist-yet").toString());
        // No table exists → must not throw, environment unchanged for our key
        assertDoesNotThrow(() -> processor.postProcessEnvironment(env, new SpringApplication()));
        assertNull(env.getProperty("gateway.api.base-url"));
    }

    @Test
    void dbValueOverridesYamlButNotSystemEnvironment(@TempDir Path dir) throws Exception {
        String dbPath = dir.resolve("settings").toString();
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + dbPath + ";MODE=LEGACY", "sa", "");
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE settings (skey VARCHAR(200) PRIMARY KEY, svalue CLOB, secret BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMP, updated_by VARCHAR(100))");
            s.execute("INSERT INTO settings (skey, svalue, secret) VALUES ('gateway.api.base-url', 'from-db', FALSE)");
        }
        org.springframework.core.env.StandardEnvironment env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "settings-db-path-source", java.util.Map.of("gateway.settings.db-path", dbPath)));
        // application.yml-like source: appended last (lowest, like config-data) with a competing value
        env.getPropertySources().addLast(new org.springframework.core.env.MapPropertySource(
                "applicationConfig", java.util.Map.of("gateway.api.base-url", "from-yaml")));

        processor.postProcessEnvironment(env, new SpringApplication());

        // DB wins over yaml
        assertEquals("from-db", env.getProperty("gateway.api.base-url"));
    }
}
