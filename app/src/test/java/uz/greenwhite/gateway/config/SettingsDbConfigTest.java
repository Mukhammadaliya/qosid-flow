package uz.greenwhite.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

class SettingsDbConfigTest {

    @Test
    void createsSchemaAndTablesAreQueryable() {
        SettingsProperties props = new SettingsProperties();
        // in-memory H2 for the test, named so tables persist for the connection
        props.setDbPath("mem:settings_test_task1;DB_CLOSE_DELAY=-1");

        SettingsDbConfig config = new SettingsDbConfig();
        DataSource ds = config.settingsDataSource(props);
        JdbcTemplate jdbc = config.settingsJdbcTemplate(ds);
        config.initSchema(ds);

        // All three tables exist and are empty
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM settings", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM settings_audit", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admin_account", Integer.class));
    }
}
