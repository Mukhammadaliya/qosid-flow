package uz.greenwhite.qosid.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SettingsRepositoryTest {

    private SettingsRepository repo;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc = TestSettingsDb.fresh("settings_test_repo_");
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
    void findAuditReturnsLowercaseAliasedRowsWithinLimit() {
        repo.insertAudit("k1", "o1", "n1", "admin");
        repo.insertAudit("k2", "o2", "n2", "admin");
        repo.insertAudit("k3", "o3", "n3", "admin");
        List<Map<String, Object>> rows = repo.findAudit(2);
        assertEquals(2, rows.size());
        // lowercase JSON contract (quoted aliases survive H2 upper-casing)
        assertTrue(rows.get(0).containsKey("skey"));
        assertTrue(rows.get(0).containsKey("old_value"));
        assertTrue(rows.get(0).containsKey("new_value"));
        assertTrue(rows.get(0).containsKey("changed_by"));
        assertTrue(rows.get(0).containsKey("changed_at"));
    }

    @Test
    void findMissingReturnsEmpty() {
        assertTrue(repo.find("nope").isEmpty());
    }
}
