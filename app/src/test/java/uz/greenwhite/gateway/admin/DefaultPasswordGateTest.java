package uz.greenwhite.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import uz.greenwhite.qosid.settings.AdminAccountRepository;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seeded {@code admin}/{@code admin} account must not be usable for anything but changing
 * its own password: with that account an attacker stops the gateway through the ops sidecar,
 * rewrites {@code gateway.api.base-url} and restarts it, redirecting the whole pipeline.
 *
 * <p>Server-enforced (the SPA is not trusted): while the stored password still matches the
 * default, only {@code login}, {@code login-token}, {@code logout}, {@code me},
 * {@code change-password} and {@code health} answer normally — every other
 * {@code /qosid/admin/api/**} route returns {@code 403 {"error":"password-change-required"}}.
 *
 * <p>The gate is off by default in tests (see {@code src/test/resources/application.properties})
 * because most admin tests legitimately log in with the seeded default password; this class turns
 * it back on explicitly.
 *
 * <p>Method order is fixed on purpose: the last test changes the password for real, and
 * {@link AdminAuthService#isDefaultPassword()} latches its negative result (see its javadoc),
 * so a "password is default" assertion after it would read the latch, not the DB.
 */
@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:default_pw_gate_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        "gateway.rocksdb.path=./target/test-data/rocksdb-default-pw-gate",
        "gateway.admin.force-password-change=true"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DefaultPasswordGateTest {

    private static final String NEW_PASSWORD = "Str0ng-Passw0rd";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired AdminAccountRepository repository;
    @Autowired PasswordEncoder passwordEncoder;

    /** Leave the shared in-memory account exactly as the initializer seeded it. */
    @AfterEach
    void restoreDefaultPassword() {
        repository.updatePassword("admin", passwordEncoder.encode("admin"));
    }

    private MockHttpSession login(String password) throws Exception {
        var res = mvc.perform(post("/qosid/admin/api/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", password))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    @Test
    @Order(0)
    void pipelineStaysOpenWhileTheGateIsActive() throws Exception {
        // SAFETY 29: the gate is registered on adminSecurityChain ONLY. If it ever leaks onto
        // apiSecurityChain, every proxied request dies — assert the pipeline here, with the gate on.
        mvc.perform(get("/qosid/api/v1/requests/0:123/status")).andExpect(status().isNotFound());
        mvc.perform(post("/qosid/api/v1/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"0:1\",\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(1)
    void loginStillWorksAndFlagsThatThePasswordMustChange() throws Exception {
        // Login MUST keep working — it is the only way in, and changing the password needs a session.
        mvc.perform(post("/qosid/admin/api/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value("true"));
    }

    @Test
    @Order(2)
    void allowlistedRoutesStayOpenSoTheOperatorCannotLockThemselfOut() throws Exception {
        MockHttpSession s = login("admin");
        mvc.perform(get("/qosid/admin/api/me").session(s)).andExpect(status().isOk());
        mvc.perform(get("/qosid/admin/api/health")).andExpect(status().isOk());
        mvc.perform(post("/qosid/admin/api/logout").session(s).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(3)
    void protectedRouteIsBlockedWhilePasswordIsDefault() throws Exception {
        MockHttpSession s = login("admin");
        mvc.perform(get("/qosid/admin/api/settings").session(s))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("password-change-required"));
        // The gate must not leak the state to unauthenticated callers, and must not turn the
        // 401 that SecurityIsolationTest guards into a 403.
        mvc.perform(get("/qosid/admin/api/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    void fullLoop403ThenChangePasswordThen200() throws Exception {
        MockHttpSession s = login("admin");
        mvc.perform(get("/qosid/admin/api/settings").session(s))
                .andExpect(status().isForbidden());

        mvc.perform(post("/qosid/admin/api/change-password").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "oldPassword", "admin", "newPassword", NEW_PASSWORD))))
                .andExpect(status().isNoContent());

        // change-password invalidates the session on purpose -> log in again with the new password.
        MockHttpSession s2 = login(NEW_PASSWORD);
        mvc.perform(get("/qosid/admin/api/settings").session(s2)).andExpect(status().isOk());
    }

    @Test
    @Order(5)
    void loginReportsNoChangeNeededOnceThePasswordIsNotTheDefault() throws Exception {
        repository.updatePassword("admin", passwordEncoder.encode(NEW_PASSWORD));
        mvc.perform(post("/qosid/admin/api/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", NEW_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value("false"));
    }
}
