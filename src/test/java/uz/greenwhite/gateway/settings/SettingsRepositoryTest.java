package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uz.greenwhite.gateway.config.SettingsDbConfig;
import uz.greenwhite.gateway.config.SettingsProperties;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SettingsRepositoryTest {

    private SettingsRepository repo;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        SettingsProperties props = new SettingsProperties();
        props.setDbPath("mem:settings_test_repo_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        SettingsDbConfig config = new SettingsDbConfig();
        DriverManagerDataSource ds = (DriverManagerDataSource) config.settingsDataSource(props);
        config.initSchema(ds);
        jdbc = config.settingsJdbcTemplate(ds);
        repo = new SettingsRepository(jdbc);
    }

    @Test
    void upsertInsertsThenUpdates() {
        repo.upsert("gateway.retry.max-attempts", "3", false, "admin");
        Optional<SettingsRecord> r = repo.find("gateway.retry.max-attempts");
        assertTrue(r.isPresent());
        assertEquals("3", r.get().getValue());
        assertFalse(r.get().isSecret());

        repo.upsert("gateway.retry.max-attempts", "5", false, "admin");
        assertEquals("5", repo.find("gateway.retry.max-attempts").get().getValue());
        assertEquals(1, repo.findAll().size()); // still one row
    }

    @Test
    void insertAuditAppendsRow() {
        repo.insertAudit("k", "old", "new", "admin");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM settings_audit WHERE skey='k'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void findMissingReturnsEmpty() {
        assertTrue(repo.find("nope").isEmpty());
    }
}
