package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Ops twin of {@code AdminCookieHardeningTest}. The ops console can stop the gateway and
 * rewrite settings, so its cookies deserve the same attributes as the gateway's:
 * {@code OPS_SESSION} HttpOnly, {@code OPS-XSRF-TOKEN} JS-readable (the SPA echoes it in
 * {@code X-OPS-XSRF-TOKEN}, see {@code frontend/src/api/ops.ts}), both {@code SameSite=Lax},
 * {@code Secure} off until {@code COOKIE_SECURE=true} — prod reaches :8092 over plain HTTP
 * through an SSH tunnel.
 *
 * <p>Container-level attributes, so a real port is required — MockMvc cannot see them.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsCookieHardeningTest {

    @Autowired TestRestTemplate rest;
    @MockitoBean AdminAuthDao authDao;

    /** Login renders both cookies: OPS_SESSION by the container, OPS-XSRF-TOKEN by CsrfCookieFilter. */
    private List<String> loginCookies() {
        when(authDao.verify(any(), any())).thenReturn(true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> res = rest.postForEntity("/ops/api/login",
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"admin\"}", headers), Void.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of());
    }

    private static String cookie(List<String> cookies, String name) {
        return cookies.stream().filter(c -> c.startsWith(name + "=") && !c.startsWith(name + "=;"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Set-Cookie for " + name + " in " + cookies));
    }

    @Test
    void opsSessionCookieIsHttpOnlySameSiteLaxAndNotSecureByDefault() {
        String session = cookie(loginCookies(), "OPS_SESSION");
        assertThat(session).containsIgnoringCase("HttpOnly");
        assertThat(session).containsIgnoringCase("SameSite=Lax");
        assertThat(session)
                .as("prod reaches ops over plain HTTP via SSH tunnel — Secure must stay opt-in")
                .doesNotContainIgnoringCase("Secure");
    }

    @Test
    void opsCsrfCookieStaysJsReadableAndSameSiteLax() {
        String xsrf = cookie(loginCookies(), "OPS-XSRF-TOKEN");
        assertThat(xsrf)
                .as("The SPA reads OPS-XSRF-TOKEN from document.cookie — HttpOnly here breaks CSRF")
                .doesNotContainIgnoringCase("HttpOnly");
        assertThat(xsrf).containsIgnoringCase("SameSite=Lax");
        assertThat(xsrf).doesNotContainIgnoringCase("Secure");
    }
}
