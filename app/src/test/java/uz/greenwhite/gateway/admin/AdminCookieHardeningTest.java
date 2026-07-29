package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cookie attributes are set by the servlet container / {@code CookieCsrfTokenRepository},
 * so they are only observable over a real port (MockMvc never renders Set-Cookie attributes).
 *
 * <p>Three invariants:</p>
 * <ul>
 *   <li>{@code JSESSIONID} is {@code HttpOnly} — the session id must be unreadable from JS.</li>
 *   <li>{@code XSRF-TOKEN} is deliberately <b>NOT</b> {@code HttpOnly}: the SPA reads it with
 *       {@code getCookie('XSRF-TOKEN')} in {@code frontend/src/api/client.ts} and echoes it in
 *       the {@code X-XSRF-TOKEN} header. Making it HttpOnly breaks every state-changing call.</li>
 *   <li>Both carry {@code SameSite=Lax} — same-origin XHR is unaffected, cross-site POSTs are not.</li>
 * </ul>
 *
 * <p>{@code Secure} must stay OFF here: prod is reached over plain HTTP through an SSH tunnel,
 * so hard-coding it would lock out login. {@link AdminCookieSecureFlagTest} covers the opt-in.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:cookie_hardening_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        "gateway.rocksdb.path=./target/test-data/rocksdb-cookie"
})
class AdminCookieHardeningTest {

    @LocalServerPort
    int port;

    @Test
    void sessionCookieIsHttpOnlySameSiteLaxAndNotSecureByDefault() {
        // JSESSIONID only exists once a session does (SessionCreationPolicy.IF_REQUIRED),
        // so it has to come off the login response, not an anonymous GET.
        String jsessionid = AdminCookieFlow.login(port).cookie("JSESSIONID");

        assertThat(jsessionid).containsIgnoringCase("HttpOnly");
        assertThat(jsessionid).containsIgnoringCase("SameSite=Lax");
        assertThat(jsessionid)
                .as("prod reaches the console over plain HTTP via SSH tunnel — Secure must stay opt-in")
                .doesNotContainIgnoringCase("Secure");
    }

    @Test
    void csrfCookieStaysJsReadableIsSameSiteLaxAndNotSecureByDefault() {
        // Rendered on any admin-chain request by CsrfCookieFilter.
        List<String> cookies = AdminCookieFlow.anonymousHealthGet(port);
        String xsrf = AdminCookieFlow.pick(cookies, "XSRF-TOKEN");

        assertThat(xsrf)
                .as("The SPA reads XSRF-TOKEN from document.cookie — HttpOnly here breaks CSRF")
                .doesNotContainIgnoringCase("HttpOnly");
        assertThat(xsrf).containsIgnoringCase("SameSite=Lax");
        assertThat(xsrf).doesNotContainIgnoringCase("Secure");
    }
}
