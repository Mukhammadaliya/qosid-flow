# C-faza: Tenant Rename + Chegara-Tarjima — Dizayn hujjati

**Sana:** 2026-07-11
**Holat:** Tasdiqlangan (brainstorming sessiyasi asosida)
**Muallif:** Muxammadali + Claude
**Bog'liq:** [2026-07-10-qosid-flow-upgrade-design.md](2026-07-10-qosid-flow-upgrade-design.md) (asosiy upgrade spec, C-faza addendum §7a)

## 1. Maqsad

`company_id`/`filial_id` tushunchalarini ichki `tenant_id`/`extra_tenant_id`ga o'tkazish — **lekin main app'ga (Biruni/bmb) tegmasdan**. Biruni bmb moduli `company_id` yuboradi va qaytishda ham shuni kutadi; agar wire kontraktni o'zgartirsak, bmb buziladi. Yechim: ichki yadro to'liq `tenant` nomida bo'ladi, chegaralarda (main app bilan gaplashadigan joylarda) sozlanadigan tarjima qatlami turadi.

## 2. Kelishilgan qarorlar (savol-javob natijalari)

| Savol | Qaror |
|---|---|
| "UI mapping" nima | bmb `company_id`/`filial_id` yuboradi; wire kontrakt buzilmasligi uchun maydon nomlari UI'da sozlanadigan bo'ladi (default `company_id`/`filial_id`). |
| Faza tartibi | **Avval B-faza, keyin C.** Mapping UI'da sozlanadi → Settings Service (B) oldin bo'lishi shart. |
| Rename chuqurligi | **Variant A + boundary translation:** main app o'z kaliti bilan yuboradi; ilk handle qiladigan joyda (ingress) ichki `tenant`ga rename; main app'ga qaytadigan joylarda (egress) teskari o'girish. Ichki yadro 100% tenant nomida. |
| Tarjima arxitekturasi | **Variant 1:** aniq `TenantMappingService` (toInternal/toExternal), har chegarada ochiq chaqiriladi. Jackson-magic (V2) va ikki-model (V3) rad etildi. |
| `extra_tenant_id` roli | Ixtiyoriy; saqlanadi, filtrlash/statistikada ishlatiladi; idempotency/partition **kalitlariga kirmaydi**. |
| REST `/queue` tenant | Autentifikatsiyalangan principal'dan (B-faza auth). `companyId=0` qat'iy qo'yish yo'qoladi. |
| Qiymat mapping | Ko'lamdan tashqarida (`100`→`"ACME"` kabi emas). |

## 3. Boundary xaritasi

```
  Biruni/bmb                         qosid-flow (ichki: tenant)              main app APIs
  (company_id/filial_id)
       │
       │  pull JSON {company_id, filial_id, ...}
       ▼  [INGRESS: toInternal — sozlangan kalit → tenantId/extraTenantId]
  BiruniClient.pullRequests ───────► RequestMessage {tenantId, extraTenantId, ...}
                                          │
  REST client ──API key/OAuth2──►  ProxyController
       (principal.tenantId)         [INGRESS: principal → tenantId]
                                          │
                                     ── ichki pipeline (Kafka/Redis/CB) tenantId bilan ──
                                          │
       ◄──────────────────────────  HttpRequestService ──► tashqi API
                                          │
       save JSON {company_id, ...}        ▼
  BiruniClient.saveResponse ◄──── [EGRESS: toExternal — tenantId → sozlangan kalit]
  OAuth2ProviderFetcher     ◄──── [EGRESS: toExternal (company_id body)]
```

**Ingress (main app → gateway, tenant'ga rename):**
- Biruni pull — `BiruniClient.pullRequests`: JSON'dagi sozlangan kalit (`company_id`) → `tenantId`.
- REST proxy — `ProxyController`: tenant autentifikatsiyalangan principal'dan (JSON maydonidan EMAS).

**Egress (gateway → main app, teskari o'girish):**
- Biruni save — `ResponseSaveRequest` / `BiruniClient.saveResponse`: `tenantId` → sozlangan tenant kaliti (`company_id`). **`extra_tenant` egress'da qaytarilmaydi** — hozirgi save faqat `company_id`+`request_id` bilan korrelatsiya qiladi, `filial_id` kutmaydi; qo'shsak bmb'ni kutilmagan maydon bilan xavf ostiga qo'yamiz.
- OAuth fetch — `OAuth2ProviderFetcher.fetchFromOracle`: `company_id` body sozlangan tenant kaliti bilan (extra yo'q).

**Ichki (rename maqsadi, main app yo'q):** Redis kalitlari, Kafka key, metrics teglari, DLQ, status API, consumer'lar, `HttpRequestService`, `RequestValidator`.

## 4. Ichki rename

Butun ichki kod `companyId`→`tenantId`, `filialId`→`extraTenantId` (≈15 fayl: `RequestMessage`, `ResponseMessage`, `DlqMessage`, `RequestState`, `Request`, `Response`, `ResponseSaveRequest`, ikkala request consumer, `OracleResponseConsumer`, `HttpRequestService`, `ProxyController`, `RequestValidator`, `OAuth2ProviderService`, `OAuth2ProviderFetcher`, `TestController`).

- `compositeId` = `tenantId:requestId` — **format o'zgarmaydi** (qiymat o'sha, faqat manba maydon nomi). Redis kalitlari `request:state:{tenantId}:{requestId}`, lock, token-evicted — barchasi.
- `extraTenantId` kalitlarga kirmaydi.
- Modellardagi `@JsonProperty("company_id")` wire-binding'lari olib tashlanadi — wire nomlash endi `TenantMappingService`da (chegarada), model ichki nom bilan qoladi.

**Migratsiya YO'Q:** qiymat o'zgarmaydi, eski `request:state:100:5` kaliti shu formatda qoladi, endi `tenantId=100` deb o'qiladi. Kafka'dagi eski xabarlar kerak bo'lsa Oracle'dan qayta yaratiladi (A-faza kabi maxsus himoya shart emas).

## 5. TenantMappingService

**Sozlanadigan maydon nomlari (B-faza `SettingsService`da):**

| Sozlama kaliti | Default | Ma'nosi |
|---|---|---|
| `gateway.tenant.field-name` | `company_id` | Biruni pull JSON'da tenant kaliti |
| `gateway.tenant.extra-field-name` | `filial_id` | Biruni pull JSON'da extra-tenant kaliti (ixtiyoriy) |
| `gateway.tenant.default-rest-tenant` | `0` | REST principal tenant bermasa (dev) fallback |

Default'lar `company_id`/`filial_id` — sozlama tegilmasa bmb hozirgidek ishlaydi.

**Interfeys:**
- `toInternal(JsonNode pullElement) → TenantPair(tenantId, extraTenantId)` *(ingress)*: sozlangan kalitlardan o'qiydi. `BiruniClient.pullRequests` raw JSON element'larni oladi; mapper tenant/extra ajratadi; qolgan maydonlar `ObjectMapper.convertValue` bilan `RequestMessage`ga o'tadi.
- `toExternal(tenantId) → Map<String,Object>` *(egress)*: sozlangan tenant kaliti bilan Map (`{"company_id":100}`). **Extra-tenant egress'da qaytarilmaydi** (§3 sababi) — shuning uchun `toExternal` faqat tenant'ni oladi. `extra-field-name` sozlamasi faqat INGRESS parsing'ni boshqaradi.

**Sozlama manbai:** `SettingsService.get(key, default)` (B-faza). C-faza B'dan keyin bajarilgani uchun mavjud.

## 6. REST `/queue` yo'lida tenant

- `ProxyController` `companyId=0L` qat'iy qo'yishni tashlaydi; tenant'ni autentifikatsiyalangan principal'dan oladi.
- **Kontrakt:** B-faza `AuthResult` `tenantId` (majburiy) va ixtiyoriy `extraTenantId` qaytaradi. Auth mexanizmi (API-key/OAuth2) B-fazada hal bo'ladi; C shunchaki "principal tenant'ni beradi" kontraktiga tayanadi.
- `extraTenantId` ixtiyoriy `X-Extra-Tenant-Id` header orqali (pass-through/statistika; kalitga kirmaydi; soxtalashsa xavf past).
- Principal tenant bermasa (NoOp dev profil) → `default-rest-tenant` (dev `0`). Prod'da auth majburiy, bu holat yuz bermaydi.

## 7. Testlash

- `TenantMappingService` unit: `toInternal` (default kalitlar; o'zgartirilgan kalitlar; extra yo'q; null qiymat), `toExternal` (faqat tenant kaliti chiqishi).
- Kontrakt/serialization: Biruni pull JSON → `RequestMessage` (tenant ajratildi); `ResponseSaveRequest` → JSON (`company_id` chiqadi).
- `ProxyController`: principal tenant → `RequestMessage`; `X-Extra-Tenant-Id` override.
- Regressiya: mavjud 28 test yashil qoladi (rename kompilyatsiya darajasida).
- Lokal smoke: Biruni pull → save aylanishi smartup24 bilan (default mapping bilan bmb ishlashini isbotlash).

## 8. Risklar

| Risk | Yumshatish |
|---|---|
| Rename keng diff — joy o'tkazib yuborilishi | Ghost-grep `companyId`/`company_id`; kompilyator qolganini ushlaydi |
| bmb save/OAuth format buzilishi | `toExternal` default `company_id`; kontrakt testi + smoke |
| Sozlama noto'g'ri kalit → pull'da tenant null | `toInternal` null → DLQ aniq xabar bilan (jim NPE emas); `RequestValidator` |
| B-faza `AuthResult`da tenant bermasligi | C B kontraktini talab qiladi; `default-rest-tenant` fallback |

## 9. B-fazaga bog'liqlik (majburiy)

C-faza B-fazadan **keyin** bajariladi. B'dan ikki narsa talab qilinadi (B-faza dizaynida qayd etilishi kerak):
1. `SettingsService.get(key, default)` — mapping kalitlarini o'qish.
2. `AuthResult.tenantId` (+ ixtiyoriy `extraTenantId`) — REST tenant.

## 10. Ko'lamdan tashqarida

- Qiymat mapping (`company_id=100` → `tenant_id="ACME"`).
- `extra_tenant_id`ni idempotency/partition kalitiga qo'shish.
- Bir nechta main app'ni bir vaqtda qo'llab-quvvatlash.
- REST auth mexanizmining o'zi (API-key/OAuth2 — B-faza).
- `extra_tenant`ni egress'da (Biruni save) qaytarish — hozir bmb kutmaydi; kelajakda kerak bo'lsa alohida qo'shiladi.
