package uz.greenwhite.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stop-to-edit: PUT /source-auth is refused on the running service (409) — config
 * is edited via the ops sidecar while stopped. GET (masked) and POST /test stay live.
 * Config for read/test assertions is seeded through SettingsService, the same internal
 * write path the migration runner and ops sidecar use.
 */
@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:source_auth_test;DB_CLOSE_DELAY=-1",
        "gateway.rocksdb.path=./target/test-data/rocksdb-source-auth",
        "gateway.settings.encryption-key=test-key-0123456789",
        "gateway.telegram.enabled=false"
})
@AutoConfigureMockMvc
class SourceAuthApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired SettingsService settingsService;

    private MockHttpSession login() throws Exception {
        var res = mvc.perform(post("/qosid/admin/api/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin"))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    @Test
    void unauthenticatedGetIs401() throws Exception {
        mvc.perform(get("/qosid/admin/api/source-auth")).andExpect(status().isUnauthorized());
    }

    @Test
    void putReturns409StopRequiredAndDoesNotPersist() throws Exception {
        MockHttpSession s = login();
        // Distinct payload values: other tests in this class legitimately seed apikey
        // settings via SettingsService in the same shared context/DB.
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "apikey", "apikeyHeader", "X-Blocked-Header", "apikeyValue", "blocked-v"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("stop-required"));

        assertThat(settingsService.get("gateway.source.auth.apikey.header", ""))
                .as("blocked PUT must not write any source-auth setting")
                .isNotEqualTo("X-Blocked-Header");
    }

    @Test
    void getMasksStoredSecrets() throws Exception {
        MockHttpSession s = login();
        settingsService.set("gateway.source.auth.type", "apikey", false, "test");
        settingsService.set("gateway.source.auth.apikey.header", "X-Api-Key", false, "test");
        settingsService.set("gateway.source.auth.apikey.value", "secret-k", true, "test");

        mvc.perform(get("/qosid/admin/api/source-auth").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("apikey"))
                .andExpect(jsonPath("$.apikeyHeader").value("X-Api-Key"))
                .andExpect(jsonPath("$.apikeyValue").value("***")); // masked
    }

    @Test
    void testEndpointDoesNotConsumePullEndpointAndReturnsOkForApikey() throws Exception {
        // I3: /test must never hit the source pull URI (it consumes pending requests) —
        // it only needs to resolve the configured auth headers. Still live under stop-to-edit.
        MockHttpSession s = login();
        settingsService.set("gateway.source.auth.type", "apikey", false, "test");
        settingsService.set("gateway.source.auth.apikey.header", "X-Api-Key", false, "test");
        settingsService.set("gateway.source.auth.apikey.value", "secret-k", true, "test");

        mvc.perform(post("/qosid/admin/api/source-auth/test").session(s).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.type").value("apikey"));
    }
}
