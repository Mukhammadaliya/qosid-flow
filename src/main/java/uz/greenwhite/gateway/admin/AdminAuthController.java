package uz.greenwhite.gateway.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.admin.dto.ChangePasswordRequest;
import uz.greenwhite.gateway.admin.dto.LoginRequest;
import uz.greenwhite.gateway.exception.AuthenticationException;

import java.util.Map;

@RestController
@RequestMapping("/qosid/admin/api")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService authService;

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest req, HttpServletRequest request) {
        if (!authService.authenticate(req.getUsername(), req.getPassword())) {
            throw new AuthenticationException("Invalid username or password");
        }
        var auth = new UsernamePasswordAuthenticationToken(
                req.getUsername(), null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return ResponseEntity.ok(Map.of("username", req.getUsername(), "status", "AUTHENTICATED"));
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
