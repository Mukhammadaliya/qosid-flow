# QosID Flow — 3 daqiqalik video ssenariysi

**Maqsad:** President Tech Award, Inkubatsiya treki, «Loyiha videosiga havola» maydoni
**Xronometraj:** 2:50–3:00 (chegara — 3 daqiqa, oshib ketmasin)
**Til:** o'zbek. Rus va ingliz versiyalari: `pta-video-script-ru.md`, `pta-video-script-en.md`
**Format:** **animatsion deck yozuvi + ovoz.** Jonli tizim ko'rsatilmaydi

> **Raqamlar o'zgarsa — uchala ssenariyda ham o'zgartiring.** Ular ovozda so'z bilan
> aytiladi, ya'ni deck bo'yicha raqamli qidiruv ularni topmaydi.
>
> **HAL QILINMAGAN: ovoz va deck bir xil emas.** Uchala ssenariy hozir yumaloqlangan
> raqamlarni aytadi — «oltmish mingdan oshiq», «to'qson to'qqiz foiz», «deyarli mingta»,
> «qirqtadan ko'p» — va 27-iyul hodisasini umuman aytmaydi. Deck ekranda esa aniq
> qiymatlar turadi: **60 600** so'rov/kun · **99.68 %** · cho'qqi **918** so'rov/s ·
> bir daqiqada **241** DLQ · **41** xavfsizlik qoidasi. Ya'ni hakam ekranda bir raqamni
> ko'rib, quloqda boshqasini eshitadi. **Ovoz yozishdan oldin bittasini tanlang:** yoki
> ssenariylarga aniq raqamlarni qaytarasiz, yoki deckdagi raqamlarni yumaloqlaysiz.
> Manba o'lchovlar: 60 591/kun, 99.68 %, 918/s (28.07), 241 DLQ (27.07 14:27 UTC), 41 qoida.

**Tuzilma:** ssenariy deckning 12 slaydi bo'yicha boradi, ekranda ham faqat deck turadi.
Slaydlar animatsiyali: sarlavha joylashadi, bloklar ketma-ket keladi, 3-slaydda paketlar
shlyuzdan o'tadi, 5-slaydda raqamlar sanab chiqadi, 12-slaydda satrlar chizib tashlanadi.
**Ovoz slaydni o'qib bermaydi** — ekran ko'rsatadi, ovoz esa ekranda yozilmagan narsani
aytadi. Bu format eng ko'p buziladigan joy: yozilganini takrorlasangiz, video ortiqcha
bo'lib qoladi.

**Tempni animatsiyaga moslang.** Slaydga o'tganda kirish ~0.6 soniya davom etadi — gapni
shundan keyin boshlang, aks holda birinchi so'zlar bo'sh ekranga tushadi.

---

## Xronometraj

Budjet — deck animatsiyasi bo'yicha belgilangan oyna; so'z — matnning hozirgi holati.

| vaqt (budjet) | slayd | ekranda | so'z | ~davomiylik | holat |
|---|---|---|---|---|---|
| 0:00–0:14 | 01 | Sarlavha, paketlar oqadi | 51 | 23.2s | **9s oshadi** |
| 0:14–0:30 | 02 | To'rtta muammo navbat bilan | 31 | 14.1s | ✓ |
| 0:30–0:54 | 03 | Topologiya, paketlar shlyuzdan o'tadi | 42 | 19.1s | 5s bo'sh |
| 0:54–1:16 | 04 | Konsolning uchta ekrani | 55 | 25.0s | **3s oshadi** |
| 1:16–1:44 | 05 | Raqamlar sanaydi, keyin hodisa | 30 | 13.6s | **14s bo'sh** |
| 1:44–1:54 | 06 | Biznes-model | 22 | 10.0s | ✓ |
| 1:54–2:03 | 07 | Bozor | 9 | 4.1s | 5s bo'sh |
| 2:03–2:15 | 08 | Raqobat | 30 | 13.6s | ✓ |
| 2:15–2:24 | 09 | Kanallar | 14 | 6.4s | 3s bo'sh |
| 2:24–2:32 | 10 | Jamoa | 9 | 4.1s | 4s bo'sh |
| 2:32–2:48 | 11 | Bor / yo'q ustunlari | 45 | 20.5s | **4s oshadi** |
| 2:48–3:00 | 12 | Yakun, satrlar chizib tashlanadi | 24 | 10.9s | ✓ |

**362 so'z ≈ 2:45** (2.2 so'z/s), sekinroq o'qilsa ~3:00 ga tegib ketadi. Chegara 3 daqiqa,
ya'ni zaxira kichik — birinchi o'qishni sekundomer bilan o'lchang.

Uchta joyni bilib turing:

- **01-slayd 9 soniya oshadi.** Sababi — ikkinchi blok, ya'ni taglayn jumlasi. U **ekranda
  ham yozilgan**, ssenariyning o'z qoidasi esa «ovoz slaydni o'qib bermaydi» deydi. Eng
  aniq qisqartirish shu: o'sha blokni olib tashlasangiz 01-slayd 30 so'z, 13.6 soniya
  bo'ladi va budjetga aynan tushadi (jami 341 so'z, 2:35).
- **05-slaydda 14 soniya bo'sh qoladi** — 27-iyul hodisasi matndan olingan, ekranda esa
  o'sha paytda hodisa animatsiyasi ketadi: ovoz jim, ekran gapiradi.
- **04 va 11-slaydlar** budjetdan 3–4 soniya oshadi: animatsiyani uzaytirish yoki matnni
  qisqartirish kerak.

---

## Ssenariy

### 0:00–0:14 · Slayd 01 — Ilgak

**Ekranda:** sarlavha slaydi, paketlar shlyuz orqali oqib turadi. Kursorni umuman
harakatlantirmang — birinchi kadr tinch bo'lsin.

> Korxona tizimlari bir-biri bilan doimiy ravishda ma'lumotlar almashadi. 
> O'sha jarayon uzilganda — buyurtma yo'qoladi, to'lov o'tmaydi, hisobot kelmaydi.
> Va ko'pincha, afsuski buni birinchi bo'lib mijoz aytadi.
> E'tiboringizda — QosID Flow.

> Korxona tizimlari o‘rtasidagi har bir so‘rovni ishonchli yetkazadigan platforma. Yukni vaqt bo‘yicha taqsimlaydi: yo‘qolmaydi, takrorlanmaydi va asosiysi barcha jarayonlar ko‘rinib turadi.
---

### 0:14–0:30 · Slayd 02 — Muammo

**Ekranda:** to'rtta muammo navbat bilan chiqadi. Gapingizni animatsiyaga moslang — har
band ekranga chiqqanda o'sha haqida gapiring.

> Integratsiyalar odatda qo'lda yozilgan skriptlar bilan qilinadi.
> Minglab so'rov birdaniga kelsa — server ko'tara olmaydi. 
> Tashqi API sekinlashsa — skript bloklanadi.
> Xato bo'lsa — jim yutiladi.
> Va yo'qolganini hech kim qaytarmaydi.

---

### 0:30–0:54 · Slayd 03 — Yechim

**Ekranda:** topologiya. Paketlar shlyuzdan o'tib rangi ko'kdan yashilga o'zgaradi, shlyuz
konturi sekin nafas oladi. **Kursor bilan bitta paketni kuzatib boring** — bu videoning eng
tushunarli besh soniyasi.

> QosID Flow asosiy ilovangizning oldida ikkala yo'nalishda turadi: unga kelayotgan
> so'rovlarni ham, undan tashqi API'larga chiqayotganini ham nazorat qiladi.
> Ikkala oqim bitta quvurdan o'tadi: navbatga yoziladi, xato bo'lsa qayta uriniladi — va asosiysi yo'qolmaydi.
>
> Odatda buning uchun ikkita alohida mahsulot kerak bo'ladi.  *(uzun chiqsa — tashlab yuboring)*

---

### 0:54–1:16 · Slayd 04 — Mahsulot

**Ekranda:** konsolning uchta ekrani. Har biriga kursor bilan ishora qiling va rasm
ustida 2–3 soniya turing — hakam ko'rishga ulgursin.

> Jarayonlarning hammasi ko'rinib turadi. 
> Birinchisi — jonli statistika: o'tkazuvchanlik,
> muvaffaqiyat, kechikish.
> Ikkinchisi — xatolar. Yetkazib bo'lmagan so'rov yo'qolmaydi: alohida navbatga tushadi,
> Telegramga xabar keladi, sababini sun'iy intellekt orqali tahlil qilish mumkin bo'ladi.
> Uchinchisi — operator paneli: to'xtatish, ishga tushirish, jonli jurnal.
> Sozlamalar esa faqat tizim to'xtaganda tahrirlanadi — noto'g'ri sozlama jonli trafikka
> ta'sir o'tkaza olmaydi.

---

### 1:16–1:44 · Slayd 05 — Prototip emas

**Ekranda:** to'rtta raqam noldan o'z qiymatiga sanab chiqadi (~0.9 soniya). **Sanoq
tugagunicha kutib turing** — raqam o'sayotganda uni aytish chalkash eshitiladi.

> Bu prototip emas. Aprel oyidan beri production serverda ishlab kelayotgan tizim:
> kuniga oltmish mingdan oshiq so'rov,
> to'qson to'qqiz foiz natija.
> Soniyasiga deyarli mingta so'rov, yo'qotishsiz.
> Va bular imkoniyat chegarasi emas.

---

### 1:44–1:54 · Slayd 06 — Biznes-model

> Bizning loyihamizning beshta daromad oqimi bor, bugungi fokus uchtasiga:
> tizimni joriy etish xizmatlari,
> har bir konnektor uchun to'lov,
> va boshqariladigan bulutli yechim.

---

### 1:54–2:03 · Slayd 07 — Bozor

> Biz ikki segment kesishmasida turamiz: integratsiya platformalari va API-shlyuzlar.

---

### 2:03–2:15 · Slayd 08 — Raqobat

> API-shlyuzlar kiruvchi trafikni oladi, lekin ilova o'chiq bo'lsa so'rov yo'qoladi.
> Integratsiya platformalari chiquvchini uddalaydi, lekin HTTP so'rovlar uchun darvoza bo'la olmaydi.
> Ba'zi hollarda ularning ish-oqim dvigatellari katta hajmlarga mo'ljallanmagan bo'ladi.

---

### 2:15–2:24 · Slayd 09 — Bozorga chiqish

> Asosan uch kanal:
> pilot ekotizimi mijozlari,
> IT Park rezidentlari,
> ERP-integratorlar,
> keyin boshqariladigan bulutga o'tamiz.

---

### 2:24–2:32 · Slayd 10 — Jamoa

> Besh kishimiz: uchtamiz tizimni takomillashtiramiz, ikkitamiz bozorni analiz qilamiz.


---

### 2:32–2:48 · Slayd 11 — Nima bor, nima yo'q

**Ekranda:** ikki ustun. **O'ng ustunda to'xtang** — bu videoning eng halol kadri va,
aslida, arizaning sababi.

> Bizda ishlab chiqarishda ishlaydigan tizim bor, qirqtadan ko'p xavfsizlik qoidasi bor,
> uch tilli boshqaruv konsoli bor.
> Bizda yo'q narsa — asosiy kompaniyadan tashqarida mijoz, narx modelimiz belgilanmagan, sotuv kanali,
> bozor validatsiyasi mavjud emas.
> Inkubatsiyaga mahsulot qurish uchun emas, **uni kim sotib olishini aniqlash uchun kelyapmiz**.

---

### 2:48–3:00 · Slayd 12 — Yakun

**Ekranda:** to'rtta satr navbat bilan chizib tashlanadi, o'ngda yashil javob paydo bo'ladi.
Oxirgi ikki soniyada logotip va havolalar.

> Bizning uch oylik rejamiz — uchta natija:
> birinchi mijoz,
> tasdiqlangan narx va biznes-model,
> IT Park orqali hamkorlik.
> Bu — QosID Flow. E'tiboringiz uchun rahmat.

---

## Yozib olish bo'yicha amaliy maslahatlar

**Deck**
- `f` — to'liq ekran; o'ng/chap o'q bilan slayd almashtiriladi
- Til: `1` `2` `3` tugmalari yoki `?lang=uz|ru|en`. To'g'ri tilni **yozishdan oldin** oching
- 1920×1080, bildirishnomalar o'chirilgan, boshqa tablar yopiq

**Animatsiyani hisobga oling**
- Slaydga o'tganda kirish ~0.6 soniya — gapni shundan keyin boshlang
- 5-slaydda raqamlar 0.9 soniya sanaydi — tugashini kuting
- 3 va 12-slaydlarda harakat uzluksiz; shoshilmang, kadr o'zi ishlaydi

**Ovoz**
- **Alohida yozib oling**, ekran bilan bir vaqtda emas. Xato qilsangiz butun videoni emas,
  faqat o'sha slaydni qayta dublyaj qilasiz
- Mikrofon og'izdan 15–20 sm, jim xona, deraza yopiq
- Har slaydga bitta dubl — ssenariy shunday bo'lingan

**Montaj**
- Slaydlar orasida 0.5 soniyalik pauza
- Raqamlar ekranda kamida 3 soniya
- Musiqa qo'ysangiz juda past. Umuman qo'ymaslik ham normal
- Chiqarish: MP4, H.264, 1080p, 30 fps

**Nima qilmaslik kerak**
- Slaydni o'qib bermang — ekrandagini takrorlamang, uni to'ldiring
- Arxitekturani chuqur tushuntirmang; buning uchun repo bor
- «Biz eng yaxshi mahsulotmiz» demang — raqamlar o'zi gapiradi
- 3:01 bo'lsa ham qayta montaj qiling

---

## Oxirgi kadrdagi havolalar

- GitHub: `github.com/Mukhammadaliya/qosid-flow-showcase`
- Demo: *(demo instans tayyor bo'lganda qo'shiladi)*
