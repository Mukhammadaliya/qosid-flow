package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deferred half of the cookie hardening: {@code COOKIE_SECURE=true} must actually add
 * {@code Secure} to BOTH gateway cookies. Prod ships with it off (plain HTTP over an SSH
 * tunnel — see {@link AdminCookieHardeningTest}); this test is what proves flipping the
 * switch works the day TLS/nginx lands, so nobody has to discover it in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:cookie_secure_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        "gateway.rocksdb.path=./target/test-data/rocksdb-cookie-secure",
        "gateway.security.cookie-secure=true",
        "server.servlet.session.cookie.secure=true"
})
class AdminCookieSecureFlagTest {

    @LocalServerPort
    int port;

    @Test
    void cookieSecureTrueMarksTheCsrfCookieSecure() {
        String xsrf = AdminCookieFlow.pick(AdminCookieFlow.anonymousHealthGet(port), "XSRF-TOKEN");
        assertThat(xsrf).containsIgnoringCase("Secure");
    }

    @Test
    void cookieSecureTrueMarksTheSessionCookieSecure() {
        String jsessionid = AdminCookieFlow.login(port).cookie("JSESSIONID");
        assertThat(jsessionid).containsIgnoringCase("Secure");
        assertThat(jsessionid).as("hardening is additive — HttpOnly/SameSite stay on")
                .containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
    }
}
