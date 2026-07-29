package uz.greenwhite.gateway.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Makes the seeded {@code admin}/{@code admin} account useless for anything but changing its own
 * password. While the stored password still matches the default, every admin route outside
 * {@link #ALLOWLIST} answers {@code 403 {"error":"password-change-required"}}.
 *
 * <p>Why it matters: with the default account an attacker stops the gateway through the ops
 * sidecar, rewrites {@code gateway.api.base-url} while it is stopped, and starts it again —
 * every pipeline request is then delivered to a host of their choosing.
 *
 * <p>Enforcement is server-side because the SPA cannot be trusted; the login response only
 * carries a {@code passwordChangeRequired} hint so the UI can show the right form.
 *
 * <p><b>SAFETY:</b> register this filter ONLY on {@code adminSecurityChain}. On
 * {@code apiSecurityChain} it would block {@code /qosid/api/**} and kill the pipeline
 * (SAFETY rule 29).
 *
 * <p>Placement matters too: it runs AFTER Spring Security's {@code AuthorizationFilter}, so an
 * unauthenticated caller still gets {@code 401} and learns nothing about the password state.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultPasswordGateFilter extends OncePerRequestFilter {

    private static final String ADMIN_PREFIX = "/qosid/admin/api";

    /**
     * Exact paths (never prefixes) that must keep working while the password is the default —
     * without them the operator is locked out: changing the password needs access, and access
     * would need the password changed.
     */
    private static final Set<String> ALLOWLIST = Set.of(
            ADMIN_PREFIX + "/login",
            ADMIN_PREFIX + "/login-token",
            ADMIN_PREFIX + "/logout",
            ADMIN_PREFIX + "/me",
            ADMIN_PREFIX + "/change-password",
            ADMIN_PREFIX + "/health");

    private final AdminAuthService authService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = pathWithinApplication(request);
        if (!path.startsWith(ADMIN_PREFIX) || ALLOWLIST.contains(path) || !authService.isDefaultPassword()) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Blocked admin call to {} — the default password is still in use", path);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"password-change-required\"}");
    }

    /** Request path without the servlet context path, so allowlist matching is deployment-agnostic. */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return (context != null && !context.isEmpty() && uri.startsWith(context))
                ? uri.substring(context.length())
                : uri;
    }
}
