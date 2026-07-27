package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import uz.greenwhite.gateway.notification.TelegramNotificationService;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stop-to-edit: PUT 409, GET maskalangan, POST /test strukturali JSON (never-500).
 */
@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:telegram_config_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false",
        "gateway.settings.encryption-key=test-key-0123456789",
        "gateway.rocksdb.path=./target/test-data/rocksdb-telegram-config"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TelegramConfigControllerTest {

    @Autowired MockMvc mvc;
    @Autowired SettingsService settingsService;
    @MockitoBean TelegramNotificationService telegramService;

    @Test
    @Order(1)
    @WithMockUser(roles = "ADMIN")
    void configTokenEmptyWhenNotStored() throws Exception {
        mvc.perform(get("/qosid/admin/api/telegram-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botToken").value(""));
    }

    @Test
    @Order(2)
    @WithMockUser(roles = "ADMIN")
    void configMasksStoredToken() throws Exception {
        settingsService.set("gateway.telegram.enabled", "true", false, "test");
        settingsService.set("gateway.telegram.bot-token", "123:abc", true, "test");
        settingsService.set("gateway.telegram.chat-id", "-100200", false, "test");
        settingsService.set("gateway.telegram.message-thread-id", "8520", false, "test");

        mvc.perform(get("/qosid/admin/api/telegram-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value("true"))
                .andExpect(jsonPath("$.botToken").value("***"))
                .andExpect(jsonPath("$.chatId").value("-100200"))
                .andExpect(jsonPath("$.messageThreadId").value("8520"));
        assertThat(settingsService.getSecret("gateway.telegram.bot-token", "")).isEqualTo("123:abc");
    }

    @Test
    @Order(3)
    @WithMockUser(roles = "ADMIN")
    void putConfigReturns409StopRequiredAndDoesNotWrite() throws Exception {
        settingsService.set("gateway.telegram.bot-token", "tok-first", true, "test");

        mvc.perform(put("/qosid/admin/api/telegram-config").with(csrf())
                        .contentType("application/json")
                        .content("{\"enabled\":\"true\",\"botToken\":\"tok-evil\",\"chatId\":\"1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("stop-required"));

        assertThat(settingsService.getSecret("gateway.telegram.bot-token", "")).isEqualTo("tok-first");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void testEndpointReturnsServiceResult() throws Exception {
        when(telegramService.sendTest()).thenReturn(Map.of("ok", false, "error", "telegram-disabled"));
        mvc.perform(post("/qosid/admin/api/telegram-config/test").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("telegram-disabled"));
    }

    @Test
    void configUnauthenticatedIs401() throws Exception {
        mvc.perform(get("/qosid/admin/api/telegram-config")).andExpect(status().isUnauthorized());
    }
}
