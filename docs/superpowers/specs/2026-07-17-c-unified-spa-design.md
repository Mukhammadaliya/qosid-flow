# C-loyiha: Yagona SPA — dizayn spec

**Sana:** 2026-07-17
**Holat:** Tasdiqlangan (user bilan brainstorming orqali)
**Versiya maqsadi:** v1.13.0

## Maqsad

React SPA'ni gateway JAR ichidan chiqarib, nginx serve qiladigan mustaqil statik
frontend'ga aylantirish va ops konsolini (start/stop + jonli log) shu SPA ichiga
sahifa sifatida qo'shish. Natija: **bitta UI, bitta login ekrani** — gateway
o'chirilganda ham UI tirik qoladi va uni qayta yoqish mumkin bo'ladi
("tovuq-tuxum" muammosi yo'qoladi).

Bu spec'dan tashqarida: gateway→qosid rename (1-daraja, alohida keyingi loyiha).

## Kontekst va turtki

- `frontend/` allaqachon mustaqil Vite+React loyihasi; hozir Maven frontend
  profili `dist`ni gateway JAR `static/` ichiga copy qiladi (D0 qarori).
- Ops sidecar (`qosid-ops`, :8092, B-loyiha) o'z 95-qatorlik HTML sahifasiga ega —
  dizayni SPA'dan farq qiladi, foydalanuvchi ikki joyda login qiladi.
- SPA JAR ichida bo'lgani uchun gateway Stop qilinsa UI ham o'chadi — ops
  funksiyasini SPA'ga qo'shishga to'siq shu edi.

## Arxitektura

### Prod (nginx — mavjud instance, config yangilanadi)

```
nginx :443 (mavjud self-signed TLS)
 ├── /               → statik SPA (root: /opt/services/qosid-flow/frontend/dist)
 │                     try_files $uri /index.html   (React Router fallback)
 ├── /qosid/         → proxy_pass http://127.0.0.1:8090
 │                     (gateway API; tashqi /qosid/api/v1/queue klientlari ham
 │                      shu yo'ldan — wire-o'zgarish YO'Q)
 └── /ops/api/       → proxy_pass http://127.0.0.1:8092  (ops API)
```

- Actuator (`:8091`) tashqariga ochilmaydi (hozirgidek server-ichki).
- Nginx config namunasi repo'da saqlanadi: `deploy/nginx-qosid.conf`.

### Lokal dev (nginx'siz)

`cd frontend && npm run dev` — vite dev-server proxy:
`/qosid → http://localhost:8090` (mavjud), `/ops/api → http://localhost:8092` (yangi;
ATAYLAB `/ops` emas — `/ops` SPA'ning o'z sahifa-route'i, proxy qilinmasligi kerak),
`/actuator → :8090` (mavjud). Lokalda uch narsa yoqiladi: gateway JAR, ops JAR, vite.

### Deploy oqimi (prod)

`git pull` → `mvn package` (JAR'lar; frontend profil `npm build`gacha ishlaydi,
JAR'ga copy YO'Q) → nginx root `frontend/dist`ga qaraydi → `systemctl restart`
faqat JVM o'zgarganda. **UI-only o'zgarishda gateway restart kerak emas.**

## Komponent o'zgarishlari

### Gateway (JAR'dan SPA chiqadi)

- `SpaForwardController` o'chiriladi (testlari bilan).
- Maven frontend profilining `copy-frontend-dist` bosqichi olib tashlanadi —
  profil `npm ci && npm run build`gacha qoladi (prod'da dist yig'ish uchun).
- `WelcomePageHandlerMapping`ga tayangan hech narsa qolmasligi tekshiriladi.
- **E1-E5 pipeline'ga tegilmaydi** (SAFETY qoidalari daxlsiz).

### Ops sidecar

- `static/ops/index.html` o'chiriladi — ops faqat API beradi
  (status / start / stop / logs SSE).
- Cookie rename (bitta domen ostida gateway bilan to'qnashmaslik uchun;
  gateway cookie'lari o'zgarmaydi):
  - sessiya: `JSESSIONID` → `OPS_SESSION`
    (`server.servlet.session.cookie.name`)
  - CSRF: `OPS-XSRF-TOKEN` cookie + `X-OPS-XSRF-TOKEN` header
    (CookieCsrfTokenRepository sozlamalari)
- Yangi endpoint: `GET /ops/api/me` — SPA sessiya tekshiruvi uchun
  (gateway'dagi `/qosid/admin/api/me` bilan bir xil shakl).

### Frontend (SPA)

- **Ops API client** (`src/api/ops.ts`): alohida CSRF cookie/header nomlari,
  `credentials` bilan.
- **Yagona login**: mavjud login sahifasi submit'da parallel ikkita POST —
  `/qosid/admin/api/login` + `/ops/api/login`. Logout ham ikkalasiga.
- **Sessiya tekshiruvi** (refresh'da): `/qosid/admin/api/me` + `/ops/api/me`
  parallel; natijaga qarab rejim.
- **Rejim matritsasi:**

  | Gateway | Ops | SPA holati |
  |---|---|---|
  | ✅ | ✅ | To'liq rejim |
  | ❌ down | ✅ | Degraded: faqat Ops sahifasi, qolgan bandlar disabled + "gateway o'chiq" belgisi |
  | ✅ | ❌ down | Ops sahifasida "ops sidecar ishlamayapti" banner, qolgani to'liq |
  | ❌ 401 | ❌ 401 | Noto'g'ri parol xatosi |

  Farqlash qoidasi: ikkalasi 401 → parol xato; connection-error/5xx → o'sha
  server o'chiq.
- **Yangi `/ops` sahifasi** (Ant Design + Smartup palitra, uz i18n):
  - Status kartasi: yashil/qizil badge, 5s poll (`/ops/api/status`).
  - Start/Stop toggle: Stop'da tasdiqlash modali ("Gateway to'xtatilsinmi?
    Navbatdagi so'rovlar Kafka'da kutadi"); amal davomida loading (start
    ~15-20s holat o'tishi).
  - Jonli log: SSE `/ops/api/logs/stream`, monospace, smart auto-scroll
    (pastda — ergashadi, yuqorida — to'xtaydi), pauza tugmasi, xotirada oxirgi
    500 satr. D2 Errors log-uslubiga vizual mos.
  - Sidebar'ga "Ops" bandi.

## Xato-holatlar

- Degraded rejimda ops so'rovi 401 qaytarsa → faqat ops uchun qayta-login taklifi.
- SSE uzilsa → EventSource avtomatik reconnect (~3s).
- Start/stop buyruq xatosi → ops API'ning mavjud never-throw strukturali javobi
  Ant `message` sifatida ko'rsatiladi.
- Gateway to'liq rejimda ishlab turib o'chirilsa → navbatdagi poll qizilga
  o'tkazadi, sidebar bandlari disabled bo'ladi (sahifadan chiqarib yubormaydi).

## Test

- **Frontend unit (vitest):** login matritsasining 4 holati; ops client CSRF
  header nomlari; degraded-rejim routing guard.
- **Frontend E2E (Playwright, mock API):** login → /ops sahifa → status →
  stop tasdiqlash → start → log oqimi ko'rinishi.
- **Ops backend:** cookie nomlari (`OPS_SESSION`, `OPS-XSRF-TOKEN`) assertion,
  `/ops/api/me` auth'li/auth'siz.
- **Gateway backend:** SpaForwardController testlari o'chadi; qolgan to'plam
  yashil qolishi shart.

## Hujjatlar

- `CLAUDE.md` — Ops console bo'limi + endpoint jadvali yangilanadi.
- `ai-context/ARCHITECTURE.md` — "Ops console (sidecar)" bo'limi qayta yoziladi
  (SPA endi nginx'da, ops faqat API).
- `ai-context/CONFIGURATION.md` — ops cookie sozlamalari.
- Deploy hujjati — nginx config + yangi deploy oqimi.
- `VERSION.md` → 1.13.0.

## Qabul mezonlari

1. Nginx orqali bitta origin'da: SPA `/`, gateway API `/qosid/`, ops API `/ops/api/`.
2. Bitta login ekrani ikkala sessiyani ochadi; gateway o'chiq bo'lsa ham login
   ishlab, Ops sahifasi ochiladi va Start gateway'ni ko'taradi.
3. Gateway JAR'da endi SPA yo'q; ops JAR'da HTML yo'q.
4. `/ops` sahifasi: status, start/stop (tasdiqlash bilan), jonli log — barchasi
   SPA dizaynida, uz tilida.
5. Barcha mavjud backend/frontend testlar yashil; yangi testlar qo'shilgan.
