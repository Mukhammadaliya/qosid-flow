package uz.greenwhite.ops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Errors and Statistics modules read Loki and Prometheus — neither of which the gateway
 * owns. Before this API they were reachable ONLY through {@code /qosid/admin/api/**}, so the
 * console lost its diagnostics exactly when the gateway was stopped, which is when a diagnosis
 * is most wanted. These routes are the ops-side twin.
 *
 * <p>Both backends are pointed at a closed port so the degrade path is what gets asserted:
 * unreachable infra must answer 200 with an error string, never 500 — a console that 500s on
 * a down Loki is a second outage on top of the first.
 */
@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opsobservability;DB_CLOSE_DELAY=-1",
        "ops.settings-encryption-key=test-aes-key",
        "ops.gateway.start-command=echo s",
        "ops.gateway.stop-command=echo s",
        "gateway.loki.url=http://127.0.0.1:1",
        "gateway.prometheus.url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class OpsObservabilityApiTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode("s3cret-pass");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate opsJdbc;
    @MockitoBean GatewayStatusService status;

    @BeforeEach
    void seed() {
        opsJdbc.update("DELETE FROM admin_account");
        opsJdbc.update("INSERT INTO admin_account (username, password_hash) VALUES (?, ?)", "admin", PASSWORD_HASH);
    }

    private MockHttpSession login() throws Exception {
        MvcResult login = mvc.perform(post("/ops/api/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"s3cret-pass\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    void logsRequireASession() throws Exception {
        mvc.perform(get("/ops/api/logs").param("source", "dlq"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statsRequireASession() throws Exception {
        mvc.perform(get("/ops/api/stats").param("preset", "kpi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unreachableLokiDegradesToEmptyEntriesPlusError() throws Exception {
        mvc.perform(get("/ops/api/logs").param("source", "dlq").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.entries").isEmpty())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void unreachablePrometheusDegradesToEmptySeriesPlusError() throws Exception {
        mvc.perform(get("/ops/api/stats").param("preset", "kpi").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void labelValuesAnswerAnArrayEvenWhenLokiIsDown() throws Exception {
        mvc.perform(get("/ops/api/logs/labels").param("name", "container").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values").isArray());
    }

    @Test
    void unknownStatsPresetIsReportedNotThrown() throws Exception {
        mvc.perform(get("/ops/api/stats").param("preset", "no-such-preset").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("unknown preset")));
    }
}
