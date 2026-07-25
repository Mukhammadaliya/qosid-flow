package uz.greenwhite.qosid.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AdminAccountRepositoryTest {

    private AdminAccountRepository repo;

    @BeforeEach
    void setup() {
        repo = new AdminAccountRepository(TestSettingsDb.fresh("admin_test_"));
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
