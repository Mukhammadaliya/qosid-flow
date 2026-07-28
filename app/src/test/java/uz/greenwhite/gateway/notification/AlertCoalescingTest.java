package uz.greenwhite.gateway.notification;

import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import uz.greenwhite.gateway.config.TelegramProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.settings.SettingsService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bitta hodisa — bitta ogohlantirish.
 *
 * <p>2026-07-27 14:27 da bitta CB oynasi 212 ta DLQ yozuvi yaratdi, ularning har biri alohida
 * Telegram chaqiruviga aylandi: 14 tasi ketdi, <b>298 tasi</b> {@code 429 retry after N} bilan
 * qaytdi. Kanal aynan kerak bo'lgan paytda ishlamay qoldi. Shu sababli bir xil
 * (sabab-sinfi, host) juftligidagi ogohlantirishlar bitta oynada birlashtiriladi va 429
 * javobidagi {@code retry_after} hurmat qilinadi — darhol qayta urinish aynan o'sha 298 ta
 * muvaffaqiyatsizlikni yaratgan edi.
 */
class AlertCoalescingTest {

    private final RestTemplate rest = mock(RestTemplate.class);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    private SettingsService settings() {
        SettingsService s = mock(SettingsService.class);
        when(s.get(eq("gateway.telegram.enabled"), anyString())).thenReturn("true");
        when(s.getSecret(eq("gateway.telegram.bot-token"), anyString())).thenReturn("tok123");
        when(s.get(eq("gateway.telegram.chat-id"), anyString())).thenReturn("42");
        when(s.get(eq("gateway.telegram.message-thread-id"), anyString())).thenReturn("");
        return s;
    }

    private GatewayMetrics metrics() {
        GatewayMetrics m = mock(GatewayMetrics.class);
        Counter c = mock(Counter.class);
        when(m.getAlertCoalesced()).thenReturn(c);
        return m;
    }

    private TelegramNotificationService service(long windowMs) {
        TelegramProperties p = new TelegramProperties();
        p.setEnabled(false);            // DB qatori yutadi — env fallback ishlatilmaydi
        p.setBotToken("");
        p.setCoalesceWindowMs(windowMs);
        return new TelegramNotificationService(p, settings(), metrics(), timer, rest);
    }

    private DlqMessage dlq(long requestId, String reason, String host) {
        return DlqMessage.builder()
                .tenantId(100L).requestId(requestId)
                .failureReason(reason)
                .errorSource("SYSTEM")
                .url("POST https://" + host + "/b/trade/api/sm24:order_export")
                .build();
    }

    /** Yuborilgan xabarlar matni — postForEntity'ning HttpEntity tanasidagi "text". */
    @SuppressWarnings("unchecked")
    private List<String> sentTexts() {
        var captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest, org.mockito.Mockito.atLeastOnce())
                .postForEntity(anyString(), captor.capture(), eq(String.class));
        return captor.getAllValues().stream()
                .map(e -> String.valueOf(((Map<String, Object>) e.getBody()).get("text")))
                .toList();
    }

    private HttpClientErrorException tooManyRequests(int retryAfter) {
        String body = "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\","
                + "\"parameters\":{\"retry_after\":" + retryAfter + "}}";
        return HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @org.junit.jupiter.api.AfterEach
    void stopTimer() {
        timer.shutdownNow();
    }

    @Test
    void oneIncidentProducesOneImmediateAlertAndOneSummary() {
        TelegramNotificationService svc = service(600);

        for (int i = 0; i < 200; i++) {
            svc.sendDlqAlert(dlq(15073141L + i,
                    "Circuit breaker still open after " + (40000 + i) + "ms", "smartup.online"));
        }

        // Birinchisi darhol ketadi, qolgan 199 tasi kutadi.
        verify(rest, times(1)).postForEntity(anyString(), any(), eq(String.class));

        // Oyna yopilgach — aynan bitta jamlovchi xabar, jami 2 ta HTTP chaqiruv.
        verify(rest, timeout(3000).times(2)).postForEntity(anyString(), any(), eq(String.class));
        assertThat(sentTexts().get(1))
                .as("jamlovchi xabar nechta yozuv bosilganini va guruhni aytishi kerak")
                .contains("199").contains("smartup.online");
    }

    @Test
    void distinctReasonHostPairsAreNotMerged() {
        TelegramNotificationService svc = service(5000);

        svc.sendDlqAlert(dlq(1L, "Circuit breaker still open after 40000ms", "smartup.online"));
        svc.sendDlqAlert(dlq(2L, "Circuit breaker still open after 40000ms", "regos.uz"));
        svc.sendDlqAlert(dlq(3L, "Read timed out", "smartup.online"));

        verify(rest, times(3)).postForEntity(anyString(), any(), eq(String.class));
    }

    /** Raqamlari bilan farq qiluvchi bir xil sabab — bitta guruh. */
    @Test
    void reasonsDifferingOnlyInNumbersShareOneGroup() {
        TelegramNotificationService svc = service(5000);

        svc.sendDlqAlert(dlq(1L, "Failed to send to Kafka after 3 attempts", "smartup.online"));
        svc.sendDlqAlert(dlq(2L, "Failed to send to Kafka after 7 attempts", "smartup.online"));

        verify(rest, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void rateLimitSuppressesFurtherSendsInsteadOfRetryingImmediately() {
        when(rest.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(tooManyRequests(30));
        TelegramNotificationService svc = service(5000);

        svc.sendDlqAlert(dlq(1L, "Circuit breaker still open after 40000ms", "smartup.online"));
        svc.sendDlqAlert(dlq(2L, "Read timed out", "regos.uz"));
        svc.sendDlqAlert(dlq(3L, "Connection refused", "example.com"));

        // 429 dan keyin darhol qayta urinish aynan o'sha 298 ta muvaffaqiyatsizlikni yaratgan edi.
        verify(rest, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }

    /** Rate-limit tugagach, keyingi xabar nechta ogohlantirish yutilganini aytadi. */
    @Test
    void suppressedCountIsReportedInTheNextMessage() throws Exception {
        when(rest.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(tooManyRequests(0))          // suppress = 0 + 1s zaxira
                .thenReturn(null);
        TelegramNotificationService svc = service(5000);

        svc.sendDlqAlert(dlq(1L, "Circuit breaker still open after 40000ms", "smartup.online"));
        svc.sendDlqAlert(dlq(2L, "Read timed out", "regos.uz"));

        Thread.sleep(1200);
        svc.sendDlqAlert(dlq(3L, "Connection refused", "example.com"));

        List<String> texts = sentTexts();
        assertThat(texts).hasSize(2);
        assertThat(texts.get(1))
                .as("yutilgan ogohlantirishlar soni keyingi xabarda aytilishi kerak")
                .contains("2");
    }

    /**
     * Guruhlash sababdagi raqamlarni normallashtiradi — lekin raqamsiz o'zgaruvchan qism
     * (upstream javobi, UUID) bo'lsa har xabar o'z guruhini ochib, oqim qaytib kelardi.
     * Ochiq guruhlar soni chegaralangan: chegaradan oshgani bitta jamlanmaga tushadi.
     */
    @Test
    void aFloodOfDistinctReasonsCannotReopenTheAlertFlood() {
        TelegramNotificationService svc = service(5000);

        for (int i = 0; i < 200; i++) {
            svc.sendDlqAlert(dlq(i, "Upstream rejected order ref-" + (char) ('a' + i % 26)
                    + "-" + java.util.UUID.nameUUIDFromBytes(String.valueOf(i).getBytes()),
                    "smartup.online"));
        }

        // 20 ta guruh + 1 ta "turli sabablar" guruhi = ko'pi bilan 21 ta darhol ketgan xabar.
        verify(rest, org.mockito.Mockito.atMost(21))
                .postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void coalescedAlertsAreCounted() {
        GatewayMetrics m = metrics();
        TelegramProperties p = new TelegramProperties();
        p.setEnabled(false);
        p.setBotToken("");
        p.setCoalesceWindowMs(5000);
        TelegramNotificationService svc =
                new TelegramNotificationService(p, settings(), m, timer, rest);

        svc.sendDlqAlert(dlq(1L, "Circuit breaker still open after 40000ms", "smartup.online"));
        svc.sendDlqAlert(dlq(2L, "Circuit breaker still open after 41000ms", "smartup.online"));

        verify(m.getAlertCoalesced()).increment(anyDouble());
    }
}
