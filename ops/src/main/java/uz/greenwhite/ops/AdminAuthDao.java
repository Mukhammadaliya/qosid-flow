package uz.greenwhite.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/** Verifies credentials against the shared admin_account table using BCrypt (same password as the admin UI). */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthDao {

    private final JdbcTemplate opsJdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public boolean verify(String username, String password) {
        if (username == null || password == null) return false;
        // Shared-H2 AUTO_SERVER handoff (gateway restart window) can transiently fail the
        // read — retry once so it doesn't surface as a spurious "wrong password" 401.
        for (int attempt = 1; ; attempt++) {
            try {
                List<String> hashes = opsJdbc.query(
                        "SELECT password_hash FROM admin_account WHERE username = ?",
                        (rs, n) -> rs.getString("password_hash"), username);
                return !hashes.isEmpty() && encoder.matches(password, hashes.get(0));
            } catch (Exception e) {
                if (attempt >= 2) {
                    log.warn("Admin auth lookup failed (settings DB unreachable?): {}", e.getMessage());
                    return false;
                }
                log.debug("Admin auth lookup failed (attempt {}), retrying: {}", attempt, e.getMessage());
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }
}
