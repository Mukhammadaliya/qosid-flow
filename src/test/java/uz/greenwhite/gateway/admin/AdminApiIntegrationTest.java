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
        "gateway.settings.db-path=mem:admin_it_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        // Own RocksDB dir: this test's Spring context is cached alongside SecurityIsolationTest's
        // (different gateway.settings.db-path -> different context key), and on Windows the
        // shared default path's LOCK file cannot be held by two open RocksDB instances at once.
        "gateway.rocksdb.path=./target/test-data/rocksdb-admin-it"
})
@AutoConfigureMockMvc
class AdminApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/qosid/admin/api/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin"))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void unauthenticatedSettingsReturns401() throws Exception {
        mvc.perform(get("/qosid/admin/api/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginThenReadAndWriteSettings() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/qosid/admin/api/settings").session(session))
                .andExpect(status().isOk());

        mvc.perform(put("/qosid/admin/api/settings/gateway.retry.max-attempts")
                        .session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "5", "secret", false))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/qosid/admin/api/settings/audit").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void auditRowsUseLowercaseJsonKeys() throws Exception {
        // H2 upper-cases unquoted column labels; the frontend audit table binds to lowercase
        // dataIndex keys ('skey', 'old_value', ...) — the API must preserve them.
        MockHttpSession session = login();

        mvc.perform(put("/qosid/admin/api/settings/gateway.retry.max-attempts")
                        .session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "7", "secret", false))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/qosid/admin/api/settings/audit").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.skey == 'gateway.retry.max-attempts' && @.new_value == '7')]").exists())
                .andExpect(jsonPath("$[0].skey").exists())
                .andExpect(jsonPath("$[0].changed_by").value("admin"))
                .andExpect(jsonPath("$[0].SKEY").doesNotExist())
                .andExpect(jsonPath("$[0].OLD_VALUE").doesNotExist());
    }

    @Test
    void changePasswordWithShortNewPasswordReturns400NotStackTrace() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/qosid/admin/api/change-password")
                        .session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("oldPassword", "admin", "newPassword", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
