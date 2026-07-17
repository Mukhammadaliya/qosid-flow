package uz.greenwhite.gateway.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Dedicated H2 DataSource for the control-plane settings DB.
 * Independent of the excluded Oracle DataSourceAutoConfiguration — this is the
 * only DataSource in the app and is used solely by the settings/admin layer.
 */
@Slf4j
@Configuration
public class SettingsDbConfig {

    @Bean(destroyMethod = "close")
    public DataSource settingsDataSource(SettingsProperties props) {
        String path = props.getDbPath();
        String autoServer = path.startsWith("mem:") ? "" : ";AUTO_SERVER=TRUE";
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("settings-h2");
        cfg.setDriverClassName("org.h2.Driver");
        cfg.setJdbcUrl("jdbc:h2:" + path + ";MODE=LEGACY" + autoServer);
        cfg.setUsername("sa");
        cfg.setPassword("");
        // Pool with a permanently-held connection pins the H2 AUTO_SERVER role to this JVM:
        // the ops sidecar then always attaches as a TCP client, and the server role never
        // thrashes between the two JVMs (which made concurrent access — e.g. the SPA's
        // parallel login POSTs — transiently fail with "Failed to obtain JDBC Connection").
        cfg.setMinimumIdle(1);
        cfg.setMaximumPoolSize(5);
        cfg.setMaxLifetime(0); // embedded DB, nothing server-side to age out — keep the pin permanent
        HikariDataSource ds = new HikariDataSource(cfg);
        // Init schema HERE, inside the DataSource bean, so it exists before ANY
        // consumer (SettingsService @PostConstruct, repositories) touches the DB.
        // A separate init bean has undefined ordering vs SettingsService — bug.
        initSchema(ds);
        return ds;
    }

    @Bean
    @Qualifier("settingsJdbcTemplate")
    public JdbcTemplate settingsJdbcTemplate(DataSource settingsDataSource) {
        return new JdbcTemplate(settingsDataSource);
    }

    /** Runs settings-schema.sql (idempotent CREATE IF NOT EXISTS). Public for tests. */
    public void initSchema(DataSource ds) {
        try (Connection conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("settings-schema.sql"));
            log.info("Settings DB schema initialized");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize settings DB schema", e);
        }
    }
}
