# B-faza: Settings Service + UI Poydevori — Dizayn hujjati

**Sana:** 2026-07-11
**Holat:** B1 tasdiqlangan; B2/B3/B4 skelet (o'z navbatida to'liq brainstorm qilinadi)
**Muallif:** Muxammadali + Claude
**Bog'liq:** [2026-07-10-qosid-flow-upgrade-design.md](2026-07-10-qosid-flow-upgrade-design.md) §4 (asosiy eskiz), [2026-07-11-c-faza-tenant-rename-design.md](2026-07-11-c-faza-tenant-rename-design.md) (C-faza B1+B2'ni kutadi)

## 1. Maqsad

Runtime sozlamalarni `.yaml`dan UI/DB'ga ko'chirish poydevorini qurish — shunda oddiy developerlar tizimlararo integratsiyani kod emas, sozlama orqali quradi. B-faza katta bo'lgani uchun 4 sub-fazaga bo'lingan; bu hujjat **B1 (Settings poydevori)** ni to'liq, B2/B3/B4 ni skelet sifatida beradi.

## 2. Kelishilgan qarorlar (savol-javob natijalari)

| Savol | Qaror |
|---|---|
| Dekompozitsiya | **Variant 1:** 4 sub-faza (B1 poydevor → B2 tenant-mapping → B3 outbound auth → B4 inbound auth), har biri alohida spec/plan/release. |
| Outbound auth | Qosid'da faqat **bitta target** (main app) → **bitta global** config (Basic/API-KEY/OAuth2). Ichki-provider requestlar (o'z `oauth2_provider`ini olib yuradiganlar) bu global config'dan **mustasno**. (B3) |
| Inbound auth (`/queue`) | API-key **va** OAuth2 (qosid token beradi), UI global config'da **tur tanlanadigan**. Tenant'ni beradi. (B4) |
| Admin akkaunt | Bitta admin, rolsiz. Birinchi startup `admin`/`admin` (bcrypt), UI'da o'zgartiriladi, unutilsa config/env reset. |
| Secret saqlash | AES bilan shifrlash (master kalit env'dan). **Tuzatish:** faqat-solishtiriladiganlar (admin parol, inbound API-key) — hash; qosid ishlatadigan outbound credential'lar — AES. |
| Baza | H2 embedded, fayl rejimi. |
| Hot-reload | Runtime sozlamalar restart'siz (kesh orqali); bootstrap (Kafka/Redis/port/H2 yo'li) restart talab qiladi. |

## 3. B-faza umumiy dekompozitsiyasi

| Sub-faza | Ko'lam | Bog'liqlik | Holat |
|---|---|---|---|
| **B1** | H2 + SettingsService + audit + admin login + `/qosid/admin/api` asosi | — | **Bu hujjat (to'liq)** |
| ~~**B2**~~ | ~~TenantMappingService sozlamalari UI/Settings'da~~ | — | **BEKOR — C-fazaga qo'shildi** (2026-07-12): B1 tenant sozlamalarini allaqachon saqlaydi/serve qiladi; qolgan ish (tipli validatsiya + sinash endpointi) C-faza spec'iga o'tdi |
| **B3** | Source kanali (Biruni pull+save) auth: Basic/API-KEY/OAuth2, UI-sozlanadigan; so'rov-forwarding TEGILMAYDI | B1 | **To'liq dizayn:** [2026-07-12-b3-source-auth-design.md](2026-07-12-b3-source-auth-design.md) |
| **B4** | Inbound auth (API-key + OAuth2 issuer), UI-tanlanadigan, `AuthResult.tenantId` | B1 | Skelet (§9) |

C-faza faqat **B1 + B2** ni kutadi (REST tenant fallback bor, shuning uchun B4'siz ham ishlaydi).

---

# B1 — Settings Poydevori (to'liq dizayn)

## 4. Ombor (H2)

**Texnik nuqta:** `application.yml`da `DataSourceAutoConfiguration` **o'chirilgan** (Oracle JDBC uchun). Shuning uchun settings uchun **alohida, aniq `DataSource` + `JdbcTemplate`** bean (`SettingsDbConfig`) yaratiladi — asosiy pipeline'ga tegmaydi. H2 fayl rejimi: `./data/settings.mv.db`.

**B1 jadvallar:**

| Jadval | Ustunlar | Vazifa |
|---|---|---|
| `settings` | `skey` (PK), `svalue` (TEXT), `secret` (BOOLEAN), `updated_at`, `updated_by` | Umumiy key-value. `secret=true` → AES shifrlangan. |
| `settings_audit` | `id` (PK auto), `skey`, `old_value`, `new_value`, `changed_by`, `changed_at` | O'zgarishlar tarixi; secret qiymatlar `***` bilan maskalanadi. |
| `admin_account` | `id` (PK), `username`, `password_hash` (bcrypt), `updated_at`, `last_login_at` | Bitta admin yozuvi. |

`oauth2_providers`, `api_keys` jadvallari B3/B4'da qo'shiladi.

**Sxema:** `schema.sql` (`CREATE TABLE IF NOT EXISTS`) alohida H2 DataSource'ga `DataSourceInitializer` orqali, yoki startup'da qo'lda. Flyway/Liquibase — YAGNI, shart emas.

## 5. SettingsService

Barcha runtime sozlamalar uchun yagona kirish nuqtasi. Mavjud `*Properties` klasslari qoladi, manba DB bo'ladi.

**Interfeys:**
- `get(key, default) → String` — kesh → DB → default. (C-faza `TenantMappingService` shuni chaqiradi.)
- `getSecret(key, default) → String` — AES deshifrlab qaytaradi.
- `set(key, value, secret, changedBy)` — DB'ga yozadi (secret → AES), keshni yangilaydi, audit yozadi.
- `getAll() → List` — UI uchun, secret'lar maskalangan.

**Kesh:** startup'da barcha `settings` `ConcurrentHashMap`ga o'qiladi; `set()` keshni yangilaydi; o'qish har doim keshdan. Bitta instance — distributed invalidation kerak emas.

**yaml→DB migratsiya (birinchi startup):** `settings` bo'sh bo'lsa, ko'chiriladigan yaml qiymatlari (upstream base-url, retry, timeout, Telegram, S3, content-type, polling, tenant-mapping default'lari) bir marta DB'ga ko'chiriladi (idempotent, tranzaksiyada). Keyin DB manba. Bootstrap sozlamalari (Kafka/Redis/port/H2 yo'li) yaml'da qoladi.

**Hot-reload:** o'zgarish keshga darhol tushadi → o'qiydigan kod keyingi o'qishda yangi qiymatni oladi, **restart shart emas**. Startup'da bir marta o'qiladigan bootstrap sozlamalari restart talab qiladi (UI "restart kerak" belgisi bilan).

**Qiymat tipi:** hammasi String; o'qiganda kerakli tipга aylantiriladi; murakkab qiymatlar (content-type ro'yxati) JSON string.

## 6. Admin autentifikatsiya

- **Birinchi startup:** `admin_account` bo'sh → `admin`/`admin` (bcrypt), log WARN.
- **Login:** `POST /qosid/admin/api/login` → bcrypt tekshiruv → sessiya; `last_login_at` yangilanadi.
- **Sessiya:** Spring Security `HttpSession` + `SESSION` cookie (`HttpOnly`, `SameSite=Strict`, prod `Secure`), TTL sozlanadigan (default 8h), server-side (bitta instance).
- **Parol o'zgartirish:** `POST /qosid/admin/api/change-password` (eski + yangi), sessiyada, eski parolni tasdiqlaydi.
- **Config-reset:** env `ADMIN_RESET=true` startup → parolni `admin`ga qaytaradi + WARN; yoki `ADMIN_PASSWORD_RESET=<yangi>` aniq parol bilan.

**Xavfsizlik:**
- Parol → **bcrypt** (bir tomonlama, hech qachon deshifrlanmaydi).
- Login **rate-limit** (IP/username, masalan 5/daqiqa), in-memory hisoblagich.
- **Spring Security** (`spring-boot-starter-security`) qo'shiladi — quyidagi izolyatsiya bilan.

## 7. Admin API asosi + xavfsizlik integratsiyasi

**Endpointlar (B1):**
- `POST /login`, `POST /logout`, `POST /change-password`, `GET /me`
- `GET /settings`, `PUT /settings/{key}` (secret maskalangan), `GET /settings/audit`
- `GET /health` (auth'siz)

**Integratsiya xavfi va yechim:** hozir Spring Security yo'q; uni qo'shsak default holatda **hamma endpoint** yopiladi. Yechim — **ikki alohida `SecurityFilterChain`**:
1. `adminSecurityChain` (order 1, `securityMatcher("/qosid/admin/api/**")`) — sessiya talab (login/health mustasno), CSRF yoqilgan.
2. `apiSecurityChain` (order 2, qolganlar) — `permitAll` + mavjud `AuthorizationProvider` o'z holicha. Pipeline (`/qosid/api/**`) va actuator **tegilmaydi**.

**CSRF:** admin API cookie-sessiya → CSRF token (`XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header); SPA (D-faza) same-origin (nginx proxy) cookie'ni o'qib header'da qaytaradi.

**Xatoliklar:** uniform `ErrorResponse` (401/403/400), A-faza correlation-ref siyosati saqlanadi.

## 8. Testlash va risklar (B1)

**Testlash:**
- `SettingsService` unit: get/set/getSecret (kesh/DB/default), audit yozilishi, secret AES round-trip + audit maskalash.
- yaml→DB migratsiya: bo'sh → ko'chirish; to'la → o'tkazib yuborish (idempotent).
- H2 integratsiya (in-memory test rejimi): schema, CRUD.
- Admin auth: bcrypt login (to'g'ri/noto'g'ri), sessiya, change-password, rate-limit (6-urinish bloklanishi), config-reset.
- **Security chain izolyatsiyasi (eng muhim):** `/qosid/api/**` va actuator ochiq qolishini tasdiqlovchi `@SpringBootTest`+MockMvc testi.
- Regressiya: mavjud 28 test yashil.
- Lokal smoke: login (admin/admin) → sessiya → `/settings`; `/qosid/api/v1/queue` hali ishlashi.

**Risklar:**

| Risk | Yumshatish |
|---|---|
| Spring Security pipeline'ni yopishi | Ikki `SecurityFilterChain`, izolyatsiya testi majburiy |
| H2 excluded-autoconfig to'qnashuvi | Alohida aniq `DataSource`/`JdbcTemplate`, Oracle exclude'ga tegilmaydi |
| AES master kalit yo'qligi (dev) | Startup WARN + secret'siz rejim (dev); prod'da majburiy |
| Default admin/admin qolib ketishi | Startup WARN; D-faza UI o'zgartirishni undaydi |
| yaml→DB migratsiya qisman/xato | Idempotent (faqat bo'sh baza), tranzaksiya, test |

**B1 chegarasi:** pipeline yadrosi (consumer, CB, RocksDB, Redis state) o'zgarmaydi — faqat yangi mustaqil qatlam qo'shiladi.

---

## 9. B2/B3/B4 skelet (keyin to'liq brainstorm)

**B2 — BEKOR (2026-07-12):** B1 tenant sozlamalarini (`gateway.tenant.*`) allaqachon DB'da saqlaydi va generic settings API orqali tahrirlash imkonini beradi. Qolgan ish (tipli validatsiya + "sinash" endpointi) C-faza spec'iga (§5a/§5b) qo'shildi. C-faza endi faqat B1'ni kutadi.

**B3 — Outbound global auth:** bitta global config `gateway.outbound.auth.type` ∈ {basic, apikey, oauth2} + tipга mos credential (basic: login/parol; apikey: header nomi + kalit; oauth2: client_id/secret/token_url/grant). Credential'lar AES. `HttpRequestService` global config'ga qarab auth qo'shadi; ichki-provider (`oauth2_provider` bor) requestlar mustasno (mavjud logika). `oauth2_providers` jadvali (agar per-target kengaytirilsa). Mavjud `BiruniOAuth2Client`/`StandardOAuth2Client` machinery'sini umumlashtirish.

**B4 — Inbound auth:** `gateway.inbound.auth.type` ∈ {apikey, oauth2}, UI-tanlanadigan. `api_keys` jadvali (nom, `key_hash` SHA-256, tenant binding, revoke/last-used). OAuth2 issuer yo'li: qosid `/oauth/token` (client_credentials) token beradi (Spring Authorization Server yoki minimal hand-rolled — brainstormda hal). `ApiKeyAuthorizationProvider`/`OAuth2AuthorizationProvider` mavjud `AuthorizationProvider` SPI orqali; NoOp faqat dev. **`AuthResult` `tenantId` (+ ixtiyoriy `extraTenantId`) qaytaradi** — C-faza REST tenant kontrakti. `/status`, `/result` faqat o'sha tenant'ga ochiq.

## 10. Ko'lamdan tashqarida (B1)

- Rollar / multi-user (bitta admin).
- Distributed sozlama keshi (bitta instance).
- Flyway/Liquibase migratsiya (YAGNI).
- B2/B3/B4 tafsilotlari (o'z spec'larida).
- UI'ning o'zi (D-faza).
