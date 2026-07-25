# D-loyiha: Stop-to-edit sozlamalar + Maven reactor + avto-ulanish — dizayn spec

**Sana:** 2026-07-17
**Holat:** Tasdiqlangan (user bilan brainstorming orqali)
**Versiya maqsadi:** v1.14.0

## Maqsad

Operator workflow'ini tabiiy qilish: **to'xtat → sozla → ishga tushir**. Hozir qosid
servisi o'chiq bo'lsa Settings (va boshqa sahifalar) umuman ishlamaydi — sozlash uchun
servisni "bekorchi" holatda yoqib, keyin restart qilishga majbur. Start'dan keyin esa
qayta login talab qilinadi. Bu spec uchala og'riqni hal qiladi:

1. **Qat'iy stop-to-edit modeli** (user tanlovi): BARCHA tahrirlanadigan sozlamalar
   (secretlar ham) FAQAT servis to'xtatilganda o'zgartiriladi — ops sidecar orqali.
   Servis ishlayotganda Settings read-only.
2. **Maven reactor + `qosid-settings-core` umumiy modul** (user tanlovi: "sekin bo'lsa
   ham eng strukturalangan"): registry/cipher/repository ikkala JVM uchun yagona kod
   manbasi — nusxa klass va drift yo'q.
3. **Avto-ulanish (token handoff):** Ops'dan start qilingandan keyin logout/login
   kerak emas — bir-martalik token orqali qosid sessiyasi avtomatik yaratiladi.

Bu spec'dan tashqarida: gateway→qosid to'liq rename (alohida keyingi loyiha; bu spec
faqat yangi umumiy modul paketini `uz.greenwhite.qosid.*` deb boshlab, yo'nalishni
belgilaydi). E1-E5 pipeline'ga TEGILMAYDI (SAFETY).

## Kontekst (mavjud mexanizmlarga tayanish)

- `SettingsEnvironmentPostProcessor` (A-loyiha) boot'da H2'dagi editable non-secret
  qatorlarni Environment'ga qo'shadi; `SettingsService` runtime o'qishlari (jonli
  kalitlar, secretlar) DB'dan oladi → **o'chiq holatda H2'ga yozilgan hamma narsa
  keyingi start'da o'zi kuchga kiradi**, yangi qo'llash mexanizmi kerak emas.
- Ops sidecar shared H2'ni allaqachon ochadi (AUTO_SERVER; gateway tomonda endi
  HikariCP pinning — 2026-07-17 fix). Ops'da auth (`OPS_SESSION`) va CSRF bor.
- SPA'da `gatewayUp`/`opsUp` holatlari va degraded rejim mavjud (C-loyiha).

## 1. Maven reactor + settings-core

Yangi repo strukturasi:

```
qosid-flow/
  pom.xml            # aggregator: packaging=pom, artifactId=qosid-parent
  settings-core/     # YANGI: artifactId=qosid-settings-core
    src/main/java/uz/greenwhite/qosid/settings/
    src/main/resources/settings-schema.sql
  app/               # qosid servisi — hozirgi root src/ git mv bilan ko'chadi
    pom.xml          # artifactId=qosid-flow (JAR nomi o'zgarmaydi)
  ops/               # mavjud modul, reactor a'zosi bo'ladi
  frontend/          # Maven modul EMAS; app pom frontend-profil yo'llari ../frontend
```

**settings-core tarkibi** (Spring-context'siz, faqat spring-jdbc + h2 + spring-security-crypto):
- `SettingsRegistry` — kalitlar ro'yxati, guruh/label/tur/effectClass/editable (yagona manba)
- `SettingsCipher` — AES-GCM (format o'zgarmaydi, mavjud shifrlangan qiymatlar o'qiladi)
- `SettingsRepository`, `SettingsRecord`, `SettingsAuditRepository` (audit yozish/o'qish)
- `AdminAccountRepository` — ikkala JVM auth'i uchun (ops'dagi `AdminAuthDao` SQL'i shu yerga)
- `LoginHandoffRepository` — token handoff jadvali (3-bo'lim)
- `settings-schema.sql` (idempotent; `login_handoff` jadvali qo'shiladi)

Ko'chirish qoidalari: klasslar `uz.greenwhite.gateway.settings`/`admin`dan git mv bilan
`uz.greenwhite.qosid.settings`ga; app'dagi importlar yangilanadi; app/ops testlaridagi
tegishli unit-testlar core'ga ko'chadi. App'dagi Spring-bog'liq qatlam (SettingsService
kesh, controllers, SecurityConfig, PostProcessor) joyida qoladi — faqat core'ga tayanadi.

**Build/deploy ta'siri (hammasi shu loyihada yangilanadi):**
- `.github/workflows/build.yml` — root `mvn package` reactor'ni quradi; artefakt yo'li
  `app/target/qosid-flow-*.jar` (+ `ops/target/qosid-ops.jar`ni ham release'ga qo'shish)
- prod systemd `gateway-service.service` ExecStart yo'li → `app/target/...` (deploy checklist)
- `ops-local-start.cmd` JAR yo'li; `CLAUDE.md`/`ai-context` docs; `docker/promtail` ta'sirsiz

## 2. Qat'iy stop-to-edit

### Ops API (yangi, `OPS_SESSION` auth + CSRF ostida)

| Endpoint | Vazifa |
|---|---|
| `GET /ops/api/settings` | Barcha qatorlar registry metadata bilan; secret qiymatlar `***` mask |
| `PUT /ops/api/settings/{key}` | Guard zanjiri: (1) qosid servisi UP → **409** `{"error":"service-running"}`; (2) registry'da editable emas → 400; (3) secret → AES-GCM shifrlab yozish; (4) `settings_audit`ga yozuv, author `admin@ops`; secret uchun mask'langan audit (mavjud konvensiya) |
| `GET /ops/api/settings/audit` | Audit tarixi (read-only) |

- UP-tekshiruv `GatewayStatusService` orqali (mavjud, 2s timeout, never-throw). Poll
  oralig'idagi race qabul qilinadi: yozuv paytida servis endi ko'tarilayotgan bo'lsa ham
  ziyon yo'q — servis baribir boot'da DB'dan o'qigan yoki keyingi restartda oladi.
- Ops env'iga `SETTINGS_ENCRYPTION_KEY` qo'shiladi (gateway bilan AYNAN bir xil qiymat).
  Kalit yo'q/noto'g'ri holatda secret PUT → 400 aniq xabar bilan (never-500).
- `ops.settings-db-path` allaqachon bor; sxema init endi core'dagi SQL bilan (ops o'z
  nusxa-CREATE'larini yo'qotadi).

### Qosid servisi tomonida

- Barcha sozlama-yozuv endpointlari — generic `PUT /qosid/admin/api/settings/{key}`,
  `PUT /source-auth`, `PUT/POST tenant-mapping`, `PUT /ai/config` — endi **doim 409**
  `{"error":"stop-required"}` qaytaradi. GET'lar (ro'yxat, registry, audit, masked
  secretlar) o'zgarishsiz qoladi.
- Istisno: admin parol almashtirish (`admin_account`) sozlama emas — mavjud xatti-harakat
  saqlanadi (hozir alohida parol UI yo'q; scope'dan tashqarida).
- `SettingsRegistry`ning `effectClass` maydoni saqlanadi, lekin qat'iy modelda amaliy
  farq yo'qoladi — UI bitta izoh ko'rsatadi (pastda). `restart-pending` bayrog'i va
  banner olib tashlanadi (endi hamma edit o'chiq holatda bo'ladi — bayroq ma'nosiz).

### SPA (ikki rejimli Settings)

- **Servis UP:** Settings/typed sahifalar read-only — qiymatlar ko'rinadi, edit
  kontrollari disabled + qulf ikonkasi, sahifa tepasida banner: "Tahrirlash uchun
  servisni to'xtating" (Ops sahifasiga havola). Effect-class rang-badge'lari o'rniga
  bitta izoh: "O'zgarishlar servis to'xtatilgan holatda kiritiladi va keyingi
  ishga tushirishda qo'llanadi".
- **Servis DOWN + ops sessiya bor:** Settings to'liq tahrirlash rejimida — ops API
  bilan. Hozirgi "degraded: faqat Ops" nav-gating yumshaydi: **Sozlamalar** nav bandi
  ham ochiq qoladi (Errors/Statistics UP talab qiladi, disabled qoladi).
- Typed formalar (Source Auth, Tenant Mapping, AI config) o'chiq rejimda SPA-tomondagi
  kalit-xaritalash orqali o'sha ops generic API bilan ishlaydi (form maydoni ↔
  `gateway.source.auth.*` va h.k. — xaritalar `frontend/src/api/settingsKeys.ts`da
  markazlashadi). Secret maydonlarda mavjud "bo'sh qoldirsang saqlanmaydi/mask hech
  qachon qayta yuborilmaydi" semantikasi saqlanadi. "Test" tugmalari o'chiq rejimda
  disabled + hint ("Test uchun servis ishlab turishi kerak").

## 3. Avto-ulanish (login-token handoff)

Oqim:
1. SPA (ops sessiya bilan) `POST /ops/api/handoff` → ops 32-bayt `SecureRandom` token
   yaratadi; H2 `login_handoff(token_hash, username, expires_at)` jadvaliga **SHA-256
   hash** yozadi (TTL 60s); tokenning o'zi faqat javobda.
2. SPA servis ko'tarilishini poll qiladi (`/ops/api/status` mavjud poll), `up:true`
   bo'lgach `POST /qosid/admin/api/login-token {token}`.
3. Qosid servisi: hash bo'yicha qator topadi, muddat tekshiradi, **qatorni o'chiradi**
   (bir-martalik), sessiya yaratadi (session-rotation, XSRF cookie — oddiy login bilan
   bir xil yo'l).
4. SPA `refresh()` → to'liq nav. Har kuzatilgan down→up o'tishda ham xuddi shu oqim
   (opsUp bo'lsa): OpsPage'dagi transition-hook handoff'ni chaqiradi.

Xavfsizlik: token faqat hash'da saqlanadi; TTL 60s; bir-martalik (delete-on-use);
`login-token` endpoint'i login rate-limiter'iga qo'shiladi; muddati o'tgan qatorlar
har ikkala tomonda best-effort tozalanadi (boot'da + token tekshiruvda). Yiqilish
rejimi: token yaroqsiz/muddat o'tgan → 401, SPA jimgina login sahifasiga tushadi
(hozirgi xatti-harakat) — hech qachon bloklamaydi.

## 4. Xavfsizlik hardening (shu loyihada)

- **Ops login rate-limit** — qosid servisidagi bilan bir xil siyosat (per-username,
  soxta-akkauntlarga ta'sir qilmaydi). Sabab: ops endi AES kalit + sozlama-yozish
  kuchiga ega; C-loyiha docs'ida tavsiya sifatida qayd etilgan qarz.
- Ops settings PUT'lari ham CSRF ostida (mavjud `OPS-XSRF-TOKEN` mexanizmi).

## 5. Deploy checklist (v1.13→v1.14)

1. prod systemd `gateway-service.service`: `ExecStart` yo'li `app/target/qosid-flow-1.0.0-SNAPSHOT.jar`
2. `qosid-ops.service` env'iga `SETTINGS_ENCRYPTION_KEY=<gateway bilan bir xil>` (chmod 600 .env)
3. `mvn clean package -DskipTests` endi root'da (reactor) — ops JAR ham birga chiqadi
4. CI `build.yml` yangi yo'llar bilan; release'ga ikkala JAR biriktiriladi
5. SPA yangi build (`frontend/dist`) — nginx statik, JAR restart talab qilmaydi
6. VERSION.md → 1.14.0

## 6. Test rejasi

- **settings-core:** cipher contract-testlar (qat'iy test-vektorlar: berilgan kalit +
  plaintext → decrypt(encrypt(x))==x va eski-format fixture decrypt) — ikkala JVM
  moslik kafolati; registry testlari (mavjudlaridan ko'chadi); repository CRUD (mem H2).
- **ops:** PUT guard'lar (UP→409, read-only kalit→400, secret-kalitsiz→400), masked GET,
  audit yozuv, handoff (TTL, bir-martalik, noto'g'ri token→401), login rate-limit.
- **app:** login-token endpoint (muvaffaqiyat, muddat, qayta ishlatish), barcha settings
  PUT'lar 409, PostProcessor regressiya (mavjud testlar yashil qoladi), SecurityIsolation
  regressiya.
- **SPA vitest:** ikki rejimli settings client (UP→gateway GET/read-only, DOWN→ops),
  typed-form kalit-xaritalash, avto-ulanish hook.
- **Playwright e2e:** login → stop → sozlama o'zgartirish (oddiy + secret) → start →
  avto-ulanish (login sahifasiz) → yangi qiymat read-only ko'rinishda.
- Regressiya: butun mavjud suite (app 168, ops 23, vitest 48) yashil.

## Ochiq savollar / keyingi loyihalarga

- gateway→qosid to'liq rename (paketlar, systemd unit nomi, log fayllar) — alohida spec.
- Errors/Statistics'ni o'chiq holatda qisman ishlatish (Loki mustaqil ishlaydi) — scope'dan tashqarida.
- Admin parol almashtirish UI — scope'dan tashqarida (D0'dan qolgan qarz).
