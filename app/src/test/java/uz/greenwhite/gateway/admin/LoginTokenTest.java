package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uz.greenwhite.qosid.settings.LoginHandoffRepository;
import uz.greenwhite.qosid.settings.LoginRateLimiter;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 5 (D-loyiha): POST /qosid/admin/api/login-token exchanges a one-time handoff
 * token (minted by the ops sidecar, only its SHA-256 hash in the shared H2) for a
 * regular admin session — same response shape and session semantics as POST /login.
 */
@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:login_token_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        "gateway.rocksdb.path=./target/test-data/rocksdb-login-token"
})
@AutoConfigureMockMvc
class LoginTokenTest {

    @Autowired MockMvc mvc;
    @Autowired LoginHandoffRepository handoffRepository;
    @Autowired LoginRateLimiter rateLimiter;

    @BeforeEach
    void resetRateLimiter() {
        // shared bean in the cached context — a prior test's failures must not bleed over
        rateLimiter.reset(AdminAuthService.TOKEN_RATE_KEY);
    }

    private MvcResult postToken(String token) throws Exception {
        return mvc.perform(post("/qosid/admin/api/login-token")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andReturn();
    }

    @Test
    void validTokenCreatesSession() throws Exception {
        handoffRepository.insert(LoginHandoffRepository.sha256Hex("tok-valid"), "admin",
                Instant.now().plusSeconds(60));

        MvcResult res = mvc.perform(post("/qosid/admin/api/login-token")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-valid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) res.getRequest().getSession(false);
        assertThat(session).as("login-token must establish a session").isNotNull();

        mvc.perform(get("/qosid/admin/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void reusedTokenRejected() throws Exception {
        handoffRepository.insert(LoginHandoffRepository.sha256Hex("tok-once"), "admin",
                Instant.now().plusSeconds(60));

        assertThat(postToken("tok-once").getResponse().getStatus()).isEqualTo(200);

        MvcResult second = postToken("tok-once");
        assertThat(second.getResponse().getStatus()).isEqualTo(401);
        assertThat(second.getResponse().getContentAsString()).contains("\"error\":\"invalid-token\"");
    }

    @Test
    void expiredTokenRejected() throws Exception {
        handoffRepository.insert(LoginHandoffRepository.sha256Hex("tok-expired"), "admin",
                Instant.now().minusSeconds(1));

        MvcResult res = postToken("tok-expired");
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
        assertThat(res.getResponse().getContentAsString()).contains("\"error\":\"invalid-token\"");
    }

    @Test
    void rateLimited() throws Exception {
        // same LoginRateLimiter policy as /login: 5 failures / 60s window, then 429
        for (int i = 0; i < 5; i++) {
            assertThat(postToken("bogus-" + i).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(postToken("bogus-final").getResponse().getStatus()).isEqualTo(429);
    }
}
