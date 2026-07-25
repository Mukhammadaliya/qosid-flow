# Telegram Settings UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `gateway.telegram.*` (enabled, bot-token, chat-id, message-thread-id) UI orqali boshqariladi — DB_MANAGED kalitlar, per-send o'qish, admin API (GET/PUT-409/test), SPA "Telegram" tab.

**Architecture:** AI-config (D5) patternining nusxasi: kalitlar `SettingsRegistry.DB_MANAGED`da (registr `SETTINGS`da EMAS — General ro'yxatiga tushmaydi, boot-injection skip qiladi), `TelegramNotificationService` qiymatlarni yuborish paytida `SettingsService`dan o'qiydi (DB yutadi → env/yaml fallback), yozish faqat ops stop-to-edit orqali. Spec: `docs/superpowers/specs/2026-07-22-telegram-settings-design.md`.

**Tech Stack:** Java 21 + Spring Boot 3.3 (Maven reactor: settings-core/app/ops), React + antd + vitest (frontend).

## Global Constraints

- E1-E5 pipeline'ga TEGILMAYDI: DLQ-consumer'lar, ack, idempotency, CB o'zgarmaydi (SAFETY-RULES.md).
- `NotificationService` interfeysi o'zgarmaydi.
- Sirlar hech qachon plaintext'da GET/audit/log/xato-javobga chiqmaydi.
- Ops sidecar'da PRODUCTION kod o'zgarishi YO'Q (faqat test qo'shiladi).
- Har commit `debug` branch'da, xabar oxirida `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Backend test: `mvn test -pl <module> -Dtest=<Class>` (reactor root'dan). Frontend: `cd frontend && npm test`.

---

### Task 1: settings-core — DB_MANAGED'ga 4 telegram kaliti

**Files:**
- Modify: `settings-core/src/main/java/uz/greenwhite/qosid/settings/SettingsRegistry.java` (DB_MANAGED map, ~67-qator)
- Test: `settings-core/src/test/java/uz/greenwhite/qosid/settings/SettingsRegistryTest.java`

**Interfaces:**
- Produces: `SettingsRegistry.isDbManaged("gateway.telegram.enabled"|".bot-token"|".chat-id"|".message-thread-id") == true`; `isDbManagedSecret("gateway.telegram.bot-token") == true`, qolgan 3 tasi false. Keyingi tasklar (2,3,4) shu kalit nomlariga tayanadi.

- [ ] **Step 1: Failing test yozish** — `SettingsRegistryTest.java`ga qo'shish:

```java
@Test
void telegramKeysAreDbManaged() {
    assertThat(SettingsRegistry.isDbManaged("gateway.telegram.enabled")).isTrue();
    assertThat(SettingsRegistry.isDbManaged("gateway.telegram.bot-token")).isTrue();
    assertThat(SettingsRegistry.isDbManaged("gateway.telegram.chat-id")).isTrue();
    assertThat(SettingsRegistry.isDbManaged("gateway.telegram.message-thread-id")).isTrue();
    // faqat token sir
    assertThat(SettingsRegistry.isDbManagedSecret("gateway.telegram.bot-token")).isTrue();
    assertThat(SettingsRegistry.isDbManagedSecret("gateway.telegram.enabled")).isFalse();
    assertThat(SettingsRegistry.isDbManagedSecret("gateway.telegram.chat-id")).isFalse();
    assertThat(SettingsRegistry.isDbManagedSecret("gateway.telegram.message-thread-id")).isFalse();
    // registr SETTINGS ro'yxatiga KIRMAYDI (General'da ko'rinmasin, boot-injection skip)
    assertThat(SettingsRegistry.isKnown("gateway.telegram.bot-token")).isFalse();
    assertThat(SettingsRegistry.isEditable("gateway.telegram.bot-token")).isFalse();
}
```

- [ ] **Step 2: Testni ishga tushirib FAIL ko'rish**

Run: `mvn test -pl settings-core -Dtest=SettingsRegistryTest`
Expected: FAIL — `telegramKeysAreDbManaged` (isDbManaged false qaytaradi)

- [ ] **Step 3: Minimal implementatsiya** — `SettingsRegistry.java`da `DB_MANAGED` map'iga (AI kalitlaridan keyin) qo'shish:

```java
        Map.entry("gateway.ai.model", false),
        Map.entry("gateway.telegram.enabled", false),
        Map.entry("gateway.telegram.bot-token", true),
        Map.entry("gateway.telegram.chat-id", false),
        Map.entry("gateway.telegram.message-thread-id", false)
```

(mavjud oxirgi entry `gateway.ai.model`dan keyin vergul bilan davom ettiriladi). DB_MANAGED javadoc'idagi "(source-channel outbound auth B3 + AI config D5)" izohini "(source-auth B3 + AI D5 + Telegram)" ga yangilang.

- [ ] **Step 4: Test PASS**

Run: `mvn test -pl settings-core -Dtest=SettingsRegistryTest`
Expected: PASS (barcha mavjud testlar ham)

- [ ] **Step 5: Commit**

```bash
git add settings-core/src
git commit -m "feat(settings): telegram kalitlari DB_MANAGED ro'yxatida (token secret)"
```

---

### Task 2: TelegramNotificationService — per-send o'qish + NoOp o'chirish

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/notification/TelegramNotificationService.java`
- Delete: `app/src/main/java/uz/greenwhite/gateway/notification/NoOpNotificationService.java`
- Test: `app/src/test/java/uz/greenwhite/gateway/notification/TelegramNotificationServiceTest.java` (qayta yoziladi)

**Interfaces:**
- Consumes: Task 1 kalit nomlari; `SettingsService.get(key, default)` / `getSecret(key, default)` (mavjud).
- Produces: `public TelegramNotificationService(TelegramProperties, SettingsService)` bean-konstruktor; package-private `TelegramNotificationService(TelegramProperties, SettingsService, RestTemplate)` test-konstruktor; `public Map<String, Object> sendTest()` — `{ok:true}` yoki `{ok:false, error:<redakted>}`; `isEnabled()` endi per-call. Task 3 controller `sendTest()`ga tayanadi.

- [ ] **Step 1: Failing testlarni yozish** — `TelegramNotificationServiceTest.java`ni TO'LIQ almashtirish:

```java
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
```

- [ ] **Step 2: Testni ishga tushirib FAIL ko'rish**

Run: `mvn test -pl app -Dtest=TelegramNotificationServiceTest`
Expected: COMPILE FAIL (konstruktor signature yo'q, sendTest yo'q)

- [ ] **Step 3: Implementatsiya** — `TelegramNotificationService.java`da quyidagilarni o'zgartirish. Klass sarlavhasi va konstruktorlar (eski 17-42 qatorlar o'rniga; `@ConditionalOnProperty` import'i ham olib tashlanadi, `SettingsService` import qilinadi):

```java
@Slf4j
@Service
public class TelegramNotificationService implements NotificationService {

    private final TelegramProperties properties;
    private final SettingsService settings;
    private final RestTemplate restTemplate;
    private static final String TELEGRAM_API = "https://api.telegram.org/bot%s/sendMessage";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
    public boolean isEnabled() {
        return effectiveEnabled() && !effectiveToken().isBlank();
    }
```

`sendDlqAlert` — `if (!enabled)` o'rniga `if (!isEnabled())` (xabar matni o'zgarmaydi), catch-blokdagi log endi redaksiyalanadi:

```java
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
```

`sendMessage` — effektiv qiymatlardan foydalanadi (thread-id bo'sh bo'lmasa qo'shiladi):

```java
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
```

`formatMessage`, `translateErrorSource`, `translateFailureReason`, `escapeHtml`, `truncate` — O'ZGARMAYDI. Eski `private final boolean enabled` maydoni O'ChIRILADI.

`NoOpNotificationService.java` O'ChIRILADI (bean endi doim yaratiladi — `@ConditionalOnMissingBean` hech qachon otmasdi).

- [ ] **Step 4: Ghost-code grep**

Run: `grep -ri "NoOpNotificationService" app/ ops/ settings-core/ ai-context/ CLAUDE.md README.md --include="*.java" --include="*.md" -l` (Grep tool bilan)
Expected: hech qanday natija (docs Task 7da yangilanadi — u yerda chiqsa ro'yxatga oling)

- [ ] **Step 5: Test PASS + to'liq app testlar**

Run: `mvn test -pl app -Dtest=TelegramNotificationServiceTest`
Expected: PASS (8/8)
Run: `mvn test -pl app`
Expected: PASS — e'tibor: ko'p @SpringBootTest'lar `gateway.telegram.enabled=false` property qo'yadi; bean endi doim yaratiladi, lekin DB qatori yo'q + properties.enabled=false → isEnabled()=false, hech narsa yuborilmaydi. Birorta test NoOp bean'iga tayansa — testni yangi semantikaga moslang (isEnabled()=false kutish).

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(telegram): per-send konfiguratsiya (DB yutadi, env fallback) + sendTest; NoOp o'chirildi"
```

---

### Task 3: TelegramConfigController — GET / PUT-409 / POST test

**Files:**
- Create: `app/src/main/java/uz/greenwhite/gateway/admin/TelegramConfigController.java`
- Test: `app/src/test/java/uz/greenwhite/gateway/admin/TelegramConfigControllerTest.java`

**Interfaces:**
- Consumes: Task 2 `TelegramNotificationService.sendTest()`; `SettingsService.get/getSecret/set`.
- Produces: `GET/PUT /qosid/admin/api/telegram-config`, `POST /qosid/admin/api/telegram-config/test`. GET JSON: `{enabled, botToken ("" | "***"), chatId, messageThreadId}` — Task 6 frontend shu shaklga tayanadi.

- [ ] **Step 1: Failing test** — `TelegramConfigControllerTest.java` (AiControllerTest patterni):

```java
package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
    @MockBean TelegramNotificationService telegramService;

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
```

- [ ] **Step 2: FAIL ko'rish**

Run: `mvn test -pl app -Dtest=TelegramConfigControllerTest`
Expected: FAIL — 404 (controller yo'q)

- [ ] **Step 3: Controller yozish** — `TelegramConfigController.java`:

```java
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
```

- [ ] **Step 4: Test PASS**

Run: `mvn test -pl app -Dtest=TelegramConfigControllerTest`
Expected: PASS (5/5). Shuningdek: `mvn test -pl app -Dtest=StopRequiredEnforcementTest` — PASS (agar u barcha admin PUT'larni skanersa, yangi endpoint qamrovga tushadi; fail bo'lsa testga `/qosid/admin/api/telegram-config` qo'shing).

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat(admin): telegram-config API — GET maskalangan, PUT 409 stop-required, POST /test"
```

---

### Task 4: Ops — birinchi-marta telegram-token PUT testi (faqat test)

**Files:**
- Modify: `ops/src/test/java/uz/greenwhite/ops/OpsSettingsApiTest.java` (yangi test-metod)

**Interfaces:**
- Consumes: Task 1 DB_MANAGED registratsiyasi (ops prod-kodi o'zgarmaydi — `OpsSettingsService.put` birinchi-yaratishda secret-flag'ni `isDbManagedSecret`dan oladi).

- [ ] **Step 1: Test qo'shish** — `putCreatesDbManagedSecretWithNoPreexistingRow`dan keyin:

```java
    @Test
    void putCreatesTelegramTokenAsSecretWithNoPreexistingRow() throws Exception {
        // Telegram kalitlari DB_MANAGED: birinchi saqlashda token secret=true bilan yaratiladi.
        when(status.isUp()).thenReturn(false);
        OpsSession s = login();
        mvc.perform(authedPut(s, "gateway.telegram.bot-token", "123:abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SAVED"));
        String stored = opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class, "gateway.telegram.bot-token");
        assertThat(stored).isNotEqualTo("123:abc");
        assertThat(testCipher.decrypt(stored)).isEqualTo("123:abc");
        Boolean secretFlag = opsJdbc.queryForObject(
                "SELECT secret FROM settings WHERE skey = ?", Boolean.class, "gateway.telegram.bot-token");
        assertThat(secretFlag).isTrue();
        String auditNew = opsJdbc.queryForObject(
                "SELECT new_value FROM settings_audit WHERE skey = ?", String.class, "gateway.telegram.bot-token");
        assertThat(auditNew).isEqualTo("***");
        // sir bo'lmagan telegram kaliti ham birinchi-yaratishda ochiq saqlanadi
        mvc.perform(authedPut(s, "gateway.telegram.chat-id", "-100200"))
                .andExpect(status().isOk());
        assertThat(opsJdbc.queryForObject(
                "SELECT svalue FROM settings WHERE skey = ?", String.class, "gateway.telegram.chat-id"))
                .isEqualTo("-100200");
    }
```

- [ ] **Step 2: Test PASS ko'rish** (Task 1 allaqachon merge bo'lgan — bu regression-qulf, red-fazasi yo'q)

Run: `mvn test -pl ops -Dtest=OpsSettingsApiTest`
Expected: PASS (barcha metodlar)

- [ ] **Step 3: Commit**

```bash
git add ops/src
git commit -m "test(ops): telegram bot-token birinchi-yaratilishda secret=true bilan saqlanadi"
```

---

### Task 5: Frontend — settingsKeys telegram mapping + TYPED_FORM_KEYS

**Files:**
- Modify: `frontend/src/api/settingsKeys.ts`
- Test: `frontend/src/api/settingsKeys.test.ts`

**Interfaces:**
- Produces: `TelegramConfigFormValues {enabled?, botToken?, chatId?, messageThreadId?}`, `telegramConfig(f): SettingEntry[]`, `telegramConfigFromRows(rows): TelegramConfigFormValues` — Task 6 sahifa shu funksiyalarga tayanadi. TYPED_FORM_KEYS 4 telegram kalitini o'z ichiga oladi (General yashiradi).

- [ ] **Step 1: Failing testlar** — `settingsKeys.test.ts`ga qo'shish:

```ts
describe('settingsKeys.telegramConfig', () => {
  it('TYPED_FORM_KEYS covers telegram keys (hidden from General)', () => {
    expect(TYPED_FORM_KEYS.has('gateway.telegram.enabled')).toBe(true);
    expect(TYPED_FORM_KEYS.has('gateway.telegram.bot-token')).toBe(true);
    expect(TYPED_FORM_KEYS.has('gateway.telegram.chat-id')).toBe(true);
    expect(TYPED_FORM_KEYS.has('gateway.telegram.message-thread-id')).toBe(true);
  });

  it('maps form values; blank token omitted (keep-existing)', () => {
    const entries = telegramConfig({ enabled: true, botToken: '', chatId: '-100', messageThreadId: '8520' });
    expect(entries).toContainEqual({ key: 'gateway.telegram.enabled', value: 'true', secret: false });
    expect(entries).toContainEqual({ key: 'gateway.telegram.chat-id', value: '-100', secret: false });
    expect(entries).toContainEqual({ key: 'gateway.telegram.message-thread-id', value: '8520', secret: false });
    expect(entries.find((e) => e.key === 'gateway.telegram.bot-token')).toBeUndefined();
  });

  it('emits a typed token as secret; masked token omitted', () => {
    expect(telegramConfig({ enabled: false, botToken: '123:abc' }))
      .toContainEqual({ key: 'gateway.telegram.bot-token', value: '123:abc', secret: true });
    expect(telegramConfig({ enabled: false, botToken: MASK })
      .find((e) => e.key === 'gateway.telegram.bot-token')).toBeUndefined();
  });

  it('telegramConfigFromRows maps rows, token starts blank', () => {
    const f = telegramConfigFromRows([
      { key: 'gateway.telegram.enabled', value: 'true' },
      { key: 'gateway.telegram.bot-token', value: '***' },
      { key: 'gateway.telegram.chat-id', value: '-100' },
    ]);
    expect(f).toEqual({ enabled: true, botToken: '', chatId: '-100', messageThreadId: '' });
  });
});
```

Import qatoriga `telegramConfig, telegramConfigFromRows, MASK` qo'shiladi.

- [ ] **Step 2: FAIL ko'rish**

Run: `cd frontend && npm test`
Expected: FAIL (telegramConfig eksport yo'q)

- [ ] **Step 3: Implementatsiya** — `settingsKeys.ts`: TYPED_FORM_KEYS'ga (oxiriga) qo'shish:

```ts
  'gateway.telegram.enabled',
  'gateway.telegram.bot-token',
  'gateway.telegram.chat-id',
  'gateway.telegram.message-thread-id',
```

AI-blokidan keyin yangi bo'lim:

```ts
// ------------------------------------------------------------ telegram config

export interface TelegramConfigFormValues {
  enabled?: boolean;
  botToken?: string;
  chatId?: string;
  messageThreadId?: string;
}

export function telegramConfig(f: TelegramConfigFormValues): SettingEntry[] {
  return [
    plain('gateway.telegram.enabled', String(!!f.enabled)),
    plain('gateway.telegram.chat-id', f.chatId),
    plain('gateway.telegram.message-thread-id', f.messageThreadId),
    ...secretEntry('gateway.telegram.bot-token', f.botToken),
  ];
}

export function telegramConfigFromRows(rows: Row[]): TelegramConfigFormValues {
  const m = byKey(rows);
  return {
    enabled: (m['gateway.telegram.enabled'] ?? 'false') === 'true',
    botToken: '',
    chatId: m['gateway.telegram.chat-id'] ?? '',
    messageThreadId: m['gateway.telegram.message-thread-id'] ?? '',
  };
}
```

`settingsKeys` eksport obyektiga `telegramConfig, telegramConfigFromRows` qo'shiladi. Fayl-sarlavha kommentidagi "(Source Auth / Tenant Mapping / AI config)" → "(Source Auth / Tenant Mapping / AI / Telegram config)".

- [ ] **Step 4: Test PASS**

Run: `cd frontend && npm test`
Expected: PASS (barcha mavjud testlar bilan)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api
git commit -m "feat(spa): telegram settings-key mapping + TYPED_FORM_KEYS (General'dan yashirish)"
```

---

### Task 6: Frontend — api/telegram.ts + TelegramConfigPage + tab + i18n

**Files:**
- Create: `frontend/src/api/telegram.ts`
- Create: `frontend/src/pages/settings/TelegramConfigPage.tsx`
- Modify: `frontend/src/pages/settings/SettingsPage.tsx` (yangi tab)
- Modify: `frontend/src/locales/uz.json` (telegram.* kalitlari)
- Test: `frontend/src/pages/settings/TelegramConfigPage.test.tsx`

**Interfaces:**
- Consumes: Task 3 GET JSON `{enabled, botToken, chatId, messageThreadId}` va `POST .../test` `{ok, error?}`; Task 5 `telegramConfig`/`telegramConfigFromRows`; mavjud `opsSettings.getAll()/put(key, value)`, `ModeBanner`, `useAuth`.

- [ ] **Step 1: api/telegram.ts yozish**

```ts
import { api } from './client';

const BASE = '/qosid/admin/api/telegram-config';

export interface TelegramConfig {
  enabled: string;          // '' = DB'da yo'q (env fallback amalda), 'true'/'false' = DB qiymati
  botToken: string;         // '' yoki '***'
  chatId: string;
  messageThreadId: string;
}
export interface TelegramTestResult { ok: boolean; error?: string }

export const getTelegramConfig = () => api.get<TelegramConfig>(BASE).then(r => r.data);
export const testTelegram = () => api.post<TelegramTestResult>(`${BASE}/test`).then(r => r.data);
```

- [ ] **Step 2: uz.json'ga tarjimalar** (mavjud `ai` bo'limi yonida, JSON strukturasiga mos joyga):

```json
"telegram": {
  "settingsTitle": "Telegram xabarnoma",
  "enabled": "Yoqilgan",
  "botToken": "Bot token",
  "chatId": "Chat ID",
  "messageThreadId": "Mavzu (thread) ID",
  "test": "Test yuborish",
  "testOk": "Sinov xabari yuborildi",
  "hint": "DLQ ogohlantirishlari shu bot orqali yuboriladi. DB'da qiymat bo'lmasa, env-var (TELEGRAM_*) amal qiladi."
}
```

- [ ] **Step 3: Failing sahifa-test** — `TelegramConfigPage.test.tsx` (AiConfigPage.test.tsx patterni):

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../../i18n';
import { TelegramConfigPage } from './TelegramConfigPage';
import * as telegramApi from '../../api/telegram';
import { opsSettings } from '../../api/opsSettings';
import { AuthContext } from '../../auth/AuthContext';

beforeEach(() => vi.restoreAllMocks());

const opsRows = [
  { key: 'gateway.telegram.enabled', value: 'true', secret: false, group: 'Boshqa', label: 'gateway.telegram.enabled', type: 'text', editable: true },
  { key: 'gateway.telegram.bot-token', value: '***', secret: true, group: 'Boshqa', label: 'gateway.telegram.bot-token', type: 'text', editable: true },
  { key: 'gateway.telegram.chat-id', value: '-100200', secret: false, group: 'Boshqa', label: 'gateway.telegram.chat-id', type: 'text', editable: true },
];

function renderPage(overrides: Partial<{ gatewayUp: boolean; opsUp: boolean }> = {}) {
  const value = {
    user: 'admin', loading: false, gatewayUp: true, opsUp: true, gatewayAuthExpired: false,
    login: async () => {}, logout: async () => {}, refresh: async () => {}, ...overrides,
  };
  return render(
    <AuthContext.Provider value={value as never}>
      <MemoryRouter>
        <TelegramConfigPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('TelegramConfigPage — service UP (read-only mode)', () => {
  it('loads via the gateway, disables inputs, test button enabled', async () => {
    const get = vi.spyOn(telegramApi, 'getTelegramConfig').mockResolvedValue(
      { enabled: 'true', botToken: '***', chatId: '-100200', messageThreadId: '' });
    const opsGet = vi.spyOn(opsSettings, 'getAll').mockResolvedValue([] as never);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('-100200')).toBeInTheDocument());
    expect(get).toHaveBeenCalled();
    expect(opsGet).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue('-100200')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Saqlash' })).toBeNull();
    expect(screen.getByRole('button', { name: i18n.t('telegram.test') })).toBeEnabled();
  });

  it('shows the test result', async () => {
    vi.spyOn(telegramApi, 'getTelegramConfig').mockResolvedValue(
      { enabled: '', botToken: '', chatId: '', messageThreadId: '' });
    vi.spyOn(telegramApi, 'testTelegram').mockResolvedValue({ ok: false, error: 'telegram-disabled' });
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByRole('button', { name: i18n.t('telegram.test') })).toBeEnabled());
    await userEvent.click(screen.getByRole('button', { name: i18n.t('telegram.test') }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('telegram-disabled'));
  });
});

describe('TelegramConfigPage — service DOWN + ops session (edit mode)', () => {
  it('loads from ops rows and saves without the untouched token', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const put = vi.spyOn(opsSettings, 'put').mockResolvedValue(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('-100200')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Saqlash' }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.telegram.enabled', 'true'));
    expect(put).toHaveBeenCalledWith('gateway.telegram.chat-id', '-100200');
    expect(put).toHaveBeenCalledWith('gateway.telegram.message-thread-id', '');
    expect(put).toHaveBeenCalledTimes(3); // token bo'sh -> keep-existing, PUT yo'q
  });

  it('sends the token too when a new one is typed', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const put = vi.spyOn(opsSettings, 'put').mockResolvedValue(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('-100200')).toBeInTheDocument());
    await userEvent.type(screen.getByLabelText(i18n.t('telegram.botToken')), '123:abc');
    await userEvent.click(screen.getByRole('button', { name: 'Saqlash' }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.telegram.bot-token', '123:abc'));
    expect(put).toHaveBeenCalledTimes(4);
  });

  it('disables the Test button (test is gateway-side)', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('-100200')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: i18n.t('telegram.test') })).toBeDisabled();
  });
});
```

- [ ] **Step 4: FAIL ko'rish**

Run: `cd frontend && npm test`
Expected: FAIL (TelegramConfigPage mavjud emas)

- [ ] **Step 5: TelegramConfigPage.tsx yozish** (AiConfigPage strukturasi):

```tsx
import { useEffect, useState } from 'react';
import { Card, Form, Input, Switch, Button, Space, Alert, Tooltip, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getTelegramConfig, testTelegram } from '../../api/telegram';
import { opsSettings } from '../../api/opsSettings';
import { telegramConfig as telegramEntries, telegramConfigFromRows, TelegramConfigFormValues } from '../../api/settingsKeys';
import { useAuth } from '../../auth/AuthContext';
import { ModeBanner } from './ModeBanner';

export function TelegramConfigPage() {
  const { t } = useTranslation();
  const { gatewayUp, opsUp } = useAuth();
  // Stop-to-edit: faqat gateway DOWN + ops sessiya tirik bo'lganda yoziladi (AiConfigPage bilan bir xil).
  const editMode = !gatewayUp && opsUp;

  const [form] = Form.useForm<TelegramConfigFormValues>();
  const [loaded, setLoaded] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setLoaded(false);
    const load: Promise<TelegramConfigFormValues> = editMode
      ? opsSettings.getAll().then(telegramConfigFromRows)
      : getTelegramConfig().then((c) => ({
          enabled: c.enabled === 'true',
          botToken: '', // maskalangan token inputga qo'yilmaydi (bo'sh = keep-existing)
          chatId: c.chatId,
          messageThreadId: c.messageThreadId,
        }));
    load
      .then((c) => form.setFieldsValue(c))
      .catch(() => {})
      .finally(() => setLoaded(true));
  }, [form, editMode]);

  const onSave = async () => {
    setSaving(true);
    try {
      // Bo'sh/maskalangan token mapping tomonidan tashlab ketiladi (keep-existing semantikasi).
      for (const entry of telegramEntries(form.getFieldsValue())) {
        await opsSettings.put(entry.key, entry.value);
      }
      message.success(t('settings.saved'));
    } catch (e: any) {
      const code = e?.response?.data?.error;
      message.error(code === 'service-running'
        ? t('settings.serviceRunning')
        : (e?.response?.data?.message ?? code ?? t('settings.saveFailed', 'Saqlashda xato')));
    } finally {
      setSaving(false);
    }
  };

  const onTest = async () => {
    setTestResult(null);
    const r = await testTelegram();
    setTestResult(r.ok ? `✅ ${t('telegram.testOk')}` : `❌ ${r.error}`);
  };

  return (
    <Card title={t('telegram.settingsTitle')} loading={!loaded}>
      <ModeBanner editMode={editMode} />
      <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('telegram.hint')} />
      <Form form={form} layout="vertical" style={{ maxWidth: 520 }} disabled={!editMode}>
        <Form.Item name="enabled" label={t('telegram.enabled')} valuePropName="checked">
          <Switch />
        </Form.Item>
        <Form.Item name="botToken" label={t('telegram.botToken')}>
          <Input.Password placeholder={t('common.secretPlaceholder')} />
        </Form.Item>
        <Form.Item name="chatId" label={t('telegram.chatId')}>
          <Input placeholder="-1001234567890" />
        </Form.Item>
        <Form.Item name="messageThreadId" label={t('telegram.messageThreadId')}>
          <Input placeholder="8520" />
        </Form.Item>
      </Form>
      <Space>
        {editMode && (
          <Button type="primary" onClick={onSave} loading={saving}>{t('settings.save')}</Button>
        )}
        {/* Test gateway'ning admin API'si orqali — servis ishlab turishi kerak. */}
        <Tooltip title={editMode ? t('settings.testNeedsService') : undefined}>
          <Button onClick={onTest} disabled={editMode}>{t('telegram.test')}</Button>
        </Tooltip>
      </Space>
      {testResult && <Alert style={{ marginTop: 16 }} type={testResult.startsWith('✅') ? 'success' : 'error'} message={testResult} />}
    </Card>
  );
}
```

- [ ] **Step 6: SettingsPage.tsx'ga tab** — import + items'ga:

```tsx
import { TelegramConfigPage } from './TelegramConfigPage';
// items ichida, 'ai' tab'idan keyin:
        { key: 'telegram', label: t('telegram.settingsTitle'), children: <TelegramConfigPage /> },
```

- [ ] **Step 7: Test PASS + build**

Run: `cd frontend && npm test`
Expected: PASS (barcha testlar, jumladan GeneralSettingsPage testi — telegram kalitlari General'da ko'rinmasligi TYPED_FORM_KEYS orqali avtomatik)
Run: `cd frontend && npm run build`
Expected: xatosiz build

- [ ] **Step 8: Commit**

```bash
git add frontend/src frontend/src/locales
git commit -m "feat(spa): Telegram sozlamalar tab'i — enabled/token/chat-id/thread-id + test tugmasi"
```

---

### Task 7: Docs yangilash + ghost-grep + to'liq verifikatsiya

**Files:**
- Modify: `ai-context/CONFIGURATION.md` (gateway.telegram.* bo'limi)
- Modify: `ai-context/ARCHITECTURE.md` (Admin Control-Plane API jadvali)
- Modify: `CLAUDE.md` (REST API jadvali)
- Modify: `ai-context/DOMAIN-GLOSSARY.md` / `ai-context/EXTENSION-GUIDE.md` / `README.md` — FAQAT NoOpNotificationService yoki eski telegram-tavsif tilga olingan bo'lsa

- [ ] **Step 1: CONFIGURATION.md** — `gateway.telegram.*` bo'limini yangilash: 4 kalit endi **DB-managed** (settings-DB, UI "Telegram" tab / ops stop-to-edit orqali; token AES-secret), DB qatori yo'q bo'lsa env-var (`TELEGRAM_ENABLED`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `TELEGRAM_MESSAGE_THREAD_ID`) fallback; `connect-timeout-ms`/`read-timeout-ms` env-only qoladi. DB-managed kalitlar jadvali/ro'yxati bo'lsa (source-auth/AI yonida) — telegram kalitlarini qo'shish.

- [ ] **Step 2: ARCHITECTURE.md** — Admin Control-Plane API bo'limiga `GET/PUT /qosid/admin/api/telegram-config` + `POST .../test` qatori (PUT → 409 stop-required, D-model); notification qatlami tavsifida NoOpNotificationService o'chirilgani va per-send o'qish aks ettirilishi.

- [ ] **Step 3: CLAUDE.md** — REST API jadvaliga qator:

```markdown
| GET/PUT/POST    | `/qosid/admin/api/telegram-config[/test]`        | Admin (session auth): DLQ Telegram-alert konfiguratsiyasi — DB-managed (token AES-secret, maskalangan), env-var fallback. **PUT → `409 stop-required`** (D stop-to-edit). `POST .../test` saqlangan config bilan sinov xabari, never-500. |
```

- [ ] **Step 4: Ghost-grep**

Run (Grep tool): `NoOpNotificationService` bo'yicha butun repo — natija bo'sh bo'lishi kerak; `gateway.telegram` bo'yicha `ai-context/` — hamma tilga olishlar yangi modelga mos bo'lishi kerak.

- [ ] **Step 5: To'liq verifikatsiya**

Run: `mvn clean package -DskipTests` → BUILD SUCCESS
Run: `mvn test` (reactor: settings-core + app + ops) → barcha testlar PASS
Run: `cd frontend && npm test` → PASS

- [ ] **Step 6: Commit**

```bash
git add ai-context CLAUDE.md README.md
git commit -m "docs: telegram-config admin API + DB-managed telegram kalitlari hujjatlashtirildi"
```

---

## Self-Review natijasi

- **Spec qamrovi:** registry (T1), service refactor + NoOp o'chirish + redaksiya (T2), admin API GET/PUT/test (T3), ops birinchi-yaratish (T4), SPA mapping/General-yashirish (T5), tab+sahifa+i18n (T6), docs+ghost-grep (T7). Spec'dagi barcha bo'limlar qoplangan.
- **Placeholder yo'q:** har kod-qadamda to'liq kod berilgan.
- **Tip-izchillik:** `sendTest(): Map<String,Object>` (T2) ↔ controller `telegram.sendTest()` (T3); GET JSON maydonlari (T3) ↔ `TelegramConfig` interfeysi (T6); kalit nomlari T1↔T2↔T4↔T5 bir xil.
