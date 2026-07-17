package uz.greenwhite.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import uz.greenwhite.qosid.settings.AdminAccount;
import uz.greenwhite.qosid.settings.AdminAccountRepository;
import uz.greenwhite.qosid.settings.LoginRateLimiter;

import java.util.Optional;

/** Verifies credentials against the shared admin_account table using BCrypt (same password as the admin UI). */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthDao {

    private final AdminAccountRepository accounts;
    private final LoginRateLimiter rateLimiter;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * Same rate-limit policy as the gateway's AdminAuthService.authenticate: per-username,
     * limiter consulted only for EXISTING accounts (unknown users get a plain 401 and never
     * build limiter state — no username enumeration via 429, and the map stays bounded to
     * real accounts); the check runs before the password match, so a correct password during
     * lockout still throws; success resets the counter.
     *
     * @throws LoginRateLimiter.TooManyAttemptsException when the username is locked out
     */
    public boolean verify(String username, String password) {
        if (username == null || password == null) return false;
        Optional<AdminAccount> account = findWithRetry(username);
        if (account.isEmpty()) {
            return false; // unknown user (or DB down): no rate-limit state
        }
        rateLimiter.checkAllowed(username);
        if (encoder.matches(password, account.get().getPasswordHash())) {
            rateLimiter.reset(username);
            return true;
        }
        rateLimiter.recordFailure(username);
        return false;
    }

    /**
     * Shared-H2 AUTO_SERVER handoff (gateway restart window) can transiently fail the
     * read — retry once so it doesn't surface as a spurious "wrong password" 401.
     */
    private Optional<AdminAccount> findWithRetry(String username) {
        for (int attempt = 1; ; attempt++) {
            try {
                return accounts.findByUsername(username);
            } catch (Exception e) {
                if (attempt >= 2) {
                    log.warn("Admin auth lookup failed (settings DB unreachable?): {}", e.getMessage());
                    return Optional.empty();
                }
                log.debug("Admin auth lookup failed (attempt {}), retrying: {}", attempt, e.getMessage());
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
    }
}
