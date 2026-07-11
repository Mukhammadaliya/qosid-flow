package uz.greenwhite.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards SAFETY: adding Spring Security must NOT lock the pipeline or actuator.
 * /qosid/api/** and actuator stay open; /qosid/admin/api/** is protected.
 */
@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:sec_isolation_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false"
})
@AutoConfigureMockMvc
class SecurityIsolationTest {

    @Autowired MockMvc mvc;

    @Test
    void adminApiRequiresAuth() throws Exception {
        mvc.perform(get("/qosid/admin/api/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminHealthIsOpen() throws Exception {
        mvc.perform(get("/qosid/admin/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    void pipelineStatusEndpointNotLockedBySecurity() throws Exception {
        // Unknown id → 404 from the handler, NOT 401/403 from security.
        mvc.perform(get("/qosid/api/v1/requests/0:123/status"))
                .andExpect(status().isNotFound());
    }
}
