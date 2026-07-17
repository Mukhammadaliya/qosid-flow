package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import uz.greenwhite.gateway.config.SettingsDbConfig;
import uz.greenwhite.gateway.config.SettingsProperties;

import javax.sql.DataSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AdminAccountRepositoryTest {

    private AdminAccountRepository repo;

    @BeforeEach
    void setup() {
        SettingsProperties props = new SettingsProperties();
        props.setDbPath("mem:admin_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        SettingsDbConfig config = new SettingsDbConfig();
        DataSource ds = config.settingsDataSource(props);
        config.initSchema(ds);
        JdbcTemplate jdbc = config.settingsJdbcTemplate(ds);
        repo = new AdminAccountRepository(jdbc);
    }

    @Test
    void insertFindUpdate() {
        assertEquals(0, repo.count());
        repo.insert("admin", "hash1");
        assertEquals(1, repo.count());
        Optional<AdminAccount> a = repo.findByUsername("admin");
        assertTrue(a.isPresent());
        assertEquals("hash1", a.get().getPasswordHash());

        repo.updatePassword("admin", "hash2");
        assertEquals("hash2", repo.findByUsername("admin").get().getPasswordHash());

        repo.updateLastLogin("admin");
        assertNotNull(repo.findByUsername("admin").get().getLastLoginAt());
    }
}
