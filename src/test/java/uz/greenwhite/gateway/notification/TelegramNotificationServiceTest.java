package uz.greenwhite.gateway.notification;

import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.config.TelegramProperties;
import uz.greenwhite.gateway.model.kafka.DlqMessage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TelegramNotificationServiceTest {

    @Test
    void blankTokenDisablesServiceInsteadOfSendingDoomedRequests() {
        TelegramProperties props = new TelegramProperties();
        props.setBotToken("");   // if TelegramProperties is not @Data with setters, construct however the class allows
        props.setChatId(0L);

        TelegramNotificationService service = new TelegramNotificationService(props);

        assertFalse(service.isEnabled());
        assertDoesNotThrow(() -> service.sendDlqAlert(
                DlqMessage.builder().tenantId(1L).requestId(2L).failureReason("x").build()));
    }
}
