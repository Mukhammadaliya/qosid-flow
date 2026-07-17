package uz.greenwhite.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uz.greenwhite.qosid.settings.AdminAccountRepository;

import javax.sql.DataSource;

/** Connects to the SAME H2 settings DB as the gateway (shared admin_account), via AUTO_SERVER mixed-mode. */
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
        return ds;
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
}
