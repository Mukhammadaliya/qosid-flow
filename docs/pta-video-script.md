# QosID Flow — 3 daqiqalik video ssenariysi

**Maqsad:** President Tech Award, Inkubatsiya treki, «Loyiha videosiga havola» maydoni
**Xronometraj:** 2:50–3:00 (chegara — 3 daqiqa, oshib ketmasin)
**Til:** o'zbek. Rus va ingliz versiyalari: `pta-video-script-ru.md`, `pta-video-script-en.md`
**Format:** **animatsion deck yozuvi + ovoz.** Jonli tizim ko'rsatilmaydi

> **Raqamlar o'zgarsa — uchala ssenariyda ham o'zgartiring.** Ular ovozda so'z bilan
> aytiladi, ya'ni deck bo'yicha raqamli qidiruv ularni topmaydi. Quyida uchraydigan beshtasi:
> **60 600** so'rov/kun · **99.68 %** · cho'qqi **918** so'rov/s · bir daqiqada **241** DLQ ·
> **41** xavfsizlik qoidasi.

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

| vaqt | slayd | ekranda | so'z |
|---|---|---|---|
| 0:00–0:14 | 01 | Sarlavha, paketlar oqadi | ~30 |
| 0:14–0:30 | 02 | To'rtta muammo navbat bilan | ~34 |
| 0:30–0:54 | 03 | Topologiya, paketlar shlyuzdan o'tadi | ~52 |
| 0:54–1:16 | 04 | Konsolning uchta ekrani | ~44 |
| 1:16–1:44 | 05 | Raqamlar sanaydi, keyin hodisa | ~58 |
| 1:44–1:54 | 06 | Biznes-model | ~20 |
| 1:54–2:03 | 07 | Bozor | ~18 |
| 2:03–2:15 | 08 | Raqobat | ~24 |
| 2:15–2:24 | 09 | Kanallar | ~18 |
| 2:24–2:32 | 10 | Jamoa | ~15 |
| 2:32–2:48 | 11 | Bor / yo'q ustunlari | ~30 |
| 2:48–3:00 | 12 | Yakun, satrlar chizib tashlanadi | ~20 |

**389 so'z.** Tezlikka qarab 2:46 (2.35 so'z/s) dan 3:05 (2.1) gacha — birinchi o'qishni
sekundomer bilan o'lchang. Belgilangan to'rt satr 28 so'z, ya'ni ~13 soniya: uzun chiqsa o'shalarni tashlang.
Birinchi o'qishni sekundomer bilan o'lchang; uzun chiqsa, *(uzun chiqsa — tashlab yuboring)*
belgisi qo'yilgan to‘rt satrni olib tashlang — shunda 2:52 qoladi.

---

## Ssenariy

### 0:00–0:14 · Slayd 01 — Ilgak

**Ekranda:** sarlavha slaydi, paketlar shlyuz orqali oqib turadi. Kursorni umuman
harakatlantirmang — birinchi kadr tinch bo'lsin.

> Korxona tizimlari bir-biri bilan doim gaplashadi. O'sha suhbat uzilganda — buyurtma
> yo'qoladi, to'lov o'tmaydi, hisobot kelmaydi.
> Va ko'pincha buni birinchi bo'lib mijoz aytadi.
> Men Muxammadali. Bu — QosID Flow.

---

### 0:14–0:30 · Slayd 02 — Muammo

**Ekranda:** to'rtta muammo navbat bilan chiqadi. Gapingizni animatsiyaga moslang — har
band ekranga chiqqanda o'sha haqida gapiring.

> Integratsiyalar odatda qo'lda yozilgan skriptlar bilan qilinadi.
> Ming so'rov birdaniga kelsa — server ko'tara olmaydi. Tashqi API sekinlashsa — skript
> bloklanadi. Xato bo'lsa — jim yutiladi.
> Va yo'qolganini hech kim qaytarmaydi.

---

### 0:30–0:54 · Slayd 03 — Yechim

**Ekranda:** topologiya. Paketlar shlyuzdan o'tib rangi ko'kdan yashilga o'zgaradi, shlyuz
konturi sekin nafas oladi. **Kursor bilan bitta paketni kuzatib boring** — bu videoning eng
tushunarli besh soniyasi.

> QosID Flow asosiy ilovangizning oldida ikkala yo'nalishda turadi: unga kelayotgan
> so'rovlarni ham, undan tashqi API'larga chiqayotganini ham.
> Ikkala oqim bitta quvurdan o'tadi: navbatga yoziladi, qayta uriniladi — va yo'qolmaydi.
>
> Bitta tafsilot muhim. Mijozga «qabul qildik» javobi so'rov navbatga **yozilgandan keyin**
> qaytariladi. Ya'ni bu «urinib ko'ramiz» emas, «saqlab qo'ydik» degani.
> Odatda buning uchun ikkita alohida mahsulot kerak bo'ladi.  *(uzun chiqsa — tashlab yuboring)*

---

### 0:54–1:16 · Slayd 04 — Mahsulot

**Ekranda:** konsolning uchta ekrani. Har biriga kursor bilan ishora qiling va rasm
ustida 2–3 soniya turing — hakam ko'rishga ulgursin.

> Bularning hammasi ko'rinib turadi. Birinchisi — jonli statistika: o'tkazuvchanlik,
> muvaffaqiyat, kechikish.
> Ikkinchisi — xatolar. Yetkazib bo'lmagan so'rov yo'qolmaydi: alohida navbatga tushadi,
> Telegramga xabar keladi, sababini AI tahlil qilib beradi.
> Uchinchisi — operator paneli: to'xtatish, ishga tushirish, jonli jurnal.
> Sozlamalar esa faqat tizim to'xtaganda tahrirlanadi — noto'g'ri sozlama jonli trafikni
> ura olmaydi.

---

### 1:16–1:44 · Slayd 05 — Prototip emas

**Ekranda:** to'rtta raqam noldan o'z qiymatiga sanab chiqadi (~0.9 soniya). **Sanoq
tugagunicha kutib turing** — raqam o'sayotganda uni aytish chalkash eshitiladi.

> Bu prototip emas. Aprel oyidan beri ishlab chiqarishda: kuniga oltmish ming olti yuz
> so'rov, to'qson to'qqiz nuqta oltmish sakkiz foiz muvaffaqiyat.
> O'lchangan cho'qqi — soniyasiga to'qqiz yuz o'n sakkiz, yo'qotishsiz.
>
> Yigirma yettinchi iyulda avariya bo'ldi: bir daqiqada ikki yuz qirq bitta so'rov xato
> navbatiga tushdi. Sababini topdik, tuzatdik, o'lchadik.
> Ertasi kuni besh barobar zichroq yuklama o'tdi — bitta so'rov ham yo'qolmadi.

---

### 1:44–1:54 · Slayd 06 — Biznes-model

> Beshta daromad oqimi bor, bugungi fokus uchtasi: joriy etish xizmatlari, har bir
> konnektor uchun to'lov, va boshqariladigan bulut.

---

### 1:54–2:03 · Slayd 07 — Bozor

> Biz ikki segment kesishmasida turamiz: integratsiya platformalari va API-shlyuzlar.
> Plasdarm — O'zbekiston korxona va davlat integratsiyalari.  *(uzun chiqsa — tashlab yuboring)*

---

### 2:03–2:15 · Slayd 08 — Raqobat

> API-shlyuzlar kiruvchi trafikni oladi, lekin sinxron — ilova o'chiq bo'lsa so'rov yo'qoladi.
> Integratsiya platformalari chiquvchini uddalaydi, lekin HTTP darvozasi emas.
> Ish-oqim dvigatellari bu hajmga mo'ljallanmagan.  *(uzun chiqsa — tashlab yuboring)*

---

### 2:15–2:24 · Slayd 09 — Bozorga chiqish

> Uch kanal, eng past ishqalanishdan: pilot ekotizimi mijozlari, IT Park rezidentlari va
> ERP-integratorlar, keyin boshqariladigan bulut.

---

### 2:24–2:32 · Slayd 10 — Jamoa

> Besh kishimiz: uchtasi quradi, ikkitasi bozorni tekshiradi.

---

### 2:32–2:48 · Slayd 11 — Nima bor, nima yo'q

**Ekranda:** ikki ustun. **O'ng ustunda to'xtang** — bu videoning eng halol kadri va,
aslida, arizaning sababi.

> Bizda ishlab chiqarishda ishlaydigan tizim bor, qirq bitta xavfsizlik qoidasi bor,
> uch tilli boshqaruv konsoli bor.
> Bizda yo'q narsa — asosiy kompaniyadan tashqarida mijoz, narx modeli, sotuv kanali,
> bozor validatsiyasi.
> Shuning uchun inkubatsiyaga kelyapmiz: mahsulot qurishga emas, **uni kim sotib olishini
> aniqlashga**.

---

### 2:48–3:00 · Slayd 12 — Yakun

**Ekranda:** to'rtta satr navbat bilan chizib tashlanadi, o'ngda yashil javob paydo bo'ladi.
Oxirgi ikki soniyada logotip va havolalar.

> Uch oydan keyin o'sha ustun bo'shaydi: birinchi pullik pilot, tasdiqlangan narx,
> IT Park orqali hamkorlik, yigirma korxona bilan suhbat.  *(uzun chiqsa — tashlab yuboring)*
> Tizim har kuni oltmish ming so'rovni yetkazadi. Qolgani — mahsulotning o'zini kimga
> yetkazishni bilish.
> QosID Flow. Rahmat.

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

- GitHub: `github.com/Mukhammadaliya/qosid-flow`
- Demo: *(demo instans tayyor bo'lganda qo'shiladi)*
