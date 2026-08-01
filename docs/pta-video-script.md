# QosID Flow — 3 daqiqalik video ssenariysi

**Maqsad:** President Tech Award, Inkubatsiya treki, «Loyiha videosiga havola» maydoni
**Xronometraj:** 2:55–3:00 (chegara — 3 daqiqa, oshib ketmasin)
**Til:** o'zbek. Rus/ingliz versiyasi uchun matn tarjima qilinadi, tuzilma o'zgarmaydi
**Format:** ekran yozuvi + ovoz. Yuz shart emas; oxirgi kadrda bo'lsa yaxshi

**Tuzilma:** ssenariy **deckning 12 slaydi bo'yicha** boradi. Shunday qilinganining sababi —
hakam avval videoni, keyin PDF'ni (yoki teskarisi) ko'radi; ikkalasi bir xil yo'ldan borsa,
ikkinchisi birinchisini mustahkamlaydi. Lekin **slayd o'qilmaydi**: 3, 4 va 5-slaydlarda
ekranda slayd emas, jonli tizim turadi. Slayd — skelet, jonli tizim — go'sht.

---

## Yozishdan oldin: bitta to'siq

4-slayd jonli demo talab qiladi, lokal instansda esa trafik yo'q — Statistika sahifasi
bo'sh chiqadi. Ikki yo'l bor:

1. **Lokalda sun'iy yuk yaratish** (tavsiya etiladi) — ekranda haqiqiy interfeys, haqiqiy
   xatti-harakat, lekin mijoz ma'lumoti yo'q. Video ochiq havolada turadi, prod
   skrinshotidan farqli o'laroq uni orqaga qaytarib bo'lmaydi.
2. Prod'ga tunnel — haqiqiy raqamlar, lekin ekranda tenant nomerlari, so'rov id'lari va
   host nomlari ko'rinadi. **Tavsiya qilinmaydi.**

---

## Xronometraj jadvali

| vaqt | slayd | ekranda | ovoz (so'z) |
|---|---|---|---|
| 0:00–0:14 | 01 | Slayd: sarlavha, paketlar oqadi | ~29 |
| 0:14–0:30 | 02 | Slayd: to'rtta muammo | ~34 |
| 0:30–0:50 | 03 | **Jonli:** topologiya animatsiyasi | ~40 |
| 0:50–1:22 | 04 | **Jonli tizim:** so'rov → konsol | ~64 |
| 1:22–1:50 | 05 | **Jonli:** statistika, keyin slayd | ~56 |
| 1:50–2:00 | 06 | Slayd: biznes-model | ~20 |
| 2:00–2:09 | 07 | Slayd: bozor | ~18 |
| 2:09–2:21 | 08 | Slayd: raqobat | ~24 |
| 2:21–2:30 | 09 | Slayd: kanallar | ~18 |
| 2:30–2:38 | 10 | Slayd: jamoa | ~16 |
| 2:38–2:52 | 11 | Slayd: bor / yo'q | ~28 |
| 2:52–3:00 | 12 | Slayd: yakun | ~15 |

**Jami ~360 so'z.** Sekin gapirilganda ~2:52 — zaxira bor.

---

## Ssenariy

### 0:00–0:14 · Slayd 01 — Ilgak

**Ekranda:** sarlavha slaydi. Paketlar shlyuz orqali oqib turadi. Kursorsiz, tinch.

> Korxona tizimlari bir-biri bilan doim gaplashadi. O'sha suhbat uzilganda — buyurtma
> yo'qoladi, to'lov o'tmaydi, hisobot kelmaydi.
> Va ko'pincha buni birinchi bo'lib mijoz aytadi.
> Men Muxammadali. Bu — QosID Flow.

---

### 0:14–0:30 · Slayd 02 — Muammo

**Ekranda:** to'rtta muammo navbat bilan chiqadi. Har biri chiqqanda 1 soniya to'xtang.

> Integratsiyalar odatda qo'lda yozilgan skriptlar bilan qilinadi.
> Birdaniga ming so'rov kelsa — server ko'tara olmaydi.
> Tashqi API sekinlashsa — skript bloklanadi.
> Xato bo'lsa — jim yutiladi.
> Tizim tiklanganda esa yo'qolganini hech kim qaytarmaydi.

---

### 0:30–0:50 · Slayd 03 — Yechim

**Ekranda:** topologiya diagrammasi. Paketlar shlyuzdan o'tib, rangi ko'kdan yashilga
o'zgarishini **kursor bilan kuzatib boring** — bu videoning eng tushunarli 5 soniyasi.

> QosID Flow asosiy ilovangizning oldida ikkala yo'nalishda turadi.
> Unga kelayotgan so'rovlarni ham boshqaradi, undan tashqi API'larga chiqayotganini ham.
> Ikkala oqim bitta quvurdan o'tadi: navbatga yoziladi, qayta uriniladi — va yo'qolmaydi.
> Odatda buning uchun ikkita alohida mahsulot kerak bo'ladi.

---

### 0:50–1:22 · Slayd 04 — Mahsulot *(videoning yuragi)*

Uch qisqa kadr. Slayd o'rniga **jonli tizim**.

**A · 0:50–1:06 — So'rov yuborish**
**Ekranda:** terminal. Bitta `POST`. Javob — `202 ACCEPTED` va kuzatuv havolasi. **Zoom.**

> So'rov yuboraman. Javob darhol keladi: ikki yuz ikki va kuzatuv havolasi.
> Diqqat qiling — bu javob so'rov navbatga yozilgandan **keyin** qaytariladi.
> Ya'ni «qabul qildik» degani «urinib ko'ramiz» emas, «saqlab qo'ydik» degani.

**B · 1:06–1:14 — Xatolar**
**Ekranda:** Xatolar sahifasi. Bitta yozuvni oching, tafsiloti ko'rinsin.

> Yetkazib bo'lmagan so'rov yo'qolmaydi: alohida navbatga tushadi, Telegramga xabar
> keladi va sababi shu yerda turadi.

**C · 1:14–1:22 — Operator paneli**
**Ekranda:** Ops sahifasi, jonli log oqimi.

> Sozlamalar esa faqat tizim to'xtaganda tahrirlanadi — noto'g'ri sozlama jonli trafikni
> ura olmaydi.

---

### 1:22–1:50 · Slayd 05 — Prototip emas

**Ekranda:** avval jonli Statistika sahifasi (5 soniya), keyin slaydga o'ting — raqamlar
sanab chiqadi. **Raqamlar ekranda kamida 3 soniya tursin.**

> Bu prototip emas. Aprel oyidan beri ishlab chiqarishda: kuniga oltmish ming olti yuz
> so'rov, to'qson to'qqiz nuqta oltmish sakkiz foiz muvaffaqiyat.
> O'lchangan cho'qqi — soniyasiga to'qqiz yuz o'n sakkiz, yo'qotishsiz.
>
> Yigirma yettinchi iyulda avariya bo'ldi: bir daqiqada ikki yuz qirq bitta so'rov xato
> navbatiga tushdi. Sababini topdik, tuzatdik, o'lchadik.
> Ertasi kuni besh barobar zichroq yuklama o'tdi — bitta so'rov ham yo'qolmadi.

---

### 1:50–2:00 · Slayd 06 — Biznes-model

> Beshta daromad oqimi bor, bugungi fokus uchtasi: joriy etish xizmatlari, har bir
> konnektor uchun to'lov, va boshqariladigan bulut.

---

### 2:00–2:09 · Slayd 07 — Bozor

> Biz ikki segment kesishmasida turamiz: integratsiya platformalari va API-shlyuzlar.
> Boshlang'ich plasdarm — O'zbekiston korxona va davlat integratsiyalari.

---

### 2:09–2:21 · Slayd 08 — Raqobat

> API-shlyuzlar kiruvchi trafikni oladi, lekin sinxron — ilova o'chiq bo'lsa so'rov yo'qoladi.
> Integratsiya platformalari chiquvchini uddalaydi, lekin HTTP darvozasi emas.
> Ish-oqim dvigatellari bu hajmga mo'ljallanmagan.

---

### 2:21–2:30 · Slayd 09 — Bozorga chiqish

> Uch kanal, eng past ishqalanishdan boshlaymiz: avval pilot ekotizimi mijozlari,
> keyin IT Park rezidentlari va ERP-integratorlar, so'ngra boshqariladigan bulut.

---

### 2:30–2:38 · Slayd 10 — Jamoa

> Besh kishimiz: uchtasi quradi, ikkitasi bozorni tekshiradi.

---

### 2:38–2:52 · Slayd 11 — Nima bor, nima yo'q

**Ekranda:** ikki ustun. O'ng ustunda to'xtang — bu videoning eng halol kadri.

> Bizda ishlab chiqarishda ishlaydigan tizim bor, qirq bitta xavfsizlik qoidasi bor,
> uch tilli boshqaruv konsoli bor.
> Bizda yo'q narsa — asosiy kompaniyadan tashqarida mijoz, narx modeli, sotuv kanali,
> bozor validatsiyasi.
> Shuning uchun inkubatsiyaga kelyapmiz: mahsulot qurishga emas, **uni kim sotib olishini
> aniqlashga**.

---

### 2:52–3:00 · Slayd 12 — Yakun

**Ekranda:** yopuvchi slayd, to'rtta satr chiziladi. Oxirgi 2 soniyada logotip va havolalar.

> Uch oydan keyin o'sha ustun bo'shaydi.
> Tizim har kuni oltmish ming so'rovni yetkazadi. Qolgani — mahsulotning o'zini kimga
> yetkazishni bilish.
> QosID Flow. Rahmat.

---

## Yozib olish bo'yicha amaliy maslahatlar

**Ekran**
- 1920×1080, brauzer to'liq ekranda (`f` tugmasi deckni to'liq ekranga oladi)
- Bildirishnomalarni o'chiring: Windows → *Focus assist* / *Diqqatni jamlash*
- Keraksiz tablar yopilsin, brauzer profili toza bo'lsin (parol taklifi chiqmasin)
- Konsol sahifalarida shriftni 125–150% ga kattalashtiring — hakam telefonda ko'rishi mumkin
- Kursorni sekin harakatlantiring; muhim joyda 1–2 soniya to'xtang

**Ovoz**
- **Alohida yozib oling**, ekran bilan bir vaqtda emas. Xato qilsangiz butun videoni qayta
  yozish shart bo'lmaydi — faqat o'sha bo'limni qayta dublyaj qilasiz
- Mikrofon og'izdan 15–20 sm, jim xona, deraza yopiq
- Har slaydni alohida dubl qiling; montajda birlashtiriladi

**Montaj**
- Slaydlar orasida 0.5 soniyalik pauza
- Raqamlar ekranda kamida 3 soniya
- Musiqa qo'ysangiz juda past. Umuman qo'ymaslik ham normal
- Chiqarish: MP4, H.264, 1080p, 30 fps

**Nima qilmaslik kerak**
- Slaydni o'qib bermang — eng ko'p uchraydigan xato
- Arxitekturani chuqur tushuntirmang; buning uchun repo bor
- «Biz eng yaxshi mahsulotmiz» demang — raqamlar o'zi gapiradi
- 3:01 bo'lsa ham qayta montaj qiling

---

## Oxirgi kadrdagi havolalar

- GitHub: `github.com/Mukhammadaliya/qosid-flow`
- Demo: *(demo instans tayyor bo'lganda qo'shiladi)*
