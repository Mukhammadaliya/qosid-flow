# QosID Flow — 3 daqiqalik video ssenariysi

**Maqsad:** President Tech Award, Inkubatsiya treki, «Loyiha videosiga havola» maydoni
**Xronometraj:** 2:55–3:00 (chegara — 3 daqiqa, oshib ketmasin)
**Til:** o'zbek. Rus tilida ham yozib olish mumkin — matn o'zgarmaydi, faqat tarjima
**Format:** ekran yozuvi + ovoz. Yuzni ko'rsatish shart emas; oxirgi 10 soniyada bo'lsa yaxshi

**Demo tayyor deb hisoblanadi:** namunaviy ma'lumot bilan to'ldirilgan alohida instans,
read-only kirish, Statistics/Errors/Ops sahifalari to'la.

---

## Umumiy tamoyillar

- **Slayd o'qimang.** Ekranda jonli tizim bo'lsin, slayd emas. Bu videoning butun kuchi shunda:
  boshqalar maketni ko'rsatadi, biz ishlayotgan tizimni ko'rsatamiz.
- **Har bir da'vodan keyin dalil.** «Yo'qolmaydi» dedingizmi — ekranda o'sha zahoti ko'rsating.
- **Sekin gapiring.** 3 daqiqaga ~420 so'z sig'adi. Shoshsangiz tushunarsiz bo'ladi.
- **Kursor bilan ko'rsating**, zoom qiling. Hakam kichik ekranda ko'radi.

---

## Ssenariy

### 0:00–0:15 — Ilgak

**Ekranda:** qora fon, oq matn: bitta jumla, keyin logotip

**Ovoz:**
> Korxona tizimlari bir-biri bilan gaplashadi. Bu suhbat uzilganda — buyurtma yo'qoladi,
> to'lov o'tmaydi, hisobot kelmaydi. Va ko'pincha buni hech kim sezmaydi.
> Men Muxammadali, bu — QosID Flow.

*(~35 so'z)*

---

### 0:15–0:45 — Nima qiladi: ikki yo'nalish

**Ekranda:** ikki strelkali diagramma. Avval kiruvchi strelka chiziladi, keyin chiquvchi.

```
KIRUVCHI    mijozlar ──▶ QosID Flow ──▶ asosiy ilova
CHIQUVCHI   asosiy ilova ──▶ QosID Flow ──▶ tashqi API'lar
```

**Ovoz:**
> QosID Flow asosiy ilovangizning oldida ikkala yo'nalishda turadi.
> Unga kelayotgan so'rovlarni ham boshqaradi, undan chiqayotganini ham.
> Ikkalasi bitta quvurdan o'tadi va bir xil kafolat oladi:
> so'rov navbatga yoziladi, qayta uriniladi, kuzatiladi — va yo'qolmaydi.
> Odatda buning uchun ikkita alohida mahsulot kerak bo'ladi.

*(~55 so'z)*

---

### 0:45–1:35 — Jonli demo

Bu videoning yuragi. Uch qismga bo'linadi, har biri qisqa.

**A. So'rov yuborish (0:45–1:05)**

**Ekranda:** terminal yoki Postman. Bitta `POST` so'rovi yuboriladi.
Javob — `202 ACCEPTED` va kuzatuv havolasi. **Zoom qiling.**
Keyin o'sha havola ochiladi va natija ko'rsatiladi.

**Ovoz:**
> So'rov yuboraman. Javob darhol keladi — ikki yuz ikki, va kuzatuv havolasi.
> Diqqat: bu javob so'rov navbatga **yozilgandan keyin** qaytariladi.
> Ya'ni «qabul qildik» degani «urinib ko'ramiz» emas — «saqlab qo'ydik» degani.
> Mijoz ulanishni ushlab turmaydi, natijani mana shu havoladan oladi.

*(~50 so'z)*

**B. Statistika (1:05–1:20)**

**Ekranda:** Statistics sahifasi — o'tkazuvchanlik grafigi, muvaffaqiyat foizi, kechikish.

**Ovoz:**
> Har bir so'rov ko'rinib turadi: o'tkazuvchanlik, muvaffaqiyat foizi, kechikish.
> Bu jonli tizimning haqiqiy ko'rsatkichlari.

*(~20 so'z)*

**C. Xatolar va Ops (1:20–1:35)**

**Ekranda:** Errors sahifasi — bitta xato ochiladi, tafsiloti ko'rinadi.
Keyin Ops sahifasi — jonli log oqimi ko'rsatiladi.

**Ovoz:**
> Xato bo'lsa — u yo'qolmaydi, alohida navbatga tushadi va xabar keladi.
> Sababini shu yerda ko'rasiz. Operator paneli esa tizimni to'xtatish, ishga tushirish
> va jonli logni kuzatish imkonini beradi.

*(~35 so'z)*

---

### 1:35–2:05 — Prod dalillari

**Ekranda:** to'rtta yirik raqam, ketma-ket chiqadi

```
60 600   so'rov / kun
99.68  % muvaffaqiyat
918      so'rov / soniya — cho'qqi, yo'qotishsiz
527      avtomatik test
```

**Ovoz:**
> Bu prototip emas. Tizim aprel oyidan beri ishlab chiqarishda:
> kuniga ellik ming so'rov, to'qson to'qqiz nuqta to'qqiz to'qqiz to'rt foiz muvaffaqiyat.
> Cho'qqi yuklama — soniyasiga uch yuz o'ttiz so'rov, bitta ham yo'qotishsiz.
> Uch yuz yetmish to'qqizta avtomatik test.

*(~45 so'z)*

---

### 2:05–2:35 — Hodisa va undan chiqqan xulosa

**Ekranda:** oddiy grafik — 27-iyul: DLQ bir daqiqada 241 ga sakraydi. Keyin 28-iyul: burst 5× baland,
DLQ nol. Ikkita ustun yonma-yon.

**Ovoz:**
> Yigirma yettinchi iyulda bizda avariya bo'ldi. O'ttiz soniyalik uzilish ikki yuz o'n ikkita
> jonli so'rovni xato navbatiga tushirdi.
> Sababini topdik, uchta o'zgarish kiritdik va o'lchadik.
> Ertasi kuni besh barobar kuchliroq yuklama o'tdi — bitta so'rov ham yo'qolmadi.
> Biz shunday ishlaymiz: muammo, sabab, o'lchangan yechim.

*(~55 so'z)*

---

### 2:35–2:55 — Nima bor, nima yo'q va so'rov

**Ekranda:** ikki ustun. Chapda «Bizda bor», o'ngda «Bizda yo'q». Keyin jamoa: 5 kishi.

**Ovoz:**
> Bizda ishlaydigan tizim bor, yuridik shaxs bor, besh kishilik jamoa bor.
> Bizda yo'q narsa — asosiy kompaniyadan tashqarida mijoz, narx modeli, sotuv kanali.
> Shuning uchun inkubatsiyaga kelyapmiz: mahsulot qurishga emas,
> uni kim sotib olishini aniqlashga.
> Uch oyda maqsadimiz — yigirmata korxona bilan suhbat, tasdiqlangan narx va birinchi
> pullik pilot.

*(~60 so'z)*

---

### 2:55–3:00 — Yakun

**Ekranda:** logotip, nom, GitHub havolasi, demo havolasi

**Ovoz:**
> QosID Flow. Rahmat.

---

## Jami so'z: ~355. Sekin gapirilganda ~2:50. Zaxira bor.

---

## Yozib olish bo'yicha amaliy maslahatlar

**Ekran yozuvi**
- Ekran o'lchami 1920×1080, brauzer to'liq ekranda, keraksiz tab va bildirishnomalarsiz
- Shrift kattalashtiriladi (brauzerda Ctrl + `+` bilan 125–150%) — hakam telefonda ko'rishi mumkin
- Kursorni sekin harakatlantiring; muhim joyda 1–2 soniya to'xtang

**Ovoz**
- Alohida yozib oling, ekran yozuvi bilan birga emas. Keyin montajda birlashtiriladi —
  xato qilsangiz butun videoni qayta yozish shart bo'lmaydi
- Mikrofon og'izdan 15–20 sm; jim xona; deraza yopiq
- Har bo'limni alohida dubl qiling

**Montaj**
- Bo'limlar orasida 0.5 soniyalik pauza — hakam nafas oladi
- Raqamlar ekranda kamida 3 soniya tursin
- Musiqa qo'ysangiz — juda past, ovoz ustidan chiqmasin. Umuman qo'ymaslik ham normal

**Nima qilmaslik kerak**
- Slayd o'qib bermang — bu eng ko'p uchraydigan xato
- Arxitekturani chuqur tushuntirmang; 3 daqiqada bunga vaqt yo'q, buning uchun repo bor
- «Biz eng yaxshi mahsulotmiz» demang — raqamlar o'zi gapiradi
- Vaqtdan oshmang. 3:01 bo'lsa ham qayta montaj qiling

---

## Videoda ko'rsatiladigan havolalar (oxirgi kadr)

- GitHub: `github.com/Mukhammadaliya/qosid-flow`
- Demo: *(demo instans tayyor bo'lganda qo'shiladi)*
