package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /ops/api/login must carry the exact same LoginRateLimiter policy as the gateway's
 * /qosid/admin/api/login (AdminAuthService.authenticate): per-username, 5 failures / 60s;
 * the limiter is consulted only for EXISTING accounts (unknown usernames never build limiter
 * state, so attackers can't distinguish real accounts by a 429 — and can't fill the map with
 * fake keys); the check runs BEFORE the password match, so a correct password during lockout
 * is still 429; a successful login resets the counter.
 *
 * Each test uses its own username — the limiter is a context singleton with a 60s window,
 * so state must not leak between tests.
 */
@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opsratelimit;DB_CLOSE_DELAY=-1",
        "ops.gateway.start-command=echo s",
        "ops.gateway.stop-command=echo s"
})
@AutoConfigureMockMvc
class OpsLoginRateLimitTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate opsJdbc;

    void seed(String username, String password) {
        opsJdbc.execute("CREATE TABLE IF NOT EXISTS admin_account (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(100) UNIQUE, password_hash VARCHAR(200), updated_at TIMESTAMP, last_login_at TIMESTAMP)");
        opsJdbc.update("DELETE FROM admin_account WHERE username = ?", username);
        opsJdbc.update("INSERT INTO admin_account (username, password_hash) VALUES (?, ?)",
                username, new BCryptPasswordEncoder().encode(password));
    }

    private MockHttpServletRequestBuilder login(String username, String password) {
        return post("/ops/api/login").contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    void sixthFailureOnExistingAccountIsRateLimited() throws Exception {
        seed("limit-user", "s3cret-pass");
        for (int i = 0; i < 5; i++) {
            mvc.perform(login("limit-user", "wrong")).andExpect(status().isUnauthorized());
        }
        mvc.perform(login("limit-user", "wrong"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate-limited"));
    }

    @Test
    void unknownUsernameIsNeverRateLimited() throws Exception {
        seed("real-user", "s3cret-pass"); // table must exist; ghost stays unseeded
        for (int i = 0; i < 7; i++) {
            mvc.perform(login("ghost", "wrong")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void correctPasswordDuringLockoutIsStillRateLimited() throws Exception {
        seed("locked-user", "s3cret-pass");
        for (int i = 0; i < 5; i++) {
            mvc.perform(login("locked-user", "wrong")).andExpect(status().isUnauthorized());
        }
        mvc.perform(login("locked-user", "s3cret-pass"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate-limited"));
    }

    @Test
    void successfulLoginResetsFailureCount() throws Exception {
        seed("reset-user", "s3cret-pass");
        for (int i = 0; i < 4; i++) {
            mvc.perform(login("reset-user", "wrong")).andExpect(status().isUnauthorized());
        }
        mvc.perform(login("reset-user", "s3cret-pass")).andExpect(status().isOk());

        // A single post-success wrong attempt is 401 whether or not reset happened
        // (with reset: count 0 -> checkAllowed passes -> 401 -> count becomes 1;
        //  without reset: count was already 4 -> checkAllowed still passes (4 < 5)
        //  -> 401 -> count becomes 5), so it can't tell the two cases apart on its own.
        // The SECOND post-success wrong attempt is where reset and no-reset diverge:
        //  with reset: checkAllowed sees count 1 (< 5) -> allowed -> 401;
        //  without reset: checkAllowed sees count 5 (>= 5, still in window) -> 429.
        // So asserting both post-success attempts are 401 only passes when reset fired.
        mvc.perform(login("reset-user", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(login("reset-user", "wrong")).andExpect(status().isUnauthorized());
    }
}
