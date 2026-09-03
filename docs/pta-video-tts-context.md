# QosID Flow — TTS uchun kontekst fayli (o'zbek, 12 slayd)

**Maqsad:** ovoz generatsiya qiluvchi modelga (Gemini yoki boshqa TTS) berish uchun yagona fayl.
**Manbalar:** `docs/pta-video-script.md` (ssenariy) + `docs/pta-deck/index.html` (deck matni).
Bu fayl **hosila** — ikkalasi o'zgarsa qayta yaratilishi kerak, aks holda eskiradi.

> **Bu faylni to'liq bering, `index.html` ni bermang.** Deck fayli 69 KB va uning
> uch chorak qismi CSS bilan SVG animatsiya — modelga faqat shovqin qo'shadi. Ekranda
> nima turgani quyida har slayd uchun qisqa yozilgan.

---

## 1. Modelga vazifa

> Sen professional ovozli aktyorsan. Quyidagi o'zbek tilidagi matnni **slayd bo'yicha
> alohida** o'qib ber. Har slayd — alohida audio fayl.
>
> Ohang: ishonchli, xotirjam, mutaxassisga xos. Reklama ohangi emas, ma'ruza ohangi ham
> emas — hamkorga tushuntirayotgan muhandis ohangi.
>
> Tezlik: sekundiga ~2.2 so'z. Har jumladan keyin qisqa pauza, har slayd oxirida uzunroq.
> Raqamlarni shoshmasdan, ajratib ayt.
>
> **Faqat «O'qiladigan matn» bo'limlarini o'qi.** «Ekranda» va «Izoh» qatorlari — rejissyor
> uchun, ular ovozga chiqmaydi.

---

## 2. Loyiha nima (kontekst uchun, o'qilmaydi)

QosID Flow — korxona tizimlari o'rtasidagi so'rovlarni ishonchli yetkazuvchi Kafka-asosidagi
integratsiya platformasi. Asosiy ilovaning oldida **ikkala yo'nalishda** turadi: unga
kelayotgan so'rovlarni ham (tashqi mijozlar → asosiy ilova), undan chiqayotganini ham
(asosiy ilova → tashqi API'lar). So'rov navbatga yoziladi, chegaralangan qayta urinish,
circuit breaker va OAuth2 bilan yetkaziladi; yetkazib bo'lmagani jim yo'qolmaydi.

2026-yil apreldan beri ishlab chiqarishda. Taqdimot — President Tech Award 2026,
inkubatsiya treki. Video 3 daqiqadan oshmasligi kerak.

---

## 3. Talaffuz

Matnda to'rtta chet so'z bor, qolgani toza o'zbekcha:

| Yozilishi | Qanday aytilishi |
|---|---|
| QosID Flow | «Qosid Flou» — «Qosid» (xabarchi) + inglizcha «flow» |
| API | «a-pi-i» (harflab) |
| Telegram | «Telegram» (o'zbekcha odatdagi talaffuz) |
| HTTP | «ha-te-te-pe» (harflab) |

---

## 4. DIQQAT — raqamlar deck bilan mos kelmayapti

Ssenariyning o'zida yozilgan qoida bor: raqamlar uchala tilda va ekranda bir xil bo'lishi
kerak. Hozir mos kelmaydi. **Ovoz yozishdan oldin qaysi biri to'g'ri ekanini hal qiling** —
aks holda hakam ekranda bir raqamni ko'rib, quloqda boshqasini eshitadi.

| | Ssenariyda aytiladi | Ekranda (deck) | O'lchangan manba |
|---|---|---|---|
| kunlik hajm | «oltmish mingdan oshiq» | **60 600** | 60 591/kun |
| muvaffaqiyat | «to'qson to'qqiz foiz» | **99.68 %** | 99.68 % |
| cho'qqi | «deyarli mingta» | **918 /s** | 918 (28.07), oddiy kunda 380 (31.07) |
| 27-iyul hodisasi | **olib tashlangan** | bor (241 DLQ) | 241 DLQ, 14:27 UTC |
| xavfsizlik qoidasi | «qirqtadan ko'p» | **41** | 41 |

Muvaffaqiyat qat'iy usulda hisoblangan: xato navbatiga tushgan so'rov muvaffaqiyatsiz
sanaladi, keyin qayta yuborilib o'tgan bo'lsa ham.

---

## 5. Slaydlar

Quyida har slayd uchun: xronometraj · ekranda nima turadi · o'qiladigan matn.
O'qiladigan matn imlo jihatidan tozalangan (apostroflar tiklandi, takror so'zlar olindi) —
**mazmun tegilmagan**, o'zgarishlar ro'yxati 6-bo'limda.

---

### Slayd 01 · 0:00–0:14 · Ilgak

**Ekranda:** sarlavha, `QosID Flow`, ostida taglayn, paketlar shlyuz orqali oqib turadi.

**O'qiladigan matn:**

> Korxona tizimlari bir-biri bilan doimiy ravishda ma'lumotlar almashadi.
> O'sha jarayon uzilganda — buyurtma yo'qoladi, to'lov o'tmaydi, hisobot kelmaydi.
> Va ko'pincha, afsuski, buni birinchi bo'lib mijoz aytadi.
> E'tiboringizda — QosID Flow.

**IXTIYORIY BLOK** — ovoz yozishdan oldin qoldirish yoki o'chirish kerak:

> Korxona tizimlari o'rtasidagi har bir so'rovni ishonchli yetkazadigan platforma. Yukni
> vaqt bo'yicha taqsimlaydi: yo'qolmaydi, takrorlanmaydi va asosiysi, barcha jarayonlar
> ko'rinib turadi.

**Izoh:** bu taglayn **ekranda ham yozilgan**, ssenariyning o'z qoidasi esa «ovoz slaydni
o'qib bermaydi» deydi. U 01-slaydni 21 so'zga uzaytirib, 14 soniyalik budjetdan 9 soniya
oshiradi. O'chirsangiz slayd budjetga aynan tushadi. Qoldirsangiz — 01-slayd animatsiyasini
uzaytirish kerak.

---

### Slayd 02 · 0:14–0:30 · Muammo

**Ekranda:** to'rtta muammo navbat bilan chiqadi — server ko'tara olmaydi · skript bloklanadi ·
xato jim yutiladi · yo'qolgani qaytmaydi.

**O'qiladigan matn:**

> Integratsiyalar odatda qo'lda yozilgan skriptlar bilan qilinadi.
> Minglab so'rov birdaniga kelsa — server ko'tara olmaydi.
> Tashqi API sekinlashsa — skript bloklanadi.
> Xato bo'lsa — jim yutiladi.
> Va yo'qolganini hech kim qaytarmaydi.

---

### Slayd 03 · 0:30–0:54 · Yechim

**Ekranda:** topologiya diagrammasi. Paketlar shlyuzdan o'tib rangi ko'kdan yashilga o'zgaradi.

**O'qiladigan matn:**

> QosID Flow asosiy ilovangizning oldida ikkala yo'nalishda turadi: unga kelayotgan
> so'rovlarni ham, undan tashqi API'larga chiqayotganini ham nazorat qiladi.
> Ikkala oqim bitta quvurdan o'tadi: navbatga yoziladi, xato bo'lsa qayta uriniladi — va
> asosiysi, yo'qolmaydi.
> Odatda buning uchun ikkita alohida mahsulot kerak bo'ladi.

---

### Slayd 04 · 0:54–1:16 · Mahsulot

**Ekranda:** konsolning uchta skrinshoti — statistika · xatolar · operator paneli.

**O'qiladigan matn:**

> Jarayonlarning hammasi ko'rinib turadi.
> Birinchisi — jonli statistika: o'tkazuvchanlik, muvaffaqiyat, kechikish.
> Ikkinchisi — xatolar. Yetkazib bo'lmagan so'rov yo'qolmaydi: alohida navbatga tushadi,
> Telegramga xabar keladi, sababini sun'iy intellekt orqali tahlil qilish mumkin bo'ladi.
> Uchinchisi — operator paneli: to'xtatish, ishga tushirish, jonli jurnal.
> Sozlamalar esa faqat tizim to'xtaganda tahrirlanadi — noto'g'ri sozlama jonli trafikka
> ta'sir o'tkaza olmaydi.

---

### Slayd 05 · 1:16–1:44 · Prototip emas

**Ekranda:** to'rtta raqam noldan sanab chiqadi — 60 600 so'rov/kun · 99.68 % · 918/s · 527 test.
Keyin 27.07 → 28.07 hodisasi.

**O'qiladigan matn:** *(raqamlar 4-bo'limdagi ziddiyatga qarab tasdiqlanishi kerak)*

> Bu prototip emas. Aprel oyidan beri production serverda ishlab kelayotgan tizim:
> kuniga oltmish mingdan oshiq so'rov,
> to'qson to'qqiz foiz natija.
> Soniyasiga deyarli mingta so'rov, yo'qotishsiz.
> Va bular imkoniyat chegarasi emas.

---

### Slayd 06 · 1:44–1:54 · Biznes-model

**Ekranda:** beshta daromad modeli jadvali, uchtasi ajratilgan.

**O'qiladigan matn:**

> Bizning loyihamizning beshta daromad oqimi bor, bugungi fokus uchtasiga:
> tizimni joriy etish xizmatlari,
> har bir konnektor uchun to'lov,
> va boshqariladigan bulutli yechim.

---

### Slayd 07 · 1:54–2:03 · Bozor

**Ekranda:** ikki segment kesishmasi — integratsiya middleware va API-shlyuz.

**O'qiladigan matn:**

> Biz ikki segment kesishmasida turamiz: integratsiya platformalari va API-shlyuzlar.

---

### Slayd 08 · 2:03–2:15 · Raqobat

**Ekranda:** uch qatlam — API-shlyuz (Kong, Apigee) · iPaaS (Confluent, MuleSoft) ·
ish-oqim dvigatellari (n8n, Airflow).

**O'qiladigan matn:**

> API-shlyuzlar kiruvchi trafikni oladi, lekin ilova o'chiq bo'lsa so'rov yo'qoladi.
> Integratsiya platformalari chiquvchini uddalaydi, lekin HTTP so'rovlar uchun darvoza
> bo'la olmaydi.
> Ba'zi hollarda ularning ish-oqim dvigatellari katta hajmlarga mo'ljallanmagan bo'ladi.

---

### Slayd 09 · 2:15–2:24 · Bozorga chiqish

**Ekranda:** uch kanal, raqamlangan.

**O'qiladigan matn:**

> Asosan uch kanal:
> pilot ekotizimi mijozlari,
> IT Park rezidentlari,
> ERP-integratorlar,
> keyin boshqariladigan bulutga o'tamiz.

---

### Slayd 10 · 2:24–2:32 · Jamoa

**Ekranda:** besh kishining dumaloq surati, ismi va roli — Muhammadali Ergashev (Product
Owner) · Shavkat Mominov (Biznes-analitik) · Sherzod Chorivey (Backend · Platforma) ·
Jaloliddin Temir (Backend · Integratsiya) · Sarvar Jamoliddinov (Frontend · DevOps).

**O'qiladigan matn:**

> Besh kishimiz: uchtamiz tizimni takomillashtiramiz, ikkitamiz bozorni analiz qilamiz.

---

### Slayd 11 · 2:32–2:48 · Nima bor, nima yo'q

**Ekranda:** ikki ustun — «Bizda bor» va «Bizda yo'q».

**O'qiladigan matn:**

> Bizda ishlab chiqarishda ishlaydigan tizim bor, qirqtadan ko'p xavfsizlik qoidasi bor,
> uch tilli boshqaruv konsoli bor.
> Bizda yo'q narsa — asosiy kompaniyadan tashqarida mijoz, narx modelimiz belgilanmagan,
> sotuv kanali, bozor validatsiyasi mavjud emas.
> Shuning uchun inkubatsiyaga kelyapmiz: mahsulot qurishga emas, uni kim sotib olishini
> aniqlashga.

---

### Slayd 12 · 2:48–3:00 · Yakun

**Ekranda:** to'rtta satr navbat bilan chizib tashlanadi, o'ngda yashil javob paydo bo'ladi.
Oxirida logotip va havolalar.

**O'qiladigan matn:**

> Bizning uch oylik rejamiz — uchta natija:
> birinchi mijoz,
> tasdiqlangan narx va biznes-model,
> IT Park orqali hamkorlik.
> Bu — QosID Flow. E'tiboringiz uchun rahmat.

**Izoh:** ekranda **to'rtta** satr chizib tashlanadi (mijoz · narx · sotuv kanali · bozor
validatsiyasi), ovoz esa uchtasini nomlaydi. Bu ziddiyat emas — ssenariyning o'z qoidasi
«ovoz slaydni o'qib bermaydi» deydi, ro'yxatning to'liq holi ekranda turadi. «O'sha ustun
bo'shaydi» degan jumla ham olib tashlandi: chizib tashlash animatsiyasining o'zi shuni
ko'rsatadi.

---

## 6. Imlo tuzatishlari (mazmun tegilmagan)

**Bu bo'lim endi bo'sh — hammasi manba ssenariyda ham tuzatildi**, ya'ni bu fayl bilan
`pta-video-script.md` o'rtasida matn farqi qolmadi.

Tuzatilganlar (tarix uchun): apostrofsiz `sorov` → `so'rov` (05 va 08-slaydlar),
`Sun'iy Intelekt` → `sun'iy intellekt`, `trafikga ta'sir o'tqaza` → `trafikka ta'sir
o'tkaza`, takrorlangan `yo'qotishsiz`, ortda qolgan `quradi`, va bloklar oxiridagi
yo'q nuqtalar (TTS pauza qo'yishi uchun kerak).

**Nega apostrof muhim:** TTS uchun `sorov` va `so'rov` — boshqa-boshqa so'z, birinchisi
noto'g'ri unli bilan o'qiladi. Kelajakda matn tahrirlansa, shu narsani tekshirish kerak.

Rejissyor izohlari (`*(uzun chiqsa — tashlab yuboring)*`, `**Ekranda:** …`) esa bu faylning
«o'qiladigan matn» bo'limlariga umuman kiritilmagan — ular ovozga chiqmasligi kerak.

Shuningdek rejissyor izohlari (`*(uzun chiqsa — tashlab yuboring)*`, `**Ekranda:** …`)
o'qiladigan matndan chiqarildi — ular ovozga chiqmasligi kerak.

---

## 7. Xronometraj ogohlantirishi

Ssenariy sarlavhasidagi jadval **389 so'z / 2:46–3:05** deb yozilgan. Tahrirlardan keyin
matn qisqardi — hozir **341 so'z**:

| Slayd | So'z | ~Davomiylik (2.2 so'z/s) | Ssenariydagi budjet |
|---|---|---|---|
| 01 | 30 (+21 ixtiyoriy) | 13.6s (ixtiyoriy blok bilan 23.2s) | 0:00–0:14 — blok qolsa **9s oshadi** |
| 02 | 31 | 14.1s | 0:14–0:30 ✓ |
| 03 | 42 | 19.1s | 0:30–0:54 — **5s bo'sh** |
| 04 | 55 | 25.0s | 0:54–1:16 — **3s oshadi** |
| 05 | 30 | 13.6s | 1:16–1:44 — **14s bo'sh** |
| 06 | 22 | 10.0s | 1:44–1:54 ✓ |
| 07 | 9 | 4.1s | 1:54–2:03 — 5s bo'sh |
| 08 | 30 | 13.6s | 2:03–2:15 ✓ |
| 09 | 14 | 6.4s | 2:15–2:24 — 3s bo'sh |
| 10 | 9 | 4.1s | 2:24–2:32 — 4s bo'sh |
| 11 | 45 | 20.5s | 2:32–2:48 — **4s oshadi** |
| 12 | 24 | 10.9s | 2:48–3:00 ✓ |

**Jami ~2:35** (sekinroq o'qilsa ~2:51). Chegara 3 daqiqa, ya'ni xavf yo'q.

Ikkita joyni bilib turish kerak: **05-slaydda 14 soniya bo'sh qoladi** (27-iyul hodisasi
olib tashlangani sababli), ekranda esa aynan o'sha paytda hodisa animatsiyasi ketadi —
ya'ni ovoz jim turganda ekranda voqea davom etadi. **04 va 11-slaydlar** esa budjetdan
oshadi, ularning animatsiyasini uzaytirish yoki matnni qisqartirish kerak bo'ladi.

Bu jadval taxminiy. Audio tayyor bo'lgach har klipning haqiqiy uzunligini o'lchab, deck
animatsiyasiga moslash kerak.
