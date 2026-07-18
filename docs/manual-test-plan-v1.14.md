# Manual test-plan — v1.14 (lokal, 2026-07-18)

Muhit: gateway :8090, ops :8092, SPA :5173, smartup24 :8080, docker infra (Redis/RedPanda/Loki/Prometheus).
Belgilar: `[x]` — o'tdi, `[!]` — xato topildi (izoh yozing), `[ ]` — hali qilinmagan, `[-]` — skip.

---

## A. Oracle (Biruni) oqimi — E1→E5

- [x] **A1. Oddiy so'rov to'liq sikli** — smartup24'da request yaratish → 5s ichida pull → COMPLETED, javob Biruni'ga saqlanadi. *(315 bilan o'tdi, idempotency skip ham ko'rindi)*
- [x] **A2. OAuth2 provider so'rovi (supplier22)** — provider-config fetch (Bearer) → supplier token → yuborish → save. *(315)*
- [ ] **A3. Parallel yuklama** — smartup24'da ketma-ket 5–10 ta request yaratish → hammasi COMPLETED, birortasi ikki marta yuborilmagan (log'da duplicate-skip bo'lishi normal, natija bitta).
- [ ] **A4. Muvaffaqiyatsiz maqsad → DLQ** — mavjud bo'lmagan URL'ga (masalan `http://localhost:9/xyz`) request yaratish → retry'lar (default 3) → `Sending to Oracle DLQ` → Biruni'da holat **F**. DLQ xabari Errors modulida ko'rinadi (F1 bilan bog'liq).
- [ ] **A5. Katta javob** — threshold'dan (1024 B) katta javob qaytaruvchi so'rov → RocksDB'ga yoziladi, Biruni'ga saqlanadi, `/status`da inline emas `resultUrl`.
- [ ] **A6. Pull dormant→uyg'onish** — (xohlasangiz) Endpointlar'dan base-url'ni bo'shatib saqlang → restart → log'da BITTA warn, xato-spam yo'q → qiymatni qaytarib saqlang → restart'siz (LIVE) pull tiklanadi.

## B. REST API oqimi (`/qosid/api/v1/queue/**`) — eng katta tekshirilmagan qism

- [ ] **B1. Oddiy proxy POST** →
  `curl -s -X POST http://localhost:8090/qosid/api/v1/queue/b/anor/mkw/warehouses\$export -H "Content-Type: application/json" -d '{}'`
  → `202` + `requestId` + polling URL → `GET /qosid/api/v1/requests/{id}/status` PENDING→COMPLETED → `GET .../result` to'liq javob, original Content-Type.
  *(Yo'l API proxy manzili (`http://localhost:8080/smartup24/b`) ostidagi real endpointga moslang.)*
- [ ] **B2. GET metodi ham proxy'lanadi** — `GET /qosid/api/v1/queue/<biror-GET-endpoint>` → 202 → natija.
- [ ] **B3. Inline vs resultUrl** — kichik JSON javob → `/status`da `result` inline; katta javob → `resultUrl`.
- [ ] **B4. Callback** — so'rovga `X-Callback-Url: http://localhost:8090/qosid/api/v1/callback` header qo'shish → tugagach gateway logida callback yuborilgani + qabul qilingani (o'zining callback receiver'iga).
- [ ] **B5. BiruniUpload (S3)** — `BiruniUpload: param` bilan multipart yuborish (JSON param + fayl) → S3 mock (`tools/s3-mock`) ishlayotgan bo'lsa fayl SHA bilan tushadi, `files` header bilan Biruni `run()` yo'li. S3 mock O'CHIQ holda → 3x retry → multipart fallback (Biruni DB'da `store_kind='D'`).
- [ ] **B6. Noma'lum request id** — `GET /qosid/api/v1/requests/0:999999/status` → 404 (500 emas).
- [ ] **B7. Hop-by-hop/gzip** — Postman'dan (default `Accept-Encoding: gzip`) so'rov → result buzilmagan plain body.

## C. Sozlamalar / stop-to-edit

- [x] **C1. UP rejim read-only** — qulflar, banner, PUT'lar 409. *(walkthrough)*
- [x] **C2. Stop → edit → start → avto-ulanish (login'siz)**. *(walkthrough)*
- [x] **C3. Endpointlar UI'dan** — union ro'yxat, saqlash, LIVE ta'sir. *(walkthrough)*
- [ ] **C4. Secret round-trip** — Source Auth'da client-secret'ni qayta kiritib saqlash → GET'da `***` → bo'sh qoldirib qayta saqlash → eski qiymat SAQLANADI (Sinash o'tishi bilan isbot).
- [ ] **C5. Umumiy Saqlash tugmasi** — 2-3 qatorni birga o'zgartirish → `Saqlash (3)` → bitta bosishda hammasi, "3 ta sozlama saqlandi".
- [ ] **C6. Audit** — Sozlamalar → Audit tab: bugungi o'zgarishlar ro'yxati (kim/qachon/eski-yangi, secretlar masked).

## D. Source auth variantlari

- [x] **D1. oauth2 + biruni client-type** — token mint + pull/save/provider-fetch Bearer. *(315)*
- [ ] **D2. basic'ka qaytarish** — type=basic + smartup24 login/parol → restart → pull ishlaydi (Bearer'siz). Keyin oauth2'ga qaytaring.
- [ ] **D3. Sinash tugmasi** — basic'da ham, oauth2'da ham to'g'ri natija; noto'g'ri parol bilan aniq xato xabari (500 emas).
- [ ] **D4. Token keshi** — Redis'da `source:oauth2:token` kaliti paydo bo'ladi (`docker exec redis redis-cli GET source:oauth2:token`); TTL bor; `DEL` qilsangiz keyingi pull yangi token oladi.

## E. Ops konsoli

- [x] **E1. Start/Stop + confirm + badge**. *(walkthrough)*
- [ ] **E2. Jonli SSE log** — Ops sahifasida gateway logi real-time oqadi; Pauza tugmasi to'xtatadi/davom ettiradi; gateway restart'ida oqim uzilib qayta ulanadi.
- [ ] **E3. Ops login rate-limit** — logout → 5 marta noto'g'ri parol → 6-chisida `429` xabari (keyin 60s kutib to'g'ri parol bilan kiriladi).
- [ ] **E4. Degraded rejim** — gateway STOP holatida brauzerni yangilash → login → to'g'ri /ops'ga tushadi, faqat Ops+Sozlamalar ochiq, banner bor.

## F. Errors moduli (Loki)

- [ ] **F1. DLQ sahifasi** — A4'dan tushgan DLQ yozuvi ko'rinadi (request id, sabab); drawer ochiladi.
- [ ] **F2. JAR log** — bugungi gateway loglari, filtr/qidiruv ishlaydi.
- [ ] **F3. Docker log** — konteyner tanlash (redis/redpanda) → loglar keladi.
- [-] **F4. AI tahlil** — default OFF; sozlamoqchi bo'lsangiz `gateway.ai.*` to'ldirib bitta xatoni analyze qiling.

## G. Statistics (Prometheus)

- [ ] **G1. KPI kartalari** — bugungi testlardan keyin so'rovlar soni/foizlar > 0.
- [ ] **G2. Grafiklar** — funnel, throughput, errors, p95 latency — A3/A4 dan keyin ma'noli qiymatlar.

## H. Chidamlilik (ixtiyoriy, buzuvchi — oxirida qiling)

- [ ] **H1. Stop paytida kelgan so'rovlar** — gateway'ni STOP qiling → smartup24'da 2-3 request yarating → START → hammasi avtomatik pull qilinib COMPLETED bo'ladi (confirm-modal va'dasi).
- [ ] **H2. Circuit Breaker** — A4 turidagi (o'chiq server) so'rovlardan ketma-ket 5-10 ta → log'da CB OPEN → shu domenga yangi so'rovlar darhol 503 (kutmasdan) → server "qaytgach" (boshqa ishlaydigan domen bilan) normal.
- [ ] **H3. RedPanda restart** — `docker restart redpanda` → gateway qayta ulanadi, so'rovlar yo'qolmaydi.
- [ ] **H4. Redis restart** — `docker restart redis` → qisqa uzilishdan keyin normal; cheksiz retry siklі YO'Q (safety net).

## I. Admin / xavfsizlik

- [ ] **I1. Parol o'zgartirish** — UI'dan → sessiya tugaydi → yangi parol bilan ikkala backend'ga login. *(Ops env'dagi eski parol bilan hech narsa buzilmasligi kerak — shared H2.)*
- [ ] **I2. Gateway login rate-limit** — 5 xato → 429.
- [ ] **I3. CSRF himoyasi** — cookie'siz curl bilan `PUT /ops/api/settings/...` → 403.

---

**Yakun mezoni:** A–G to'liq yashil (H/I ixtiyoriy) → `debug` push + v1.15.0 release PR.
