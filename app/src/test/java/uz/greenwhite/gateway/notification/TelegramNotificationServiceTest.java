package uz.greenwhite.gateway.notification;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import uz.greenwhite.gateway.config.TelegramProperties;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-send konfiguratsiya: DB qatori yutadi, yo'q bo'lsa TelegramProperties
 * (env/yaml) fallback. Token sir — getSecret orqali.
 */
class TelegramNotificationServiceTest {

    private SettingsService settingsWith(String enabled, String token, String chatId, String threadId) {
        SettingsService s = mock(SettingsService.class);
        when(s.get(eq("gateway.telegram.enabled"), anyString()))
                .thenAnswer(inv -> enabled != null ? enabled : inv.getArgument(1));
        when(s.getSecret(eq("gateway.telegram.bot-token"), anyString()))
                .thenAnswer(inv -> token != null ? token : inv.getArgument(1));
        when(s.get(eq("gateway.telegram.chat-id"), anyString()))
                .thenAnswer(inv -> chatId != null ? chatId : inv.getArgument(1));
        when(s.get(eq("gateway.telegram.message-thread-id"), anyString()))
                .thenAnswer(inv -> threadId != null ? threadId : inv.getArgument(1));
        return s;
    }

    private TelegramProperties props(boolean enabled, String token, Long chatId) {
        TelegramProperties p = new TelegramProperties();
        p.setEnabled(enabled);
        p.setBotToken(token);
        p.setChatId(chatId);
        return p;
    }

    @Test
    void dbRowWinsOverEnvFallback() {
        // env: disabled/bo'sh; DB: enabled + token bor -> DB yutadi
        SettingsService s = settingsWith("true", "db-token", "77", null);
        TelegramNotificationService svc =
                new TelegramNotificationService(props(false, "", 0L), s, mock(RestTemplate.class));
        assertTrue(svc.isEnabled());
    }

    @Test
    void envFallbackWhenNoDbRows() {
        SettingsService s = settingsWith(null, null, null, null);
        TelegramNotificationService svc =
                new TelegramNotificationService(props(true, "env-token", 5L), s, mock(RestTemplate.class));
        assertTrue(svc.isEnabled());
    }

    @Test
    void disabledWhenNoTokenAnywhere() {
        SettingsService s = settingsWith(null, null, null, null);
        TelegramNotificationService svc =
                new TelegramNotificationService(props(true, "", 0L), s, mock(RestTemplate.class));
        assertFalse(svc.isEnabled());
        assertDoesNotThrow(() -> svc.sendDlqAlert(
                DlqMessage.builder().tenantId(1L).requestId(2L).failureReason("x").build()));
    }

    @Test
    void dbEnabledFalseOverridesEnvTrue() {
        SettingsService s = settingsWith("false", null, null, null);
        TelegramNotificationService svc =
                new TelegramNotificationService(props(true, "env-token", 5L), s, mock(RestTemplate.class));
        assertFalse(svc.isEnabled());
    }

    @Test
    void sendDlqAlertPostsWhenEnabled() {
        SettingsService s = settingsWith("true", "tok123", "42", null);
        RestTemplate rt = mock(RestTemplate.class);
        TelegramNotificationService svc =
                new TelegramNotificationService(props(false, "", 0L), s, rt);
        svc.sendDlqAlert(DlqMessage.builder().tenantId(1L).requestId(2L).failureReason("boom").build());
        verify(rt).postForEntity(eq("https://api.telegram.org/bottok123/sendMessage"), any(), eq(String.class));
    }

    @Test
    void sendTestReturnsOkOnSuccess() {
        SettingsService s = settingsWith("true", "tok123", "42", null);
        RestTemplate rt = mock(RestTemplate.class);
        TelegramNotificationService svc =
                new TelegramNotificationService(props(false, "", 0L), s, rt);
        Map<String, Object> r = svc.sendTest();
        assertThat(r.get("ok")).isEqualTo(true);
        verify(rt).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void sendTestDisabledWithoutSending() {
        SettingsService s = settingsWith(null, null, null, null);
        RestTemplate rt = mock(RestTemplate.class);
        TelegramNotificationService svc =
                new TelegramNotificationService(props(true, "", 0L), s, rt);
        Map<String, Object> r = svc.sendTest();
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(r.get("error")).isEqualTo("telegram-disabled");
        verify(rt, never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void sendTestRedactsTokenFromErrors() {
        SettingsService s = settingsWith("true", "tok123", "42", null);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("I/O error on POST https://api.telegram.org/bottok123/sendMessage"));
        TelegramNotificationService svc =
                new TelegramNotificationService(props(false, "", 0L), s, rt);
        Map<String, Object> r = svc.sendTest();
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat((String) r.get("error")).doesNotContain("tok123").contains("***");
    }
}
