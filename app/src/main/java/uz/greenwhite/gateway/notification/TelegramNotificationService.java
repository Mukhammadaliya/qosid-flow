package uz.greenwhite.gateway.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import uz.greenwhite.gateway.config.TelegramProperties;
import uz.greenwhite.gateway.model.kafka.DlqMessage;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(name = "gateway.telegram.enabled", havingValue = "true")
public class TelegramNotificationService implements NotificationService {

    private final TelegramProperties properties;
    private final RestTemplate restTemplate;
    private final boolean enabled;
    private static final String TELEGRAM_API = "https://api.telegram.org/bot%s/sendMessage";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public TelegramNotificationService(TelegramProperties properties) {
        this.properties = properties;
        this.enabled = properties.getBotToken() != null && !properties.getBotToken().isBlank();

        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);

        if (enabled) {
            log.info("Telegram notification ENABLED: chatId={}", properties.getChatId());
        } else {
            log.warn("Telegram notification DISABLED: bot token is blank (set TELEGRAM_BOT_TOKEN)");
        }
    }

    @Override
    public void sendDlqAlert(DlqMessage message) {
        if (!enabled) {
            log.warn("Telegram disabled — DLQ alert not sent: {}", message.getCompositeId());
            return;
        }

        try {
            String text = formatMessage(message);
            sendMessage(text);
            log.info("DLQ alert sent to Telegram: {}", message.getCompositeId());
        } catch (Exception e) {
            log.error("Failed to send DLQ alert to Telegram: {} - {}",
                    message.getCompositeId(), e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    private String formatMessage(DlqMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔴 <b>ОПОВЕЩЕНИЕ DLQ</b>\n\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📌 <b>Компания:</b> ").append(msg.getTenantId());
        sb.append(" | <b>Запрос:</b> ").append(msg.getRequestId()).append("\n");

        if (msg.getUrl() != null) {
            sb.append("🔗 <b>URL:</b> <code>").append(escapeHtml(msg.getUrl())).append("</code>\n");
        }

        sb.append("❌ <b>Причина:</b> [").append(translateErrorSource(msg.getErrorSource())).append("] ");
        sb.append(escapeHtml(truncate(translateFailureReason(msg.getFailureReason()), 200))).append("\n");

        if (msg.getHttpStatus() > 0) {
            sb.append("📡 <b>HTTP Статус:</b> ").append(msg.getHttpStatus()).append("\n");
        }

        sb.append("🔄 <b>Попыток:</b> ").append(msg.getAttemptCount()).append("\n");

        if (msg.getFailedAt() != null) {
            sb.append("🕐 <b>Время:</b> ").append(msg.getFailedAt().format(FORMATTER)).append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━━");
        return sb.toString();
    }

    /**
     * Translates errorSource code to Russian label
     */
    private String translateErrorSource(String errorSource) {
        if (errorSource == null) return "НЕИЗВЕСТНО";
        return switch (errorSource) {
            case "HTTP"   -> "Ошибка внешнего API";
            case "SYSTEM" -> "Внутренняя системная ошибка";
            case "KAFKA"  -> "Ошибка Kafka";
            default       -> errorSource;
        };
    }

    /**
     * Translates known failureReason patterns to Russian.
     * Unknown/external messages are passed through as-is.
     */
    private String translateFailureReason(String reason) {
        if (reason == null) return "Неизвестная ошибка";

        // Validation failed
        if (reason.startsWith("Validation failed:")) {
            return reason.replace("Validation failed:", "Ошибка валидации:");
        }

        // Kafka publish failed (broker down, timeout, or message too large)
        if (reason.startsWith("Failed to publish response to Kafka:")) {
            return reason.replace("Failed to publish response to Kafka:",
                    "Не удалось опубликовать ответ в Kafka:");
        }

        // Response too large for Kafka
        if (reason.startsWith("Response too large for Kafka:")) {
            return reason.replace("Response too large for Kafka:", "Ответ слишком большой для Kafka:");
        }

        // Failed to send to Kafka after N attempts
        if (reason.startsWith("Failed to send to Kafka after")) {
            return reason.replaceAll(
                    "Failed to send to Kafka after (\\d+) attempts",
                    "Не удалось отправить в Kafka после $1 попыток"
            );
        }

        // Timeout-related messages
        if (reason.toLowerCase().contains("timeout") || reason.toLowerCase().contains("timed out")) {
            return "Превышено время ожидания: " + reason;
        }

        // Interrupted during retry
        if (reason.startsWith("Interrupted during retry wait")) {
            return "Прервано во время ожидания повтора";
        }

        // External API errors or unknown — keep as-is
        return reason;
    }

    private void sendMessage(String text) {
        String url = String.format(TELEGRAM_API, properties.getBotToken());

        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", properties.getChatId());
        body.put("text", text);
        body.put("parse_mode", "HTML");

        if (properties.getMessageThreadId() != null) {
            body.put("message_thread_id", properties.getMessageThreadId());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    }

    private String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String truncate(String text, int maxLength) {
        if (text == null)
            return "";
        if (text.length() <= maxLength)
            return text;
        return text.substring(0, maxLength) + "...";
    }
}