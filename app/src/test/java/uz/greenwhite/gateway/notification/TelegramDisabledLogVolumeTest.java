package uz.greenwhite.gateway.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;
import uz.greenwhite.gateway.config.TelegramProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * With Telegram switched off, every DLQ record used to log its own WARN. That is one line per
 * failed request — so the louder the incident, the louder the log, which is backwards: the demo
 * box wrote 152 of them in under an hour of ordinary traffic, and a real burst would write one
 * per DLQ'd request while adding nothing after the first (the alert channel is off, and the DLQ
 * record itself is already logged by `DlqAuditLogger`).
 *
 * <p>Telegram being disabled is a configuration state, not a per-record event: say it once.
 */
class TelegramDisabledLogVolumeTest {

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    private Logger serviceLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(TelegramNotificationService.class);
        originalLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);   // pinned: the app runs this package at DEBUG
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        serviceLogger.detachAppender(appender);
        serviceLogger.setLevel(originalLevel);
        timer.shutdownNow();
    }

    private TelegramNotificationService disabledService() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.get(eq("gateway.telegram.enabled"), anyString())).thenReturn("false");
        GatewayMetrics metrics = mock(GatewayMetrics.class);
        when(metrics.getAlertCoalesced()).thenReturn(mock(Counter.class));
        TelegramProperties props = new TelegramProperties();
        props.setEnabled(false);
        return new TelegramNotificationService(props, settings, metrics, timer, mock(RestTemplate.class));
    }

    private static DlqMessage dlq(long id) {
        return DlqMessage.builder()
                .tenantId(0L)
                .requestId(id)
                .failureReason("HTTP 404: Not Found")
                .build();
    }

    @Test
    void saysTelegramIsOffOnceRatherThanOncePerDlqRecord() {
        TelegramNotificationService service = disabledService();

        for (int i = 0; i < 50; i++) {
            service.sendDlqAlert(dlq(i));
        }

        assertThat(appender.list).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void theOneLineStillExplainsWhyNoAlertArrived() {
        TelegramNotificationService service = disabledService();

        service.sendDlqAlert(dlq(1));

        // Not get(0): the constructor logs its own INFO "initialized" line first.
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage().toLowerCase())
                    .contains("telegram")
                    .contains("disabled");
        });
    }
}
