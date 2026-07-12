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
    @Autowired uz.greenwhite.gateway.settings.SettingsService settingsService;

    /** Blanks a setting so a test can start from a "nothing stored" state (H2 mem DB is shared per class). */
    private void clearSetting(MockHttpSession s, String key) throws Exception {
        mvc.perform(put("/qosid/admin/api/settings/" + key).session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "", "secret", false))))
                .andExpect(status().isNoContent());
    }

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

    @Test
    void putBasicWithoutPasswordAndNoneStoredIs400() throws Exception {
        // I2: fresh config (no password row yet) — username-only save must not silently
        // persist an empty-password Basic auth.
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("type", "basic", "basicUsername", "u"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putBasicWithoutPasswordButAlreadyStoredKeepsExisting() throws Exception {
        // Keep-existing-password semantics: once a password row exists, a later save that
        // only changes the username must succeed without requiring the password again.
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "basic", "basicUsername", "u1", "basicPassword", "p1"))))
                .andExpect(status().isNoContent());

        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("type", "basic", "basicUsername", "u2"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void putOauth2WithoutSecretButAlreadyStoredKeepsExisting() throws Exception {
        // Blocker 2: UI omits the secret when the masked field is untouched — editing e.g.
        // scope/TTL with a stored secret must succeed AND keep the stored secret.
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "oauth2",
                                "oauth2TokenUrl", "http://x/token",
                                "oauth2ClientId", "cid",
                                "oauth2ClientSecret", "sekret-1"))))
                .andExpect(status().isNoContent());

        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "oauth2",
                                "oauth2TokenUrl", "http://x/token2",
                                "oauth2ClientId", "cid2",
                                "oauth2Scope", "read"))))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(
                settingsService.getSecret("gateway.source.auth.oauth2.client-secret", ""))
                .isEqualTo("sekret-1"); // stored secret untouched by the secret-less update

        mvc.perform(get("/qosid/admin/api/source-auth").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oauth2ClientId").value("cid2"))
                .andExpect(jsonPath("$.oauth2ClientSecret").value("***"));
    }

    @Test
    void putApikeyWithoutValueButAlreadyStoredKeepsExisting() throws Exception {
        // Blocker 2, apikey shape: header-only update must succeed once a value is stored.
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "apikey", "apikeyHeader", "X-Api-Key", "apikeyValue", "key-1"))))
                .andExpect(status().isNoContent());

        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "apikey", "apikeyHeader", "X-Custom-Key"))))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(
                settingsService.getSecret("gateway.source.auth.apikey.value", ""))
                .isEqualTo("key-1");

        mvc.perform(get("/qosid/admin/api/source-auth").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apikeyHeader").value("X-Custom-Key"))
                .andExpect(jsonPath("$.apikeyValue").value("***"));
    }

    @Test
    void putApikeyWithoutValueAndNoneStoredIs400() throws Exception {
        MockHttpSession s = login();
        clearSetting(s, "gateway.source.auth.apikey.value"); // other tests in this class may have stored one
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("type", "apikey", "apikeyHeader", "X-Api-Key"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putOauth2WithoutSecretAndNoneStoredIs400() throws Exception {
        MockHttpSession s = login();
        clearSetting(s, "gateway.source.auth.oauth2.client-secret");
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "oauth2", "oauth2TokenUrl", "http://x", "oauth2ClientId", "cid"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putOauth2NonNumericCacheTtlIs400() throws Exception {
        // Minor(b): oauth2CacheTtlSeconds is optional but must parse as a long when present.
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "oauth2",
                                "oauth2TokenUrl", "http://x",
                                "oauth2ClientId", "cid",
                                "oauth2ClientSecret", "secret",
                                "oauth2CacheTtlSeconds", "notANumber"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testEndpointDoesNotConsumePullEndpointAndReturnsOkForApikey() throws Exception {
        // I3: /test must never hit the source pull URI (it consumes pending requests) —
        // it only needs to resolve the configured auth headers.
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "apikey", "apikeyHeader", "X-Api-Key", "apikeyValue", "secret-k"))))
                .andExpect(status().isNoContent());

        mvc.perform(post("/qosid/admin/api/source-auth/test").session(s).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.type").value("apikey"));
    }
}
