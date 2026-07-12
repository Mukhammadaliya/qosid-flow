# D3: Statistics Dashboard — Prometheus proxy + Smartup chartlar — Dizayn hujjati

**Sana:** 2026-07-12
**Holat:** Tasdiqlangan (brainstorming sessiyasi asosida)
**Muallif:** Muxammadali + Claude
**Bog'liq:** [2026-07-12-d0-d1-frontend-foundation-settings-design.md](2026-07-12-d0-d1-frontend-foundation-settings-design.md) (Statistics nav placeholder), [2026-07-12-d2-error-module-design.md](2026-07-12-d2-error-module-design.md) (Loki proxy pattern qayta ishlatiladi). D-fazaning uchinchi quyi-fazasi.

## 1. Maqsad

Qosid operatorlariga pipeline **message-flow statistikasini** ko'rsatuvchi dashboard: throughput, success/error rate, pipeline funnel, xato breakdown, latency. Ma'lumot manbai — allaqachon mavjud `GatewayMetrics` (E1-E5 + DLQ + E2E + API + RocksDB + Callback + Upload counter/timer'lari, `/actuator/prometheus`da ochilgan, Prometheus tomonidan scrape qilinadi). D3 buni SPA'da **Smartup-native chartlar** bilan ko'rsatadi.

## 2. Kelishilgan qarorlar (savol-javob natijalari)

| Savol | Qaror |
|---|---|
| Stats manba | **Prometheus proxy + custom chartlar** (Grafana embed EMAS) — D2/Loki pattern, Smartup-native, izchil UX |
| Panellar | **Message-flow fokusli** — KPI kartalar + throughput + funnel + error breakdown + latency |
| Chart lib | **@ant-design/plots** (Funnel tayyor, Ant theme, Smartup rang) |
| Latency | **p95 boshidan** — micrometer percentile-histogram yoqiladi → PromQL `histogram_quantile` |
| Interaktivlik | Faqat ko'rish + vaqt-oraliq picker + auto-refresh toggle |

## 3. Arxitektura (D2 pattern)

```
qosid JAR ──/actuator/prometheus──> Prometheus (scrape, host.docker.internal:8091)
                                          ↑
Browser ── SPA (/statistics) ──> qosid /qosid/admin/api/stats ──PromQL query/query_range──┘
                                  (admin-auth proxy; Prometheus ichki, 127.0.0.1:9090)
```

- Backend **read-only proxy** Prometheus HTTP API'sini so'raydi: `/api/v1/query` (instant, KPI/funnel uchun) va `/api/v1/query_range` (vaqt-qatori, chartlar uchun).
- Prometheus **ichki** qoladi: compose'da `127.0.0.1:9090:9090` loopback bind (D2/Loki singari — 0.0.0.0 ochilmaydi); host JAR uni `localhost:9090` orqali so'raydi.
- Config `gateway.prometheus.url` — **D2'da o'rgangan `@Value`+SettingsService fallback pattern**: `settings.get("gateway.prometheus.url", @Value("${gateway.prometheus.url:http://localhost:9090}"))`. H2 override yutadi, yaml/`PROMETHEUS_URL` haqiqiy fallback. (D2 o'lik-config blocker takrorlanmaydi.)
- Prometheus-down/xato → graceful `{series: [], error: "..."}` HTTP 200 (**never-500**, D2 pattern).
- **Preset so'rovlar** (brauzerdan ixtiyoriy PromQL EMAS): proxy nomlangan preset'larni qabul qiladi, ularni qat'iy PromQL'ga xaritalaydi. Bu injection yuzasini yopadi va API'ni sodda qiladi.

## 4. Ilova o'zgarishlari

### 4.1 Percentile-histogram (p95 uchun)
`application.yml` `management` bo'limiga:
```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        gateway.request.e2e.duration: true
        gateway.http.request.duration: true
```
Bu micrometer'ni `_seconds_bucket` (histogram) seriyalarini emit qilishga majbur qiladi → PromQL `histogram_quantile(0.95, ...)` ishlaydi. Pipeline mantig'i tegilmaydi — faqat metrik-detallashtirish.

### 4.2 Metrik nomlash (micrometer → Prometheus)
Micrometer nomlarni konvertatsiya qiladi: nuqta→pastki chiziq; **Counter'larga `_total` qo'shiladi** (masalan `gateway.http.request.total` → `gateway_http_request_total_total`); Timer'lar `_seconds_count`/`_seconds_sum`/`_seconds_bucket` beradi. **Aniq PromQL nomlari implementatsiyada jonli `/actuator/prometheus` chiqishiga qarab yakunlanadi** (plan bosqichida tekshiriladi) — quyidagilar vakillik uchun.

## 5. Backend — Stats proxy API

Yangi `StatsController` (`/qosid/admin/api/stats`, admin himoyalangan chain):

- `GET /stats?preset=<name>&from=&to=&step=` — preset bo'yicha PromQL, natija normallashtiriladi.
- Preset'lar (har biri qat'iy PromQL, vakillik ko'rinishi):
  - **`kpi`** (instant, `/query`): throughput = `sum(rate(gateway_api_request_received_total[5m]))`; success% / error% = HTTP success vs (4xx+5xx+timeout) nisbati; dlq = `increase(gateway_dlq_sent_total[$window])`; e2e_p95 = `histogram_quantile(0.95, sum(rate(gateway_request_e2e_duration_seconds_bucket[5m])) by (le))`. Natija: KPI raqamlar to'plami.
  - **`throughput`** (range, `/query_range`): API-received rate, HTTP-success rate, error rate — vaqt bo'yicha ko'p seriya.
  - **`funnel`** (instant): `increase(...[window])` — oracle_pull_success, kafka_produce_success, consumer_received, http_success, oracle_save_success (5 bosqich).
  - **`errors`** (range): HTTP 4xx / 5xx / timeout + DLQ rate — vaqt bo'yicha stacked seriyalar.
  - **`latency`** (range): E2E p95 va HTTP p95 — `histogram_quantile(0.95, sum(rate(..._bucket[5m])) by (le))`.
- `from`/`to` (default oxirgi 1 soat), `step` (default 30s), preset uchun `$window` (default 5m).
- Normalizatsiya: Prometheus javobi (`data.result[].values` yoki `.value`) → `{series: [{name, points: [[ts, value]]}], error?}`. Instant preset'lar bitta-nuqtali seriyalar (yoki KPI uchun `{kpis: {throughput, successRate, errorRate, dlq, e2eP95}}`).
- WebClient/RestClient bilan Prometheus'ga (D2 `RestClient` yondashuvi qayta ishlatiladi). Prometheus-down → `{series:[], error}` yoki `{kpis:{}, error}`.

## 6. UI (Statistics nav yoqiladi → `/statistics`)

D0+D1'dagi disabled `Statistics` nav elementi yoqiladi:
- **Yuqori qator:** KPI kartalar (Ant `Statistic`/`Card`) — Throughput (req/s), Success %, Error %, DLQ (oyna), E2E p95 (ms).
- **2×2 chart grid** (@ant-design/plots, Smartup theme):
  - Throughput (Line/Area) — API-received / HTTP-success / error.
  - Pipeline Funnel — pull→produce→consume→http→save.
  - Error breakdown (Column/stacked) — 4xx/5xx/timeout/DLQ.
  - Latency (Line) — E2E p95 / HTTP p95.
- **Boshqaruv:** vaqt-oraliq picker (preset 15m/1h/6h/24h, default 1h) + **auto-refresh** toggle (default 30s, pauza qilinadi). Manba `api/stats.ts` wrapper (`queryStats(preset, params)`).

## 7. Testlash

- **Stats proxy:** PromQL preset xaritalash (har preset to'g'ri PromQL beradi), Prometheus javob normalizatsiyasi (range → series, instant → kpis), Prometheus-down → `{series:[]/kpis:{}, error}` (never-500), noma'lum preset → graceful xato (500 emas), auth-gate (401 login'siz). Prometheus HTTP mock (MockRestServiceServer).
- **UI (Vitest):** KPI kartalar render (mock kpis), chart data mapping (mock series → @ant-design/plots data), auto-refresh toggle, vaqt-oraliq o'zgarishi query param'ga o'tadi. Mock backend.
- **Regressiya:** mavjud backend testlar + frontend testlar yashil; pipeline (E1-E5) tegilmaydi — faqat read-only proxy + histogram config qo'shiladi.
- **Jonli smoke:** lokal compose (prometheus up) + JAR; `/statistics`da KPI + chartlar ko'rinishi; Prometheus-down → graceful bo'sh + xato banner.

## 8. Xavfsizlik / deploy

- Prometheus compose'da `127.0.0.1:9090:9090` loopback bind (hozir `9090:9090` — D2/Loki singari tuzatiladi).
- `gateway.prometheus.url` default `http://localhost:9090` (host JAR); prod'da `PROMETHEUS_URL` yoki settings override.
- Prometheus JAR'ni `host.docker.internal:8091/actuator/prometheus`dan scrape qiladi (mavjud, o'zgarmaydi).
- **Deploy MANUAL** — user staging manual-testdan keyin master'ga merge + prod compose'ni yangilaydi.

## 9. Risklar

| Risk | Yumshatish |
|---|---|
| Prometheus down → UI 500 | Proxy graceful → `{series:[]/kpis:{}, error}` 200, xato banner |
| Prometheus brauzerga ochilishi | Loopback bind + admin-auth proxy (ichki qoladi) |
| Metrik nomlari noto'g'ri PromQL | Aniq nomlar plan'da jonli `/actuator/prometheus`dan tekshiriladi |
| loki.url o'lik-config bug takrori | D2 `@Value`+SettingsService fallback pattern qayta ishlatiladi |
| p95 uchun histogram cardinality/hajm | Faqat 2 timer'da histogram yoqiladi (e2e + http), butun metrikalarda emas |
| @ant-design/plots bundle hajmi | Faqat kerakli chartlar import; lazy-load ixtiyoriy (keyin) |

## 10. Ko'lamdan tashqarida

- Ixtiyoriy PromQL brauzerdan (faqat preset'lar).
- Alerting (mavjud alertmanager o'z ishida).
- Grafana embed (Prometheus'ni Grafana'da ham ko'rish mumkin, lekin UI mustaqil).
- Per-tenant statistika (hozir global; tenant label bo'yicha bo'lish keyingi iteratsiya).
- AI xato-analiz (**D5** — keyin).
- Tarixiy eksport / hisobotlar (YAGNI).
