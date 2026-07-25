package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import uz.greenwhite.qosid.settings.AdminAccount;
import uz.greenwhite.qosid.settings.AdminAccountRepository;
import uz.greenwhite.qosid.settings.LoginRateLimiter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared-H2 AUTO_SERVER handoff (gateway restart window) can transiently fail the DB read.
 * A single failed read must NOT surface as "wrong password" (401) — verify() retries once
 * before giving up, so the SPA's unified login doesn't randomly drop into gateway-only mode.
 * The DAO now goes through the shared settings-core AdminAccountRepository; the retry loop
 * is exercised through a real repository over a mocked JdbcTemplate.
 */
class AdminAuthDaoRetryTest {

    private final String hash = new BCryptPasswordEncoder().encode("s3cret");

    private AdminAccount account() {
        return AdminAccount.builder().id(1L).username("admin").passwordHash(hash).build();
    }

    private AdminAuthDao dao(JdbcTemplate jdbc) {
        return new AdminAuthDao(new AdminAccountRepository(jdbc), new LoginRateLimiter());
    }

    @SuppressWarnings("unchecked")
    @Test
    void verifyRetriesOnceOnTransientDbFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("admin")))
                .thenThrow(new CannotGetJdbcConnectionException("H2 server handoff in progress"))
                .thenReturn(List.of(account()));

        assertTrue(dao(jdbc).verify("admin", "s3cret"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void verifyReturnsFalseWhenDbKeepsFailing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("admin")))
                .thenThrow(new CannotGetJdbcConnectionException("still down"));

        assertFalse(dao(jdbc).verify("admin", "s3cret"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void wrongPasswordFailsWithoutRetry() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("admin")))
                .thenReturn(List.of(account()));

        assertFalse(dao(jdbc).verify("admin", "wrong"));
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), eq("admin"));
    }
}
