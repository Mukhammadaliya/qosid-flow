package uz.greenwhite.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import uz.greenwhite.qosid.settings.AdminAccountRepository;

/** Verifies credentials against the shared admin_account table using BCrypt (same password as the admin UI). */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthDao {

    private final AdminAccountRepository accounts;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public boolean verify(String username, String password) {
        if (username == null || password == null) return false;
        // Shared-H2 AUTO_SERVER handoff (gateway restart window) can transiently fail the
        // read — retry once so it doesn't surface as a spurious "wrong password" 401.
        for (int attempt = 1; ; attempt++) {
            try {
                return accounts.findByUsername(username)
                        .map(a -> encoder.matches(password, a.getPasswordHash()))
                        .orElse(false);
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
