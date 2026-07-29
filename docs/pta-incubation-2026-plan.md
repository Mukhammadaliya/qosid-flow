# QosID Flow — President Tech Award 2026, Inkubatsiya treki

**Holat:** ariza tayyorlanmoqda · **Muddat: 10-avgust 2026** · **Hujjat sanasi: 2026-07-29**

Bu fayl — arizaga oid barcha kelishilgan qarorlar va tahlilning to'liq nusxasi. Ish jarayonida
shu fayl yangilanib boradi.

---

## 0. Asosiy qarorlar (kelishilgan)

- **Trek:** Inkubatsiya. Bu yagona mos trek — pastdagi 1-bo'limga qarang.
- **Startap nomi:** `QosID Flow` (aynan shu yozilish)
- **Yuridik shaxs:** IT Park bilan shartnoma tuzgan mavjud yuridik shaxs loyihani o'z loyihasi
  sifatida oladi. G'olib majburiyati allaqachon bajarilgan.
- **Mulkchilik/IP masalasi:** egasi qarori bilan yopiq, qayta ko'tarilmaydi.
- **Jamoa:** 5 kishi — 1 PO, 3 dasturchi, 1 biznes-analitik.
- **Pilot:** Smartup24, nom aytishga va raqamlarni keltirishga ruxsat bor.
- **Biznes-modellar:** beshtasi ham keltiriladi, **top-3: Managed Cloud (№2),
  Per-konnektor/hajm (№4), Joriy etish xizmatlari (№5)**.
- **«Nega inkubatsiya kerak?»** javobi taqdimotga alohida slayd bo'lib kiradi.

---

## 1. Nega inkubatsiya — yagona mos trek

| Trek | Talab | Bizda |
|---|---|---|
| Akseleratsiya | kamida 3 oy sotuv tarixi | sotuv yo'q → mos emas |
| Best Startup Project | Seed/Series A + daromad yoki imzolangan tijorat shartnomasi | ikkalasi yo'q → mos emas |
| **Inkubatsiya** | g'oya/MVP/prototip, jamoa 2–5, 18+ | ✓ |

Inkubatsiya bosqichini belgilaydigan narsa **mahsulot yetukligi emas, tijorat/tashkiliy bosqich**.
QosID Flow: texnologiya prodda pishgan, biznes noldan — bu inkubatsiyaning ta'rifi.

**Rasmiy shartlar (awards.gov.uz/uz/pta/incubation-program):**

- Ishtirok mezonlari: g'oya/MVP/ishlaydigan prototip bosqichi; dasturda ishtirokka tayyorlik;
  aniq belgilangan muammo va yechim; jamoa 2–5 a'zo; 18 yosh va undan katta
- Dastur: 3 oylik, onlayn — validatsiya, mahsulotni shakllantirish, mentorlik, seminarlar
- Mukofot: har g'olibga **$40 000 qaytarilmaydigan grant**
- G'olib majburiyati: O'zbekistonda yuridik shaxs + IT Park bilan shartnoma
- Ishtirok faqat O'zbekiston fuqarolari uchun
- Voronka: **200 ariza → 100 ishtirokchi → 25 g'olib**
- Sahifadagi nomuvofiqlik: bir joyda «25 g'olib», boshqa joyda «15 ta startap grant oladi» —
  aniqlashtirish kerak

---

## 1b. Ikki yo'nalishli shlyuz — to'liq tavsif

Bu pitch'ning eng keng qamrovli gapi va u kod bilan tasdiqlangan: ikkala oqim ham prodda
ishlayapti, ikkalasi ham bitta quvurdan o'tadi va bir xil kafolat oladi.

```
KIRUVCHI    tashqi mijozlar ──▶ QosID Flow ──▶ main app
                                navbat · retry · back-pressure · auth

CHIQUVCHI   main app ─────────▶ QosID Flow ──▶ tashqi API'lar
                                retry · circuit breaker · OAuth2
```

---

### KIRUVCHI yo'nalish — main app'ni himoyalash

Tashqi mijozlar `/qosid/api/v1/queue/**` ga yozadi; prodda `API_BASE_URL` main app'ga
qaratilgan. So'rov quyidagi qadamlardan o'tadi, va **har bir qadam aniq bir nosozlikni yopadi**:

**1. Auth darvozasi** (`AuthorizationProvider`)
Ulanadigan interfeys — standart holatda ochiq, lekin har qanday autentifikatsiya sxemasi
qo'shiladi. Main app o'z auth logikasini o'zgartirmasdan oldida darvoza oladi.

**2. Tana hajmi chegarasi** — `Content-Length` bo'yicha **darhol**
Prodda sinovdan o'tgan: 21 MB tana **413** oldi va faqat 1.77 MB yuklangandan keyin rad etildi,
ya'ni chegara buferlashdan **oldin** ishlaydi. Bu main app'ga ham, bizga ham xotira hujumidan
himoya.

**3. Callback URL validatsiyasi**
`X-Callback-Url` sarlavhasi tekshiriladi — mijoz bizni ichki manzillarga so'rov yuborishga
majburlay olmaydi (SSRF himoyasi).

**4. Fayl yuklash** — `BiruniUpload: param|alone`
Multipart tahlil qilinadi, fayllar S3'ga yuklanadi (3 marta urinish), SHA-256 bo'yicha
dublikatlar yo'qotiladi. **S3 ishlamasa** — avtomatik zaxira yo'l: fayl main app'ga multipart
sifatida uzatiladi va DB'ga saqlanadi. Ya'ni fayl oqimi S3 nosozligida ham to'xtamaydi.

**5. Kafka'ga qo'yish — va faqat shundan keyin javob**
Bu eng muhim nozik nuqta: `202 ACCEPTED` javobi **so'rov Kafka'ga haqiqatan yozilgandan keyin**
qaytariladi (`sendApiRequest(...).get(10s)`). Ya'ni «qabul qildik» degani «urinib ko'ramiz» emas,
**«bardoshli navbatga yozildi»** degani. Agar Kafka javob bermasa, mijoz xato oladi va so'rovni
o'zi qayta yuborishi mumkin — jim yo'qolish yo'q.

**6. Mijozga `202` + kuzatuv havolasi**
Mijoz ulanishni ushlab turmaydi. Natijani `GET /requests/{id}/status` va `/result` orqali oladi,
yoki `X-Callback-Url` bergan bo'lsa — tayyor bo'lganda o'zi chaqiriladi.

**7. Yetkazish** — main app'ga, retry + circuit breaker + per-host chegara bilan

**Natijada kiruvchi tomonda yopiladigan nosozliklar:**

| Nosozlik | Ilgari nima bo'lardi | QosID Flow bilan |
|---|---|---|
| Mijozlardan burst keldi | main app bo'g'iladi, timeout'lar | Kafka'da navbat, main app o'z tezligida yeydi |
| Main app o'chiq / restartda | so'rovlar yo'qoladi, 502 | navbatda kutadi, ko'tarilganda yetkaziladi |
| Main app sekinlashdi | ulanishlar band bo'ladi, kaskad | per-host chegara + CB, so'rov park qilinadi |
| Uzoq davom etadigan so'rov | mijoz timeout oladi | darhol `202`, natija keyin |
| Katta fayl / xotira hujumi | JVM xotirasi yeyiladi | `Content-Length` darvozasi, 413 |
| Xato qayerda ekani noma'lum | log qidiriladi | statistika, xatolar moduli, DLQ, alert |

---

### CHIQUVCHI yo'nalish — tashqi API'larga yetkazish

Main app tashqi API'larga o'zi bormaydi — so'rovni manba tizimida qoldiradi, QosID Flow uni
tortib oladi va yetkazadi.

**1. E1 — tortish**, har 5 soniyada, to'plam bo'yicha (`@Scheduled`)
Main app HTTP chaqiruvini kutib turmaydi: u yozadi va o'z ishini davom ettiradi.

**2. Tenant chegarasini tarjima qilish**
Ichki model va tashqi tizim maydon nomlari `TenantMappingService` orqali aynan ikki chegarada
almashtiriladi — kod ichida bitta model, simda esa tashqi tizim kutgan nomlar.

**3. Har so'rov uchun OAuth2**
Token provayderi manba tizimidan **so'rov bo'yicha** olinadi, Redis'da keshlanadi, muddati
tugashidan oldin fonda yangilanadi. `401` kelsa — kesh tozalanadi va bir marta qayta urinadi
(urinish hisobini oshirmasdan).

**4. Yetkazish** — per-host parallellik chegarasi → circuit breaker → HTTP

**5. E5 — javobni manbaga qaytarish**, chegaralangan qayta urinish bilan; muvaffaqiyatsiz
bo'lsa DLQ va alert. Javob tanasi 7 kun RocksDB'da arxivlanadi.

**Natijada chiquvchi tomonda yopiladigan nosozliklar:**

| Nosozlik | Ilgari nima bo'lardi | QosID Flow bilan |
|---|---|---|
| Tashqi API yiqildi | skript bloklanadi, kaskad | CB ochiladi, xabar park qilinadi, tiklanganda ketadi |
| Tashqi API sekin | main app thread'lari band | asinxron, main app kutmaydi |
| Token muddati tugadi / «osilib qoldi» | 401 to'lqini, alert bo'roni | kesh tozalanadi, bir marta qayta urinish |
| Bizning burst API'ni bosdi | API rate-limit qiladi, CB ochiladi | per-host chegara — CB umuman ochilmaydi |
| Javob yo'qoldi | qayta so'rash imkoni yo'q | 7 kunlik arxiv, id bo'yicha olinadi |
| Yetkazib bo'lmadi | jim yo'qoladi | DLQ + birlashtirilgan alert |

---

### Ikkala yo'nalish umumiy oladigan kafolatlar

Bular quvurning o'zida, ya'ni yo'nalishga bog'liq emas:

- **Qo'lda ack, tartib bilan** — offset ish tugagandan keyin, tartibli prefiks bo'yicha commit
  qilinadi. Crash yo'qotish emas, qayta yetkazish beradi
- **Idempotentlik** — holat tekshiruvi + taqsimlangan qulf; qayta yetkazish dublikat yaratmaydi
- **Vaqt bo'yicha taslim** — retry hisobi emas, xabar muddati hal qiladi
- **Chegaralangan xotira** — park qilingan to'plam soni ham, baytlari ham cheklangan
- **To'liq observability** — Prometheus statistikasi, Loki xatolari, DLQ, Telegram alert,
  ixtiyoriy AI xato-analizi
- **Ikki oqim ajratilgan** — alohida konsumer, topik va thread pool; bir tomondagi nosozlik
  ikkinchisini to'xtatmaydi

---

### Nega raqobatchilar buni bitta mahsulotda bermaydi

| Toifa | Kim | Nimani qiladi | Nimani qilmaydi |
|---|---|---|---|
| API-shlyuz | Kong, Apigee, nginx | Kiruvchi: auth, rate-limit, marshrutlash | **Sinxron** — main app o'chiq bo'lsa so'rov yo'qoladi; navbat yo'q |
| Integratsiya / iPaaS | Confluent, MuleSoft | Chiquvchi: asinxron yetkazish, retry | Kiruvchi HTTP darvozasi emas |
| In-house skript | har bir jamoa o'zi | Ishlaydi... yiqilguncha | Retry, CB, observability, arxiv |

Ya'ni odatda **ikkita mahsulot, ikkita litsenziya, ikkita operatsion yuk** kerak bo'ladi. QosID
Flow ikkalasini bitta quvurda beradi — chunki ikkala yo'nalish ham aslida bitta masala:
**so'rovni ishonchli yetkazish**, faqat strelka yo'nalishi boshqa.

**Bozor uchun ahamiyati:** TAM hisobi ikkita segment kesishmasidan olinadi — integratsiya
middleware **va** API-shlyuz. Bu bozorni kengaytiradi va per-konnektor narxlashni (4-model)
ikkala tomonga ham qo'llash imkonini beradi: kiruvchi kanal ham, chiquvchi integratsiya ham
narxlanadigan birlik.

**Pitch jumlasi:**
> «QosID Flow main app'ning oldida ikkala yo'nalishda turadi: unga kelayotgan so'rovlarni ham,
> undan chiqayotgan so'rovlarni ham boshqaradi. Odatda buning uchun ikkita mahsulot kerak.»

---

## 2. Raqobat holati

### Kuchli tomonlar

- **Ishlaydigan mahsulot, slayd emas** — prodda ~50.7k so'rov/kun, 99.994% muvaffaqiyat
- **O'lchangan ishonchlilik** — yuk testi: ~330 so'rov/s, 0 yo'qotish, CB ochilmadi
- **Muhandislik jarayoni** — 13 082 satr kod, 379 test, 41 xavfsizlik qoidasi
- **Yetuklik tarixi** — 326 commit, 2026-fevraldan beri, v1.0 → v2.3.1
- **Real avariya → yechim → o'lchov** — 27-iyul: 212 jonli so'rov DLQ'ga tushdi → 3 fazali
  tuzatish → 28-iyul: 5 barobar zich burst 0 yo'qotish bilan
- **Nomlangan pilot** — Smartup24, ruxsat bilan
- **Ijro qobiliyati** — yuridik shaxs + IT Park shartnomasi + to'liq jamoa
- **Balansli jamoa** — faqat dasturchilardan iborat emas

### Zaif tomonlar (hammasi tijorat tomonida)

- Daromad nol
- Ona kompaniyadan tashqarida mijoz kashfiyoti yo'q — **eng jiddiy zaiflik**
- Yagona foydalanuvchi bog'liq tomon — mustaqil traksiya emas
- TAM/SAM/SOM hisoblanmagan
- Jamoada sotuv tajribasi yo'q

Birorta zaiflik texnik emas.

---

## 3. Shanslar

**Birinchi darvoza — ariza → dastur (~50%):** shans yuqori. Ishlaydigan tizim, o'lchangan
raqamlar, tayyor yuridik shaxs, to'liq va balansli jamoa arizani yuqori qismga chiqaradi.

**Ikkinchi darvoza — dastur → grant (~25%):** natija 3 oydagi ishga bog'liq. Grant KPI asosida
beriladi; kodni yaxshilash bu darvozadan o'tkazmaydi. PO va BA aynan shu ishning odamlari,
lekin nol nuqtadan boshlanadi.

Aniq foiz aytilmaydi — bu soxta aniqlik bo'lardi.

---

## 4. Hal qiluvchi savol — taqdimotda slayd

> «Mahsulotingiz prodda ishlayotgan bo'lsa, sizga inkubatsiya nega kerak?»

Savolni kutmasdan o'zimiz ko'taramiz. **Slayd sarlavhasi: «Nima bor va nima yo'q»**

**Bizda bor:**
- Prodda ishlaydigan tizim, ~50.7k so'rov/kun
- 99.994% muvaffaqiyat, 0 yo'qotish (o'lchangan)
- 379 test, 41 xavfsizlik qoidasi
- Yuridik shaxs + IT Park shartnomasi
- 5 kishilik jamoa

**Bizda yo'q:**
- Ona kompaniyadan tashqarida bironta mijoz
- Narx modeli
- Sotuv kanali
- Bozor validatsiyasi

**Yakuniy jumla:**
> «Biz dasturdan mahsulot qurishni emas, buni kim sotib olishini aniqlashni so'raymiz.»

---

## 5. Jamoa — tarkib va formada yozilishi

**Tarkib:** 1 PO · 3 dev · 1 BA

**Kuchli tomoni:** balansli, faqat dasturchilardan iborat emas.

**Bo'shlig'i:** sotuvchi yo'q. Bu inkubatsiya uchun jiddiy muammo emas — 3 oylik dastur bitim
yopish emas, **validatsiya** haqida, va mijoz-suhbati o'tkazish aynan PO va BA ishi.

**Formada rollar shunday yoziladi** (umumiy lavozim nomlari emas):

- **Product Owner** — mahsulot yo'nalishi, mijoz kashfiyoti (customer discovery)
- **Biznes-analitik** — bozor tadqiqoti, narx modeli, mijoz-suhbatlari tahlili
- **Backend/Platforma dasturchisi** — pipeline, ishonchlilik
- **Backend/Integratsiya dasturchisi** — manba konnektorlari
- **Frontend/DevOps dasturchisi** — konsol, joriy etish

**Pitch'dagi jumla:**
> «Uch oyda maqsadimiz sotuv emas, validatsiya — shuning uchun mijoz kashfiyotini PO va
> biznes-analitik olib boradi, dasturchilar esa har bir tasdiqlangan gipotezani mahsulotga
> kiritadi.»

---

## 6. Biznes-modellar

### Top-3 (urg'u beriladi)

**№2 — Managed Cloud** · 6–12 oy
Biz hostlaymiz va boshqaramiz, mijoz faqat ulanadi.
To'lov: oylik obuna. Analog: Confluent Cloud.

**№4 — Per-konnektor / hajm** · narxlash asosi
Har integratsiya-konnektor yoki oylik so'rov hajmi bo'yicha.
To'lov: pay-as-you-go. Analog: Zapier, n8n.

**№5 — Joriy etish xizmatlari** · bugun
O'rnatish, maxsus konnektor yozish, integratsiya-konsalting.
To'lov: loyiha narxi. Analog: mahalliy integratorlar.

### Jadvalda qoladi, urg'usiz

**№1 — Open-core** · 12+ oy — yadro ochiq, korxona qatlami pullik. Confluent, GitLab, Kong.
**№3 — Self-host + Support** · bugun — mijoz o'rnatadi, biz SLA beramiz. Red Hat.

### Tanlov bo'yicha ikkita eslatma

**№2 + №4 birgalikda bitta butun hikoya** — «managed cloud, narxi konnektor va hajm bo'yicha»
zamonaviy usage-based SaaS modeli. Konnektor arxitekturada **real birlik**
(`SOURCE-INTEGRATION-CONTRACT`), ya'ni narxlash o'lchovi mahsulot tuzilishiga mos.

**№3 tushib qolgani bitta segmentni yopadi.** Davlat idoralari va banklar ma'lumot rezidensiyasi
sababli managed cloud'ni ishlata olmaydi. Og'zaki pitch'da bir jumla ayting:

> «Bulutdan foydalana olmaydigan tartibga solinadigan mijozlar — davlat va banklar — uchun
> self-host va support varianti bor.»

### KPI taklifi (grant KPI asosida berilgani uchun o'zimiz taklif qilamiz)

- **20 ta korxona bilan mijoz-suhbati** — PO va BA olib boradi
- **Narx modeli tasdiqlangan** — 3 ta potensial mijozdan narxga munosabat
- **Birinchi pullik pilot** ona kompaniyadan tashqarida yoki imzolangan niyat xati
- **Ikkinchi joriy etish** — Smartup24'dan boshqa muhitga o'rnatish

Oxirgi band texnik ustunlikni tijorat KPI'siga aylantiradi: Biruni bog'liqligini olib tashlash
shunchaki refaktoring emas — «boshqa mijozga o'rnatsa bo'ladi» degan biznes fakti.

---

## 7. Taqdimot — 12 slayd

1. **Muqova** — QosID Flow · «Korxona integratsiyalari uchun Kafka-asosli middleware» · kontaktlar
2. **Muammo** — qo'lda yozilgan mo'rt integratsiya skriptlari; xato yo'qoladi, retry yo'q,
   observability yo'q; tashqi API yiqilsa kaskad avariya
3. **Yechim — ikki yo'nalishli shlyuz** — «Qosid» = xabarchi. Main app'ning oldida ikkala
   yo'nalishda turadi: **kiruvchi** so'rovlar (mijozlar → main app) va **chiquvchi** so'rovlar
   (main app → tashqi API'lar). Ikkalasi ham: qabul → Kafka → yetkazish (retry + CB + OAuth2)
   → natija. Xabar yo'qolmaydi. Diagramma ikki strelka bilan chiziladi — 1b-bo'limga qarang
4. **Mahsulot** — arxitektura diagrammasi + skrinshotlar (Statistics, Errors, Ops konsoli)
5. **⭐ Nima bor va nima yo'q** — 4-bo'limdagi slayd
6. **⭐ Traksiya** — 50.7k so'rov/kun, 99.994%, 0 yo'qotish; avariya → tuzatish → 5× zich burst
7. **Biznes-model** — 5 modelli jadval, top-3 ajratilgan
8. **Bozor** — O'zbekiston enterprise + davlat. **Ikki segment kesishmasi:** integratsiya
   middleware va API-shlyuz (1b-bo'lim). TAM/SAM/SOM *(hali hisoblanmagan)*
9. **Raqobat** — global: Confluent/MuleSoft (chiquvchi, async) va Kong/Apigee (kiruvchi, API
   shlyuz). Ular qimmat, mahalliy support yo'q, data-rezidensiya muammosi bor va **odatda
   bittasini** qiladi. Biz ikkalasini bitta mahsulotda beramiz. Asl raqib esa — in-house skript
10. **Go-to-market** — Smartup/Biruni ekotizimi → IT Park rezidentlari va ERP-integratorlar →
    managed cloud
11. **Jamoa** — 5 kishi, rollari 5-bo'limdagidek
12. **So'rov va KPI** — $40k grant; 3 oylik KPI'lar

5 va 6-slaydlar hal qiluvchi.

---

## 8. Ariza formasi — maydonma-maydon

- **Startap nomi** → `QosID Flow`
- **Kategoriya** → Korporativ yechimlar (muqobil: Chuqur texnologiyalar)
- **Moliyalashtirish bosqichi** → Pre-seed (maydon moliyalashtirishni so'raydi, mahsulot
  bosqichini emas — javob rost)
- **MVP havolasi** → demo instans (9-bo'lim)
- **Video** → ≤3 daqiqa: muammo → arxitektura → jonli demo → traksiya → so'rov
- **GitHub havolasi** → toza public repo (9-bo'lim)
- **Tavsif** → yoziladi
- **Taqdimot** → PDF/PPTX, ≤40 MB
- **Logotip** → SVG/PNG. «QosID» yozilishi brend uchun qulay — «Qosid» (xabarchi) + «ID»
- **Faoliyat hududi** → Toshkent
- **Jamoa** → 5 a'zo, rollari 5-bo'limdagidek
- Ommaviy taklif checkbox

---

## 9. Ikkita bloker

### MVP havolasi

Prod SPA admin-parol ortida, real mijoz ma'lumotlari bor.

1. **Demo instans (tavsiya)** — namunaviy ma'lumot, read-only kirish. Hakam Statistics, Errors,
   Ops sahifalarini ko'radi. ~1 kunlik ish.
2. Ekran yozuvi video ichida + README skrinshotlari — zaifroq
3. Prodga vaqtinchalik akkaunt — tavsiya etilmaydi

### GitHub havolasi

Repo private, tarixida 3 ta sir. Yechim: **tarixsiz (squash) yangi public showcase repo**;
hozirgi repo private qoladi. Public README'ning o'zi pitch materiali bo'ladi.

---

## 10. Reja — 10-avgustgacha

- **1–2-kun** — Tavsif matni + video ssenariysi
- **1–2-kun** — Jamoa rollarining yakuniy matni
- **2–4-kun** — Toza public repo + README
- **3–5-kun** — Demo instans (seed ma'lumot, read-only)
- **4–7-kun** — Taqdimot, 12 slayd
- **5–7-kun** — TAM/SAM/SOM hisobi (BA bilan)
- **6–8-kun** — Video yozib olish
- **8–9-kun** — Logotip
- **9–10-kun** — Yig'ish va ko'rib chiqish

Zaxira 2–3 kun — video yozib olish odatda kutilganidan uzoq davom etadi.

---

## Manbalar

- awards.gov.uz/uz/pta va /uz/pta/incubation-program
- Rasmiy Telegram kanali (muddatlar shu yerdan tasdiqlangan)
- Biznes-model va pitch deck qoralamasi: 2026-07-20 sessiyasi
