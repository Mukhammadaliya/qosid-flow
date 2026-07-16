package uz.greenwhite.ops;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class OpsSecurityConfig {

    @Bean
    public SecurityFilterChain opsChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrf = new CsrfTokenRequestAttributeHandler(); // plain (no XOR) — avoids the SPA breakage seen in B1
        http
            .securityMatcher("/ops/**")
            .authorizeHttpRequests(a -> a
                .requestMatchers("/ops", "/ops/", "/ops/index.html", "/ops/api/login").permitAll()
                .anyRequest().authenticated())
            .csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrf)
                .ignoringRequestMatchers("/ops/api/login")) // pre-session entry point
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint(
                (req, res, ex) -> res.sendError(401)));
        return http.build();
    }
}
