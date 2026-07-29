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

## 1b. Ikki yo'nalishli shlyuz — pozitsiyaning asosi

QosID Flow main app'ning oldida **ikkala yo'nalishda** turadi. Bu pitch'dagi eng keng qamrovli
gap va u kod bilan tasdiqlangan — ikkala oqim ham prodda ishlayapti.

**Chiquvchi (ORACLE oqimi, E1–E5):** main app → QosID Flow → tashqi API'lar
Manbadan so'rovlar tortiladi, Kafka orqali yuk taqsimlanadi, tashqi API'ga retry + circuit
breaker + OAuth2 bilan yetkaziladi, javob manbaga qaytariladi.

**Kiruvchi (REST oqimi):** tashqi mijozlar → `/qosid/api/v1/queue/**` → main app
Prodda `API_BASE_URL` aynan main app'ga qaratilgan.

Kiruvchi yo'nalish nima beradi:

- **Main app burst'dan himoyalanadi** — so'rov Kafka'da navbatga tushadi, main app o'z tezligida yeydi
- **Main app o'chiq bo'lsa ham so'rov yo'qolmaydi** — navbatda kutadi, tiklanganda yetkaziladi
- **Circuit breaker va per-host chegara** main app'ni bosib qolishdan saqlaydi
- **Mijoz timeout o'rniga `202` + kuzatuv havolasini oladi** — uzun so'rovlar ulanishni band qilmaydi
- **Auth darvozasi** — ulanadigan `AuthorizationProvider`
- **Fayl yuklash** — S3 va zaxira yo'li bilan
- Kiruvchi trafik ham xuddi shu observability ostida: statistika, xatolar, DLQ, alert

**Bozor uchun ahamiyati:** bu bizni bitta emas, **ikkita** toifa kesishmasiga qo'yadi —
integratsiya middleware (Confluent tomoni) **va** API-shlyuz (Kong tomoni). TAM hisobida
ikkala segment ham hisobga olinadi.

**Pitch jumlasi:**
> «QosID Flow main app'ning oldida ikkala yo'nalishda turadi: unga kelayotgan so'rovlarni ham,
> undan chiqayotgan so'rovlarni ham boshqaradi.»

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
