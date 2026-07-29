package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.notification.TelegramNotificationService;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.HashMap;
import java.util.Map;

/**
 * Telegram DLQ-alert konfiguratsiyasi, /qosid/admin/api/** session-auth zanjiri ostida.
 * Stop-to-edit (D): yozish ishlab turgan servisda rad etiladi (409 stop-required) —
 * tahrirlash gateway to'xtatilganda ops sidecar orqali. GET maskalangan, /test never-500.
 */
@RestController
@RequestMapping("/qosid/admin/api/telegram-config")
@RequiredArgsConstructor
public class TelegramConfigController {

    private static final String MASK = "***";
    private final SettingsService settings;
    private final TelegramNotificationService telegram;

    @GetMapping
    public ResponseEntity<Map<String, String>> getConfig() {
        Map<String, String> out = new HashMap<>();
        out.put("enabled", settings.get("gateway.telegram.enabled", ""));
        // shifrlangan qiymatni ochmasdan bor-yo'qligini tekshirish (AiController patterni)
        out.put("botToken", settings.get("gateway.telegram.bot-token", "").isBlank() ? "" : MASK);
        out.put("chatId", settings.get("gateway.telegram.chat-id", ""));
        out.put("messageThreadId", settings.get("gateway.telegram.message-thread-id", ""));
        return ResponseEntity.ok(out);
    }

    /** Stop-to-edit: barcha settings-yozishlar ishlab turgan servisda rad etiladi. */
    @PutMapping
    public ResponseEntity<Map<String, String>> putConfig() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "stop-required"));
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        return ResponseEntity.ok(telegram.sendTest());
    }
}
