package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opsctl;DB_CLOSE_DELAY=-1",
        "ops.gateway.start-command=echo s",
        "ops.gateway.stop-command=echo s"
})
@AutoConfigureMockMvc
class OpsControllerTest {

    @Autowired MockMvc mvc;
    @MockBean GatewayStatusService status;
    @MockBean GatewayControlService control;

    @Test
    void statusRequiresAuth() throws Exception {
        mvc.perform(get("/ops/api/status")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void statusReportsUp() throws Exception {
        when(status.isUp()).thenReturn(true);
        mvc.perform(get("/ops/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.up").value(true));
    }

    @Test
    @WithMockUser
    void stopInvokesControlService() throws Exception {
        when(control.stop()).thenReturn(new GatewayControlService.ControlResult(true, 0, "stopped"));
        mvc.perform(post("/ops/api/stop").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}
