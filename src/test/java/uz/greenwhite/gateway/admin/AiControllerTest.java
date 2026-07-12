package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import uz.greenwhite.gateway.ai.AiAnalysisResult;
import uz.greenwhite.gateway.ai.AiAnalysisService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:ai_controller_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        "gateway.rocksdb.path=./target/test-data/rocksdb-ai-controller"
})
@AutoConfigureMockMvc
class AiControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AiAnalysisService aiAnalysisService;

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
