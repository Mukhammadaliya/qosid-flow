# D0+D1: Frontend Poydevori + Settings Moduli — Dizayn hujjati

**Sana:** 2026-07-12
**Holat:** Tasdiqlangan (brainstorming sessiyasi asosida)
**Muallif:** Muxammadali + Claude
**Bog'liq:** [2026-07-10-qosid-flow-upgrade-design.md](2026-07-10-qosid-flow-upgrade-design.md) §4 (UI eskizi); B1 (SettingsService, admin API, admin auth, SPA-CSRF) — tayyor. Bu D-fazaning birinchi quyi-fazasi.

## 1. Maqsad

Qosid uchun web UI poydevorini qurish (D0) va uning ustiga birinchi real modulni — **Settings** (D1) — yetkazish. Oddiy developerlar `.yaml` emas, UI orqali tizimlararo integratsiyani sozlaydi. Backend admin API'lari (B1) tayyor; bu faza ularга ishlaydigan, ko'rinadigan interfeys beradi.

D-faza katta bo'lgani uchun quyi-fazalarga bo'lingan (D0 poydevor → D1 settings → D2 errors → D3 statistics → D4 Loki → D5 AI). Bu hujjat **D0+D1** ni to'liq beradi; D2-D5 faqat nav placeholder sifatida eslatiladi.

## 2. Kelishilgan qarorlar (savol-javob natijalari)

| Savol | Qaror |
|---|---|
| Stack | React + TypeScript + Vite, Ant Design, react-i18next |
| Serving | SPA Spring static resource'idan → **same-origin** (B1 session-cookie + SPA-CSRF tabiiy ishlaydi, CORS yo'q) |
| Build | Repo ichida `frontend/`; `frontend-maven-plugin` → `mvn package` npm build qiladi va `target/classes/static/` ga ko'chiradi → **bitta JAR**; `-P skip-frontend` profili |
| Komponent kutubxona | Ant Design + `ConfigProvider` theme-token'lar |
| Palitra | Smartup brand (PPT slayd kontenti + logodan chiqarilgan): primary `#0095D9`, success `#2EC27E`, ink `#1A2333`, muted `#6B7689`, bg `#F4F6F9`, border `#E6EDF3`; warning `#F5A623`, error `#E5484D` |
| Logo | Qosid uchun alohida (smartup logosi EMAS) — SVG wordmark + geometrik mark, dizaynda taklif → user tanlaydi |
| i18n | O'zbekcha + i18n-ready (react-i18next, `uz.json`; keyin RU/EN faqat locale fayli) |
| Settings UX | **Gibrid** — typed formalar (Source Auth, Tenant Mapping + Test) + generic guruhlangan key-value jadval (Advanced) |

## 3. Smartup palitra (Ant theme token)

```
colorPrimary:        #0095D9   // brand ko'k (logo)
colorSuccess:        #2EC27E   // yashil (logo)
colorWarning:        #F5A623   // amber (derived)
colorError:          #E5484D   // qizil (derived)
colorTextHeading:    #1A2333   // navy-ink (logo wordmark)
colorTextSecondary:  #6B7689   // muted
colorBgLayout:       #F4F6F9   // light surface
colorBorder:         #E6EDF3   // border
colorAccentMagenta:  #A2228D   // kam ishlatiladi
```

Bitta `frontend/src/theme.ts` faylda; `ConfigProvider`ga uzatiladi. Kelajakda dark-theme shu token'lardan.

## 4. D0 — Frontend poydevori

### 4.1 Loyiha tuzilishi
```
frontend/
  package.json, vite.config.ts, tsconfig.json
  index.html
  src/
    main.tsx, App.tsx
    theme.ts                 // Smartup token
    i18n.ts, locales/uz.json
    api/client.ts            // fetch/axios wrapper + CSRF + 401 handling
    auth/                    // AuthProvider, useAuth, LoginPage, RequireAuth
    layout/                  // AppLayout (Sider+Header+Content), Nav, Logo
    pages/settings/          // D1 (§5)
    components/              // umumiy (form field, secret input, ...)
```

### 4.2 Auth oqimi
- **Login sahifa** → `POST /qosid/admin/api/login` (username/password). Muvaffaqiyat → session cookie o'rnatiladi.
- **Session tekshirish:** app yuklanganда `GET /qosid/admin/api/me` — 200 bo'lsa authenticated, 401 bo'lsa login'ga.
- **RequireAuth** route guard: auth yo'q → `/login`.
- **Logout:** `POST /qosid/admin/api/logout` → login'ga.
- **Change-password:** `POST /qosid/admin/api/change-password` (Header user menyusidan). Birinchi-boot `admin`/`admin` — parol o'zgartirishga undash banneri.
- **CSRF:** `api/client.ts` `XSRF-TOKEN` cookie'ni o'qib `X-XSRF-TOKEN` header'ga qo'yadi (mavjud B1 SPA-CSRF mexanizmi). Har 401 → login'ga redirect.

### 4.3 Layout shell
- Ant `Layout`: chap **Sider** (collapsible nav), yuqori **Header** (Qosid logo chapда, o'ngда til-switcher + user-menu[change-password, logout]), **Content** (routed pages).
- **Nav elementlari:** `Settings` (faol, D1). `Errors`, `Statistics`, `Logs` — disabled placeholder (tooltip: "keyingi fazada"). D2-D5 shularni yoqadi.
- **React Router:** `/login`, `/` → `/settings`, `/settings/*`.

### 4.4 Build integratsiyasi
- `frontend-maven-plugin`: `install-node-and-npm` → `npm ci` → `npm run build`; output `frontend/dist` → `target/classes/static/`.
- `pom.xml` yangi profil `frontend` (default faol) va `skip-frontend` (CI/tez build uchun).
- **SPA fallback:** Spring `WebMvcConfigurer` yoki controller — noma'lum non-API GET → `index.html`; `/qosid/api/**`, `/qosid/admin/api/**`, `/actuator/**`, static asset'lar tegilmaydi.

### 4.5 Qosid logo
- Yengil SVG: wordmark "Qosid" (ink `#1A2333`) + geometrik mark (primary/success gradient, "qosid"=xabarchi → flow/marshrut motivi). Dizaynda 2-3 variant taklif → user tanlaydi. `frontend/src/layout/Logo.tsx` (inline SVG).

## 5. D1 — Settings moduli (gibrid)

Sidebar/tab 3 bo'lim (`pages/settings/`):

### 5.1 Source Auth (`SourceAuthPage`)
- `GET /qosid/admin/api/source-auth` → forma to'ldiriladi (secret'lar `***` masklangan).
- `type` dropdown: `basic` | `apikey` | `oauth2` → shartli maydonlar (basic: username/password; apikey: header/value; oauth2: token-url/client-id/client-secret/scope/cache-ttl). Secret'lar parol-input.
- **Saqlash:** `PUT /source-auth` (validatsiya backend'da; xato → forma error).
- **Test tugma:** `POST /source-auth/test` → `{ok, type, message}` strukturaviy natija (Alert bilan ko'rsatiladi).

### 5.2 Tenant Mapping (`TenantMappingPage`)
- Maydonlar: `gateway.tenant.field-name`, `extra-field-name`, `default-rest-tenant`, `trust-extra-tenant-header` (toggle) — generic settings API orqali o'qish/yozish (`GET /settings`, `PUT /settings/{key}`).
- **Test tugma:** namuna Biruni-pull JSON'ni textarea'ga joylab `POST /qosid/admin/api/tenant-mapping/test` → resolve bo'lgan `tenantId`/`extraTenantId` ko'rsatiladi.

### 5.3 Advanced (`AdvancedSettingsPage`)
- `GET /qosid/admin/api/settings` → barcha kalitlar, key-prefix bo'yicha guruhlangan (`gateway.response.*`, `gateway.kafka.*`, ...). Ant `Table` (collapse-per-group).
- Inline tahrir → `PUT /qosid/admin/api/settings/{key}`. Secret kalitlar `***` masklangan, tahrirda yangi qiymat kiritiladi.
- **Audit:** `GET /qosid/admin/api/settings/audit` → kim/qachon/qaysi kalitni o'zgartirgani jadvali.

## 6. Testlash (faza oxirida Claude qiladi; user yakuniy manual test)

- **Frontend unit (Vitest + React Testing Library):** RequireAuth guard (auth yo'q → login), Source Auth forma type-switch (dropdown → to'g'ri maydonlar), settings jadval inline-edit PUT chaqiradi, i18n `t()` kalitlar render bo'ladi.
- **E2E (Playwright):** login → settings → Source Auth Test tugma oqimi (mock backend yoki real app).
- **Spring integratsiya:** `/` (va noma'lum yo'l) `index.html` beradi; `/qosid/api/**` va `/qosid/admin/api/**` chain'lari tegilmaydi (SPA fallback API'ni yutmaydi).
- **Jonli smoke:** `mvn package` → JAR ko'tarish → brauzerda login + har uch settings bo'limini haqiqiy drive (Playwright/chromium screenshot bilan).
- **Regressiya:** mavjud backend testlar (94) yashil qoladi — faqat yangi frontend qatlami + SPA-fallback config qo'shiladi, pipeline yadrosi tegilmaydi.

## 7. Risklar

| Risk | Yumshatish |
|---|---|
| SPA fallback API yo'llarini yutib qo'yishi | Fallback faqat non-API, non-static GET; `/qosid/api`, `/qosid/admin/api`, `/actuator` aniq istisno + integratsiya testi |
| CSRF/session same-origin buzilishi | Same-origin serving; `api/client.ts` XSRF-TOKEN→header; B1 mexanizmi qayta ishlatiladi |
| Build sekinlashuvi (node har build'да) | `skip-frontend` profili; `npm ci` cache; frontend faqat o'zgarganda rebuild |
| Prod deploy o'zgarishi | Chiqиsh baribir bitta JAR; systemd/`mvn package` oqimi o'zgarmaydi |
| Secret'lar UI'da ochilishi | GET'da `***` mask (B1); frontend hech qachon plaintext secret ko'rsatmaydi |
| Ant "look" Smartup'ga to'liq mos emasligi | ConfigProvider token'lar + kerak bo'lsa component-level override |

## 8. Bog'liqlik

B1 (admin auth: login/logout/me/change-password; settings GET/PUT/audit; SPA-CSRF; 2 SecurityFilterChain), B3 (source-auth API), C-faza (tenant-mapping/test API) — **hammasi tayyor**. Yangi: Node/npm build-time (frontend-maven-plugin orqali, runtime'da emas).

## 9. Ko'lamdan tashqarida (keyingi D-fazalar)

- **D2** — Error moduli (DLQ/server/internal xatolar) — yangi backend API kerak.
- **D3** — Statistics dashboard (message flow, Prometheus).
- **D4** — Loki log integratsiyasi.
- **D5** — AI xato-analiz.
- Dark theme (token'lar tayyor, lekin bu fazada yoqilmaydi).
- Bir nechta admin akkaunt / rol (B1 bitta akkaunt).
