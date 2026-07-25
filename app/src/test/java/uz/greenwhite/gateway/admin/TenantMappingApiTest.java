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

@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:tenant_api_test;DB_CLOSE_DELAY=-1",
        "gateway.rocksdb.path=./target/test-data/rocksdb-tenant-api",
        "gateway.telegram.enabled=false"
})
@AutoConfigureMockMvc
class TenantMappingApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private MockHttpSession login() throws Exception {
        var res = mvc.perform(post("/qosid/admin/api/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin"))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    @Test
    void tenantFieldNameWriteBlockedWith409() throws Exception {
        // Stop-to-edit: the tenant field-name is written via the generic settings PUT,
        // which the running service always refuses (edited via the ops sidecar instead).
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/settings/gateway.tenant.field-name")
                        .session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "", "secret", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("stop-required"));
    }

    @Test
    void mappingTestExtractsTenant() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/qosid/admin/api/tenant-mapping/test")
                        .session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company_id\":100,\"filial_id\":5,\"request_id\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(100))
                .andExpect(jsonPath("$.extraTenantId").value(5))
                .andExpect(jsonPath("$.tenantField").value("company_id"));
    }

    @Test
    void mappingTestRequiresAuth() throws Exception {
        mvc.perform(post("/qosid/admin/api/tenant-mapping/test").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
