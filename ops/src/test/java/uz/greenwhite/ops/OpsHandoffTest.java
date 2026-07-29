package uz.greenwhite.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uz.greenwhite.qosid.settings.LoginHandoffRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 5 (D-loyiha): POST /ops/api/handoff mints a one-time login token for the
 * already-authenticated operator. Only the SHA-256 hash lands in login_handoff
 * (shared H2), TTL ~60s; the gateway later exchanges the raw token for a session.
 */
@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opshandoff;DB_CLOSE_DELAY=-1",
        "ops.gateway.start-command=echo s",
        "ops.gateway.stop-command=echo s"
})
@AutoConfigureMockMvc
class OpsHandoffTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode("s3cret-pass");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate opsJdbc;
    @Autowired ObjectMapper om;

    @BeforeEach
    void seed() {
        // admin_account/login_handoff tables come from core's settings-schema.sql (OpsDbConfig init)
        opsJdbc.update("DELETE FROM login_handoff");
        opsJdbc.update("DELETE FROM admin_account");
        opsJdbc.update("INSERT INTO admin_account (username, password_hash) VALUES (?, ?)", "admin", PASSWORD_HASH);
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

    @Test
    void handoffWithoutSessionIs401() throws Exception {
        // matching CSRF cookie/header pair so the CSRF filter passes and auth (401) is what rejects
        mvc.perform(post("/ops/api/handoff")
                        .cookie(new Cookie("OPS-XSRF-TOKEN", "t"))
                        .header("X-OPS-XSRF-TOKEN", "t"))
                .andExpect(status().isUnauthorized());
        Integer rows = opsJdbc.queryForObject("SELECT COUNT(*) FROM login_handoff", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void handoffIssuesOneTimeTokenStoringOnlyItsHash() throws Exception {
        OpsSession s = login();

        Instant before = Instant.now();
        MvcResult res = mvc.perform(post("/ops/api/handoff")
                        .session(s.session())
                        .cookie(new Cookie("OPS-XSRF-TOKEN", s.csrf()))
                        .header("X-OPS-XSRF-TOKEN", s.csrf()))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, String> body = om.readValue(res.getResponse().getContentAsString(), Map.class);
        String token = body.get("token");
        assertThat(token).isNotBlank();
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32); // 32 random bytes, base64url

        Map<String, Object> row = opsJdbc.queryForMap(
                "SELECT token_hash, username, expires_at FROM login_handoff");
        assertThat(row.get("token_hash")).isNotEqualTo(token); // raw token must NEVER be stored
        assertThat(row.get("token_hash")).isEqualTo(LoginHandoffRepository.sha256Hex(token));
        assertThat(row.get("username")).isEqualTo("admin");
        Instant expiresAt = ((Timestamp) row.get("expires_at")).toInstant();
        assertThat(expiresAt).isBetween(before.plusSeconds(30), before.plusSeconds(90)); // ~60s TTL
    }
}
