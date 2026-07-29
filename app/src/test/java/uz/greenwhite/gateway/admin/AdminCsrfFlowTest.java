package uz.greenwhite.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the SPA-compatible CSRF flow works end-to-end over a REAL servlet — no
 * MockMvc {@code .with(csrf())} shortcut. A genuine HTTP client must be able to:
 *   1. GET an admin-chain endpoint, capture the raw XSRF-TOKEN cookie from Set-Cookie.
 *   2. Echo that raw cookie value back verbatim in the X-XSRF-TOKEN header on a POST.
 * Before Fix C1 (default XorCsrfTokenRequestAttributeHandler + deferred token render) this
 * fails: the cookie is never seeded on GET, and even if it were, the raw echoed value would
 * be rejected by the XOR handler. After Fix C1, login succeeds with 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:csrf_flow_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        "gateway.rocksdb.path=./target/test-data/rocksdb-csrf"
})
class AdminCsrfFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper om;

    private static final Pattern XSRF_COOKIE_PATTERN = Pattern.compile("XSRF-TOKEN=([^;]+)");
    private static final Pattern JSESSIONID_COOKIE_PATTERN = Pattern.compile("JSESSIONID=([^;]+)");

    @Test
    void realHttpClientCanLoginUsingCookieEchoedInHeader() {
        TestRestTemplate rest = new TestRestTemplate();
        String base = "http://localhost:" + port;

        // Step 1: GET an admin-chain endpoint to have the XSRF-TOKEN cookie rendered.
        ResponseEntity<String> healthResponse = rest.getForEntity(base + "/qosid/admin/api/health", String.class);
        assertThat(healthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        String xsrfToken = null;
        String jsessionId = null;
        for (String setCookie : healthResponse.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
            Matcher xsrfMatcher = XSRF_COOKIE_PATTERN.matcher(setCookie);
            if (xsrfMatcher.find()) xsrfToken = xsrfMatcher.group(1);
            Matcher sessionMatcher = JSESSIONID_COOKIE_PATTERN.matcher(setCookie);
            if (sessionMatcher.find()) jsessionId = sessionMatcher.group(1);
        }
        assertThat(xsrfToken)
                .as("XSRF-TOKEN cookie must be rendered on the response to an admin-chain GET")
                .isNotNull();

        // Step 2: POST /login, echoing the raw cookie value verbatim in the X-XSRF-TOKEN header —
        // exactly what a browser-based SPA (or curl copying the cookie) would do.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", xsrfToken);
        StringBuilder cookieHeader = new StringBuilder("XSRF-TOKEN=").append(xsrfToken);
        if (jsessionId != null) cookieHeader.append("; JSESSIONID=").append(jsessionId);
        headers.set(HttpHeaders.COOKIE, cookieHeader.toString());

        String body;
        try {
            body = om.writeValueAsString(Map.of("username", "admin", "password", "admin"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ResponseEntity<String> loginResponse = rest.exchange(
                base + "/qosid/admin/api/login", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(loginResponse.getStatusCode())
                .as("Real client echoing the raw cookie value in X-XSRF-TOKEN must be accepted, not 403'd")
                .isEqualTo(HttpStatus.OK);
    }
}
