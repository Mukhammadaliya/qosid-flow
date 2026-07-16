# B-loyiha: Ops konsoli (sidecar) — Dizayn hujjati

**Sana:** 2026-07-16
**Holat:** Tasdiqlangan (brainstorming sessiyasi asosida)
**Muallif:** Muxammadali + Claude
**Bog'liq:** A-loyiha ([2026-07-15 settings-editability]) — Settings'dagi restart-pending banner "Ops konsolidan restart qiling" deb shu konsolga havola qiladi. B1 Settings control-plane (`admin_account`, H2, BCrypt auth).

## 1. Maqsad

Gateway JVM'ni **UI o'chmasdan** boshqarish (bitta toggle: ishlab tursa STOP, o'chgan bo'lsa START) va **real-time loglarni alohida oynada** ko'rish. Gateway to'xtatilganda ham tirik turadigan **alohida doim-ishlaydigan sidecar** talab qilinadi (o'chgan gateway o'zini yoqa olmaydi).

## 2. Kelishilgan qarorlar

| Savol | Qaror |
|---|---|
| Amallar | **Bitta toggle**: UP→STOP, DOWN→START |
| Mustaqillik | **Alohida sidecar jarayon** (o'z systemd unit'i, port 8092) — gateway o'chsa ham ishlaydi |
| Texnologiya | **Kichik Spring Boot app** (`spring-boot-starter-web` only; Kafka/Redis/RocksDB yo'q) |
| Auth | **Yagona admin login** — shared H2 `admin_account`, BCrypt (shifrlash-kaliti kerak emas) |
| H2 kirish | **AUTO_SERVER=TRUE** (mixed-mode) — gateway'ning `SettingsDbConfig` URL'iga qo'shiladi |
| Ops UI | **Bitta o'zicha-yetarli HTML sahifa** (vanilla JS, npm/Vite build YO'Q) |
| Boshqaruv | **Sozlanadigan buyruq** (`ProcessBuilder`) — prod systemctl, lokal skript |
| Loglar | `gateway.log`'ni to'g'ridan-to'g'ri tail (SSE) — Loki kerak emas |

## 3. Arxitektura

```
  brauzer (:8092/ops, alohida oyna)
        │  admin login (shared H2, BCrypt)
        ▼
  ┌─────────────────────────────────┐   DOIM ishlaydi (systemd qosid-ops)
  │  qosid-ops SIDECAR (yangi modul) │   gateway o'chsa ham tirik
  │  Spring Boot web-only :8092      │
  │   • toggle START/STOP            │───► ProcessBuilder (sozlanadigan buyruq)
  │   • real-time log SSE            │        prod: systemctl start/stop gateway-service
  │   • GatewayStatusService (HTTP)  │───► GET :8090/.../health (UP/DOWN)
  └─────────────────────────────────┘
        │ shared H2 (AUTO_SERVER)         ┌─────────────────────────┐
        └────────── admin_account ───────►│ gateway JVM :8090 (mavjud)│ STOP→o'ladi; sidecar START qiladi
                                          └─────────────────────────┘
```

**H2 mixed-mode nuansi:** AUTO_SERVER'da faylni birinchi ochgan jarayon TCP-server bo'ladi. Ikkalasi ham per-request qisqa ulanish ishlatadi (gateway allaqachon `DriverManagerDataSource`, pool'siz). Gateway UP → u host; gateway DOWN → sidecar auth uchun faylni ochadi (server bo'ladi) → gateway'ni yoqadi → gateway qayta host. Qisqa ulanish-uzilishlar bo'lishi mumkin (ops-tool uchun maqbul). **Pipeline (E1-E5) tegilmaydi** — bu control-plane DB config o'zgarishi.

## 4. Backend komponentlar (`uz.greenwhite.ops`)

- `OpsApplication` — Spring Boot main (web-only).
- `GatewayStatusService` — `GET {health-url}` (timeout 2s) → `up: boolean`. HTTP-asosli status prod/lokal'da bir xil ishlaydi.
- `GatewayControlService` — `ProcessBuilder` bilan sozlangan start/stop buyrug'ini ishga tushiradi; exit-code + chiqishni qaytaradi; xato → strukturaviy natija (never-throw).
- `LogTailService` — `{log-file}`ni tail: ulanishda oxirgi ~200 qator (fayl oxiridan seek), so'ng fayl pozitsiyasini kuzatib yangi baytlarni o'qiydi; fayl qisqarsa (logback rotatsiyasi) pozitsiya 0'ga. Har qatorni SSE subscriber'ga uzatadi.
- `OpsController`:
  - `GET /ops` → o'zicha-yetarli HTML sahifa (static resource)
  - `POST /ops/api/login` → admin login (shared H2, BCrypt), session yaratadi
  - `GET /ops/api/status` → `{up: boolean}`
  - `POST /ops/api/start` → start-buyruq → `{ok, exitCode, output}`
  - `POST /ops/api/stop` → stop-buyruq → `{ok, exitCode, output}`
  - `GET /ops/api/logs/stream` → SSE (`text/event-stream`) jonli log qatorlari
- `OpsSecurityConfig` — `SecurityFilterChain` `/ops/**` (login/status permitAll bo'lishi mumkin emas — hammasi himoyalangan, faqat login endpoint permitAll); session-auth; CSRF-token POST'lar uchun; `admin_account`ni shared H2'dan (AUTO_SERVER) BCrypt bilan tekshiradi. Admin UI bilan bir xil parol.

**Config (`ops/src/main/resources/application.yml`):**
- `ops.port` = 8092
- `ops.gateway.health-url` = `http://localhost:8090/qosid/admin/api/health`
- `ops.gateway.start-command` / `stop-command` (prod: `systemctl start/stop gateway-service`; lokal: skript)
- `ops.log-file` = gateway `logs/gateway.log` yo'li
- `ops.settings-db-path` = gateway bilan bir xil H2 yo'li (`SETTINGS_DB_PATH`)

**Modul strukturasi:** alohida Maven modul `ops/` (o'z `pom.xml`, `spring-boot-starter-web` + `spring-boot-starter-security` + `spring-security-crypto` (BCrypt) + `spring-boot-starter-jdbc` + H2 driver). Asosiy gateway pom'iga tegilmaydi. Chiqadi: `ops/target/qosid-ops.jar`.

**Gateway tomonidagi yagona o'zgarish:** `SettingsDbConfig.java` H2 URL'iga `;AUTO_SERVER=TRUE` qo'shish (mixed-mode). Boshqa hech narsa.

## 5. Ops konsoli UI (bitta HTML sahifa)

- **Holat badge:** yashil ● ISHLAYAPTI / qizil ● O'CHIQ — har ~3s `GET /ops/api/status`.
- **Toggle tugma:** UP → "To'xtatish" (qizil, tasdiq modal); DOWN → "Ishga tushirish" (yashil). Amal davomida yuklanish + holat barqarorlashguncha poll.
- **Log oynasi:** SSE stream, ulanishda oxirgi ~200 qator + jonli. Avto-scroll, **Pauza**, **Tozala**. Monospace, ERROR qizil/WARN sariq. Gateway to'xtatilganda ham oqadi (fayldan mustaqil o'qiladi).
- **Sodda (YAGNI):** filter/qidiruv yo'q (Errors modulida bor).
- Vanilla JS (SSE `EventSource` + `fetch`), inline CSS, sidecar static resource'idan.

## 6. Xavfsizlik

- Session-auth majburiy (`/ops/**`); login'siz 401.
- **Command-injection yo'q** — start/stop buyruqlari config'dan, foydalanuvchi kiritmaydi.
- CSRF-token POST start/stop uchun; same-origin session.
- Sidecar 8092 localhost bind + prod nginx orqasida / firewall.
- `gateway.log` xom ko'rinadi (admin-gated; Errors moduli bilan bir xil ishonch).
- systemctl ruxsati faqat `gateway-service` start/stop bilan cheklanadi (polkit/sudoers, tor).

## 7. Deploy

- Yangi systemd unit `qosid-ops.service` — doim ishlaydi, `Restart=always`, gateway'dan mustaqil.
- Build: `cd ops && mvn package` → `qosid-ops.jar`.
- Deploy-checklist: ops-user'ga `systemctl start/stop gateway-service` ruxsati (polkit qoida yoki sudoers NOPASSWD, tor doira); shared H2 yo'li to'g'ri; `SettingsDbConfig` AUTO_SERVER deploy qilingan; nginx `/ops`→8092 (ixtiyoriy); lokal start/stop skriptlar.
- Prod resurs: ~150-200MB (ikkinchi JVM) hisobga olinadi.
- Build MANUAL — user staging manual-testdan keyin master'ga merge.

## 8. Testlash

- `GatewayStatusService`: HTTP mock (MockRestServiceServer) → up/down; timeout → down (never-throw).
- `GatewayControlService`: zararsiz test-buyruq (`echo`) → ishga tushirish + chiqish capture; mavjud bo'lmagan buyruq → xato natija (never-throw).
- `LogTailService`: temp-fayl → oxirgi-N to'g'ri; yangi qator qo'shilsa follow; fayl qisqarsa (rotatsiya) pozitsiya 0'ga.
- `OpsController`: auth-gate (401 login'siz), `/status`, `/start` `/stop` (mock control), SSE ulanish.
- `OpsSecurityConfig`: to'g'ri parol → 200+session; xato parol → 401; `/ops/**` session talab qiladi (BCrypt test `admin_account` row).
- **Jonli smoke:** sidecar lokal ishga tushirish → login → toggle stop (gateway DOWN, health 8090 uzsilib) → toggle start (UP), o'tish davomida log oynasi shutdown/startup qatorlarini oqizishini kuzatish.
- **Regressiya:** gateway `SettingsDbConfig` AUTO_SERVER o'zgarishi mavjud testlarni buzmaydi; gateway yolg'iz (sidecarsiz) ham ishlaydi.

## 9. Ko'lamdan tashqarida

- Log filter/qidiruv (Errors modulida bor).
- Ko'p-gateway / klaster boshqaruv.
- Ops metrikalar (Statistics'da).
- Sidecar'ning o'zini UI'dan boshqarish (u doim ishlashi kerak).
- Auto-restart on crash (systemd `Restart=always` gateway uchun alohida masala — bu spec sidecar-boshqaruvga qaratilgan).
