# A-loyiha: Settings tahrirlanuvchanligi + color-coding — Dizayn hujjati

**Sana:** 2026-07-15
**Holat:** Tasdiqlangan (brainstorming sessiyasi asosida)
**Muallif:** Muxammadali + Claude
**Bog'liq:** B1 Settings control-plane ([2026-07-11 B-faza spec'lari]), `SettingsService`, `SettingsMigrationRunner`. Bu A-loyiha; keyingi kichik-loyiha **B — Ops konsoli** (real-time loglar + JVM start/stop/restart) alohida spec bilan.

## 1. Maqsad

Admin UI'dagi `gateway.*` sozlamalarini **haqiqatan tahrirlanadigan** va **ta'sir qiladigan** qilish, hamda har maydonning holatini **rang bilan** ajratib ko'rsatish. Hozirgi ikki uzilish tuzatiladi: (a) endpoint sozlamalari (`gateway.source.*`) DB'da yo'q → UI ko'rsatmaydi; (b) `gateway.api.base-url` ko'rinadi-yu runtime `ApiProperties` (startup-bound)'dan o'qigani uchun o'zgarish ta'sir qilmaydi.

## 2. Kelishilgan qarorlar

| Savol | Qaror |
|---|---|
| Ta'sir modeli | **Gibrid** — 3 sinf: `live` (jonli, restart'siz), `requires-restart` (saqlanadi, restart'da qo'llanadi), `read-only` (env/bootstrap, bloklangan) |
| Ko'lam | **Barcha `gateway.*` sozlamalari** tartibli, reyestr-boshqariladigan |
| Auto-restart | **YO'Q** — 🟡 saqlangach faqat "restart kerak" bayrog'i; user o'zi (B-loyiha tugmasi orqali) restart qiladi |
| Read-only ko'rsatish | UI'da **kulrang + qulf** bilan ko'rsatiladi (yashirilmaydi) |
| Color-coding | 🟢 live / 🟡 requires-restart / ⚪ read-only |

## 3. Arxitektura (uch qatlam)

```
                    ┌─────────────────────────────────────────┐
   boot vaqti  ───► │ SettingsEnvironmentPostProcessor (YANGI) │
                    │  H2 settings-DB → Environment PropertySrc │  → barcha @ConfigurationProperties
                    │  (env-var'dan past, application.yml'dan   │    boot'da DB override oladi
                    │   yuqori; AES-sirlar deshifrlanadi)        │  → HAR sozlama "restart-effective"
                    └─────────────────────────────────────────┘
   runtime     ───► SettingsService.get(key)  ← jonli o'qish (per-use)
                    ProxyController / BiruniClient endpoint'lari  → 🟢 restart'siz qo'llanadi
   metama'lumot ──► SettingsRegistry (YANGI): key→{group,label,type,effectClass}
                    → UI color-coding + backend validatsiya
```

**1) `SettingsEnvironmentPostProcessor` (yangi)** — `org.springframework.boot.env.EnvironmentPostProcessor`, `spring.factories` orqali ro'yxatdan o'tadi. Boot'da:
- Env'dan `SETTINGS_DB_PATH` + `SETTINGS_ENCRYPTION_KEY` oladi (bootstrap qiymatlar — ular o'zi read-only).
- H2 settings-DB'ga to'g'ridan-to'g'ri JDBC bilan ulanadi, `settings` jadvalidagi barcha qatorlarni o'qiydi (AES-shifrlangan qiymatlarni deshifrlaydi).
- `MapPropertySource` sifatida `Environment`'ga qo'shadi, **`application.yml`'dan yuqori, lekin systemEnvironment/commandLine'dan past** joyga (`addBefore`/`addAfter` bilan aniq tartib). Shunday qilib DB yaml-default'ni override qiladi, biroq infra env-var'lar (portlar, Kafka, Redis) hamon g'olib — read-only'lar env-boshqaruvda qoladi.
- DB o'qib bo'lmasa (birinchi boot, DB yo'q): jimgina o'tkazib yuboradi (yaml default'lar ishlaydi) — never-fail.

**2) Jonli o'qish (per-use)** — quyi iste'mol nuqtalari `SettingsService.get(key, fallback)`'ga o'tadi:
- `ProxyController` → `gateway.api.base-url` (endpoint string, CB/retry/ack mantig'iga tegilmaydi)
- `BiruniClient` → `gateway.source.base-url`, `.request-pull-uri`, `.response-save-uri`
- `gateway.response.threshold-size` iste'mol nuqtasi
- (Allaqachon jonli: `gateway.loki.url`, `.prometheus.url`, `gateway.tenant.*`, `gateway.source.auth.*`, `gateway.ai.*`)

**3) `SettingsRegistry` (yangi)** — har ma'lum kalitning metama'lumoti. UI va validatsiya uchun yagona manba.

## 4. Sozlamalar tasnifi (reyestr)

| effectClass | Kalitlar |
|---|---|
| 🟢 **live** | `gateway.api.base-url`; `gateway.source.base-url`, `.request-pull-uri`, `.response-save-uri`; `gateway.response.threshold-size`; `gateway.loki.url`, `gateway.prometheus.url`; `gateway.tenant.field-name`, `.extra-field-name`, `.default-rest-tenant`; `gateway.source.auth.*`; `gateway.ai.base-url`, `.model` (`.api-key` — live+secret) |
| 🟡 **requires-restart** | `gateway.retry.*` (max-attempts, backoff); `gateway.http.*` (connect/read timeout, pool); `gateway.polling.enabled`, `.interval-ms`, `.batch-size`; resilience4j CB sozlamalari (agar chiqarilsa) |
| ⚪ **read-only** | `SETTINGS_ENCRYPTION_KEY`, `SETTINGS_DB_PATH`; server portlari (8090/8091); Kafka bootstrap; Redis host/port; RocksDB yo'li; Spring profil |

**Aniq to'liq ro'yxat** implementatsiyada `application.yml`'ni to'liq sanab qat'iylashtiriladi; yuqoridagi — tasdiqlangan tamoyil va asosiy kalitlar. Reyestrda bo'lmagan `gateway.*` kalit default `requires-restart` sifatida qaraladi (xavfsiz taxmin).

> **Implementatsiya og'ishi (final review, KEEP):** `gateway.response.threshold-size` yuqoridagi jadvalda 🟢 live deb rejalangan edi, lekin implementatsiyada **REQUIRES_RESTART** deb tasniflandi — u `StatusController`/`CallbackService`'da `@Value` orqali boot-time bog'lanadi va `SettingsService`'dan jonli o'qishga o'tkazilmadi. Reyestr haqiqatni aks ettirishi kerak, shuning uchun 🟡 saqlanadi (kod live qilinmaydi). Shuningdek, `gateway.polling.batch-size` reyestrdan olib tashlandi — uni hech qanday kod o'qimaydi (`RequestPuller` faqat `polling.enabled` + `polling.interval-ms` ishlatadi).

## 5. Restart-pending bayrog'i

- Settings-DB'da meta kalit: `gateway._meta.restart-pending` (boolean).
- 🟡 (`requires-restart`) sozlama PUT bilan yozilganda → bayroq `true`.
- Boot'da (PostProcessor DB qiymatlarini Environment'ga qo'llaganidan keyin) → bayroq `false` (startup runner tozalaydi).
- `GET /settings/registry` javobida qaytadi → UI banner shartli ko'rsatadi.

## 6. Backend API o'zgarishlari (`SettingsController`)

- **`GET /qosid/admin/api/settings/registry`** (yangi) → `{ groups: [{name, settings: [{key, label, type, effectClass, value, secret}]}], restartPending: boolean }`. Sirlar masklangan (`***`).
- **`PUT /qosid/admin/api/settings/{key}`** (mavjud, kengaytiriladi):
  - Reyestrga qarshi validatsiya: kalit `read-only` bo'lsa → `400` (tahrirlanmaydi); tur mos kelmasa → `400`.
  - Saqlaydi (`SettingsService.set`). Agar `requires-restart` → restart-pending = true.
  - Mavjud tenant-validatsiya (field-name blank, default-rest-tenant raqam) saqlanadi.
- Eski `GET /settings` (flat ro'yxat) — o'rnini registry oladi; kerak bo'lsa qoldiriladi (regressiya uchun buzilmaydi).

## 7. Frontend (`frontend/src/pages/settings/`)

- `AdvancedSettingsPage.tsx` → **`SettingsPage`ning reyestr-boshqariladigan** guruhlangan color-coded ko'rinishi (yoki yangi `GeneralSettingsPage`). Akkordeon guruhlar: Endpointlar, Retry/HTTP, Polling, Tenant, Observability, AI, Source-auth, Infratuzilma.
- Har maydon: effect-class bo'yicha rang-badge/nuqta (🟢/🟡) + label + input; read-only → disabled + qulf 🔒 + kulrang.
- **Legenda** yuqorida; **restart-pending banner** (`restartPending` true bo'lsa) — "N sozlama o'zgardi, qo'llash uchun restart kerak" + B-konsolga havola matni.
- Saqlash **per-maydon** (hozirgi `putSetting` uslubi): 🟢 → toast "qo'llandi"; 🟡 → toast "saqlandi, restart kerak" + banner yoqiladi.
- `frontend/src/api/settings.ts` → `getRegistry()`, response `restartPending`; `putSetting` javobiga effect-class asosida UI reaktsiyasi.
- i18n (`uz.json`): `settings.legend.live/restart/readonly`, `settings.restartPending`, guruh nomlari, effect-badge matnlari.

## 8. Testlash

- **Backend:**
  - `SettingsEnvironmentPostProcessor`: DB qatori bor → Environment'da qiymat DB'niki; DB yo'q → yaml default (never-fail); env-var DB'dan ustun (read-only himoya); AES-sir deshifrlanadi.
  - `SettingsRegistry`: har kalit to'g'ri effectClass; noma'lum `gateway.*` → `requires-restart`.
  - `SettingsController`: `GET /registry` guruh+qiymat+effectClass+restartPending; `PUT` read-only → 400; `PUT` requires-restart → pending=true; `PUT` live → saqlanadi, pending o'zgarmaydi.
  - Jonli o'qish: `gateway.api.base-url` DB'da o'zgarsa `ProxyController` yangi manzilni oladi (mock/integ); `BiruniClient` source endpoint jonli.
  - Restart-pending boot'da tozalanadi (startup runner testi).
- **Frontend (Vitest):** reyestr-render (guruhlar+maydonlar); effect-class rang-badge; read-only disabled+qulf; restart-pending banner shartli; save oqimi (live vs restart toast).
- **Regressiya:** loki/prometheus/tenant/AI/source-auth sozlamalari ishlaydi; **pipeline (E1-E5) tegilmaydi** — mavjud testlar yashil.
- **Jonli smoke:** UI'da `gateway.api.base-url` o'zgartir → `/queue` proxy yangi manzilga **restart'siz** yuboradi (yoki loki.url o'zgartir → Errors yangi Loki'ga). 🟡 (retry) o'zgartir → banner chiqadi, restart'dan keyin qo'llanadi.

## 9. Xavfsizlik / ta'sir

- **Pipeline'ga minimal tegish:** faqat 2 endpoint iste'mol nuqtasi string-o'qishga o'tadi (ProxyController, BiruniClient) — CB/retry/idempotency/ack o'zgarmaydi (SAFETY 1-5 buzilmaydi).
- **PropertySource faqat boot-vaqt qatlami** — runtime hot-path'ga tegmaydi; DB o'qib bo'lmasa never-fail (yaml default).
- **Read-only himoya:** infra env-var'lar DB'dan ustun; UI va backend read-only kalitlarni rad etadi (sozlash orqali port/kalit o'zgartirib bo'lmaydi).
- **Sirlar:** AES-shifrlangan (B1), GET'da masklangan.
- **Admin-auth:** mavjud `/qosid/admin/api/**` chain (B1) ostida, o'zgarmaydi.

## 10. Ko'lamdan tashqarida

- JVM start/stop/restart va real-time loglar → **B-loyiha** (Ops konsoli), alohida spec.
- Runtime hot-reload of pipeline beans (retry/CB/http jonli) — ataylab yo'q (xavfli); restart-model afzal.
- Sozlamalar tarixi/versiyalash — B1 audit'dan tashqari yo'q.
- Yangi ixtiyoriy `gateway.*` kalit qo'shish UI'si (reyestrsiz) — hozir yo'q; reyestr qat'iy.
