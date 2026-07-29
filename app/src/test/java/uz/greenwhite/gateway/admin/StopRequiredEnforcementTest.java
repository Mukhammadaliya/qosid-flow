package uz.greenwhite.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Task 4 (stop-to-edit): the RUNNING qosid service must refuse ALL settings writes
 * with 409 {"error":"stop-required"} — settings are only edited via the ops sidecar
 * while the service is stopped. Diagnostics (POST .../test) and every GET stay live.
 *
 * Note: there is no dedicated PUT /qosid/admin/api/tenant-mapping endpoint — tenant
 * mapping config is written through the generic PUT /settings/{key}
 * (gateway.tenant.field-name), so that is the write surface asserted here.
 */
@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:stop_required_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        "gateway.rocksdb.path=./target/test-data/rocksdb-stop-required"
})
@AutoConfigureMockMvc
class StopRequiredEnforcementTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired uz.greenwhite.gateway.settings.SettingsService settingsService;

    private MockHttpSession login() throws Exception {
        var res = mvc.perform(post("/qosid/admin/api/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin"))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    @Test
    void settingsPutReturns409() throws Exception {
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/settings/gateway.api.base-url")
                        .session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "https://x", "secret", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("stop-required"));
    }

    @Test
    void settingsPutDoesNotPersist() throws Exception {
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/settings/gateway.api.base-url")
                        .session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "https://blocked.example", "secret", false))))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(
                        settingsService.get("gateway.api.base-url", "unset"))
                .isNotEqualTo("https://blocked.example");
    }

    @Test
    void sourceAuthPutReturns409() throws Exception {
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "basic", "basicUsername", "u", "basicPassword", "p"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("stop-required"));
    }

    @Test
    void tenantMappingPutReturns409() throws Exception {
        // Tenant mapping's write endpoint IS the generic settings PUT (see class javadoc).
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/settings/gateway.tenant.field-name")
                        .session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "company_id", "secret", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("stop-required"));
    }

    @Test
    void aiConfigPutReturns409() throws Exception {
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/ai/config").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "baseUrl", "https://llm.example/v1", "model", "m", "apiKey", "k"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("stop-required"));
    }

    @Test
    void tenantTestStillWorks() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/qosid/admin/api/tenant-mapping/test")
                        .session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company_id\":100,\"filial_id\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(100));
    }

    @Test
    void sourceAuthTestStillWorks() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/qosid/admin/api/source-auth/test").session(s).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").exists());
    }

    @Test
    void settingsGetStillWorks() throws Exception {
        MockHttpSession s = login();
        mvc.perform(get("/qosid/admin/api/settings").session(s))
                .andExpect(status().isOk());
    }

    @Test
    void registryGetHasNoRestartPendingField() throws Exception {
        MockHttpSession s = login();
        mvc.perform(get("/qosid/admin/api/settings/registry").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings").isArray())
                .andExpect(jsonPath("$.restartPending").doesNotExist());
    }
}
