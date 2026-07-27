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

## B. REST API oqimi (`/qosid/api/v1/queue/**`) — 2026-07-18 echo-server + real smoke bilan o'tkazildi

- [x] **B1. Oddiy proxy POST** — 202 + requestId → COMPLETED, inline result. Real smoke: `/queue/biruni/bmb/requests$get_oauth_provider_data` Bearer bilan smartup24'dan javob oldi.
- [x] **B2. GET metodi ham proxy'lanadi** — ✓. **BUG topildi va tuzatildi:** query-string `?`siz yopishtirilardi (`/smalla=1`) — `HttpRequestService.buildFullUrl` separator qo'shadigan qilindi (+4 unit test).
- [x] **B3. Inline vs resultUrl** — kichik → inline; 6.2KB → `resultUrl`, `/result` to'liq body + `application/json`.
- [x] **B4. Callback (SSRF himoyasi)** — localhost callback DIZAYN bo'yicha bloklandi ("must point to a public address"). Haqiqiy yetkazish testi public receiver talab qiladi (masalan webhook.site) — prod smoke'da.
- [x] **B5. BiruniUpload** — ikkala yo'l: S3 mock bilan fayl bucket'ga tushdi, `\u0000<i>` placeholder SHA bilan almashdi (birinchi urinish SDK xatosida retry+exists-check ham ishladi); S3 o'chiq holda multipart fallback maqsadga yetdi. Eslatma: placeholder JSON.stringify'dagi `\u0000` MATN shakli bo'lishi kerak (haqiqiy NUL bayt emas).
- [x] **B6. Noma'lum request id** — status ham result ham 404.
- [x] **B7. Accept-Encoding stripping** — echo `accept_encoding_seen: ABSENT` ko'rsatdi.

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
- [x] **E2. Jonli SSE log** — API'dan tasdiqlandi (`data:` qatorlar oqdi). UI Pauza + reconnect qo'lda tekshiriladi.
- [x] **E3. Ops login rate-limit** — 6:429. **BUG topildi+tuzatildi:** H2 AUTO_SERVER multi-NIC'da LAN IP e'lon qilib ops loginni 12.5s timeout qilardi (rate-limit ham yashiringan edi) → `h2.bindAddress=127.0.0.1` (`SettingsDbUrl`); login 0.085s.
- [ ] **E4. Degraded rejim** — gateway STOP holatida brauzerni yangilash → login → to'g'ri /ops'ga tushadi, faqat Ops+Sozlamalar ochiq, banner bor. *(UI'da qo'lda)*

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
  - *v2.3.0 eslatma:* CB-blocked xabar endi Kafka'da AYLANMAYDI — `CbRequeueScheduler` uni xotirada park qilib, sxema ochiqligini 2s→4s→…→30s bilan qayta tekshiradi va faqat CB trafikni qabul qilganda qayta navbatga qo'yadi. Taslim bo'lish VAQT bo'yicha (`retry.max-message-age-ms`, `createdAt` ikkala ingress'da muhrlangani uchun ishlaydi); `cb-max-requeues` (endi 1000) faqat runaway guard. Nimaga qarash kerak: `docker exec redpanda rpk topic describe gateway.api.request` — CB ochiq turganda yozuv soni deyarli O'SMAYDI (ilgari ~20ms da bir aylanardi, 2026-05-18 Redpanda 57GB avariyasi va 2026-07-27 da 212 ta jonli so'rovning DLQ'ga ketishi shundan); **Kafka lag esa o'sadi — bu normal**, parked offset ataylab commit qilinmaydi. Batafsil ssenariylar: K bo'limi.
- [ ] **H3. RedPanda restart** — `docker restart redpanda` → gateway qayta ulanadi, so'rovlar yo'qolmaydi.
- [ ] **H4. Redis restart** — `docker restart redis` → qisqa uzilishdan keyin normal; cheksiz retry siklі YO'Q (safety net).

## I. Admin / xavfsizlik

- [ ] **I1. Parol o'zgartirish (v1.15.0: MAJBURIY oqim)** — yangi o'rnatishda `admin`/`admin` bilan kirish ISHLAYDI, lekin boshqa hech narsa ishlamaydi: `GET /qosid/admin/api/settings` → **403 `password-change-required`**, ops'da `POST /ops/api/stop` ham → **403**. SPA majburiy formani ko'rsatadi. Parolni almashtirgandan keyin sessiya tugaydi → yangi parol bilan ikkala backend'ga login → hammasi ochiladi. Ops tomonida ham `POST /ops/api/change-password` bor (gateway o'chiq bo'lganda parolni almashtirishning yagona yo'li — busiz yangi o'rnatishda qulflanib qolinadi).
- [ ] **I4. ORACLE javobini tashqaridan o'qish (IDOR)** — Oracle oqimidan kelgan so'rovning composite id'sini oling (log'dan yoki `redis-cli KEYS "request:state:*"`), keyin autentifikatsiyasiz `GET /qosid/api/v1/requests/<id>/result` → **404** bo'lishi kerak (403 EMAS). REST orqali yuborilgan so'rovning `/result`i esa avvalgidek **200** qaytaradi — ikkisini ham tekshiring, aks holda gate ishlayaptimi yoki hammasini sindirdimi bilinmaydi.
- [ ] **I2. Gateway login rate-limit** — 5 xato → 429.
- [ ] **I3. CSRF himoyasi** — cookie'siz curl bilan `PUT /ops/api/settings/...` → 403.

## J. v2.1.0 — audit tuzatishlari (2026-07-26/27)

- [ ] **J1. So'rov tanasi chegarasi (413)** — `PROXY_MAX_BODY_SIZE`ni vaqtincha kichik qiling (masalan `1024`), restart → 2 KB JSON bilan `POST /qosid/api/v1/queue/...` → **413**, Kafka'ga **hech narsa qo'yilmaydi** (`redpanda-console`da yangi xabar yo'q). Chegaradan kichik tana avvalgidek 202. **Muhim:** `BiruniUpload` multipart yuklamasi bu chegaradan TA'SIRLANMAYDI — 1 MB fayl bilan upload'ni ham sinang, u o'tishi kerak (u 50MB multipart limitida). Sinovdan keyin env'ni qaytaring.
- [ ] **J2. Actuator shutdown yo'q** — `curl -XPOST localhost:8091/actuator/shutdown` → **404** (200 EMAS). `SPRING_PROFILES_ACTIVE=dev` bilan ham 404 bo'lishi kerak — endpoint hech bir profilda ochilmaydi.
- [ ] **J3. Cookie atributlari** — login qilib `Set-Cookie` sarlavhalarini ko'ring: `JSESSIONID` va `OPS_SESSION` → `HttpOnly` + `SameSite=Lax`; `XSRF-TOKEN` va `OPS-XSRF-TOKEN` → `SameSite=Lax` lekin **HttpOnly EMAS** (SPA ularni JS bilan o'qiydi — HttpOnly bo'lsa har bir yozuv 403 bo'ladi). TLS yo'qligi uchun `Secure` **bo'lmasligi** kerak. Ixtiyoriy: `COOKIE_SECURE=true` bilan restart → to'rttasida ham `Secure` paydo bo'ladi (keyin qaytaring — HTTP orqali login buziladi).
- [ ] **J4. Compose portlari loopback'da** — serverning o'zida `curl localhost:6379` ishlaydi; **tashqi mashinadan** `nc -vz 77.42.21.222 6379`, `...:19092`, `...:3000` → **ulanmasin**. Bu Docker'ning host firewall'ini chetlab o'tishini yopadi.
- [ ] **J5. `/status` javobida `httpStatus`** — REST orqali kichik javob → `{status:COMPLETED, httpStatus:200, result:{...}}`; katta javob → `resultUrl` **va** `httpStatus`; muvaffaqiyatsiz upstream → `{status:FAILED, httpStatus:502, errorMessage:...}`. Hali ishlanayotgan so'rovda maydon **umuman bo'lmasligi** kerak (`0` emas).
- [ ] **J6. Ops parol hint'i (gateway O'CHIQ holda)** — bu stop-to-edit'ning normal holati. Gateway'ni ops'dan to'xtating → SPA'ga kiring: gateway javob bermasa ham konsol majburiy parol formasini ko'rsatishi kerak (agar parol hali default bo'lsa), va forma `POST /ops/api/change-password`ga ketishi kerak (Network tab'da tekshiring). Ilgari SPA buni bilmasdi va har bir ops yozuvi tushuntirishsiz 403 bo'lardi.
- [ ] **J7. Tartibli ack — jonli tekshiruv (ENG MUHIMI)** — yuk ostida (Oracle pull faol, navbatda xabarlar bor) `systemctl restart gateway-service` → restartdan keyin: `journalctl`da `ERROR` yo'q, Kafka **lag 0 ga qaytadi**, `Bmb_Requests`da o'sha oynadagi so'rovlar **hammasi `D`** (yo'qolgan `null` holat yo'q). Dublikat qayta ishlash log'da ko'rinishi NORMAL (idempotentlik darvozasi ushlaydi) — yo'qolish esa yo'q. Tekshirish so'rovi: restart oynasidagi `request_id` diapazonida `state is null` qolgan yozuv bo'lmasligi.
- [ ] **J8. SPA bundle bo'linishi** — brauzer Network tab: Sozlamalar sahifasida `StatisticsPage-*.js` **yuklanmaydi**; Statistika sahifasiga o'tganda o'sha chunk alohida so'rov bilan keladi. Boshlang'ich yuk ~2.8 MB dan ~1.3 MB ga tushgan bo'lishi kerak.

## K. Adaptive CB backpressure (v2.3.0)

Barcha `RETRY_CB_*` kalitlari **UI'da YO'Q** (ataylab — `SettingsRegistry`da ro'yxatdan o'tmagan):
ularni `/opt/services/qosid-flow/.env` da o'zgartirib, `systemctl restart gateway-service` qilinadi.
Sinovdan keyin `.env`ni qaytarishni unutmang. Kutish oynasini kuzatish uchun foydali buyruqlar:
`journalctl -u gateway-service -f | grep -i "cb-blocked\|parked\|circuit"`,
`curl -s localhost:8091/actuator/prometheus | grep circuitbreaker_requeue`,
`docker exec redpanda rpk group describe gateway-service-group` (lag; `KAFKA_GROUP_ID`).

**K0. Sun'iy uzilish tayyorlash (K1-K4 uchun bir marta).** `gateway.api.base-url`ni hech kim
tinglamaydigan LOKAL portga qo'ying — `http://127.0.0.1:19099` (ops orqali, gateway o'chiq holda),
so'ng start. Tiklash uchun keyinroq shu portda 200 qaytaruvchi mitti listener ko'tariladi
(quyidagi bitta qator, paste buzilmasligi uchun base64 bilan):

```bash
echo 'ZnJvbSBodHRwLnNlcnZlciBpbXBvcnQgQmFzZUhUVFBSZXF1ZXN0SGFuZGxlciwgSFRUUFNlcnZlcgpjbGFzcyBIKEJhc2VIVFRQUmVxdWVzdEhhbmRsZXIpOgogICAgZGVmIHIoc2VsZik6CiAgICAgICAgc2VsZi5yZmlsZS5yZWFkKGludChzZWxmLmhlYWRlcnMuZ2V0KCdDb250ZW50LUxlbmd0aCcpIG9yIDApKQogICAgICAgIGIgPSBiJ3sib2siOnRydWV9JwogICAgICAgIHNlbGYuc2VuZF9yZXNwb25zZSgyMDApCiAgICAgICAgc2VsZi5zZW5kX2hlYWRlcignQ29udGVudC1UeXBlJywgJ2FwcGxpY2F0aW9uL2pzb24nKQogICAgICAgIHNlbGYuc2VuZF9oZWFkZXIoJ0NvbnRlbnQtTGVuZ3RoJywgc3RyKGxlbihiKSkpCiAgICAgICAgc2VsZi5lbmRfaGVhZGVycygpCiAgICAgICAgc2VsZi53ZmlsZS53cml0ZShiKQogICAgZG9fR0VUID0gZG9fUE9TVCA9IGRvX1BVVCA9IGRvX0RFTEVURSA9IGRvX1BBVENIID0gcgpIVFRQU2VydmVyKCgnMTI3LjAuMC4xJywgMTkwOTkpLCBIKS5zZXJ2ZV9mb3JldmVyKCkK' \
  | base64 -di > /tmp/echo200.py
# tiklash payti: nohup python3 /tmp/echo200.py >/tmp/echo200.log 2>&1 &
```

**Nega base-url'ni QAYTARISH ishlamaydi:** `ProxyController` har bir so'rovga `baseUrl`ni ingress
paytida MUHRLAB Kafka'ga qo'yadi. Sozlamani qaytarsangiz faqat YANGI so'rovlar to'g'ri manzilga
ketadi; navbatda/parkda turgan xabarlar hamon o'sha o'lik portga boradi. CB nomi esa host-only
(`cb-127.0.0.1`, port hisobga olinmaydi), ya'ni yangi muvaffaqiyatli trafik sxemani YOPADI va parked
xabarlar chiqariladi — lekin o'lik portga, natijada retry → DLQ. Shuning uchun tiklash = **shu portda
listener ko'tarish**.

- [ ] **K1. Park, don't spin** — K0'dagi holatda 10+ so'rov yuboring. Kutilgan CB ochiq oynada:
      jurnalda `CB-blocked, parked until cb-127.0.0.1 accepts traffic`,
      `gateway_http_circuitbreaker_requeue_pending{flow="API"}` > 0, Kafka lag **o'sadi** (bu normal,
      parked offset ataylab commit qilinmaydi), `..._requeue_exhausted_total` va
      `..._requeue_rejected_total` **0 da qoladi**, va DLQ'da sababi `Circuit breaker ...` bilan
      boshlanadigan yozuv **YO'Q**.
      *Diqqat (bu xato emas):* sxema ochilishidan OLDIN yuborilgan yoki HALF_OPEN probe sifatida
      o'tgan chaqiruvlar haqiqatan xato oladi (`Connection refused`), `attemptCount` oshadi va
      `max-attempts` (3) tugagach oddiy yo'l bilan DLQ'ga tushadi. Bu kutilgan; muhimi — CB
      bloklashining O'ZI DLQ yozuvi yaratmasligi.
      Tiklash: `nohup python3 /tmp/echo200.py >/tmp/echo200.log 2>&1 &` → ~30s ichida
      (`automatic-transition-from-open-to-half-open-enabled: true` sxemani HALF_OPEN'ga o'tkazadi)
      parked xabarlar chiqariladi va listener'ga yetadi → `requeue_pending` **0 ga tushadi**, lag 0
      ga qaytadi, yangi `Circuit breaker ...` DLQ yozuvi paydo bo'lmaydi.
- [ ] **K2. Vaqt bo'yicha taslim** — `max-message-age-ms`ni 60000 qilib, listener'ni ko'tarmasdan
      CB'ni 2 daqiqa ochiq qoldiring. Kutilgan: ~60-90s dan keyin har bir parked xabar uchun BITTA
      DLQ yozuvi, sababi **`Message expired after NNNNNms`** (`Circuit breaker still open after ...`
      EMAS — u faqat bitta yetkazib berish ichida muddat o'tsa chiqadigan burchak holat), `httpStatus
      = 0`, `errorSource = SYSTEM`; `gateway_dlq_sent_total` o'sadi, `..._requeue_exhausted_total`
      esa **~0 da qoladi**. Sabab: parkda turgan xabarning muddati TEKSHIRILMAYDI — u faqat sxema
      yo'l bergandan keyingi yetkazib berishda, `processRequest` boshidagi darvoza bilan aniqlanadi.
      *Diqqat:* bu kalit — `gateway.retry.max-message-age-ms` — `RETRY_CB_*` lardan farqli, settings
      DB'da migratsiya qilingan, ya'ni **env-var (`RETRY_MAX_MESSAGE_AGE`) endi ta'sir qilmaydi**.
      Uni gateway o'chiq holda ops orqali o'zgartiring:
      `PUT /ops/api/settings/gateway.retry.max-message-age-ms` (yoki SPA → Sozlamalar), keyin start.
      Sinovdan keyin 300000 ga qaytaring.
- [ ] **K3. Pending chegarasi** — `.env`da `RETRY_CB_MAX_PENDING=5`, restart, K0 holati, 20 so'rov.
      Kutilgan: bir vaqtda ko'pi bilan 5 ta park (`requeue_pending{flow="API"} <= 5`), CB bloklangan
      qolganlari `Circuit breaker open and requeue buffer full` sababi bilan DLQ va
      `..._requeue_rejected_total` o'sadi. (Bu qadam sozlama o'zgartirishiga bog'liq emas — chegara
      startda o'qiladi, navbatdagi xabarlar qayta manzillanmaydi.)
- [ ] **K4. Restart parked xabarni yo'qotmaydi** — CB ochiq, 10 xabar park bo'lgan holda
      `systemctl restart gateway-service`. Kutilgan: shutdown jurnalida
      `draining N parked CB requeues` (N > 0), restartdan keyin **o'sha 10 id ning har biri qaytadan
      ko'rinadi** (`Received request: <id>`) va holati bor — o'lik port hamon o'lik bo'lsa ular
      yana park bo'ladi yoki oddiy retry/expiry yo'liga tushadi; listener ko'tarilgan bo'lsa
      COMPLETED bo'ladi. **Birortasi ham izsiz yo'qolmasligi** kerak. Tekshirish:
      `docker exec redis redis-cli GET "request:state:<tenantId>:<requestId>"` — bo'sh (`nil`)
      qaytgan id yo'qolish belgisi.

---

**Yakun mezoni:** A–G to'liq yashil (H/I/J/K ixtiyoriy, lekin **J7 prod deploydan keyin majburiy**,
K1 esa CB yo'lini o'zgartirgan har bir releasedan keyin) → `debug` push + release PR.
