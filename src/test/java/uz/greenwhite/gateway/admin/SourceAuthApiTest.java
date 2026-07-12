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
        "gateway.settings.db-path=mem:source_auth_test;DB_CLOSE_DELAY=-1",
        "gateway.rocksdb.path=./target/test-data/rocksdb-source-auth",
        "gateway.settings.encryption-key=test-key-0123456789",
        "gateway.telegram.enabled=false"
})
@AutoConfigureMockMvc
class SourceAuthApiTest {

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
    void unauthenticatedGetIs401() throws Exception {
        mvc.perform(get("/qosid/admin/api/source-auth")).andExpect(status().isUnauthorized());
    }

    @Test
    void putThenGetMasksSecret() throws Exception {
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "apikey", "apikeyHeader", "X-Api-Key", "apikeyValue", "secret-k"))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/qosid/admin/api/source-auth").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("apikey"))
                .andExpect(jsonPath("$.apikeyValue").value("***")); // masked
    }

    @Test
    void putOauth2MissingTokenUrlIs400() throws Exception {
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("type", "oauth2", "oauth2ClientId", "cid"))))
                .andExpect(status().isBadRequest());
    }
}
