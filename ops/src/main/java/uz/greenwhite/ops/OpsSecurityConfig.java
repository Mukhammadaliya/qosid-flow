package uz.greenwhite.ops;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class OpsSecurityConfig {

    /**
     * @param forcePasswordChange default-password gate (security-critical, keep on). Only ever
     *                            false in tests — see {@code src/test/resources/application.properties}.
     */
    @Bean
    public SecurityFilterChain opsChain(
            HttpSecurity http,
            ObjectProvider<AdminAuthDao> authDao,
            @Value("${ops.admin.force-password-change:true}") boolean forcePasswordChange) throws Exception {
        CsrfTokenRequestAttributeHandler csrf = new CsrfTokenRequestAttributeHandler(); // plain (no XOR) — avoids the SPA breakage seen in B1
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieName("OPS-XSRF-TOKEN");      // gateway XSRF-TOKEN bilan to'qnashmasin
        csrfRepo.setHeaderName("X-OPS-XSRF-TOKEN");
        http
            .securityMatcher("/ops/**")
            .authorizeHttpRequests(a -> a
                .requestMatchers("/ops/api/login").permitAll()
                .anyRequest().authenticated())
            .csrf(c -> c
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(csrf)
                .ignoringRequestMatchers("/ops/api/login")) // pre-session entry point
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint(
                (req, res, ex) -> res.sendError(401)));
        if (forcePasswordChange) {
            // AFTER AuthorizationFilter on purpose: unauthenticated callers must still get 401
            // (OpsAuthTest/OpsControllerTest guard that) and learn nothing about the password state.
            // Being last also leaves the CSRF configuration above untouched.
            http.addFilterAfter(new OpsDefaultPasswordGateFilter(authDao.getObject()),
                    AuthorizationFilter.class);
        }
        return http.build();
    }
}

/**
 * Ported from the gateway's AdminSecurityConfig (B1). The CSRF token is deferred by default (only
 * rendered into the response cookie if something actually reads it). Without this filter, a GET
 * that never touches {@code CsrfToken.getToken()} never sends a {@code Set-Cookie: OPS-XSRF-TOKEN},
 * so a client has nothing to echo back on the next POST. Touching it on every ops-chain request
 * guarantees the cookie is always present.
 */
final class CsrfCookieFilter extends org.springframework.web.filter.OncePerRequestFilter {
    @Override
    protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                    jakarta.servlet.http.HttpServletResponse response,
                                    jakarta.servlet.FilterChain filterChain)
            throws jakarta.servlet.ServletException, java.io.IOException {
        org.springframework.security.web.csrf.CsrfToken csrfToken =
                (org.springframework.security.web.csrf.CsrfToken) request.getAttribute("_csrf");
        if (csrfToken != null) csrfToken.getToken(); // touch to render the cookie
        filterChain.doFilter(request, response);
    }
}
