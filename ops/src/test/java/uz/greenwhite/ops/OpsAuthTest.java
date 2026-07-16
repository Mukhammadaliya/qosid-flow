package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opsauth;DB_CLOSE_DELAY=-1",
        "ops.gateway.start-command=echo s",
        "ops.gateway.stop-command=echo s"
})
@AutoConfigureMockMvc
class OpsAuthTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate opsJdbc;

    void seedAdmin() {
        opsJdbc.execute("CREATE TABLE IF NOT EXISTS admin_account (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(100) UNIQUE, password_hash VARCHAR(200), updated_at TIMESTAMP, last_login_at TIMESTAMP)");
        opsJdbc.update("DELETE FROM admin_account");
        opsJdbc.update("INSERT INTO admin_account (username, password_hash) VALUES (?, ?)",
                "admin", new BCryptPasswordEncoder().encode("s3cret-pass"));
    }

    @Test
    void statusRequiresAuth() throws Exception {
        mvc.perform(get("/ops/api/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginSucceedsWithCorrectPassword() throws Exception {
        seedAdmin();
        mvc.perform(post("/ops/api/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"s3cret-pass\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void loginFailsWithWrongPassword() throws Exception {
        seedAdmin();
        mvc.perform(post("/ops/api/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
