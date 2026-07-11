package uz.greenwhite.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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

    @Bean
    public DataSource settingsDataSource(SettingsProperties props) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:" + props.getDbPath() + ";MODE=LEGACY");
        ds.setUsername("sa");
        ds.setPassword("");
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
