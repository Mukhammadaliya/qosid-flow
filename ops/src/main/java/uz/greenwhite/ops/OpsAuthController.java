package uz.greenwhite.ops;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/ops/api")
@RequiredArgsConstructor
public class OpsAuthController {

    private final AdminAuthDao authDao;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestBody LoginRequest req,
                                      HttpServletRequest request, HttpServletResponse response) {
        if (!authDao.verify(req.username(), req.password())) {
            return ResponseEntity.status(401).build();
        }
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                req.username(), null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response); // persist to session
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        request.getSession().invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(Map.of("user", auth != null ? auth.getName() : ""));
    }
}
