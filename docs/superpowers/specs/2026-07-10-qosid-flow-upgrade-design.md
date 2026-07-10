# Qosid Flow Upgrade — Dizayn hujjati

**Sana:** 2026-07-10
**Holat:** Tasdiqlangan (brainstorming sessiyasi asosida)
**Muallif:** Muxammadali + Claude

## 1. Maqsad

Qosid Flow'ni istalgan Main app bilan ishlaydigan, UI orqali sozlanadigan, xavfsiz integratsiya vositasiga aylantirish. Beshta talab:

1. Tizimga bog'liq bo'lmagan ishlash — Main app'lar o'z endpointlarini UI orqali sozlaydi.
2. `company_id` → `tenant_id`, `filial_id` → `extra_tenant_id` o'tishi (Main app'lar moslab beradi).
3. Xavfsizlikni mustahkamlash.
4. Admin UI: sozlamalar, xatoliklar, statistika, Loki loglar, AI tahlil.
5. Tahlilda topilgan barcha kritik muammolarni tuzatish.

## 2. Kelishilgan qarorlar (savol-javob natijalari)

| Savol | Qaror |
|---|---|
| Mustaqillik ko'lami | Istalgan Main app UI orqali endpoint sozlab ulanadi. **Plugin qatlami hozircha qilinmaydi** — Biruni kod joyida qoladi, faqat sozlamalari runtime'ga chiqadi. |
| Bir vaqtda nechta Main app | Faqat bitta (deployment boshiga). |
| Tenant o'tish | Qattiq o'tish, backward compatibility yo'q. Kafka'dagi eski xabarlar himoyasiz — kerak bo'lsa Oracle'dan qayta yaratiladi. |
| Kiruvchi auth (Main app → Qosid) | API key (`X-Api-Key`), UI'da boshqariladi. |
| Chiquvchi auth (Qosid → Main app) | Mavjud OAuth2 tizimi qoladi, providerlar UI'dan sozlanadi. |
| Admin UI kirish | Bitta admin akkaunt (login/parol, bcrypt), rolsiz. |
| Sozlamalar ombori | Embedded H2 (fayl rejimi). |
| UI deploy | Alohida React SPA konteyner (nginx), umumiy docker-compose'da. |
| Statistika manbai | Prometheus HTTP API (backend orqali proxy), Grafana embed emas. |
| `extra_tenant_id` roli | Ixtiyoriy maydon; saqlanadi, filtrlash/statistikada ishlatiladi, idempotency/partition kalitlariga kirmaydi. |
| UI uslubi | Smartup palitrasi (Typesense prezentatsiyasidan): `#1A2333`, `#0095D9`, `#2EC27E`, `#6B7689`, `#F4F6F9`, `#E6EDF3`, `#CFD8E3`. |

## 3. Maqsadli arxitektura

```
                        ┌─────────────────────────────────────────────┐
                        │              docker-compose                  │
Main App ──API key──▶   │  qosid-flow (backend, 8090)                 │
  (kiruvchi)            │   ├── Pipeline yadro (Kafka→CB→RocksDB)     │
Main App ◀──OAuth2───   │   ├── SettingsService ◀── H2 embedded DB    │
  (chiquvchi)           │   └── Admin API (/qosid/admin/api/**)       │
Admin (brauzer) ──▶     │  qosid-ui (React SPA, nginx)                │
                        │  RedPanda │ Redis │ Prometheus │ Grafana    │
                        │  Loki + Promtail (yangi) │ node_exporter    │
                        └─────────────────────────────────────────────┘
```

- Backend bitta jar: pipeline + settings + admin API.
- Konfiguratsiya ikki qatlam: **bootstrap** (yaml/env: portlar, Kafka/Redis/H2 manzillari) va **runtime** (H2, UI'dan, restart'siz kuchga kiradi).
- Xavfsizlik chegaralari: `/qosid/api/**` — API key; `/qosid/admin/api/**` — admin sessiya; actuator (8091) — faqat ichki tarmoq.
- Pipeline yadrosi (consumer'lar, CB, idempotency, RocksDB) bu loyihada strukturaviy o'zgarmaydi.

## 4. Fazalar

Bajarish tartibi: **A → C → B → D1 → D2 → D3 → D4**. Har faza alohida spec/plan bilan batafsillashtiriladi, alohida release qilinadi (`debug` → PR → `master`).

### A-faza — Kritik tuzatishlar

2026-07-10 dagi to'liq kod tahlili topilmalari asosida:

**Xavfsizlik:**
1. `application.yml`dan Telegram token/chat-id va Biruni credential defaultlarini olib tashlash (bo'sh default). *Kontekst: eski bot tokeni repoda ochiqligi tufayli o'g'irlanib, Telegram tomonidan muzlatilgan (2026-07-10 hodisasi) — yangi @qosid_alert_bot faqat prod `.env`da.*
2. SSRF himoyasi: `CallbackUrlValidator` — faqat http/https, private/link-local IP bloklash (10.x, 172.16-31.x, 192.168.x, 127.x, 169.254.x), ixtiyoriy allowlist. Yaroqsiz URL → 400.
3. `GlobalExceptionHandler.handleGeneric` — mijozga umumiy xabar + correlation id, tafsilot faqat logda.
4. `X-Forwarded-For` faqat trusted-proxy ro'yxatidan ishoniladi.

**Ishonchlilik:**
5. Redis-down'da `isCompleted`/`tryLock` xatosi → xabar ack qilinmaydi (nack/re-queue), yo'qolmaydi. `Integer.MAX_VALUE` safety net saqlanadi.
6. `HttpRequestService.sendRequest` boshida method validatsiyasi — sinxron throw o'rniga error-response + DLQ.
7. `RocksDBClient.putResponse` xatoni yutmaydi — saqlash muvaffaqiyatsiz bo'lsa `hasResult=false`, metrika + log.
8. Ikkala Redis lock'ga (request state, OAuth2) owner-token + compare-and-delete release.
9. `BiruniOAuth2Client`ga null-check'lar (StandardOAuth2Client uslubida).
10. Mayda: DLQ sababini to'g'ri yozish (timeout vs too-large), TelegramNotificationService'ga timeout, BiruniClient'ga connect-timeout, `replaceFirst` regex o'rniga oddiy prefix-strip.

Har tuzatish SAFETY-RULES checklist'idan o'tadi; `ai-context/` yangilanadi. Tuzatilgan har yo'l uchun unit test — test poydevori shu yerda boshlanadi.

### C-faza — Tenant rename

- Modellar (`RequestMessage`, `ResponseMessage`, `DlqMessage`, `RequestState`, `ResponseSaveRequest`, `Request`, `Response`): `companyId` → `tenantId`, yangi ixtiyoriy `extraTenantId`.
- Kalitlar: `compositeId` = `tenantId:requestId`; Redis kalitlari `request:state:{tenantId}:{requestId}` va sh.o'. `extraTenantId` kalitlarga kirmaydi.
- Kontraktlar: Biruni pull/save va OAuth provider fetch'da `company_id` → `tenant_id`, yangi `extra_tenant_id`; REST proxy'da `X-Tenant-Id` header (majburiy), ixtiyoriy `X-Extra-Tenant-Id`. B-fazadan keyin API key kontekstiga bog'langan tenant header'ni almashtirishi mumkin bo'ladi.
- Qattiq o'tish: Main app tayyor bo'lganda bitta release. Kafka'dagi eski xabarlar uchun maxsus himoya qilinmaydi (qayta yaratish mumkin). Redis'dagi eski kalitlar TTL bilan o'zi o'ladi.
- `ai-context/` to'liq yangilanadi, ghost-reference grep bilan tekshiriladi.

### B-faza — Settings Service, H2, API key

**H2 sxemasi** (`./data/settings.mv.db`):
- `settings` — key-value (JSON qiymat, versiya, o'zgargan vaqt).
- `api_keys` — nomi, `key_hash` (SHA-256), yaratilgan/revoke/oxirgi ishlatilgan vaqt.
- `oauth2_providers` — nomi, type (biruni/standard), token_url, client_id, client_secret (AES shifrlangan), scope.
- `admin_account` — bitta yozuv (login, bcrypt hash, oxirgi kirish).
- `settings_audit` — kim, qachon, eski→yangi.

**SettingsService:**
- In-memory kesh + DB; birinchi ishga tushishda yaml qiymatlari DB'ga ko'chiriladi; DB'da yo'q bo'lsa yaml default.
- Restart'siz: upstream base-url, pull/save URI, retry/timeout, threshold, Telegram, S3, content-type ro'yxati, polling.
- Restart talab (UI ogohlantiradi): Kafka/Redis manzillari, portlar, partitionlar.
- OAuth2 providerlar: birinchi manba DB, mavjud Oracle fetch fallback bo'lib qoladi.

**API key:**
- `ApiKeyAuthorizationProvider` (mavjud SPI orqali); NoOp faqat dev profil.
- `X-Api-Key` header, format `qf_<32 belgi>`, faqat yaratilganda ko'rsatiladi.
- So'rov state'ida `apiKeyId`; `/status`, `/result` faqat o'sha kalitga ochiq. Oracle-source oqim API key talab qilmaydi.

**Admin API asosi** (`/qosid/admin/api/**`): login (sessiya cookie), settings/api-keys CRUD, audit; CSRF + login rate-limit.

### D-faza — Admin UI (4 bosqich)

**Umumiy:** React + Vite SPA, alohida nginx konteyner, faqat `/qosid/admin/api`ga proxy. Smartup palitrasi (2-bo'limdagi jadval).

**D1 — Sozlamalar:** guruhlangan formalar (Upstream, OAuth2, Retry & Timeout, Telegram, S3, Xavfsizlik, Polling); har maydonda tavsif + default + "restart kerak" belgisi; API key boshqaruvi; audit tarixi; "ulanishni tekshirish" tugmalari (upstream, OAuth, S3, Telegram test).

**D2 — Xatoliklar:**
- DLQ: DLQ consumer'lar xabarni H2'ga ham yozadi (30 kun retention). UI: ro'yxat (vaqt, tenant, request id, sabab, errorSource), filtr, detal, **replay** (request topic'iga qaytarish).
- Server xatolari: Loki'dan `level=ERROR`.
- Ichki servislar: `SystemHealthIndicator` + actuator asosida jonli panel — Kafka/Redis/RocksDB/upstream, har CB holati, consumer lag, thread pool.

**D3 — Statistika + Loki:**
- Backend Prometheus'dan so'raydi (`/qosid/admin/api/stats/*`; Prometheus tashqariga ochilmaydi). Ko'rsatkichlar: message oqimi (success/fail), xatolik chastotasi (errorSource kesimida), latency, CB ochilishlari, restart chastotasi (`process_start_time_seconds`), server yuklamasi (node_exporter qo'shiladi), Kafka lag. Oraliqlar: 1h/24h/7d/30d.
- Loki + Promtail compose'ga qo'shiladi; Promtail `gateway-service` journald'ni yig'adi. UI: log qidiruv (vaqt, daraja, matn, request-id trace).

**D4 — AI tahlil (ixtiyoriy modul):**
1. "Xatoni tushuntir" — DLQ yozuv/ERROR log + Loki konteksti + metadata → Claude API → o'zbekcha root-cause gipoteza.
2. Kunlik digest — 24h xatolarni klasterlash, hisobot (Telegram'ga ham).
3. Trend ogohlantirishlar (anomaliya izohi).
AI API kaliti sozlamalarda; o'chirilgan bo'lsa modul ko'rinmaydi.

**Qo'shimcha:** request qidiruv (id/tenant, holat + timeline), test-so'rov vositasi, sozlamalar export/import (JSON).

## 5. Testlash strategiyasi

- A-faza: tuzatilgan har yo'l uchun unit test (JUnit 5) — poydevor.
- B-faza: SettingsService/ApiKey unit + H2 integratsiya.
- C-faza: kontrakt/serialization testlari.
- D-faza: admin API testlari + UI smoke (Playwright).
- Pipeline'ga tegilgan joylar: Testcontainers (Kafka, Redis).
- CI: `build.yml`ga `mvn verify` qo'shiladi.

## 6. Risklar

| Risk | Yumshatish |
|---|---|
| C-faza Main app bilan sinxron bo'lishi kerak | Bitta kelishilgan release oynasi; gateway tomoni oldindan tayyor turadi |
| Runtime sozlamalar noto'g'ri qiymat bilan buzilishi | Validatsiya + audit + eski qiymatga qaytarish (rollback) UI'da |
| H2 fayl korrupsiyasi | Kunlik backup (fayl nusxasi), settings export |
| DLQ replay noto'g'ri ishlatilishi | Tasdiq dialogi, replay ham audit'ga yoziladi |
| Loki disk sarfi | Retention limit (masalan 14 kun), compose'da volume chegarasi |

## 7. Ko'lamdan tashqarida (out of scope)

- Plugin/SPI qatlami (kelajakka qoldirildi — user qarori).
- Bir nechta Main app'ga bir vaqtda xizmat.
- Eski nomlar (`company_id`) bilan moslik.
- Multi-user / rollar tizimi (bitta admin).
- AlertManager→Telegram kanalini qayta yoqish (alohida ops qarori).
