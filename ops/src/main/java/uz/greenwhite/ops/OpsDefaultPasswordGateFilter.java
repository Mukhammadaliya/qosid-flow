package uz.greenwhite.ops;

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
 * Ops-side twin of the gateway's {@code DefaultPasswordGateFilter}. While the shared admin account
 * still carries the seeded {@code admin}/{@code admin} password, every ops route outside
 * {@link #ALLOWLIST} answers {@code 403 {"error":"password-change-required"}}.
 *
 * <p>Why the gateway-side gate alone was not enough: ops authenticates against the SAME
 * admin_account, so the original attack stayed fully open — log in to {@code /ops/api/login} with
 * the default password, {@code /ops/api/stop} the gateway, {@code PUT /ops/api/settings/...} to
 * point {@code gateway.api.base-url} at an attacker host, {@code /ops/api/start}. That is exactly
 * the scenario the gateway fix was meant to prevent.
 *
 * <p>{@code /ops/api/change-password} is in the allowlist and ops implements it itself: settings
 * are edited while the gateway is STOPPED, so the gateway's own change-password endpoint is
 * unreachable then — gating ops without a local escape hatch would lock the operator out of both
 * JVMs at once.
 *
 * <p>{@code status} is allowlisted too: it is read-only and the SPA cannot render its ops page
 * (nor the forced change-password flow around it) without it.
 *
 * <p>Placement matters: this runs AFTER Spring Security's {@code AuthorizationFilter}, so an
 * unauthenticated caller still gets {@code 401} and learns nothing about the password state.
 * It also runs after {@code CsrfFilter}, so CSRF enforcement is unchanged.
 */
@Slf4j
@RequiredArgsConstructor
public class OpsDefaultPasswordGateFilter extends OncePerRequestFilter {

    private static final String OPS_PREFIX = "/ops";
    private static final String API_PREFIX = OPS_PREFIX + "/api";

    /**
     * Exact paths (never prefixes) that keep working while the password is the default. Prefix
     * matching would be a hole: {@code settings} as a prefix would also open
     * {@code settings/audit} and {@code settings/{key}}.
     */
    private static final Set<String> ALLOWLIST = Set.of(
            API_PREFIX + "/login",
            API_PREFIX + "/logout",
            API_PREFIX + "/me",
            API_PREFIX + "/change-password",
            API_PREFIX + "/status");

    private final AdminAuthDao authDao;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = pathWithinApplication(request);
        if (!path.startsWith(OPS_PREFIX) || ALLOWLIST.contains(path) || !authDao.isDefaultPassword()) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Blocked ops call to {} — the default admin password is still in use", path);
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
