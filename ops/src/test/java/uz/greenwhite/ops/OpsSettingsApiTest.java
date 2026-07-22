package uz.greenwhite.ops;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uz.greenwhite.qosid.settings.SettingsCipher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Task 3 (D-loyiha): stop-to-edit settings API on the ops sidecar.
 * Schema comes from core's settings-schema.sql, initialized by OpsDbConfig.
 */
@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opssettings;DB_CLOSE_DELAY=-1",
        "ops.settings-encryption-key=test-aes-key",
        "ops.gateway.start-command=echo s",
        "ops.gateway.stop-command=echo s"
})
@AutoConfigureMockMvc
class OpsSettingsApiTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode("s3cret-pass");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate opsJdbc;
    @MockBean GatewayStatusService status;

    private final SettingsCipher testCipher = new SettingsCipher("test-aes-key");

    @BeforeEach
    void seed() {
        // settings/settings_audit/admin_account tables come from core's settings-schema.sql (OpsDbConfig init)
        opsJdbc.update("DELETE FROM settings_audit");
        opsJdbc.update("DELETE FROM settings");
        opsJdbc.update("DELETE FROM admin_account");
        opsJdbc.update("INSERT INTO admin_account (username, password_hash) VALUES (?, ?)", "admin", PASSWORD_HASH);
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "gateway.api.base-url", "http://old");
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, TRUE)",
                "gateway.source.auth.basic.password", testCipher.encrypt("oldpass"));
    }

    record OpsSession(MockHttpSession session, String csrf) {}

    OpsSession login() throws Exception {
        MvcResult login = mvc.perform(post("/ops/api/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"s3cret-pass\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String token = login.getResponse().getCookie("OPS-XSRF-TOKEN").getValue();
        return new OpsSession(session, token);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authedPut(
            OpsSession s, String key, String value) {
        return put("/ops/api/settings/" + key)
                .session(s.session())
                .cookie(new Cookie("OPS-XSRF-TOKEN", s.csrf()))
                .header("X-OPS-XSRF-TOKEN", s.csrf())
                .contentType("application/json")
                .content("{\"value\":\"" + value + "\"}");
    }

    @Test
    void listHidesInternalKeysButKeepsDbManaged() throws Exception {
        // Internal bookkeeping markers are hidden. Db-managed (source-auth/AI) keys REMAIN
        // in the list — the SPA's typed pages read them back through this generic GET in
        // stop-to-edit mode — with any secret row masked ("***") by displayValue.
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "admin.reset-marker", "1");
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "gateway._meta.restart-pending", "true");
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "gateway.settings.migration-completed", "true");
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "gateway.source.auth.type", "basic");
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "gateway.ai.model", "gpt-x");
        OpsSession s = login();
        mvc.perform(get("/ops/api/settings").session(s.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='gateway.api.base-url')].value").value("http://old"))
                .andExpect(jsonPath("$[?(@.key=='gateway.api.base-url')].secret").value(false))
                .andExpect(jsonPath("$[?(@.key=='gateway.api.base-url')].editable").value(true))
                .andExpect(jsonPath("$[?(@.key=='gateway.api.base-url')].group").value("Endpointlar"))
                // hidden: internal markers
                .andExpect(jsonPath("$[?(@.key=='admin.reset-marker')]").isEmpty())
                .andExpect(jsonPath("$[?(@.key=='gateway._meta.restart-pending')]").isEmpty())
                .andExpect(jsonPath("$[?(@.key=='gateway.settings.migration-completed')]").isEmpty())
                // present: db-managed source-auth/AI keys (typed pages read them back)
                .andExpect(jsonPath("$[?(@.key=='gateway.source.auth.type')].value").value("basic"))
                .andExpect(jsonPath("$[?(@.key=='gateway.ai.model')].value").value("gpt-x"))
                // present but masked: the seeded db-managed secret row
                .andExpect(jsonPath("$[?(@.key=='gateway.source.auth.basic.password')].value").value(OpsSettingsService.MASK));
    }

    @Test
    void listMergesEditableRegistryKeysWithDbRows() throws Exception {
        // Feature A: the list is the UNION of editable registry keys and DB rows, so the SPA's
        // edit mode can reach registry keys that were never migrated/saved.
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "gateway.source.auth.type", "basic");
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "admin.reset-marker", "1");
        OpsSession s = login();
        mvc.perform(get("/ops/api/settings").session(s.session()))
                .andExpect(status().isOk())
                // (a) editable registry key WITHOUT a DB row → value "", stored false, editable
                .andExpect(jsonPath("$[?(@.key=='gateway.source.base-url')].value").value(""))
                .andExpect(jsonPath("$[?(@.key=='gateway.source.base-url')].stored").value(false))
                .andExpect(jsonPath("$[?(@.key=='gateway.source.base-url')].editable").value(true))
                // the newly-registered LIVE key also surfaces without a DB row
                .andExpect(jsonPath("$[?(@.key=='gateway.source.oauth2-provider-uri')].stored").value(false))
                .andExpect(jsonPath("$[?(@.key=='gateway.source.oauth2-provider-uri')].editable").value(true))
                // (b) editable registry key WITH a DB row → value + stored true
                .andExpect(jsonPath("$[?(@.key=='gateway.api.base-url')].value").value("http://old"))
                .andExpect(jsonPath("$[?(@.key=='gateway.api.base-url')].stored").value(true))
                // (c) internal keys still absent
                .andExpect(jsonPath("$[?(@.key=='admin.reset-marker')]").isEmpty())
                // (d) db-managed non-registry rows still present, stored true
                .andExpect(jsonPath("$[?(@.key=='gateway.source.auth.type')].value").value("basic"))
                .andExpect(jsonPath("$[?(@.key=='gateway.source.auth.type')].stored").value(true))
                // (e) READ_ONLY registry key WITHOUT a DB row → absent (ops can't resolve yaml/env)
                .andExpect(jsonPath("$[?(@.key=='server.port')]").isEmpty());
    }

    @Test
    void listSurfacesReadOnlyRegistryKeyOnlyWhenStored() throws Exception {
        // A READ_ONLY registry key gains a row (e.g. via bootstrap) → it appears, locked, stored.
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "server.port", "8090");
        OpsSession s = login();
        mvc.perform(get("/ops/api/settings").session(s.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='server.port')].value").value("8090"))
                .andExpect(jsonPath("$[?(@.key=='server.port')].stored").value(true))
                .andExpect(jsonPath("$[?(@.key=='server.port')].editable").value(false));
    }

    @Test
    void putRejectedWhenGatewayUp() throws Exception {
        when(status.isUp()).thenReturn(true);
        OpsSession s = login();
        mvc.perform(authedPut(s, "gateway.api.base-url", "http://new"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("service-running"));
        String dbValue = opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class, "gateway.api.base-url");
        assertThat(dbValue).isEqualTo("http://old");
        Integer auditCount = opsJdbc.queryForObject("SELECT COUNT(*) FROM settings_audit", Integer.class);
        assertThat(auditCount).isZero();
    }

    @Test
    void putSavesWhenGatewayDown() throws Exception {
        when(status.isUp()).thenReturn(false);
        OpsSession s = login();
        mvc.perform(authedPut(s, "gateway.api.base-url", "http://new"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("gateway.api.base-url"))
                .andExpect(jsonPath("$.status").value("SAVED"));
        String dbValue = opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class, "gateway.api.base-url");
        assertThat(dbValue).isEqualTo("http://new");
        // audit row written and served by GET /ops/api/settings/audit (SPA lowercase contract)
        mvc.perform(get("/ops/api/settings/audit").session(s.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skey").value("gateway.api.base-url"))
                .andExpect(jsonPath("$[0].old_value").value("http://old"))
                .andExpect(jsonPath("$[0].new_value").value("http://new"));
    }

    @Test
    void putEncryptsSecret() throws Exception {
        when(status.isUp()).thenReturn(false);
        OpsSession s = login();
        mvc.perform(authedPut(s, "gateway.source.auth.basic.password", "newpass"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SAVED"));
        String stored = opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class, "gateway.source.auth.basic.password");
        assertThat(stored).isNotEqualTo("newpass");
        assertThat(testCipher.decrypt(stored)).isEqualTo("newpass");
        // audit must never leak the secret
        String auditNew = opsJdbc.queryForObject(
                "SELECT new_value FROM settings_audit WHERE skey = ?", String.class,
                "gateway.source.auth.basic.password");
        assertThat(auditNew).isEqualTo("***");
    }

    @Test
    void putUnknownKey400() throws Exception {
        when(status.isUp()).thenReturn(false);
        OpsSession s = login();
        mvc.perform(authedPut(s, "gateway.no.such.key", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown-key"));
    }

    @Test
    void putCreatesDbManagedSecretWithNoPreexistingRow() throws Exception {
        // Fresh install: oauth2 source-auth keys have no settings row until first save.
        // Db-managed secret key must be created (encrypted, secret=true), not rejected.
        when(status.isUp()).thenReturn(false);
        OpsSession s = login();
        mvc.perform(authedPut(s, "gateway.source.auth.oauth2.client-secret", "s3cr3t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SAVED"));
        String stored = opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class,
                "gateway.source.auth.oauth2.client-secret");
        assertThat(stored).isNotEqualTo("s3cr3t");
        assertThat(testCipher.decrypt(stored)).isEqualTo("s3cr3t");
        Boolean secretFlag = opsJdbc.queryForObject(
                "SELECT secret FROM settings WHERE skey = ?", Boolean.class,
                "gateway.source.auth.oauth2.client-secret");
        assertThat(secretFlag).isTrue();
        // audit must never leak the secret
        String auditNew = opsJdbc.queryForObject(
                "SELECT new_value FROM settings_audit WHERE skey = ?", String.class,
                "gateway.source.auth.oauth2.client-secret");
        assertThat(auditNew).isEqualTo("***");
    }

    @Test
    void putCreatesTelegramTokenAsSecretWithNoPreexistingRow() throws Exception {
        // Telegram kalitlari DB_MANAGED: birinchi saqlashda token secret=true bilan yaratiladi.
        when(status.isUp()).thenReturn(false);
        OpsSession s = login();
        mvc.perform(authedPut(s, "gateway.telegram.bot-token", "123:abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SAVED"));
        String stored = opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class, "gateway.telegram.bot-token");
        assertThat(stored).isNotEqualTo("123:abc");
        assertThat(testCipher.decrypt(stored)).isEqualTo("123:abc");
        Boolean secretFlag = opsJdbc.queryForObject(
                "SELECT secret FROM settings WHERE skey = ?", Boolean.class, "gateway.telegram.bot-token");
        assertThat(secretFlag).isTrue();
        String auditNew = opsJdbc.queryForObject(
                "SELECT new_value FROM settings_audit WHERE skey = ?", String.class, "gateway.telegram.bot-token");
        assertThat(auditNew).isEqualTo("***");
        // sir bo'lmagan telegram kaliti ham birinchi-yaratishda ochiq saqlanadi
        mvc.perform(authedPut(s, "gateway.telegram.chat-id", "-100200"))
                .andExpect(status().isOk());
        assertThat(opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class, "gateway.telegram.chat-id"))
                .isEqualTo("-100200");
    }

    @Test
    void putCreatesDbManagedPlainKeyWithNoPreexistingRow() throws Exception {
        when(status.isUp()).thenReturn(false);
        OpsSession s = login();
        mvc.perform(authedPut(s, "gateway.source.auth.oauth2.token-url", "https://idp/token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SAVED"));
        String stored = opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class,
                "gateway.source.auth.oauth2.token-url");
        assertThat(stored).isEqualTo("https://idp/token");
        Boolean secretFlag = opsJdbc.queryForObject(
                "SELECT secret FROM settings WHERE skey = ?", Boolean.class,
                "gateway.source.auth.oauth2.token-url");
        assertThat(secretFlag).isFalse();
    }

    @Test
    void putTrulyUnknownKeyStill400() throws Exception {
        when(status.isUp()).thenReturn(false);
        OpsSession s = login();
        mvc.perform(authedPut(s, "foo.bar", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown-key"));
        Integer count = opsJdbc.queryForObject(
                "SELECT COUNT(*) FROM settings WHERE skey = ?", Integer.class, "foo.bar");
        assertThat(count).isZero();
    }

    @Test
    void putReadOnlyKey400() throws Exception {
        when(status.isUp()).thenReturn(false);
        OpsSession s = login();
        // registry-known READ_ONLY key ("gateway.kafka.bootstrap-servers" is UNKNOWN per SettingsRegistryTest)
        mvc.perform(authedPut(s, "spring.kafka.bootstrap-servers", "kafka:9092"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("not-editable"));
    }

    @Test
    void putInternalKeyRejectedAndRowUnchanged() throws Exception {
        // Internal bookkeeping markers are never editable, even while the gateway is down.
        when(status.isUp()).thenReturn(false);
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "admin.reset-marker", "orig");
        OpsSession s = login();
        mvc.perform(authedPut(s, "admin.reset-marker", "hacked"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("not-editable"));
        String dbValue = opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class, "admin.reset-marker");
        assertThat(dbValue).isEqualTo("orig");
    }

    @Test
    void putDbManagedKeyStaysWritable() throws Exception {
        // The typed forms (SourceAuth/AI) save through this generic PUT in stop-to-edit mode.
        when(status.isUp()).thenReturn(false);
        OpsSession s = login();
        mvc.perform(authedPut(s, "gateway.source.auth.type", "oauth2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SAVED"));
        String dbValue = opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class, "gateway.source.auth.type");
        assertThat(dbValue).isEqualTo("oauth2");
    }

    @Test
    void allEndpointsRequireSession() throws Exception {
        mvc.perform(get("/ops/api/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/api/settings/audit")).andExpect(status().isUnauthorized());
        // matching CSRF cookie/header pair so the CSRF filter passes and auth (401) is what rejects
        mvc.perform(put("/ops/api/settings/gateway.api.base-url")
                        .cookie(new Cookie("OPS-XSRF-TOKEN", "t"))
                        .header("X-OPS-XSRF-TOKEN", "t")
                        .contentType("application/json")
                        .content("{\"value\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }
}

/** Blank encryption-key context: storing a secret must be refused, never stored in plaintext. */
@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opsnocipher;DB_CLOSE_DELAY=-1",
        "ops.settings-encryption-key=",
        "ops.gateway.start-command=echo s",
        "ops.gateway.stop-command=echo s"
})
@AutoConfigureMockMvc
class OpsSettingsNoCipherTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode("s3cret-pass");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate opsJdbc;
    @MockBean GatewayStatusService status;

    @Test
    void putSecretWithoutKey400() throws Exception {
        opsJdbc.update("DELETE FROM settings");
        opsJdbc.update("DELETE FROM admin_account");
        opsJdbc.update("INSERT INTO admin_account (username, password_hash) VALUES (?, ?)", "admin", PASSWORD_HASH);
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, TRUE)",
                "gateway.source.auth.basic.password", "whatever");
        when(status.isUp()).thenReturn(false);

        MvcResult login = mvc.perform(post("/ops/api/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"s3cret-pass\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String token = login.getResponse().getCookie("OPS-XSRF-TOKEN").getValue();

        mvc.perform(put("/ops/api/settings/gateway.source.auth.basic.password")
                        .session(session)
                        .cookie(new Cookie("OPS-XSRF-TOKEN", token))
                        .header("X-OPS-XSRF-TOKEN", token)
                        .contentType("application/json")
                        .content("{\"value\":\"newpass\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("encryption-unavailable"));
        String stored = opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class, "gateway.source.auth.basic.password");
        assertThat(stored).isEqualTo("whatever");
    }

    @Test
    void putDbManagedSecretWithoutKeyAndNoRow400() throws Exception {
        // First-time creation of a db-managed SECRET key still requires the cipher —
        // never silently store a secret in plaintext.
        opsJdbc.update("DELETE FROM settings");
        opsJdbc.update("DELETE FROM admin_account");
        opsJdbc.update("INSERT INTO admin_account (username, password_hash) VALUES (?, ?)", "admin", PASSWORD_HASH);
        when(status.isUp()).thenReturn(false);

        MvcResult login = mvc.perform(post("/ops/api/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"s3cret-pass\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String token = login.getResponse().getCookie("OPS-XSRF-TOKEN").getValue();

        mvc.perform(put("/ops/api/settings/gateway.source.auth.oauth2.client-secret")
                        .session(session)
                        .cookie(new Cookie("OPS-XSRF-TOKEN", token))
                        .header("X-OPS-XSRF-TOKEN", token)
                        .contentType("application/json")
                        .content("{\"value\":\"s3cr3t\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("encryption-unavailable"));
        Integer count = opsJdbc.queryForObject(
                "SELECT COUNT(*) FROM settings WHERE skey = ?", Integer.class,
                "gateway.source.auth.oauth2.client-secret");
        assertThat(count).isZero();
    }
}
