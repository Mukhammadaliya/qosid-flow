# D2: Error Moduli — Loki-backed Log Agregatsiyasi — Dizayn hujjati

**Sana:** 2026-07-12
**Holat:** Tasdiqlangan (brainstorming sessiyasi asosida)
**Muallif:** Muxammadali + Claude
**Bog'liq:** [2026-07-12-d0-d1-frontend-foundation-settings-design.md](2026-07-12-d0-d1-frontend-foundation-settings-design.md) (D-faza poydevori, Errors nav placeholder). Bu D-fazaning ikkinchi quyi-fazasi va **D4 (Loki)'ni yutadi**.

## 1. Maqsad

Qosid operatorlariga xatolarni bir joyda ko'rish imkonini beruvchi Error moduli. Xatolar **uch manbadan** keladi, har biri UI'da **alohida sahifa**:

1. **DLQ** — Kafka dead-letter (pipeline muvaffaqiyatsizliklari, strukturaviy `DlqMessage`).
2. **JAR service loglari** — qosid ilovasining o'z loglari (systemd JAR, logback).
3. **Docker service loglari** — infra konteynerlar (redpanda, redis, prometheus, grafana, alertmanager, ...).

Barcha uch manba **Loki orqali** yig'iladi va so'raladi — uniform arxitektura, alohida H2 error-jadval kerak emas.

## 2. Kelishilgan qarorlar (savol-javob natijalari)

| Savol | Qaror |
|---|---|
| Manbalar | 3 ta: DLQ, JAR loglari, Docker loglari — har biri alohida page |
| Log yig'ish | **Loki + Promtail** (docker-compose'ga qo'shiladi); D2+D4 birlashadi |
| DLQ manba | Loki orqali — DLQ consumer to'liq `DlqMessage`'ni strukturaviy JSON satr sifatida yozadi, Promtail label qiladi, page LogQL `json` bilan parse qiladi |
| Interaktivlik | **Faqat ko'rish** — ro'yxat/filtr/detal (retry/resolve yo'q, pipeline'ga tegmaydi) |
| Retention | **7 kun** — Loki config'ida (app cleanup job yo'q) |
| Server statuslar | (Endi mavzudan tashqari — server xatolar JAR loglariga kiradi, kod-capture emas) |

## 3. Arxitektura

```
qosid JAR (systemd) ──logback──> ${LOG_DIR}/gateway.log  ─┐
                     └─DLQ logger→ ${LOG_DIR}/dlq.log (JSON)┤
Docker konteynerlar ──> container json-logs ───────────────┤→ Promtail → Loki (7d retention)
                                                            │                    ↑
Browser ── SPA (Errors) ──> qosid /qosid/admin/api/logs ────┴──LogQL query_range─┘
                            (admin-auth proxy; Loki ichki qoladi)
```

- **Loki** ichki tarmoqda (docker-compose), brauzerga ochilmaydi. UI faqat qosid backend proxy'si orqali so'raydi (admin-auth + same-origin).
- Pipeline yadrosi (E1-E5) **tegilmaydi** — faqat (a) DLQ consumer'ga bitta strukturaviy-log statement, (b) logback appender, (c) yangi read-only proxy controller.

## 4. Ilova o'zgarishlari

### 4.1 logback-spring.xml
- Mavjud **FILE** appender (`${LOG_DIR}/gateway.log`, pattern `%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} : %msg%n`) — Promtail buni JAR loglari uchun scrape qiladi (regex pipeline: timestamp + level + logger + msg).
- Yangi **DLQ_JSON** appender: alohida logger nomi (masalan `dlq-audit`) → `${LOG_DIR}/dlq.log`, har xato bir qatorlik JSON (`ch.qos.logback...` yoki qo'lda JSON-formatlangan msg). `additivity=false` (asosiy faylga takrorlanmaydi).

### 4.2 DLQ consumer'lar (`OracleDlqConsumer`, `ApiDlqConsumer`)
Mavjud `notificationService.sendDlqAlert(message)`dan keyin, alohida `Logger("dlq-audit")` orqali to'liq `DlqMessage`'ni bir qatorlik JSON sifatida yozadi (maydonlar: category=DLQ, tenantId, requestId, originalTopic, failureReason, errorSource, httpStatus, attemptCount, url, body, failedAt). Jackson `ObjectMapper.writeValueAsString(message)` qayta ishlatiladi. Best-effort: JSON-yozuv o'z try/catch'ida — DLQ consumer mantig'ini buzmaydi. Ack o'zgarmaydi (SAFETY: MANUAL ack).

### 4.3 Promtail config (`promtail-config.yml`, docker-compose'da mount)
Uch scrape_config:
- `qosid-jar`: `${LOG_DIR}/gateway.log` → label `job=qosid-jar`; pipeline `regex` bilan `level` (INFO/WARN/ERROR) ajratiladi, `labels: level`.
- `dlq`: `${LOG_DIR}/dlq.log` → label `stream=dlq`; `json` stage (level=ERROR).
- `docker`: Docker service discovery (`docker_sd_configs` yoki `/var/lib/docker/containers/*/*-json.log`) → label `container=<nom>`; pipeline `json` (docker json-log) + `level` ajratish (best-effort, servis-formatga qarab).

### 4.4 Loki config (`loki-config.yml`)
Bitta-binary, filesystem storage, `retention_period: 168h` (7 kun), compactor retention yoqilgan.

## 5. Backend — Loki proxy API

Yangi `LogQueryController` (`/qosid/admin/api/logs`, admin himoyalangan chain ostida):

- `GET /logs?source=dlq|jar|docker&from=&to=&q=&container=&level=&limit=`
  - `source=dlq` → LogQL `{stream="dlq"} | json` (+ `q` bo'lsa `|= "q"`).
  - `source=jar` → `{job="qosid-jar"}` + `level` (default ERROR; WARN yoqilishi mumkin) + `q`.
  - `source=docker` → `{container=~"<container|.+>"}` + `level`/`q` (container ixtiyoriy).
  - `from`/`to` (default oxirgi 1 soat), `limit` (default 200, maks 1000).
  - Loki `GET {loki.url}/loki/api/v1/query_range` chaqiriladi (WebClient/RestClient), natija normallashtiriladi: `[{timestamp, level, line, labels, parsed}]` (`parsed` — dlq uchun JSON obyekt).
- `GET /logs/labels?name=container` → mavjud konteyner nomlari (docker page selektori uchun; Loki `/loki/api/v1/label/{name}/values`).
- Config: `gateway.loki.url` (yaml default `http://loki:3100`, settings'da override). Loki yo'q/erishib bo'lmasa → bo'sh natija + tushunarli xato (500 emas, `{error, entries:[]}`).

## 6. UI (Errors nav yoqiladi → 3 page)

D0+D1'dagi disabled `Errors` nav elementi yoqiladi; sub-route:
- **`/errors/dlq`** — `LogQueryPage` (source=dlq): jadval ustunlar tenant/requestId/httpStatus/errorSource/failureReason/failedAt (JSON `parsed`dan); detal drawer to'liq JSON + `body`. Filtr: vaqt-oraliq, matn.
- **`/errors/jar`** — qosid JAR loglari: ustunlar timestamp/level/logger/message; level filtr (ERROR default, WARN toggle), matn qidiruv, vaqt-oraliq.
- **`/errors/docker`** — konteyner loglari: container selektor (`/logs/labels`), timestamp/container/level/message; matn, vaqt.

Umumiy komponent `LogTable` (`api/logs.ts` wrapper: `queryLogs(params)`, `getLabelValues(name)`). Har page filtr-panel + Ant `Table` + detal drawer. Manual refresh tugma (auto-refresh ixtiyoriy, YAGNI — hozir yo'q).

## 7. Testlash

- **DLQ strukturaviy log:** unit — `DlqMessage` → bir qatorlik JSON, kutilgan maydonlar; JSON-yozuv fail bo'lsa consumer buzilmaydi (best-effort).
- **LogQuery proxy:** LogQL qurish (har source uchun to'g'ri selektor + level/q), Loki javobini normallashtirish, Loki-down → bo'sh+xato (500 emas), auth-gate (401 login'siz). Loki HTTP mock (WireMock yoki MockRestServiceServer).
- **UI (Vitest):** LogTable render, filtr param'lari to'g'ri yuboriladi, dlq detal JSON parse, docker container selektor. Mock backend.
- **Regressiya:** mavjud 102 backend test + 22 frontend test yashil; pipeline (E1-E5) tegilmaydi — DLQ consumer'ga faqat best-effort log qo'shiladi.
- **Jonli smoke:** lokal compose'ga Loki+Promtail; DLQ'ga test-xabar → dlq page'da ko'rinishi; JAR ERROR log → jar page; konteyner log → docker page.

## 8. Deploy

- Prod `docker-compose.yml`'ga **Loki + Promtail** qo'shiladi; Promtail'ga JAR log fayli yo'li (`${LOG_DIR}`) volume-mount + Docker socket yoki `/var/lib/docker/containers` read-only mount kerak.
- `gateway.loki.url` prod'da `http://loki:3100` (compose ichki tarmoq).
- **Deploy MANUAL** (release-policy) — user staging manual-testdan keyin master'ga merge + prod compose'ni yangilaydi.

## 9. Risklar

| Risk | Yumshatish |
|---|---|
| Loki proxy pipeline'ga ta'sir | Read-only, alohida controller; DLQ log best-effort (try/catch); pipeline tegilmaydi |
| Loki down → UI 500 | Proxy Loki-down'ni ushlaydi → bo'sh natija + xato xabari, 500 emas |
| Loki brauzerга ochilishi | Loki ichki tarmoqda; UI faqat admin-auth proxy orqali |
| Log hajmi / disk | Loki 7-kun retention + compactor; JAR logback allaqachon rolling |
| Docker log level ajratish har servisda farq | Best-effort level parsing; ajratilmasa butun satr ko'rsatiladi (matn filtr ishlaydi) |
| Promtail Docker socket kirish (xavfsizlik) | Read-only mount; Promtail faqat log o'qiydi |

## 10. Ko'lamdan tashqarida

- Log yozish/retry/o'chirish UI'dan (faqat ko'rish).
- AI xato-analiz (**D5** — keyin, shu Loki loglaridan foydalanadi).
- Statistika dashboard (**D3** — Prometheus, alohida).
- Grafana embed (Loki'ni Grafana'da ham ko'rish mumkin, lekin UI mustaqil).
- Auto-refresh / live-tail (keyingi iteratsiya, YAGNI).
- Metrik-asosli alerting (mavjud alertmanager o'z ishida).
