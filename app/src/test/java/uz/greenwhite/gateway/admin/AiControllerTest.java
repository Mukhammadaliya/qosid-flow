package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import uz.greenwhite.gateway.ai.AiAnalysisResult;
import uz.greenwhite.gateway.ai.AiAnalysisService;
import uz.greenwhite.gateway.settings.SettingsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stop-to-edit: PUT /ai/config is refused on the running service (409) — config is
 * edited via the ops sidecar while stopped. GET /status, GET /config (masked) and
 * POST /analyze stay live. Config for read assertions is seeded through SettingsService.
 */
@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:ai_controller_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        "gateway.settings.encryption-key=test-key-0123456789",
        "gateway.rocksdb.path=./target/test-data/rocksdb-ai-controller"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiControllerTest {

    @Autowired MockMvc mvc;
    @Autowired SettingsService settingsService;
    @MockitoBean AiAnalysisService aiAnalysisService;

    @Test
    @Order(1)
    @WithMockUser(roles = "ADMIN")
    void configApiKeyEmptyWhenNotStored() throws Exception {
        mvc.perform(get("/qosid/admin/api/ai/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value(""));
    }

    @Test
    @Order(2)
    @WithMockUser(roles = "ADMIN")
    void configMasksStoredApiKey() throws Exception {
        settingsService.set("gateway.ai.base-url", "https://llm.example/v1", false, "test");
        settingsService.set("gateway.ai.model", "gpt-4o-mini", false, "test");
        settingsService.set("gateway.ai.api-key", "sk-x", true, "test");

        mvc.perform(get("/qosid/admin/api/ai/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value("***"))
                .andExpect(jsonPath("$.baseUrl").value("https://llm.example/v1"))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"));
        assertThat(settingsService.getSecret("gateway.ai.api-key", "")).isEqualTo("sk-x");
    }

    @Test
    @Order(3)
    @WithMockUser(roles = "ADMIN")
    void putConfigReturns409StopRequiredAndDoesNotWrite() throws Exception {
        settingsService.set("gateway.ai.api-key", "sk-first", true, "test");

        mvc.perform(put("/qosid/admin/api/ai/config").with(csrf())
                        .contentType("application/json")
                        .content("{\"baseUrl\":\"https://other.example/v1\",\"apiKey\":\"sk-evil\",\"model\":\"m\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("stop-required"));

        assertThat(settingsService.getSecret("gateway.ai.api-key", "")).isEqualTo("sk-first");
    }

    @Test
    void statusUnauthenticatedIs401() throws Exception {
        mvc.perform(get("/qosid/admin/api/ai/status")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void statusReflectsEnabled() throws Exception {
        when(aiAnalysisService.enabled()).thenReturn(false);
        mvc.perform(get("/qosid/admin/api/ai/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void analyzeReturnsResult() throws Exception {
        when(aiAnalysisService.analyze(any())).thenReturn(new AiAnalysisResult("root cause X", null));
        mvc.perform(post("/qosid/admin/api/ai/analyze").with(csrf())
                        .contentType("application/json")
                        .content("{\"line\":\"boom\",\"level\":\"ERROR\",\"labels\":{},\"parsed\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis").value("root cause X"));
    }
}
