# QosID Flow — ariza formasi matnlari

Formadagi matn maydonlariga tayyor variantlar. Uzunligi cheklangan bo'lsa qisqa variant bor.

---

## «Tavsif» maydoni — asosiy variant (~1900 belgi)

QosID Flow — korxona tizimlari o'rtasidagi so'rovlarni ishonchli yetkazuvchi Kafka-asosidagi
integratsiya platformasi.

**Muammo.** Korxonalarda integratsiyalar odatda qo'lda yozilgan skriptlar bilan qilinadi. Tashqi
API sekinlashsa — skript bloklanadi; xato bo'lsa — u jim yutiladi; tizim tiklanganda —
yo'qolganini hech kim qayta yubormaydi. Natijada buyurtma o'tmaydi, to'lov tushmaydi, hisobot
kelmaydi, va ko'pincha buni faqat mijoz shikoyat qilganda bilib qolishadi.

**Yechim.** QosID Flow asosiy ilovaning oldida **ikkala yo'nalishda** turadi. Unga kelayotgan
so'rovlarni ham boshqaradi (tashqi mijozlar → asosiy ilova), undan chiqayotganini ham (asosiy
ilova → tashqi API'lar). Ikkala oqim bitta quvurdan o'tadi: so'rov Kafka navbatiga yoziladi,
chegaralangan qayta urinish, circuit breaker va OAuth2 bilan yetkaziladi, javob saqlanadi va
qaytariladi. Yetkazib bo'lmagan so'rov jim yo'qolmaydi — alohida navbatga tushadi va xabar
yuboriladi. Javob tanasi 7 kun arxivda saqlanadi va so'rov raqami bo'yicha olinadi.

Odatda buning uchun ikkita alohida mahsulot kerak bo'ladi: kiruvchi tomon uchun API-shlyuz,
chiquvchi tomon uchun integratsiya platformasi.

**Holati.** Bu prototip emas. Tizim 2026-yil apreldan beri ishlab chiqarishda: kuniga ~60 600
so'rov, 99.68% muvaffaqiyat. Cho'qqi yuklamada soniyasiga 918 so'rov bitta ham yo'qotishsiz
qayta ishlangan. 527 avtomatik test, 41 hujjatlashtirilgan xavfsizlik qoidasi. Boshqaruv
konsoli: jonli statistika, xatolar tahlili, sozlamalar va operator paneli.

**Nima uchun inkubatsiya.** Bizda texnologiya tayyor, biznes esa noldan: asosiy kompaniyadan
tashqarida mijozimiz yo'q, narx modelimiz yo'q, sotuv kanalimiz yo'q. Biz dasturdan mahsulot
qurishni emas, uni **kim sotib olishini aniqlashni** so'raymiz. Uch oylik maqsad: 20 ta korxona
bilan mijoz-suhbati, tasdiqlangan narx modeli va birinchi pullik pilot.

---

## «Tavsif» maydoni — qisqa variant (~700 belgi)

QosID Flow — korxona tizimlari o'rtasidagi so'rovlarni ishonchli yetkazuvchi Kafka-asosidagi
integratsiya platformasi. Asosiy ilovaning oldida ikkala yo'nalishda turadi: unga kelayotgan
so'rovlarni ham, undan tashqi API'larga chiqayotganini ham boshqaradi. So'rov navbatga yoziladi,
qayta uriniladi, circuit breaker bilan himoyalanadi va yo'qolmaydi.

2026-yil apreldan beri ishlab chiqarishda: kuniga ~60 600 so'rov, 99.68% muvaffaqiyat, cho'qqida
soniyasiga 918 so'rov yo'qotishsiz. 527 test.

Texnologiya tayyor, biznes noldan — inkubatsiyadan mahsulot qurishni emas, uni kim sotib olishini
aniqlashni so'raymiz.

---

## Bir jumlalik ta'rif (muqova, logotip ostiga, taqdimot sarlavhasiga)

> Korxona tizimlari o'rtasidagi har bir so'rovni ishonchli yetkazadigan platforma.
> Yukni vaqt bo'yicha taqsimlaydi: yo'qolmaydi, takrorlanmaydi, ko'rinib turadi.

Uch tilda (slayd va ariza uchun bir xil bo'lishi kerak):

- **RU** — Платформа, которая надёжно доставляет каждый запрос между корпоративными
  системами и распределяет нагрузку во времени. Ничего не теряется, не дублируется, всё видно.
- **EN** — A platform that reliably delivers every request between enterprise systems and
  spreads load over time. Nothing lost, nothing duplicated, everything visible.

**Nega «yukni vaqt bo'yicha taqsimlaydi», «load balancing» emas:** biz yukni serverlar
o'rtasida emas, vaqt bo'yicha muvozanatlaymiz — burst navbatga aylanadi. «Load balancing»
desangiz, texnik hakam darhol «demak nginx o'rnini bosasizmi?» deb so'raydi va suhbat
noto'g'ri taqqoslashga ketadi.

---

## Jamoa — formadagi rollar matni

Formada har a'zo uchun rol/lavozim so'raladi. Umumiy lavozim nomi emas, **dastur maqsadiga
bog'langan** rol yoziladi:

| A'zo | Formada yoziladigan rol |
|---|---|
| 1 | Product Owner — mahsulot yo'nalishi, mijoz kashfiyoti |
| 2 | Biznes-analitik — bozor tadqiqoti, narx modeli, mijoz-suhbatlari tahlili |
| 3 | Backend/Platforma dasturchisi — quvur, ishonchlilik |
| 4 | Backend/Integratsiya dasturchisi — manba konnektorlari |
| 5 | Frontend/DevOps dasturchisi — boshqaruv konsoli, joriy etish |

Sabab: agar beshovi ham «dasturchi» deb yozilsa, hakam «texnik jamoa, sotuvni kim qiladi?» deb
o'ylaydi. Yuqoridagi ro'yxat esa jamoaning validatsiya qila olishini ko'rsatadi.

---

## Boshqa maydonlar — tayyor javoblar

- **Startap nomi:** `QosID Flow`
- **Kategoriya:** Korporativ yechimlar *(muqobil: Chuqur texnologiyalar)*
- **Moliyalashtirish bosqichi:** Pre-seed — investitsiya jalb qilinmagan
- **Faoliyat hududi:** Toshkent
- **Yuridik holat:** Yuridik shaxs ro'yxatdan o'tgan, IT Park bilan shartnoma mavjud
  *(taqdimot slaydidan olib tashlandi — bu ariza mezoni, pitch da'vosi emas; slaydda
  o'rniga muhandislik tayyorligi turadi)*
- **GitHub:** `github.com/Mukhammadaliya/qosid-flow-showcase`
- **MVP havolasi:** *(demo instans tayyor bo'lgach)*
- **Video:** *(yozib olingach — ssenariy `docs/pta-video-script.md` da)*
