package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import uz.greenwhite.gateway.logs.LogEntry;
import uz.greenwhite.gateway.logs.LogQueryResult;
import uz.greenwhite.gateway.logs.LogQueryService;

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
        "gateway.settings.db-path=mem:log_query_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        "gateway.rocksdb.path=./target/test-data/rocksdb-log-query"
})
@AutoConfigureMockMvc
class LogQueryControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean LogQueryService logQueryService;

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/qosid/admin/api/logs?source=jar")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void returnsEntriesForAuthenticatedAdmin() throws Exception {
        when(logQueryService.query(eq("dlq"), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LogQueryResult(
                        List.of(new LogEntry("2026-07-12T10:00:00Z", "ERROR", "boom", Map.of(), null)), null));
        mvc.perform(get("/qosid/admin/api/logs?source=dlq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].line").value("boom"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void labelValuesEndpoint() throws Exception {
        when(logQueryService.labelValues("container")).thenReturn(List.of("redis", "redpanda"));
        mvc.perform(get("/qosid/admin/api/logs/labels?name=container"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values[0]").value("redis"));
    }
}
