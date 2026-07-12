package uz.greenwhite.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaForwardControllerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new SpaForwardController())
            .build();

    @Test
    void navigationRouteForwardsToIndex() throws Exception {
        mvc.perform(get("/settings")).andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void nestedNavigationRouteForwardsToIndex() throws Exception {
        mvc.perform(get("/settings/source-auth")).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void assetPathIsNotForwarded() throws Exception {
        // A path whose last segment contains a dot (bundle.js) must NOT be forwarded —
        // the resource handler serves it. standaloneSetup has no such handler, so 404.
        mvc.perform(get("/assets/app.js")).andExpect(status().isNotFound());
    }
}
