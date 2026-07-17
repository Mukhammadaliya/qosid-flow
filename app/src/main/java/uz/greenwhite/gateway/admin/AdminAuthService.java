package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import uz.greenwhite.qosid.settings.AdminAccountRepository;
import uz.greenwhite.qosid.settings.LoginRateLimiter;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;

    public boolean authenticate(String username, String password) {
        var account = repository.findByUsername(username);
        if (account.isEmpty()) {
            return false; // unknown user: no rate-limit state (bounds the map to real accounts)
        }
        rateLimiter.checkAllowed(username);
        if (passwordEncoder.matches(password, account.get().getPasswordHash())) {
            rateLimiter.reset(username);
            repository.updateLastLogin(username);
            return true;
        }
        rateLimiter.recordFailure(username);
        return false;
    }

    public void changePassword(String username, String oldPassword, String newPassword) {
        var account = repository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Admin account not found"));
        if (!passwordEncoder.matches(oldPassword, account.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("New password too short (min 8 chars)");
        }
        if ("admin".equalsIgnoreCase(newPassword)) {
            throw new IllegalArgumentException("New password must not be the default");
        }
        repository.updatePassword(username, passwordEncoder.encode(newPassword));
        log.info("Admin password changed for {}", username);
    }
}
