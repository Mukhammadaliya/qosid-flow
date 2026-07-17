package uz.greenwhite.qosid.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LoginHandoffRepositoryTest {

    private JdbcTemplate jdbc;
    private LoginHandoffRepository repo;

    @BeforeEach
    void setup() {
        jdbc = TestSettingsDb.fresh("handoff_test_");
        repo = new LoginHandoffRepository(jdbc);
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM login_handoff", Integer.class);
    }

    @Test
    void consumeReturnsUsernameOnceAndDeletes() {
        String hash = LoginHandoffRepository.sha256Hex("one-time-token");
        assertTrue(hash.matches("[0-9a-f]{64}"), "sha256Hex must be 64 lowercase hex chars");

        repo.insert(hash, "admin", Instant.now().plusSeconds(60));
        assertEquals(Optional.of("admin"), repo.consume(hash));
        assertEquals(Optional.empty(), repo.consume(hash), "second consume must be empty (one-time)");
        assertEquals(0, rowCount(), "consumed row must be deleted");
    }

    @Test
    void consumeRejectsExpired() {
        String hash = LoginHandoffRepository.sha256Hex("expired-token");
        repo.insert(hash, "admin", Instant.now().minusSeconds(1));
        assertEquals(Optional.empty(), repo.consume(hash));
    }

    @Test
    void purgeExpiredDeletesOnlyExpired() {
        repo.insert(LoginHandoffRepository.sha256Hex("dead"), "admin", Instant.now().minusSeconds(1));
        repo.insert(LoginHandoffRepository.sha256Hex("alive"), "admin", Instant.now().plusSeconds(60));
        assertEquals(1, repo.purgeExpired());
        assertEquals(1, rowCount(), "live row must survive the purge");
        assertEquals(Optional.of("admin"), repo.consume(LoginHandoffRepository.sha256Hex("alive")));
    }
}
