# Security CRITICAL Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2026-07-24 auditidagi 3 ta xavfsizlik CRITICAL'ini yopish — enumeratsiya qilinadigan IDOR, majburiy almashtirilmaydigan `admin/admin`, va git tarixidagi sirlar (oxirgisi TAYYORLASH darajasida).

**Architecture:** Uchta mustaqil ish. (1) IDOR: `RequestState.source` maydoni allaqachon mavjud lekin hech qachon to'ldirilmaydi — uni to'ldirib, `ORACLE` manbali so'rovlarning `/status` va `/result` o'qishlarini `404` bilan rad etamiz. Bu enumeratsiya qilinadigan yarmini butunlay yopadi; REST manbali ID'lar ~59 bit entropiyaga ega va ular ochiq qolishi B4-deferred qarorining ataylab qismi. (2) Majburiy parol almashtirish: server tomonda gate (SPA'ga ishonmaydi) + SPA formasi (u allaqachon yarim qurilgan — endpoint bor, forma yo'q). (3) Sir gigienasi: `.gitignore`, `.env.example`, gitleaks CI, va remediatsiya hujjati — **tarixni qayta yozish va tashqi sirlarni almashtirish BAJARILMAYDI**, faqat hujjatlashtiriladi.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Security, JUnit 5 + Mockito, React + antd + vitest.

## Global Constraints

- **Maven PATH'da YO'Q:** `MVN="/c/Program Files/JetBrains/IntelliJ IDEA 2025.3.3/plugins/maven/lib/maven3/bin/mvn.cmd"`, bash'dan.
- **`app` testlarini `-P '!frontend'` bilan** ishga tushiring (aks holda har safar `npm ci` + build, ~7 daqiqa va Windows fayl-qulfi xatari). Surefire ko'p test ajratkichi — vergul: `-Dtest='A,B'`.
- **Bir vaqtda bitta Maven** — parallel ishlashi RocksDB LOCK to'qnashuvi beradi (`app/target/test-data`).
- Docker infra YOQIQ bo'lishi shart (`docker compose up -d`) — `app` testlari Redis talab qiladi.
- **SAFETY-RULES o'qilgan bo'lishi shart.** `StatusController` Protected Zone'da EMAS, lekin `RequestStateService` (170-qator) va ikkala request-consumer HA. 29-qoida: `apiSecurityChain` `permitAll` qolishi SHART — `/qosid/api/**`ga CSRF yoki auth qo'shish pipeline'ni o'ldiradi. `SecurityIsolationTest` yashil qolishi kerak.
- **B4 qarori hurmat qilinadi:** inbound `/queue/**` autentifikatsiyasiz qoladi, polling'ga majburiy token QO'SHILMAYDI (shaffof-proxy shartnomasi).
- Har commit `debug`da, oxirida `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

---

### Task 1: IDOR — `ORACLE` manbali javoblarni tashqi o'qishdan yopish

**Muammo (jonli isbotlangan):** `GET /qosid/api/v1/requests/{id}/result` autentifikatsiyasiz HTTP 200 va 20 022 baytlik upstream javob tanasini qaytardi. Biruni manbali `request_id` ketma-ket son, `tenantId` kichik butun son → `100:900123` kabi ID'larni sanab chiqish mumkin. Oracle manbali javoblarni hech qanday tashqi o'quvchi olishi kerak emas — ular Biruni'ga save-leg orqali qaytadi.

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/state/RequestStateService.java` (`createInitialState` — source qabul qiladi)
- Modify: `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java` (chaqiruv)
- Modify: `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java` (chaqiruv)
- Modify: `app/src/main/java/uz/greenwhite/gateway/api/StatusController.java` (ikkala endpoint'ga gate)
- Test: `app/src/test/java/uz/greenwhite/gateway/api/StatusControllerSourceGateTest.java` (YANGI)

**Interfaces:**
- Produces: `RequestStateService.createInitialState(String compositeId, String source)` → `RequestState` (source persist qilinadi). Eski bir-argumentli overload SAQLANADI va `null` source bilan delegat qiladi (mavjud chaqiruvchilar/testlar buzilmasin).
- Produces: `StatusController` — `ORACLE` manbali ID uchun ikkala endpoint `404` (403 EMAS: 403 ID mavjudligini tasdiqlaydi va enumeratsiyaga yordam beradi).

- [ ] **Step 1: Failing test yozish** — `StatusControllerSourceGateTest.java`. `StatusController` konstruktori: `(RequestStateService, ResponseStorageService, ObjectMapper, ProxyProperties)`. `responseThresholdSize` `@Value` maydoni — unit-testda 0 bo'lib qoladi, shuning uchun kichik tana ham `resultUrl` yo'liga tushadi; test buni tekshirmaydi.

```java
package uz.greenwhite.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.config.ProxyProperties;
import uz.greenwhite.gateway.model.RequestState;
import uz.greenwhite.gateway.model.enums.RequestStatus;
import uz.greenwhite.gateway.storage.ResponseMetadata;
import uz.greenwhite.gateway.storage.ResponseStorageService;
import uz.greenwhite.gateway.state.RequestStateService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ORACLE-sourced responses must not be readable through the public polling
 * endpoints. Their request_id is a source-side sequential integer and tenantId is a
 * small integer, so the composite id is enumerable; the result also travels back to
 * the source over the save leg, so no external reader needs it. 404 (not 403) so the
 * response does not confirm that an id exists.
 */
class StatusControllerSourceGateTest {

    private final RequestStateService state = mock(RequestStateService.class);
    private final ResponseStorageService storage = mock(ResponseStorageService.class);

    private StatusController controller() {
        return new StatusController(state, storage, new ObjectMapper(), mock(ProxyProperties.class));
    }

    private RequestState stateWithSource(String source) {
        return RequestState.builder()
                .compositeId("100:900123")
                .status(RequestStatus.COMPLETED)
                .source(source)
                .hasResult(true)
                .build();
    }

    @Test
    void statusIs404ForOracleSourcedRequest() {
        when(state.getState("100:900123")).thenReturn(Optional.of(stateWithSource("ORACLE")));
        assertThat(controller().getStatus("100:900123").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void resultIs404ForOracleSourcedRequest() {
        when(state.getState("100:900123")).thenReturn(Optional.of(stateWithSource("ORACLE")));
        assertThat(controller().getResult("100:900123").getStatusCode().value()).isEqualTo(404);
        // The gate must run BEFORE RocksDB is touched — no body may be read at all.
        verify(storage, never()).getResponseBody(anyString());
    }

    @Test
    void resultIs404WhenNoStateExists() {
        // Defence in depth: an id whose state has expired must not fall through to RocksDB.
        when(state.getState("100:900123")).thenReturn(Optional.empty());
        assertThat(controller().getResult("100:900123").getStatusCode().value()).isEqualTo(404);
        verify(storage, never()).getResponseBody(anyString());
    }

    @Test
    void apiSourcedRequestIsStillReadable() {
        when(state.getState("0:123")).thenReturn(Optional.of(
                RequestState.builder().compositeId("0:123").status(RequestStatus.COMPLETED)
                        .source("API").hasResult(true).build()));
        ResponseMetadata meta = mock(ResponseMetadata.class);
        when(meta.getHttpStatus()).thenReturn(200);
        when(meta.getContentType()).thenReturn("application/json");
        when(storage.getResponseMetadata("0:123")).thenReturn(meta);
        when(storage.getResponseBody("0:123")).thenReturn("{\"ok\":true}".getBytes());

        assertThat(controller().getResult("0:123").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void nullSourceIsTreatedAsReadable() {
        // Legacy rows written before source was persisted must not start 404-ing.
        when(state.getState("0:999")).thenReturn(Optional.of(
                RequestState.builder().compositeId("0:999").status(RequestStatus.PROCESSING)
                        .source(null).build()));
        assertThat(controller().getStatus("0:999").getStatusCode().value()).isEqualTo(200);
    }
}
```

- [ ] **Step 2: FAIL ko'rish** — Run: `"$MVN" -B -pl app -P '!frontend' -Dtest='StatusControllerSourceGateTest' test`
  Expected: FAIL — `statusIs404ForOracleSourcedRequest` 200 oladi; `resultIs404*` ham.

- [ ] **Step 3: `StatusController`ga gate qo'shish.** Klass boshiga xususiy yordamchi:

```java
    /** ORACLE-sourced ids are enumerable and their result belongs to the source system only. */
    private boolean isExternallyReadable(RequestState state) {
        return !"ORACLE".equalsIgnoreCase(state.getSource());
    }
```

`getStatus`da `stateOpt.isEmpty()` tekshiruvidan keyin:

```java
        RequestState state = stateOpt.get();
        if (!isExternallyReadable(state)) {
            log.debug("Refusing status read for source-owned request: {}", id);
            return ResponseEntity.notFound().build();
        }
```

`getResult`ni BOSHIDAN qayta yozing — RocksDB'ga tegishdan OLDIN holatni tekshiradi:

```java
    @GetMapping("/{id}/result")
    public ResponseEntity<byte[]> getResult(@PathVariable String id) {
        Optional<RequestState> stateOpt = requestStateService.getState(id);
        if (stateOpt.isEmpty() || !isExternallyReadable(stateOpt.get())) {
            // No state (expired/unknown) or source-owned -> 404 without touching RocksDB.
            return ResponseEntity.notFound().build();
        }

        ResponseMetadata meta = responseStorageService.getResponseMetadata(id);
        if (meta == null) {
            return ResponseEntity.notFound().build();
        }

        byte[] body = responseStorageService.getResponseBody(id);
        if (body == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.status(meta.getHttpStatus())
                .contentType(MediaType.parseMediaType(meta.getContentType()))
                .contentLength(body.length)
                .body(body);
    }
```

- [ ] **Step 4: Test PASS** — Run: `"$MVN" -B -pl app -P '!frontend' -Dtest='StatusControllerSourceGateTest' test` → PASS (5 test)

- [ ] **Step 5: `source`ni holatga yozish.** `RequestStateService`da:

```java
    /** Backwards-compatible overload; prefer the two-arg form so /status and /result can gate on source. */
    public RequestState createInitialState(String compositeId) {
        return createInitialState(compositeId, null);
    }

    public RequestState createInitialState(String compositeId, String source) {
        RequestState state = RequestState.builder()
                .compositeId(compositeId)
                .status(RequestStatus.PROCESSING)
                .attemptCount(0)
                .source(source)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        saveState(state);
        return state;
    }
```

Mavjud bir-argumentli metodni O'CHIRMANG — u boshqa joylarda ishlatilishi mumkin.

- [ ] **Step 6: Ikkala consumer'da chaqiruvni yangilash.** Har ikkisida `processRequest` ichidagi:
```java
        if (requestStateService.getState(key).isEmpty()) {
            requestStateService.createInitialState(key);
        }
```
ni shunday qiling:
```java
        if (requestStateService.getState(key).isEmpty()) {
            requestStateService.createInitialState(key, message.getSource());
        }
```
`ApiRequestConsumer`da `message.getSource()` `"API"`, `OracleRequestConsumer`da `"ORACLE"` bo'ladi (`RequestPuller` o'rnatadi). Ikkala klassni birlashtirmang (10-qoida).

- [ ] **Step 7: To'liq app testlari** — Run: `"$MVN" -B -pl app -P '!frontend' test`
  Expected: PASS. **`SecurityIsolationTest` yashil qolishi SHART** (29-qoida) — u pipeline ochiqligini qo'riqlaydi va bizning o'zgarish auth emas, biznes-qoida.

- [ ] **Step 8: SAFETY-RULES checklist** — 7 bandni yozib o'ting. Idempotency: tegilmagan. Retry: tegilmagan. Metrics: yangi kod yo'li o'lchanmaydi (o'qish rad etilishi — xohlasangiz counter qo'shing, majburiy emas). DLQ: tegilmagan. Redis: `getState` xatosi — mavjud xatti-harakat. MANUAL ack: tegilmagan. Thread safety: o'qish-only.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/uz/greenwhite/gateway/api/StatusController.java \
        app/src/main/java/uz/greenwhite/gateway/state/RequestStateService.java \
        app/src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java \
        app/src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java \
        app/src/test/java/uz/greenwhite/gateway/api/StatusControllerSourceGateTest.java
git commit -F - <<'MSG'
fix(security): ORACLE manbali javoblar tashqi polling'dan yopildi (IDOR)

GET /qosid/api/v1/requests/{id}/{status,result} autentifikatsiyasiz ochiq
(B4 qarori bo'yicha ataylab), lekin Biruni manbali request_id ketma-ket son
va tenantId kichik butun son — ya'ni composite id sanab chiqiladi. Jonli
tekshiruvda autentifikatsiyasiz 20 022 baytlik upstream javob tanasi olindi.

Oracle manbali javob save-leg orqali manbaga qaytadi, tashqi o'quvchiga
kerak emas -> ikkala endpoint 404 qaytaradi (403 EMAS: 403 id mavjudligini
tasdiqlab enumeratsiyaga yordam berardi). /result endi RocksDB'ga
tegishdan OLDIN holatni tekshiradi.

RequestState.source maydoni mavjud edi lekin hech qachon to'ldirilmagan —
createInitialState endi uni saqlaydi. REST manbali id'lar ~59 bit
entropiyaga ega va ochiq qoladi (B4 qarori, shaffof-proxy shartnomasi).

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

### Task 2: Majburiy parol almashtirish — `admin/admin` bilan boshqa hech nima ishlamaydi

**Muammo (jonli tasdiqlangan):** `POST /qosid/admin/api/login` `admin`/`admin` bilan HTTP 200 qaytardi. Faqat `log.warn` bor, majburlash yo'q. Bu hisob bilan ops orqali gateway to'xtatiladi, `gateway.api.base-url` o'zgartiriladi va butun trafik boshqa manzilga buriladi.

**Design:** Server tomonda majburlash (SPA'ga ishonmaydi). Parol hali default bo'lsa: `login` ISHLAYDI (aks holda almashtirish imkoni yo'q), `me`/`logout`/`change-password` ISHLAYDI, qolgan BARCHA `/qosid/admin/api/**` `403 {"error":"password-change-required"}` qaytaradi. SPA login javobidagi flagni ko'rib majburiy forma ko'rsatadi.

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/admin/AdminAuthService.java` (default-parol aniqlash + login javobi flagi)
- Create: `app/src/main/java/uz/greenwhite/gateway/admin/DefaultPasswordGateFilter.java`
- Modify: `app/src/main/java/uz/greenwhite/gateway/config/AdminSecurityConfig.java` (filtrni admin zanjiriga qo'shish)
- Modify: `frontend/src/auth/AuthContext.tsx` + `frontend/src/api/*` (flagni o'qish)
- Create: `frontend/src/pages/ChangePasswordPage.tsx` (yoki mavjud login sahifasiga majburiy rejim)
- Test: `app/src/test/java/uz/greenwhite/gateway/admin/DefaultPasswordGateTest.java` (YANGI)
- Test: `frontend/src/pages/ChangePasswordPage.test.tsx` (YANGI)

**Interfaces:**
- Produces: `AdminAuthService.isDefaultPassword()` → `boolean` (BCrypt `matches("admin", storedHash)`).
- Produces: `GET/POST` admin endpoint'lari `403 {"error":"password-change-required"}` (default parol holatida, allowlist'dan tashqari).
- Produces: `POST /qosid/admin/api/login` javobida `{"passwordChangeRequired": true|false}`.

- [ ] **Step 1: Avval kodni o'qing.** `AdminAuthService`, `AdminAuthController`, `AdminSecurityConfig`, `AdminAccountInitializer`, va `AdminAccountRepository`ni o'qib, haqiqiy metod imzolarini va parol-hash olish yo'lini aniqlang. `AdminAccountInitializer` `admin`/`admin` seed qiladi va `ADMIN_RESET`/`ADMIN_PASSWORD_RESET` bir-martalik reset mexanizmi bor — uni buzmang.

- [ ] **Step 2: Failing test** — `DefaultPasswordGateTest`: `@SpringBootTest` + `MockMvc`. Default parol holatida: `POST /login` (admin/admin) → 200 va javobda `passwordChangeRequired: true`; keyin `GET /settings` → **403** `password-change-required`; `POST /change-password` → 200; undan keyin `GET /settings` → 200. Property'lar uchun Task 1'dagi `@SpringBootTest` property to'plamidan foydalaning (`gateway.settings.db-path=mem:...`, `gateway.rocksdb.path=./target/test-data/rocksdb-<unique>` — har test klassiga UNIKAL RocksDB yo'li, aks holda LOCK to'qnashuvi).

- [ ] **Step 3: FAIL ko'rish** — hozir `GET /settings` 200 qaytaradi.

- [ ] **Step 4: `isDefaultPassword()` qo'shish** — `AdminAuthService`ga: saqlangan hash'ni olib `passwordEncoder.matches("admin", hash)`. Hisob topilmasa `false`. Har chaqiruvda DB o'qish qimmat emas (admin trafik kam), lekin xohlasangiz `AtomicBoolean` cache + `changePassword`da invalidatsiya qiling.

- [ ] **Step 5: `DefaultPasswordGateFilter` yozish** — `OncePerRequestFilter`. `/qosid/admin/api/**` ostida, allowlist (`login`, `login-token`, `logout`, `me`, `change-password`, `health`) dan tashqari, `adminAuthService.isDefaultPassword()` true bo'lsa `403` + `{"error":"password-change-required"}` JSON. Allowlist'ni **aniq yo'l bilan** solishtiring (prefiks emas).

- [ ] **Step 6: Filtrni zanjirga ulash** — `AdminSecurityConfig.adminSecurityChain`da `.addFilterAfter(new DefaultPasswordGateFilter(...), <mos filtr>)`. **`apiSecurityChain`ga TEGMANG** (29-qoida) — filtr faqat admin zanjirida bo'lishi kerak, aks holda `/qosid/api/**` bloklanadi.

- [ ] **Step 7: Login javobiga flag** — `AdminAuthController.login` javobiga `passwordChangeRequired` qo'shing.

- [ ] **Step 8: Backend testlar PASS + `SecurityIsolationTest` yashil** — Run: `"$MVN" -B -pl app -P '!frontend' test`

- [ ] **Step 9: SPA — majburiy almashtirish formasi.** `frontend/src/layout/AppLayout.tsx`da "parol o'zgartirish" menyusi hozir shunchaki `/settings`ga o'tadi (yarim qurilgan feature; `auth.oldPassword`/`auth.newPassword` i18n kalitlari ishlatilmay yotadi). Haqiqiy forma qiling: eski parol, yangi parol, tasdiqlash; `POST /qosid/admin/api/change-password`ga yuboradi. `passwordChangeRequired` true bo'lsa boshqa sahifalarga o'tishni bloklab shu formani ko'rsatadi. Mavjud i18n kalitlarini ishlating, yangi kerak bo'lsa `uz.json`ga qo'shing.

- [ ] **Step 10: Frontend test** — `ChangePasswordPage.test.tsx`: forma render bo'ladi, mos kelmagan tasdiqlash xato beradi, muvaffaqiyatli yuborishda API chaqiriladi. Mavjud sahifa-testlari patternini nusxalang (`AiConfigPage.test.tsx`).

- [ ] **Step 11: `cd frontend && npm test -- --run`** → PASS (114 + yangi)

- [ ] **Step 12: Commit** (backend va frontend ALOHIDA commit — biri prod'da muammo bersa alohida qaytarish uchun)

---

### Task 3: Sir gigienasi — TAYYORLASH (tarix qayta yozilMAYDI)

**Muammo:** git tarixida 3 ta haqiqiy sir: o'g'irlangan Telegram token `8318506270:AAGz...` (20+ commit), prod manba paroli `password: greenwhite` + `admin@head` (`app3.greenwhite.uz`, ko'p commit), 128-belgili OAuth2 client-secret (1 commit). Ishchi katalog TOZA. `.gitignore`da `.env` yozuvi YO'Q — ya'ni hodisa qaytarilishi mumkin.

**BAJARILMAYDI (egasi qaror qiladi):** `git filter-repo` bilan tarixni qayta yozish, `master`/`debug`ga force-push, va tashqi tizimlarda sirlarni almashtirish. Bu reja faqat qaytalanishning oldini oladi va aniq qadamlarni hujjatlashtiradi.

**Files:**
- Modify: `.gitignore`
- Create: `.env.example`
- Create: `.github/workflows/secret-scan.yml`
- Create: `docs/SECURITY-REMEDIATION.md`

- [ ] **Step 1: `.gitignore`ga qo'shish** (mavjud qatorlarni o'chirmang):

```gitignore
# --- Secrets: never commit ---
.env
.env.*
!.env.example
*.pem
*.key
.claude/settings.local.json
```

`.claude/settings.local.json` hozir faqat dasturchining GLOBAL gitignore'i bilan chetlanadi (`git check-ignore -v` bilan tasdiqlangan) — boshqa klonda commit qilinadi va u prod IP, SSH kalit yo'li va `curl -u "$SOURCE_USERNAME:$SOURCE_PASSWORD"` buyrug'ini o'z ichiga oladi.

- [ ] **Step 2: `.env.example` yozish.** `README.md` uni 3 joyda havola qiladi, lekin fayl yo'q. `app/src/main/resources/application.yml`dagi BARCHA `${ENV_VAR:...}` o'rinlarini o'qib, prod uchun kerak bo'lganlarini placeholder qiymat bilan yozing. Haqiqiy sir YOZMANG. Kamida: `SOURCE_USERNAME`, `SOURCE_PASSWORD`, `S3_*`, `SETTINGS_ENCRYPTION_KEY`, `TELEGRAM_*`, `API_BASE_URL`, `TRUSTED_PROXIES`, `REDIS_*`, `KAFKA_*`, `CALLBACK_ALLOWED_HOSTS`. Har biriga bir qatorlik izoh; `API_BASE_URL` uchun 22-qoidadagi `/b` prefiks tuzog'ini yozing.

- [ ] **Step 3: gitleaks CI workflow.** `.github/workflows/secret-scan.yml` — `push` va `pull_request`da ishlaydi, `gitleaks/gitleaks-action`ni **commit SHA bilan pin qilib** (audit L6: `softprops/action-gh-release@v2` pin qilinmagani ham qayd etilgan). `fetch-depth: 0` bilan to'liq tarix skanerlansin — bu mavjud 3 sirni TOPADI va CI qizil bo'ladi; shuning uchun `.gitleaksignore` faylida ularning finger-print'larini "ma'lum, remediatsiya kutilmoqda" izohi bilan ro'yxatga oling, yoki workflow'ni avval `continue-on-error: true` bilan qo'shib, remediatsiyadan keyin qattiqlashtiring. Qaysi yo'lni tanlaganingizni hujjatda yozing.

- [ ] **Step 4: `docs/SECURITY-REMEDIATION.md` yozish.** Uch bo'lim:
  1. **Nima ochilgan** — jadval: sir, qayerda (commit + fayl), qanday tasdiqlangan, xatar darajasi. Tokenning O'ZINI YOZMANG — faqat prefiks (`8318506270:AAG…`) va commit SHA.
  2. **Almashtirish (egasi bajaradi)** — Telegram: BotFather → `/revoke`, keyin `curl https://api.telegram.org/bot<OLD>/getMe` **401** kutiladi; manba paroli: `app3.greenwhite.uz` `admin@head` parolini almashtirish + prod `.env`ni yangilash + gateway restart; OAuth2 client-secret: provayderda qayta generatsiya + settings-DB'da yangilash (ops stop-to-edit orqali).
  3. **Tarixni tozalash (qaror + force-push talab qiladi)** — aniq buyruqlar:
     ```bash
     # BACKUP birinchi
     git clone --mirror <remote> qosid-flow-backup.git
     # replacements.txt: literal=>REDACTED qatorlari (sirlarni shu faylga yozing, faylni commit QILMANG)
     git filter-repo --replace-text replacements.txt
     git push --force --all && git push --force --tags
     ```
     Ogohlantirishlar: barcha 281 commit SHA'si o'zgaradi; har bir klon qayta klonlanishi kerak; ochiq PR'lar buziladi; GitHub'da eski commit'lar keshda qolishi mumkin (support'ga murojaat kerak); `master` himoyalangan bo'lsa force-push vaqtincha ochilishi kerak. **Alternativa:** tarixni qayta yozmasdan, faqat sirlarni almashtirish + repo'ni yopiq saqlash; open-core e'lonida esa yangi, squash qilingan tarix bilan alohida public repo yaratish — bu ko'pincha xavfsizroq va arzonroq.

- [ ] **Step 5: `git check-ignore` bilan tasdiqlash**

```bash
git check-ignore -v .env .env.local .claude/settings.local.json
```
Har biri repo `.gitignore`iga (global emas) ishora qilishi kerak. `.env.example` esa chetlanMASLIGI kerak: `git check-ignore -v .env.example` → natija bo'sh.

- [ ] **Step 6: Commit**

```bash
git add .gitignore .env.example .github/workflows/secret-scan.yml docs/SECURITY-REMEDIATION.md .gitleaksignore
git commit -F - <<'MSG'
chore(security): sir gigienasi — .gitignore, .env.example, gitleaks CI, remediatsiya hujjati

Ishchi katalog toza, lekin git TARIXIDA 3 ta haqiqiy sir bor va .gitignore'da
.env yozuvi yo'q edi (hodisa qaytarilishi mumkin). Bu commit qaytalanishning
oldini oladi va remediatsiyani hujjatlashtiradi.

TARIX QAYTA YOZILMADI va sirlar ALMASHTIRILMADI — ikkisi ham egasining
qarori: birinchisi 281 commit SHA'sini o'zgartiradi va force-push talab
qiladi, ikkinchisi tashqi tizimlarda amalga oshiriladi. Qadamlar
docs/SECURITY-REMEDIATION.md'da.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
```

---

### Task 4: Hujjatlash + yakuniy verifikatsiya

- [ ] **Step 1: `CLAUDE.md` REST jadvalini yangilash** — `requests/{id}/{status,result}` qatoriga: "ORACLE manbali so'rovlar uchun 404 (natija manbaga save-leg orqali qaytadi; composite id enumeratsiya qilinadi)". Admin qatoriga: "default parol holatida `login`/`me`/`logout`/`change-password`dan tashqari hamma narsa `403 password-change-required`".
- [ ] **Step 2: `ai-context/SAFETY-RULES.md`ga 34- va 35-qoida** — (34) `/status`/`/result`da ORACHE-manba gate'ini olib tashlamang: sabab enumeratsiya + jonli isbot; (35) default-parol gate'ini `apiSecurityChain`ga ko'chirmang (29-qoida bilan to'qnashadi, pipeline o'ladi). Protected Zones jadvaliga `StatusController.java` va `DefaultPasswordGateFilter.java` qatorlari.
- [ ] **Step 3: `ai-context/ARCHITECTURE.md`** — Admin Control-Plane bo'limiga default-parol gate'i; status/result o'qish bo'limiga source gate'i.
- [ ] **Step 4: `docs/manual-test-plan-v1.14.md`** — I1 bandiga (parol almashtirish) yangi majburiy oqim; yangi band: ORACLE id bilan `/result` → 404.
- [ ] **Step 5: Ghost-grep** — `isDefaultPassword`, `password-change-required`, `DefaultPasswordGateFilter`, `isExternallyReadable`, `createInitialState` — kodda va hujjatda izchil, eski nom qolmagan.
- [ ] **Step 6: To'liq verifikatsiya**
  ```bash
  "$MVN" -B clean install          # 3 modul + frontend build
  cd frontend && npm test -- --run # vitest
  ```
  Kutilgan baza: settings-core 30, app 194 + yangi, ops 46, frontend 114 + yangi. **`clean` oldidan osilib qolgan `node.exe` jarayonini yopib qo'ying** (`app/target/node/node.exe` band bo'lsa `clean` yiqiladi).
- [ ] **Step 7: Jonui IDOR tekshiruvi** — gateway'ni ko'tarib: (a) REST so'rov yuborib, uning `/result`i 200 qaytarishini (o'zgarmagan xatti-harakat), (b) Redis'ga qo'lda `source=ORACLE` holat yozib yoki Oracle oqimi orqali so'rov yaratib, uning `/result`i **404** qaytarishini tasdiqlang. (c) `admin`/`admin` bilan login qilib `GET /qosid/admin/api/settings` → **403 password-change-required**.
- [ ] **Step 8: Commit**

---

## Self-Review natijasi

**Qamrov:** auditning 3 xavfsizlik CRITICAL'i → Task 1 (IDOR), Task 2 (admin/admin), Task 3 (tarixdagi sirlar, tayyorlash darajasida). Hujjat + verifikatsiya → Task 4.

**Ataylab qamrovdan tashqarida:** auditning HIGH bandlari (unbounded request body H2, `dev` profil actuator shutdown H3, Spring Boot 3.3.0 bump H4, cookie `Secure`/`SameSite` M1, compose portlari M6). Ular alohida reja — bu reja faqat CRITICAL'larni yopadi. Shuningdek `nack`/`asyncAcks` (tartibsiz offset commit) oldingi rejada ochiq qoldirilgan.

**Placeholder skani:** Task 2 va Task 3 qadamlarida to'liq kod berilmagan va bu ataylab — Task 2 avval 5 ta mavjud admin klassini o'qishni talab qiladi (imzolarni ko'rmasdan aniq kod yozish noto'g'ri bo'lardi), Task 3 esa `application.yml`dagi barcha env-var'lar ro'yxatiga tayanadi. Ikkalasida ham qadamlar aniq: nima o'qish, nima yozish, qanday tekshirish. Task 1 to'liq kod bilan.

**Tip izchilligi:** `createInitialState(String)` + `createInitialState(String, String)` overload'lari (Task 1 Step 5) ↔ consumer chaqiruvlari (Step 6) ✓. `isExternallyReadable(RequestState)` (Step 3) ikkala endpoint'da ✓. `isDefaultPassword()` (Task 2 Step 4) ↔ filtr (Step 5) ↔ login flagi (Step 7) ✓. `404` (403 emas) qarori test (Step 1) ↔ implementatsiya (Step 3) ↔ hujjat (Task 4) bo'ylab bir xil ✓.

**Xatar:** Task 2 filtri admin zanjirining barcha yo'llarini kesib o'tadi — allowlist noto'g'ri bo'lsa operator o'zini qulflab qo'yadi (parolni almashtirish uchun kirish kerak, kirish uchun almashtirish kerak). Shuning uchun `login`, `me`, `logout`, `change-password` allowlist'da bo'lishi MAJBURIY va Task 2 Step 2 testi aynan shu to'liq halqani tekshiradi.
