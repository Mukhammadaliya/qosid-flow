package uz.greenwhite.gateway.admin;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared HTTP flow for the cookie-attribute tests. Kept separate from the tests so both the
 * default-off and the {@code COOKIE_SECURE=true} variant drive an identical login, and so
 * neither has to duplicate the CSRF dance that {@code AdminCsrfFlowTest} documents.
 */
final class AdminCookieFlow {

    private AdminCookieFlow() {
    }

    private static final Pattern XSRF = Pattern.compile("XSRF-TOKEN=([^;]+)");

    /** Set-Cookie headers from an anonymous admin-chain GET — carries XSRF-TOKEN, no session. */
    static List<String> anonymousHealthGet(int port) {
        ResponseEntity<String> res = new TestRestTemplate()
                .getForEntity("http://localhost:" + port + "/qosid/admin/api/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
    }

    /** Log in with the seeded default account; the response is what first creates JSESSIONID. */
    static Login login(int port) {
        TestRestTemplate rest = new TestRestTemplate();
        String base = "http://localhost:" + port;

        String token = null;
        for (String setCookie : anonymousHealthGet(port)) {
            Matcher m = XSRF.matcher(setCookie);
            if (m.find()) token = m.group(1);
        }
        assertThat(token).as("XSRF-TOKEN must be rendered on an admin-chain GET").isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", token);
        headers.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);

        ResponseEntity<String> res = rest.exchange(base + "/qosid/admin/api/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"admin\"}", headers), String.class);
        assertThat(res.getStatusCode()).as("login must succeed for the cookie assertions to mean anything")
                .isEqualTo(HttpStatus.OK);

        return new Login(res.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));
    }

    static String pick(List<String> cookies, String name) {
        return cookies.stream()
                // Skip the "delete" form Spring Security emits when rotating the CSRF token.
                .filter(c -> c.startsWith(name + "=") && !c.startsWith(name + "=;"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Set-Cookie for " + name + " in " + cookies));
    }

    record Login(List<String> setCookies) {
        String cookie(String name) {
            return pick(setCookies, name);
        }
    }
}
