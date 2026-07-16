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
        try {
            List<String> hashes = opsJdbc.query(
                    "SELECT password_hash FROM admin_account WHERE username = ?",
                    (rs, n) -> rs.getString("password_hash"), username);
            return !hashes.isEmpty() && encoder.matches(password, hashes.get(0));
        } catch (Exception e) {
            log.warn("Admin auth lookup failed (settings DB unreachable?): {}", e.getMessage());
            return false;
        }
    }
}
