# Telegram sozlamalarini UI orqali boshqarish — dizayn

**Sana:** 2026-07-22
**Holat:** Tasdiqlangan (user, 2026-07-22)
**Skop:** gateway'ning o'z DLQ-alert Telegram kanali (`gateway.telegram.*`). Alertmanager'dagi Telegram (infra, `infrastructure/alertmanager/alertmanager.yml`) ataylab skopdan TAShQARIDA.

## Muammo

`gateway.telegram.*` (bot-token, chat-id, message-thread-id, enabled) faqat env-var orqali sozlanadi (`application.yml` → `TELEGRAM_BOT_TOKEN` va h.k.), UI'da boshqarib bo'lmaydi. Bot-token — sir; 2026-07-10 bot-hijack (token repoda ochiq qolgan) tajribasidan keyin uni AES-shifrlangan settings-DB'da saqlash env-fayldan xavfsizroq. User'ning settings-joylashuv prinsipi (2026-07-18) bo'yicha ham: telegram alerting boot-kritik emas — request-lifecycle taraf, ya'ni UI/settings-DB'da bo'lishi kerak.

## Texnik cheklov (dizaynni belgilagan fakt)

`SettingsEnvironmentPostProcessor` sirlarni boot'da Environment'ga ATAYLAB yuklamaydi
(`app/.../settings/SettingsEnvironmentPostProcessor.java:61`) — sirlar faqat runtime'da
`SettingsService.getSecret()` orqali o'qiladi. Shu sabab "registrga secret=true bilan qo'shish"
varianti ishlamaydi: boot-bound `TelegramProperties`ga token yetib bormaydi. Yagona to'g'ri yo'l —
AI-config (D5) patterni: DB_MANAGED kalitlar + runtime o'qish + dedicated typed page.

## Qaror (A yondashuv — AI-config patternining nusxasi)

### 1. settings-core — `SettingsRegistry.DB_MANAGED`ga 4 kalit

| Kalit | secret |
|---|---|
| `gateway.telegram.enabled` | false |
| `gateway.telegram.bot-token` | **true** |
| `gateway.telegram.chat-id` | false |
| `gateway.telegram.message-thread-id` | false |

Registr `SETTINGS` ro'yxatiga KIRMAYDI → `GET /settings/registry`da ko'rinmaydi, General
ro'yxatiga tushmaydi, boot-injection skip qiladi. Ops sidecar'da kod o'zgarishi YO'Q —
`OpsSettingsService.put()` birinchi-marta-yaratishda secret-flag'ni `isDbManagedSecret()`dan
avtomatik oladi (mavjud xatti-harakat).

### 2. Gateway — `TelegramNotificationService` refactor (per-send o'qish)

- `@ConditionalOnProperty` OLIB TAShLANADI — bean doim yaratiladi.
- Har yuborishda effektiv qiymatlar hisoblanadi, fallback-zanjir:
  - `enabled`: DB `gateway.telegram.enabled` bor bo'lsa → DB; yo'q bo'lsa → `TelegramProperties.enabled` (env `TELEGRAM_ENABLED`)
  - `bot-token`: `settings.getSecret("gateway.telegram.bot-token", properties.getBotToken())`
  - `chat-id`, `message-thread-id`: DB (non-secret get) → yo'q bo'lsa properties
  - **DB qatori bor bo'lsa DB yutadi** — mavjud "DB/UI is the source of truth" semantikasi.
    DB'da qator yo'q ekan, prod env-varlari ishlayveradi — deploy buzilmaydi.
- `isEnabled()` = effektiv enabled && effektiv token bo'sh emas — per-call.
- Disabled/token-yo'q holatda hozirgidek warn-log + skip (DLQ alert yo'qolmaydi, log qoladi).
- `NoOpNotificationService` O'ChIRILADI: bean doim mavjud bo'lgach `@ConditionalOnMissingBean`
  hech qachon otmaydi — ghost-code qoldirilmaydi (ghost-grep majburiy).
- `connectTimeoutMs`/`readTimeoutMs` boot-bound qoladi (`TelegramProperties`), UI'ga chiqmaydi (YAGNI).
- DLQ-consumer'lar (`OracleDlqConsumer`, `ApiDlqConsumer`) TEGILMAYDI — `NotificationService`
  interfeysi o'zgarmaydi (SAFETY: pipeline'ga minimal ta'sir).

### 3. Gateway — admin API (`AiController` nusxasi)

| Endpoint | Semantika |
|---|---|
| `GET /qosid/admin/api/telegram-config` | Session auth. DB qiymatlari, token maskalangan (`***` agar saqlangan, `""` aks holda) |
| `PUT /qosid/admin/api/telegram-config` | **`409 {"error":"stop-required"}`** — D stop-to-edit modeli, yozish ops orqali gateway to'xtatilganda |
| `POST /qosid/admin/api/telegram-config/test` | Effektiv config (DB→env fallback) bilan chegaralangan sinov xabari (`properties` timeout'lari); DOIM strukturali JSON, never-500; xato matnidan token redaksiya qilinadi |

### 4. SPA (frontend)

- Settings ichida yangi **"Telegram" tab** — `AiConfigPage` patterni: enabled switch,
  bot-token (password input, `***` read-back), chat-id, thread-id, "Test yuborish" tugmasi.
- Stop-to-edit rejimida: ops generic `GET /ops/api/settings` orqali o'qish
  (`telegramFromRows` helper, `aiConfigFromRows` kabi) + ops `PUT` orqali yozish.
- `TYPED_FORM_KEYS`ga 4 kalit qo'shiladi → General ro'yxati ularni yashiradi
  ("har bir sozlama roppa-rosa bitta joyda").

### 5. Hujjatlar (CLAUDE.md doc-maintenance qoidasi)

- `ai-context/CONFIGURATION.md` — `gateway.telegram.*` endi DB-managed + env fallback
- `ai-context/ARCHITECTURE.md` — Admin Control-Plane API jadvaliga telegram-config
- `CLAUDE.md` — REST API jadvaliga qator
- Ghost-code grep: `NoOpNotificationService` barcha `.java` + `ai-context/` fayllardan tozalanganini tasdiqlash

### 6. Testlar

**Backend (app):**
- Fallback-zanjir: DB yutadi / DB yo'q → env / ikkalasi yo'q → disabled
- `isEnabled()` per-call semantikasi: DB-mock orqali enabled/token kombinatsiyalari
  (stop-to-edit tufayli DB o'zgarishi amalda keyingi start'da ko'rinadi — SettingsService
  keshi boot'da yuklanadi; test bu semantikani hujjatlashtiradi)
- Controller: GET masking, PUT → 409 stop-required, test-endpoint (RestTemplate mock: muvaffaqiyat,
  Telegram 4xx, timeout → hammasi strukturali JSON)
- Registry: 4 kalit `isDbManaged`, token `isDbManagedSecret`

**Ops:**
- Birinchi-marta PUT `gateway.telegram.bot-token` → qator `secret=true` bilan yaratiladi, audit maskalangan

**Frontend:**
- `TYPED_FORM_KEYS` 4 kalitni o'z ichiga oladi (General yashiradi)
- `telegramFromRows` mapping
- Tab render / save (409 xabari) / test tugma oqimi

## Muqobillar (rad etilgan)

- **B — Gibrid:** sir bo'lmagan 3 kalit oddiy registrda (General, REQUIRES_RESTART), token alohida —
  sozlama ikki joyga bo'linadi, "bitta joy" prinsipiga zid. RAD ETILDI.
- **Registrga secret=true:** boot-injection sirlarni yuklamaydi → token bean'ga yetmaydi. TEXNIK IMKONSIZ (yuqoridagi cheklov).

## Xavfsizlik eslatmalari

- Token AES-at-rest (`SETTINGS_ENCRYPTION_KEY`), GET'larda maskalanadi, audit'da maskalanadi.
- Test-endpoint xato javoblarida token hech qachon ko'rinmasligi kerak (redaksiya testi bilan qoplanadi).
- E1-E5 pipeline'ga ta'sir yo'q: faqat notification qatlami (DLQ alert yuborish yo'li) o'zgaradi,
  ack/idempotency/CB mantig'iga tegilmaydi.
