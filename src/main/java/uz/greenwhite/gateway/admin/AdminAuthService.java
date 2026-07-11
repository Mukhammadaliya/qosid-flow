package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;

    public boolean authenticate(String username, String password) {
        rateLimiter.checkAllowed(username);
        var account = repository.findByUsername(username);
        if (account.isPresent() && passwordEncoder.matches(password, account.get().getPasswordHash())) {
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
        if (newPassword == null || newPassword.length() < 4) {
            throw new IllegalArgumentException("New password too short (min 4 chars)");
        }
        repository.updatePassword(username, passwordEncoder.encode(newPassword));
        log.info("Admin password changed for {}", username);
    }
}
