# D5: AI Xato-Analiz (opt-in) — Dizayn hujjati

**Sana:** 2026-07-12
**Holat:** Tasdiqlangan (brainstorming sessiyasi asosida)
**Muallif:** Muxammadali + Claude
**Bog'liq:** [2026-07-12-d2-error-module-design.md](2026-07-12-d2-error-module-design.md) (Loki log manbalari + LogTable drawer). D-fazaning **oxirgi** quyi-fazasi — bu tugagach dastlabki 5-qismli talab to'liq bajariladi.

## 1. Maqsad

Operatorlarga xatolarni tez tushunishda yordam beruvchi **AI-tahlil**: admin bir xato ustida "AI tahlil" tugmasini bosadi, tizim xato kontekstini sozlangan LLM'ga yuboradi va **root-cause + tuzatish taklifi** qaytaradi. **Opt-in / default-OFF** — API-kalit sozlanmaguncha funksiya butunlay uxlaydi (0 narx, majburiy bog'liqlik yo'q). Bu loyihaning "NoOp default" ruhiga mos.

## 2. Kelishilgan qarorlar (savol-javob natijalari)

| Savol | Qaror |
|---|---|
| LLM backend | **Sozlanadigan tashqi API**, OpenAI-mos `/chat/completions` (universal). Kalitni user tanlaydi: OpenAI / OpenRouter / Anthropic (OpenAI-compat) / Groq / on-prem Ollama-vLLM |
| Opt-in | **default-OFF** — funksiya faqat base-url + api-key + model sozlanganda faol; aks holda tugma ko'rinmaydi (0 narx) |
| Tahlil ko'lami | **Bitta xato, on-demand** — admin bosganda (narx nazorati); 3 manba (DLQ/JAR/Docker) uchun ham |
| Maxfiylik | Yengil **redaksiya** — Bearer/token/password/Authorization naqshlari `***` bilan masklanadi (tashqi AI'ga yuborishdan oldin) |
| Chiqish | Markdown tahlil (root-cause + tuzatish taklifi) drawer'da |

## 3. Arxitektura (D2/D3 proxy pattern)

```
Browser ── SPA (LogTable drawer "AI tahlil") ──> qosid /qosid/admin/api/ai/analyze
                                                     (admin-auth; redaksiya; never-500)
                                                          │
                                                          └── OpenAI-mos POST {base-url}/chat/completions
                                                                 (Authorization: Bearer <api-key>)
```

- **`AiAnalysisService`** — sozlangan OpenAI-mos endpoint'ga POST (`RestClient`, D2/D3 uslubi). Config `SettingsService`dan (`@Value` fallback YO'Q — kalit yo'q bo'lsa disabled). AI-down/xato → graceful `{analysis:null, error}`.
- **`AiController`** (`/qosid/admin/api/ai`, admin himoyalangan chain):
  - `GET /ai/status` → `{enabled: boolean}` — UI tugmani shartli ko'rsatadi.
  - `POST /ai/analyze` (body: xato konteksti) → `{analysis: string|null, error: string|null}`.
- Pipeline (E1-E5) tegilmaydi — faqat yangi read-only AI proxy + config + UI tugma.

## 4. Config (SettingsService, B1)

| Kalit | Default | Secret |
|---|---|---|
| `gateway.ai.base-url` | (bo'sh — sozlanadi, masalan `https://openrouter.ai/api/v1`) | — |
| `gateway.ai.api-key` | (bo'sh) | **AES** |
| `gateway.ai.model` | (bo'sh, masalan `openai/gpt-4o-mini`) | — |

**Enabled = base-url && api-key && model uchalasi ham bo'sh emas.** `/ai/status` shuni qaytaradi. Kalit yo'q → `enabled:false` → UI tugma yo'q.

## 5. Ma'lumot oqimi (analyze)

1. Admin D2 **umumiy `LogTable` drawer**ida (DLQ/JAR/Docker uchun bir xil) "AI tahlil" tugmasini bosadi.
2. Frontend `POST /ai/analyze` bilan xato kontekstini yuboradi: `{ line, level, labels, parsed }` (LogEntry maydonlari — DLQ uchun `parsed` strukturaviy failureReason/url/status, JAR/Docker uchun `line` + stack).
3. Backend:
   - **Redaksiya:** `Bearer\s+\S+`, `(?i)(authorization|password|token|api-?key)\s*[:=]\s*\S+` naqshlari `***` bilan almashtiriladi.
   - **Prompt:** system-prompt (rol: qosid pipeline SRE-yordamchi; vazifa: root-cause + amaliy tuzatish taklifi; qisqa, o'zbek yoki ingliz) + user-content (redaksiyalangan xato konteksti).
   - **LLM chaqiruv:** `POST {base-url}/chat/completions`, body `{model, messages:[system,user], temperature:0.2, max_tokens:800}`, header `Authorization: Bearer <api-key>`.
   - Javob `choices[0].message.content` → `{analysis}` qaytariladi. Xato/timeout → `{analysis:null, error}` (never-500).
4. Frontend AI-panelda (drawer ichida) markdown render qiladi, yoki xato banner.

## 6. UI

- **Umumiy `LogTable` detail drawer** (D2) — "AI tahlil" tugmasi, faqat `GET /ai/status` `enabled:true` bo'lsa ko'rinadi. Bosilganda: loading → AI markdown javob (drawer ichida panel) yoki xato.
- **Settings** — AI-config formasi (source-auth formasi singari): base-url, api-key(masked, B1 secret), model + **Test** tugma (`POST /ai/analyze` bilan kichik namuna xato → kalit/endpoint ishlashini tasdiqlaydi) + enabled holati.
- i18n (uz.json): `ai.analyze`, `ai.analyzing`, `ai.title`, `ai.notConfigured`, `ai.error`, `ai.baseUrl`, `ai.apiKey`, `ai.model`, `ai.test`.

## 7. Testlash

- **AiAnalysisService:** redaksiya naqshlari (Bearer/token/password masklanadi), prompt yig'ish (system+user), OpenAI-mos javob parse (`choices[0].message.content`), AI-down → `{analysis:null, error}` (never-500), disabled (config bo'sh) → analyze `{error:"not configured"}` va status `enabled:false`. LLM HTTP mock (MockRestServiceServer).
- **AiController:** auth-gate (401 login'siz), `/ai/status` (config bor/yo'q), `/ai/analyze` (mock service).
- **UI (Vitest):** tugma faqat `enabled` bo'lsa render; analyze → markdown ko'rsatiladi; xato banner; disabled holatda tugma yo'q. Mock backend.
- **Regressiya:** mavjud testlar yashil; pipeline (E1-E5) tegilmaydi.
- **Jonli smoke:** kalit yo'q → `/ai/status` `{enabled:false}`, drawer'da tugma yo'q (0 narx tasdiq). (Ixtiyoriy, agar kalit bo'lsa) kalit qo'yib real analyze.

## 8. Xavfsizlik / narx

- **Opt-in:** kalit yo'q → funksiya butunlay o'chiq, hech qanday tashqi chaqiruv/narx yo'q.
- **api-key** AES-shifrlangan (B1), GET'da masklangan, admin-auth ostida.
- **Redaksiya** ochiq sirlarni (token/parol/Bearer) yuborishdan oldin masklaydi — to'liq kafolat emas, lekin asosiy oqishni yopadi.
- **Narx nazorati:** faqat admin-bosganda, bitta xato, `max_tokens` cheklangan.
- **never-500:** AI-down/timeout/noto'g'ri kalit → graceful xato, pipeline'ga ta'sir yo'q.

## 9. Deploy

- Yangi infra YO'Q (tashqi API yoki mavjud on-prem endpoint). `gateway.ai.*` faqat admin UI'dan sozlanadi.
- Kalit prod'da settings'ga qo'yilganda faollashadi; qo'yilmasa — hech narsa o'zgarmaydi.
- **Deploy MANUAL** — user staging manual-testdan keyin master'ga merge.

## 10. Ko'lamdan tashqarida

- Batch/summary tahlil (oxirgi N xato klasteri) — keyingi iteratsiya.
- Avtomatik tahlil (yangi DLQ'da) — hozir faqat on-demand (narx nazorati).
- AI javoblarini keshlash/saqlash — hozir yo'q (har bosishda yangi).
- To'liq PII redaksiya kafolati (faqat yengil naqsh-asosli).
- On-prem model deploy (user o'z endpoint'ini beradi).
