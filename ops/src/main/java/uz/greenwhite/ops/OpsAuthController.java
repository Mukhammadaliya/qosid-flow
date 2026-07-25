package uz.greenwhite.ops;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.qosid.settings.LoginHandoffRepository;
import uz.greenwhite.qosid.settings.LoginRateLimiter;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/ops/api")
@RequiredArgsConstructor
public class OpsAuthController {

    /** One-time handoff token lifetime — long enough for the SPA to poll the gateway up, short enough to be useless if leaked. */
    static final Duration HANDOFF_TTL = Duration.ofSeconds(60);

    private final AdminAuthDao authDao;
    private final LoginHandoffRepository handoffRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest req,
                                                     HttpServletRequest request, HttpServletResponse response) {
        try {
            if (!authDao.verify(req.username(), req.password())) {
                return ResponseEntity.status(401).build();
            }
        } catch (LoginRateLimiter.TooManyAttemptsException e) {
            // same policy + status as the gateway's /qosid/admin/api/login (there it goes
            // through GlobalExceptionHandler; ops has none, so we answer directly)
            return ResponseEntity.status(429).body(Map.of("error", "rate-limited"));
        }
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                req.username(), null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        // Session fixation defense: rotate the session on privilege elevation (mirrors AdminAuthController).
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        request.getSession(true);
        contextRepository.saveContext(ctx, request, response); // persist to session
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().build();
    }

    /**
     * Mints a one-time login handoff token for the already-authenticated operator
     * (session + CSRF enforced by OpsSecurityConfig). Only the SHA-256 hash is stored
     * in the shared H2; the raw token goes to the SPA, which exchanges it for a gateway
     * session via POST /qosid/admin/api/login-token after the gateway comes back up.
     */
    @PostMapping("/handoff")
    public ResponseEntity<Map<String, String>> handoff() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        handoffRepository.purgeExpired(); // opportunistic housekeeping — dead rows never accumulate
        handoffRepository.insert(LoginHandoffRepository.sha256Hex(token), auth.getName(),
                Instant.now().plus(HANDOFF_TTL));
        return ResponseEntity.ok(Map.of("token", token));
    }

    public record ChangePasswordRequest(String oldPassword, String newPassword) {}

    /**
     * Ops-local password change — same contract as the gateway's
     * {@code POST /qosid/admin/api/change-password} (204 on success, 400 + {@code error} message on
     * a rule violation; the session is invalidated so the operator must log in again).
     *
     * <p>It has to live here as well: in the normal stop-to-edit state the gateway is DOWN, so its
     * change-password endpoint is unreachable. Without this, an operator gated by
     * {@link OpsDefaultPasswordGateFilter} on the default password would have nowhere left to
     * change it. Rules and storage are {@link AdminAuthDao#changePassword} — no second copy of the
     * password policy, no second encoder.
     *
     * <p>Answered directly (no 400 mapping) because ops has no GlobalExceptionHandler — same
     * reasoning as the 429 in {@link #login}.
     */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody ChangePasswordRequest req,
                                                              HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        try {
            authDao.changePassword(auth.getName(), req.oldPassword(), req.newPassword());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(Map.of("user", auth != null ? auth.getName() : ""));
    }
}
