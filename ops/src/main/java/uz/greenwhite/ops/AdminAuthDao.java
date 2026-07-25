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

    /** The account and password the gateway's AdminAccountInitializer seeds on first boot. */
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin";

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
     * True while the shared admin account still carries the seeded default password — see
     * {@link OpsDefaultPasswordGateFilter}: with it an attacker stops the gateway here, rewrites
     * {@code gateway.api.base-url} through the stop-to-edit settings API and starts it again,
     * redirecting the whole pipeline. A missing account (or an unreachable DB) counts as
     * "not default": there is nothing to force, and login is impossible anyway.
     *
     * <p>Deliberately NOT latched, unlike the gateway's {@code AdminAuthService.isDefaultPassword()}:
     * the ops JVM outlives gateway restarts, so an {@code ADMIN_RESET} directive applied by the
     * gateway at ITS startup can put the default password back underneath a long-running ops
     * process — a latch would then keep the gate open on a default account. The BCrypt cost is
     * irrelevant here because the gate never consults this for allowlisted routes (the SPA's
     * frequent poll is {@code /ops/api/status}), only for rare operator actions.
     */
    public boolean isDefaultPassword() {
        return findWithRetry(DEFAULT_USERNAME)
                .map(account -> encoder.matches(DEFAULT_PASSWORD, account.getPasswordHash()))
                .orElse(false);
    }

    /**
     * Ops-side password change. It exists because settings are edited while the gateway is STOPPED:
     * in that state the gateway's own {@code /qosid/admin/api/change-password} is unreachable, so
     * without this endpoint a gated operator on the default password could not recover anywhere.
     *
     * <p>Same rules as the gateway's {@code AdminAuthService.changePassword} (old password must
     * match, min 8 chars, the default value is refused) writing through the same shared
     * {@link AdminAccountRepository} — one place decides what a valid admin password is.
     *
     * @throws IllegalArgumentException when the account is missing or a rule is violated
     */
    public void changePassword(String username, String oldPassword, String newPassword) {
        AdminAccount account = findWithRetry(username)
                .orElseThrow(() -> new IllegalArgumentException("Admin account not found"));
        if (oldPassword == null || !encoder.matches(oldPassword, account.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("New password too short (min 8 chars)");
        }
        if (DEFAULT_PASSWORD.equalsIgnoreCase(newPassword)) {
            throw new IllegalArgumentException("New password must not be the default");
        }
        accounts.updatePassword(username, encoder.encode(newPassword));
        log.info("Admin password changed via ops for {}", username);
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
