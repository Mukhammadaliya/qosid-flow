package uz.greenwhite.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import uz.greenwhite.qosid.settings.AdminAccountRepository;
import uz.greenwhite.qosid.settings.LoginHandoffRepository;
import uz.greenwhite.qosid.settings.SettingsCipher;
import uz.greenwhite.qosid.settings.SettingsRepository;

import javax.sql.DataSource;
import java.sql.Connection;

/** Connects to the SAME H2 settings DB as the gateway (shared admin_account), via AUTO_SERVER mixed-mode. */
@Slf4j
@Configuration
public class OpsDbConfig {

    @Bean
    public DataSource opsDataSource(@Value("${ops.settings-db-path}") String dbPath) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        String autoServer = dbPath.startsWith("mem:") ? "" : ";AUTO_SERVER=TRUE";
        ds.setUrl("jdbc:h2:" + dbPath + ";MODE=LEGACY" + autoServer);
        ds.setUsername("sa");
        ds.setPassword("");
        // Init schema HERE, inside the DataSource bean, so tables exist before any consumer
        // touches the DB (mirrors the gateway's SettingsDbConfig). Idempotent — the script is
        // all CREATE TABLE IF NOT EXISTS, so it is a no-op when the gateway already created them.
        initSchema(ds);
        return ds;
    }

    /** Runs settings-core's settings-schema.sql (idempotent CREATE IF NOT EXISTS). */
    static void initSchema(DataSource ds) {
        try (Connection conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("settings-schema.sql"));
            log.info("Ops settings DB schema initialized");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize ops settings DB schema", e);
        }
    }

    @Bean
    public JdbcTemplate opsJdbc(DataSource opsDataSource) {
        return new JdbcTemplate(opsDataSource);
    }

    /** settings-core POJO wired as a bean — shared admin_account access, same class the gateway uses. */
    @Bean
    public AdminAccountRepository adminAccountRepository(JdbcTemplate opsJdbc) {
        return new AdminAccountRepository(opsJdbc);
    }

    /** settings-core POJO — same settings table the gateway reads on boot (stop-to-edit model). */
    @Bean
    public SettingsRepository settingsRepository(JdbcTemplate opsJdbc) {
        return new SettingsRepository(opsJdbc);
    }

    /** settings-core POJO — one-time login handoff tokens (ops mints, gateway consumes). */
    @Bean
    public LoginHandoffRepository loginHandoffRepository(JdbcTemplate opsJdbc) {
        return new LoginHandoffRepository(opsJdbc);
    }

    /**
     * MUST be configured with the SAME key as the gateway's gateway.settings.encryption-key,
     * otherwise secrets written here are unreadable by the gateway (and vice versa).
     * Blank key → cipher disabled → secret PUTs are rejected with encryption-unavailable.
     */
    @Bean
    public SettingsCipher settingsCipher(@Value("${ops.settings-encryption-key:}") String key) {
        return new SettingsCipher(key);
    }
}
