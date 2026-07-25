# B3: Source Channel Outbound Auth — Dizayn hujjati

**Sana:** 2026-07-12
**Holat:** Tasdiqlangan (brainstorming sessiyasi asosida)
**Muallif:** Muxammadali + Claude
**Bog'liq:** [2026-07-11-b-faza-settings-foundation-design.md](2026-07-11-b-faza-settings-foundation-design.md) §9 (B3 skelet); B1 (SettingsService, admin API, AES) tayyor.

## 1. Maqsad

Qosid main app'dan so'rovlarni **pull** qilish va javoblarni **save** qilish uchun ishlatadigan autentifikatsiyani (source kanali) sozlanadigan qilish. Hozir bu faqat **Basic** (login/parol, `SOURCE_USERNAME`/`SOURCE_PASSWORD`). B3 uning yoniga **API-KEY** va **OAuth2** qo'shadi, tur va credential UI'dan (B1 settings) boshqariladi. **So'rov-forwarding** (`HttpRequestService` per-request `oauth2_provider` → target API) TEGILMAYDI.

## 2. Kelishilgan qarorlar (savol-javob natijalari)

| Savol | Qaror |
|---|---|
| Yo'nalish | OUTBOUND (qosid → main app). Dastlab "B4/inbound" deb boshlandi, lekin foydalanuvchi izohlari doim outbound edi → B3. |
| Ko'lam | Faqat **source kanali** (Biruni pull + save) auth'i. So'rov-forwarding va per-request OAuth2 o'zgarmaydi. |
| Turlar | `basic` (hozirgi) \| `apikey` (header nomi + kalit) \| `oauth2` (client_credentials → Bearer). |
| Arxitektura | **Variant 1:** bitta `SourceAuthService`, config bo'yicha switch; oauth2 uchun token keshi. |
| API-key | Har chaqiruvda header'ga qo'shiladi. |
| OAuth2 kesh | Token Redis'da UI-sozlangan muddat davomida keshlanadi (har chaqiruvda yangilanmaydi); **per-request OAuth2 keshiga aralashmaydi** (alohida namespace). |

## 3. SourceAuthService + config

**Sozlama kalitlari** (B1 `SettingsService`da, UI-boshqariladigan; secret'lar AES):

| Kalit | Default | Secret |
|---|---|---|
| `gateway.source.auth.type` | `basic` | — |
| `gateway.source.auth.basic.username` | (SOURCE_USERNAME migratsiya) | — |
| `gateway.source.auth.basic.password` | (SOURCE_PASSWORD migratsiya) | **AES** |
| `gateway.source.auth.apikey.header` | `X-Api-Key` | — |
| `gateway.source.auth.apikey.value` | — | **AES** |
| `gateway.source.auth.oauth2.token-url` | — | — |
| `gateway.source.auth.oauth2.client-id` | — | — |
| `gateway.source.auth.oauth2.client-secret` | — | **AES** |
| `gateway.source.auth.oauth2.scope` | (bo'sh) | — |
| `gateway.source.auth.oauth2.cache-ttl-seconds` | (bo'sh → expires_in ishlatiladi) | — |

**`SourceAuthService.authHeaders() → Map<String,String>`** — har chaqiruvda `type`ni o'qiydi (dinamik):
- `basic` → `{"Authorization": "Basic " + base64(user:pass)}` (parol `getSecret`; mavjud `AuthUtil.generateBasicAuth` qayta ishlatiladi).
- `apikey` → `{<header>: <value>}`.
- `oauth2` → `{"Authorization": "Bearer " + token}` (§4).

**`BiruniClient` integratsiyasi:** hozirgi qattiq `AuthUtil.generateBasicAuth(...)` (pull + save'da) o'rniga `sourceAuthService.authHeaders()`ni chaqirib, qaytgan header(lar)ni qo'shadi. `BiruniClient` `SourceAuthService`ni inject qiladi. URI/timeout/JSON o'zgarmaydi.

## 4. OAuth2-for-source token kesh

**Izolyatsiya (eng muhim):** per-request kesh kaliti `oauth2:token:<provider>`. Source token **butunlay alohida namespace** — `source:oauth2:token`. Bu (1) provider keshiga aralashuvni va (2) "source" nomli haqiqiy provider bo'lsa to'qnashuvni oldini oladi.

**`SourceTokenService`:**
- `source:oauth2:token` Redis kalitini o'qiydi; mavjud+yaroqli bo'lsa qaytaradi (yangilamaydi).
- Miss/muddati o'tgan: settings'dan `ProviderProperties` (token-url/client-id/client-secret/scope) yig'ib, **mavjud `StandardOAuth2Client.getAccessToken(props)`** bilan client_credentials token oladi (fetch qayta ishlatiladi, per-request keshga tegmaydi), `source:oauth2:token`ga yozadi.
- **Kesh TTL:** `gateway.source.auth.oauth2.cache-ttl-seconds` (admin belgilaydi); xavfsizlik uchun `min(configured, token.expiresIn − margin)` — muddati o'tgan token uzatilmaydi. Sozlanmasa `expires_in − margin`.

## 5. Admin API + ulanishni tekshirish

B1 himoyalangan chain ostida (`/qosid/admin/api/**`):
- `GET /source-auth` — joriy config (secret'lar `***` maskalangan).
- `PUT /source-auth` — type + tanlangan tur maydonlari (secret'lar AES). Validatsiya: type ∈ {basic,apikey,oauth2}; oauth2 → token-url/client-id/client-secret bo'sh emas; apikey → header+value; basic → username/password.
- `POST /source-auth/test` — joriy (yoki yuborilgan) auth bilan Biruni pull URI'ga yengil so'rov, `{ok, httpStatus, message}`. OAuth2 bo'lsa avval token olishni ham sinaydi (token xatosini alohida ko'rsatadi).

## 6. Migratsiya / backward-compat

- `gateway.source.auth.type` default = `basic` — prod hozirgidek ishlaydi.
- B1 birinchi-startup migratsiyasi `basic.username`/`basic.password`ni `SOURCE_USERNAME`/`SOURCE_PASSWORD`dan bir marta DB'ga ko'chiradi (`password` AES → `SETTINGS_ENCRYPTION_KEY` mavjud bo'lsa; kalit yo'q bo'lsa basic-secret yozilmaydi, yaml fallback).
- `SourceAuthService` basic uchun: settings DB → bo'lmasa `GatewayProperties.getUsername/getPassword` (yaml) fallback → dev'da kalit'siz ham, migratsiyasiz eski deploy ham buzilmaydi.
- `AuthUtil.generateBasicAuth` saqlanadi (basic shoxida ishlatiladi).

## 7. Testlash

- `SourceAuthService` unit: basic (DB → Basic header), apikey (header+value), oauth2 (Bearer, SourceTokenService mock), noma'lum type → basic fallback, DB bo'sh → yaml fallback.
- `SourceTokenService`: kesh hit (yangilamaydi), miss → getAccessToken + `source:oauth2:token` yoziladi, TTL cap; **izolyatsiya testi** — kalit `source:oauth2:token` (≠ `oauth2:token:*`).
- Admin API (@SpringBootTest): GET/PUT (secret maskalash), validatsiya (oauth2 bo'sh → 400), test-connection (WireMock/mock: success+fail).
- Regressiya: mavjud 75 test yashil (default basic → BiruniClient o'zgarmaydi).
- Lokal smoke: default basic ko'tarilishi; apikey/oauth2'ga o'tkazib `/source-auth/test`.

## 8. Risklar

| Risk | Yumshatish |
|---|---|
| Prod Basic auth buzilishi | Default basic + yaml fallback; idempotent migratsiya; regressiya testi |
| Source OAuth2 per-request keshga aralashishi | Alohida namespace `source:oauth2:token`; izolyatsiya testi |
| Muddati o'tgan source token | Kesh TTL `expires_in − margin` bilan cheklangan |
| AES kalit yo'q (dev) → basic secret yozilmaydi | yaml fallback; startup WARN |
| test-connection real so'rov | Yengil GET, qisqa timeout, admin-only |

## 9. Bog'liqlik

B1 (SettingsService.get/getSecret/set, admin API + himoyalangan chain, AES cipher) — **tayyor**. Mavjud `StandardOAuth2Client`, `OAuth2TokenRedisCache` (fetch qayta ishlatiladi; kesh alohida). Redis (mavjud).

## 10. Ko'lamdan tashqarida

- So'rov-forwarding (`HttpRequestService` per-request OAuth2 → target API).
- Inbound auth (client → qosid /queue himoyasi) — bu B4, alohida.
- Bir nechta source target (bitta global config).
- OAuth2 refresh_token grant source uchun (client_credentials yetarli).
