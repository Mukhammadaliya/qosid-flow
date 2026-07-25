package uz.greenwhite.gateway.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import uz.greenwhite.gateway.config.TelegramProperties;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.settings.SettingsService;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class TelegramNotificationService implements NotificationService {

    private final TelegramProperties properties;
    private final SettingsService settings;
    private final RestTemplate restTemplate;
    private static final String TELEGRAM_API = "https://api.telegram.org/bot%s/sendMessage";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    public TelegramNotificationService(TelegramProperties properties, SettingsService settings) {
        this(properties, settings, buildRestTemplate(properties));
    }

    /** Testlar uchun: RestTemplate tashqaridan beriladi. */
    TelegramNotificationService(TelegramProperties properties, SettingsService settings, RestTemplate restTemplate) {
        this.properties = properties;
        this.settings = settings;
        this.restTemplate = restTemplate;
        log.info("Telegram notification service initialized (per-send config: settings-DB -> env fallback)");
    }

    private static RestTemplate buildRestTemplate(TelegramProperties p) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(p.getConnectTimeoutMs());
        factory.setReadTimeout(p.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    // --- effektiv konfiguratsiya: DB qatori yutadi, yo'q bo'lsa env/yaml (TelegramProperties) ---

    private boolean effectiveEnabled() {
        return Boolean.parseBoolean(
                settings.get("gateway.telegram.enabled", String.valueOf(properties.isEnabled())));
    }

    private String effectiveToken() {
        String fallback = properties.getBotToken() == null ? "" : properties.getBotToken();
        return settings.getSecret("gateway.telegram.bot-token", fallback);
    }

    private String effectiveChatId() {
        String fallback = properties.getChatId() == null ? "" : String.valueOf(properties.getChatId());
        return settings.get("gateway.telegram.chat-id", fallback);
    }

    private String effectiveThreadId() {
        String fallback = properties.getMessageThreadId() == null ? "" : String.valueOf(properties.getMessageThreadId());
        return settings.get("gateway.telegram.message-thread-id", fallback);
    }

    @Override
    public void sendDlqAlert(DlqMessage message) {
        if (!isEnabled()) {
            log.warn("Telegram disabled — DLQ alert not sent: {}", message.getCompositeId());
            return;
        }
        try {
            String text = formatMessage(message);
            sendMessage(text);
            log.info("DLQ alert sent to Telegram: {}", message.getCompositeId());
        } catch (Exception e) {
            log.error("Failed to send DLQ alert to Telegram: {} - {}",
                    message.getCompositeId(), redact(e.getMessage()));
        }
    }

    /** Admin test tugmasi: chegaralangan sinov xabari, natija strukturali, token redaksiyada. */
    public Map<String, Object> sendTest() {
        if (!isEnabled()) {
            return Map.of("ok", false, "error", "telegram-disabled");
        }
        try {
            sendMessage("✅ Qosid Flow: Telegram sozlamalari sinov xabari");
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", redact(e.getMessage()));
        }
    }

    private String redact(String msg) {
        if (msg == null) return "unknown error";
        String token = effectiveToken();
        return token.isBlank() ? msg : msg.replace(token, "***");
    }

    @Override
    public boolean isEnabled() {
        return effectiveEnabled() && !effectiveToken().isBlank();
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
        String url = String.format(TELEGRAM_API, effectiveToken());

        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", effectiveChatId());
        body.put("text", text);
        body.put("parse_mode", "HTML");

        String threadId = effectiveThreadId();
        if (!threadId.isBlank()) {
            body.put("message_thread_id", Integer.parseInt(threadId));
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