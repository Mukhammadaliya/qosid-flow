package uz.greenwhite.gateway.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.admin.dto.ChangePasswordRequest;
import uz.greenwhite.gateway.admin.dto.LoginRequest;
import uz.greenwhite.gateway.exception.AuthenticationException;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/qosid/admin/api")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService authService;

    public record TokenLoginRequest(String token) {}

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest req, HttpServletRequest request) {
        if (!authService.authenticate(req.getUsername(), req.getPassword())) {
            throw new AuthenticationException("Invalid username or password");
        }
        authService.establishSession(request, req.getUsername());
        return ResponseEntity.ok(Map.of("username", req.getUsername(), "status", "AUTHENTICATED"));
    }

    /**
     * Exchanges a one-time ops-issued handoff token for an admin session — lets the SPA
     * reconnect without re-login after the operator restarts the gateway from the ops page.
     * Same session semantics and success body as /login; rate-limited like /login.
     */
    @PostMapping("/login-token")
    public ResponseEntity<Map<String, String>> loginToken(@RequestBody TokenLoginRequest req,
                                                          HttpServletRequest request) {
        Optional<String> username = authService.authenticateToken(req.token());
        if (username.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid-token"));
        }
        authService.establishSession(request, username.get());
        return ResponseEntity.ok(Map.of("username", username.get(), "status", "AUTHENTICATED"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(Map.of("username", auth != null ? auth.getName() : "anonymous"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest req, HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        authService.changePassword(auth.getName(), req.getOldPassword(), req.getNewPassword());
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.noContent().build();
    }
}
