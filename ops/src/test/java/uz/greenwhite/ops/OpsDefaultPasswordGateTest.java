package uz.greenwhite.ops;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ops sidecar authenticates against the SAME shared admin_account as the gateway, so the
 * gateway-side default-password gate (DefaultPasswordGateFilter) left the real attack wide open:
 * log in here with admin/admin, POST /ops/api/stop, PUT the settings that the gateway reads on
 * boot (e.g. gateway.api.base-url), POST /ops/api/start — the whole pipeline is then delivered to
 * a host of the attacker's choosing. This class closes that half of the hole.
 *
 * <p>Ops needs its OWN change-password endpoint: in the normal stop-to-edit state the gateway is
 * down, so its /qosid/admin/api/change-password is unreachable — gating ops without an escape
 * hatch would lock the operator out completely.
 *
 * <p>The gate is off on the ops test classpath (see {@code src/test/resources/application.properties});
 * this class turns it back on explicitly and owns its coverage.
 */
@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opsgate;DB_CLOSE_DELAY=-1",
        "ops.settings-encryption-key=test-aes-key",
        "ops.gateway.start-command=echo s",
        "ops.gateway.stop-command=echo s",
        "ops.admin.force-password-change=true"
})
@AutoConfigureMockMvc
class OpsDefaultPasswordGateTest {

    private static final String NEW_PASSWORD = "Str0ng-Passw0rd";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate opsJdbc;
    @MockitoBean GatewayStatusService status;
    @MockitoBean GatewayControlService control;

    /** Reset to the seeded default before every case — each test decides the password state it needs. */
    @BeforeEach
    void seedDefaultPassword() {
        opsJdbc.update("DELETE FROM admin_account");
        opsJdbc.update("INSERT INTO admin_account (username, password_hash) VALUES (?, ?)",
                "admin", new BCryptPasswordEncoder().encode("admin"));
        when(control.stop()).thenReturn(new GatewayControlService.ControlResult(true, 0, "stopped"));
    }

    record OpsSession(MockHttpSession session, String csrf) {}

    private OpsSession login(String password) throws Exception {
        MvcResult login = mvc.perform(post("/ops/api/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return new OpsSession((MockHttpSession) login.getRequest().getSession(false),
                login.getResponse().getCookie("OPS-XSRF-TOKEN").getValue());
    }

    /** Authenticated + CSRF-valid request builder, so a 403 can only come from the gate. */
    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, OpsSession s) {
        return builder.session(s.session())
                .cookie(new Cookie("OPS-XSRF-TOKEN", s.csrf()))
                .header("X-OPS-XSRF-TOKEN", s.csrf());
    }

    /**
     * The whole point: default password in, protected route out, change the password here (the
     * gateway is stopped and cannot do it), and the same route works after re-login.
     */
    @Test
    void fullLoopStopBlockedThenChangePasswordThenStopWorks() throws Exception {
        OpsSession s = login("admin");

        mvc.perform(authed(post("/ops/api/stop"), s))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("password-change-required"));

        mvc.perform(authed(post("/ops/api/change-password"), s).contentType("application/json")
                        .content("{\"oldPassword\":\"admin\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        // change-password invalidates the ops session on purpose -> log in again with the new one.
        OpsSession s2 = login(NEW_PASSWORD);
        mvc.perform(authed(post("/ops/api/stop"), s2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    /**
     * The SPA has to learn about the gate from ops, not only from the gateway.
     *
     * <p>In the normal stop-to-edit state the gateway is DOWN, so its {@code /login} and
     * {@code /me} — the only responses that used to carry {@code passwordChangeRequired} — never
     * answer. Without the hint here the console cannot know it must show the change-password form,
     * and every ops write just 403s with no explanation. The value is a string because the body is
     * a {@code Map<String, String>}, matching the gateway's contract exactly.
     */
    @Test
    void loginAndMeReportPasswordChangeRequiredWhileTheDefaultIsInPlace() throws Exception {
        mvc.perform(post("/ops/api/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value("true"));

        OpsSession s = login("admin");
        mvc.perform(get("/ops/api/me").session(s.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("admin"))
                .andExpect(jsonPath("$.passwordChangeRequired").value("true"));
    }

    /** ...and stops reporting it once the password is no longer the default. */
    @Test
    void theHintClearsAfterThePasswordIsChanged() throws Exception {
        OpsSession s = login("admin");
        mvc.perform(authed(post("/ops/api/change-password"), s).contentType("application/json")
                        .content("{\"oldPassword\":\"admin\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/ops/api/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value("false"));

        OpsSession s2 = login(NEW_PASSWORD);
        mvc.perform(get("/ops/api/me").session(s2.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value("false"));
    }

    /**
     * Unauthenticated callers must still get 401 (the gate runs AFTER AuthorizationFilter), so
     * nothing about the password state leaks to an anonymous prober.
     */
    @Test
    void unauthenticatedCallerStillGets401NotThe403() throws Exception {
        mvc.perform(get("/ops/api/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/api/status")).andExpect(status().isUnauthorized());
    }

    /** Without these the operator is locked out: changing the password needs access to ops. */
    @Test
    void allowlistedRoutesStayOpenWhilePasswordIsDefault() throws Exception {
        OpsSession s = login("admin");
        when(status.isUp()).thenReturn(false);
        mvc.perform(get("/ops/api/status").session(s.session())).andExpect(status().isOk());
        mvc.perform(get("/ops/api/me").session(s.session())).andExpect(status().isOk());
        mvc.perform(authed(post("/ops/api/logout"), s)).andExpect(status().isOk());
    }

    /** Everything an attacker would actually use is blocked — and matching is exact, not by prefix. */
    @Test
    void everyOtherRouteIsBlockedIncludingSettingsAudit() throws Exception {
        OpsSession s = login("admin");
        mvc.perform(authed(post("/ops/api/start"), s))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("password-change-required"));
        mvc.perform(get("/ops/api/settings").session(s.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("password-change-required"));
        // "settings" is allowlisted nowhere, but a PREFIX match on any allowlist entry would
        // let these through — assert the sub-paths explicitly.
        mvc.perform(get("/ops/api/settings/audit").session(s.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("password-change-required"));
        mvc.perform(authed(put("/ops/api/settings/gateway.api.base-url"), s).contentType("application/json")
                        .content("{\"value\":\"http://attacker.example\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("password-change-required"));
        mvc.perform(get("/ops/api/logs/stream").session(s.session()))
                .andExpect(status().isForbidden());
        mvc.perform(authed(post("/ops/api/handoff"), s))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("password-change-required"));
    }

    /** Same rules as the gateway's AdminAuthService.changePassword — no weaker variant on ops. */
    @Test
    void changePasswordEnforcesTheSameRulesAsTheGateway() throws Exception {
        OpsSession s = login("admin");

        mvc.perform(authed(post("/ops/api/change-password"), s).contentType("application/json")
                        .content("{\"oldPassword\":\"wrong\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(authed(post("/ops/api/change-password"), s).contentType("application/json")
                        .content("{\"oldPassword\":\"admin\",\"newPassword\":\"short7c\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(authed(post("/ops/api/change-password"), s).contentType("application/json")
                        .content("{\"oldPassword\":\"admin\",\"newPassword\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());

        // all three refused -> the account is still on the default password, still gated
        mvc.perform(authed(post("/ops/api/stop"), s))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("password-change-required"));
    }

    /** Once the password is not the default, ops behaves exactly as before this change. */
    @Test
    void gateIsInertOnceThePasswordIsNotTheDefault() throws Exception {
        opsJdbc.update("UPDATE admin_account SET password_hash = ? WHERE username = ?",
                new BCryptPasswordEncoder().encode(NEW_PASSWORD), "admin");
        OpsSession s = login(NEW_PASSWORD);
        mvc.perform(authed(post("/ops/api/stop"), s)).andExpect(status().isOk());
        mvc.perform(get("/ops/api/settings").session(s.session())).andExpect(status().isOk());
    }
}
