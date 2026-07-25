package uz.greenwhite.qosid.settings;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

/** Fresh in-memory H2 initialized from the module's own classpath {@code settings-schema.sql}. */
final class TestSettingsDb {

    private TestSettingsDb() {}

    static JdbcTemplate fresh(String prefix) {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + prefix + System.nanoTime() + ";MODE=LEGACY;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (Connection conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("settings-schema.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init test settings DB schema", e);
        }
        return new JdbcTemplate(ds);
    }
}
