package uz.greenwhite.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.http.HttpStatus;

/**
 * Two isolated filter chains:
 *   1. adminSecurityChain — /qosid/admin/api/** requires a session (login/health open), CSRF on.
 *   2. apiSecurityChain   — everything else permitAll (pipeline + actuator keep their own auth).
 */
@Configuration
public class AdminSecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Default-password gate (security-critical, keep on). Only ever false in tests, where most
     * admin cases legitimately log in with the seeded default password.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityChain(
            HttpSecurity http,
            org.springframework.beans.factory.ObjectProvider<uz.greenwhite.gateway.admin.AdminAuthService> authService,
            @org.springframework.beans.factory.annotation.Value("${gateway.admin.force-password-change:true}")
            boolean forcePasswordChange) throws Exception {
        http
            .securityMatcher("/qosid/admin/api/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/qosid/admin/api/login", "/qosid/admin/api/login-token",
                        "/qosid/admin/api/health").permitAll()
                .anyRequest().authenticated())
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .exceptionHandling(eh -> eh.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .formLogin(fl -> fl.disable())
            .httpBasic(hb -> hb.disable());
        if (forcePasswordChange) {
            // AFTER AuthorizationFilter on purpose: unauthenticated callers must still get 401
            // (SecurityIsolationTest guards that) and learn nothing about the password state.
            http.addFilterAfter(
                    new uz.greenwhite.gateway.admin.DefaultPasswordGateFilter(authService.getObject()),
                    org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        }
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}

/**
 * Spring Security 6's default {@code XorCsrfTokenRequestAttributeHandler} masks the token it
 * renders into the response, and it expects the SAME masked value to come back in the request.
 * A real HTTP client (curl/Postman/an SPA) reads the raw {@code XSRF-TOKEN} cookie and echoes it
 * verbatim in the {@code X-XSRF-TOKEN} header — the XOR handler rejects that raw value, so every
 * state-changing call 403s. This handler still uses the XOR handler to *render* the cookie (so the
 * cookie value keeps rotating/masking as designed) but resolves incoming header tokens as plain
 * values, matching the documented Spring Security SPA pattern.
 */
final class SpaCsrfTokenRequestHandler extends org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler {
    private final org.springframework.security.web.csrf.CsrfTokenRequestHandler xor =
            new org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(jakarta.servlet.http.HttpServletRequest request,
                       jakarta.servlet.http.HttpServletResponse response,
                       java.util.function.Supplier<org.springframework.security.web.csrf.CsrfToken> csrfToken) {
        // Render the token (for the response cookie) using the XOR handler.
        xor.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(jakarta.servlet.http.HttpServletRequest request,
                                        org.springframework.security.web.csrf.CsrfToken csrfToken) {
        // If the client sent the token in a header (SPA/curl echo of the cookie), use the plain
        // value; only fall back to the XOR-masked handler for form-body tokens.
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (headerValue != null)
                ? super.resolveCsrfTokenValue(request, csrfToken)
                : xor.resolveCsrfTokenValue(request, csrfToken);
    }
}

/**
 * The CSRF token is deferred by default (only rendered into the response cookie if something
 * actually reads it). Without this filter, a GET that never touches {@code CsrfToken.getToken()}
 * never sends a {@code Set-Cookie: XSRF-TOKEN}, so a client has nothing to echo back on the next
 * POST. Touching it on every admin-chain request guarantees the cookie is always present.
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
