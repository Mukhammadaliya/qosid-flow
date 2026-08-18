package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import uz.greenwhite.qosid.observability.stats.StatsQueryService;
import uz.greenwhite.qosid.observability.stats.StatsResult;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:stats_controller_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        "gateway.rocksdb.path=./target/test-data/rocksdb-stats-controller"
})
@AutoConfigureMockMvc
class StatsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean StatsQueryService statsQueryService;

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/qosid/admin/api/stats?preset=kpi")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void returnsKpisForAuthenticatedAdmin() throws Exception {
        when(statsQueryService.query(eq("kpi"), any(), any(), any()))
                .thenReturn(new StatsResult(List.of(), Map.of("throughput", 7.0), null));
        mvc.perform(get("/qosid/admin/api/stats?preset=kpi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.throughput").value(7.0))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}
