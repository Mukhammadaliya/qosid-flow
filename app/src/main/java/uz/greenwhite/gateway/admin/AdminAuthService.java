package uz.greenwhite.gateway.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import uz.greenwhite.qosid.settings.AdminAccountRepository;
import uz.greenwhite.qosid.settings.LoginHandoffRepository;
import uz.greenwhite.qosid.settings.LoginRateLimiter;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    /**
     * Rate-limit key for token exchange. There is no username to key on before the token
     * is validated, so all attempts share one bucket — same 5-failures/60s policy as /login.
     */
    static final String TOKEN_RATE_KEY = "login-token";

    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;
    private final LoginHandoffRepository handoffRepository;

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

    /**
     * Exchanges a one-time handoff token (minted by the ops sidecar, only its SHA-256
     * hash in the shared H2) for the username it was issued to. One-time and TTL-bounded
     * — {@link LoginHandoffRepository#consume} deletes the row on success.
     */
    public Optional<String> authenticateToken(String token) {
        rateLimiter.checkAllowed(TOKEN_RATE_KEY);
        if (token == null || token.isBlank()) {
            rateLimiter.recordFailure(TOKEN_RATE_KEY);
            return Optional.empty();
        }
        Optional<String> username = handoffRepository.consume(LoginHandoffRepository.sha256Hex(token));
        if (username.isPresent()) {
            rateLimiter.reset(TOKEN_RATE_KEY);
            repository.updateLastLogin(username.get());
            log.info("Admin session established via ops handoff token for {}", username.get());
        } else {
            rateLimiter.recordFailure(TOKEN_RATE_KEY);
        }
        return username;
    }

    /**
     * Login-success session logic, shared by /login and /login-token: fresh SecurityContext
     * with ROLE_ADMIN + session rotation (fixation defense) + context persisted to the session.
     */
    public void establishSession(HttpServletRequest request, String username) {
        var auth = new UsernamePasswordAuthenticationToken(
                username, null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
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
