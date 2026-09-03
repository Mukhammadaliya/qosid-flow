// Deck'ni uch tilda PDF ga eksport qiladi (President Tech Award arizasi uchun).
//
// Ishlatish:
//   node deck-server.js docs/pta-deck 5500      # yoki boshqa statik server
//   npm i puppeteer && node docs/pta-deck/export-pdf.js docs/pta-deck/export
//
// NEGA BRAUZERDAGI Ctrl+P EMAS: qo'lda eksport uch tilda uch marta takrorlanadi va
// har safar sozlamalar (fon rasmlari, sahifa o'lchami) qo'lda to'g'ri qo'yilishi kerak.
// Bu skript aynan bir xil natijani beradi.
//
// UCHTA NOZIK NUQTA — ularsiz eksport jimgina buziladi:
//
//   1. printBackground: true — SHART. Chromique standart holatda fon rasmlarini
//      tashlab ketadi, deck esa qora fonda (#05090e) oq matn bilan yozilgan.
//      Busiz 12 ta oq sahifada o'qilmaydigan och matn chiqadi.
//
//   2. Rasmlar dekodlanishini kutish. `networkidle0` HTTP so'rovlar tugaganini
//      bildiradi, lekin rasm hali dekodlanmagan bo'lishi mumkin — 04-slaydda
//      konsol skrinshotlari, 10-slaydda beshta jamoa surati bo'sh chiqadi.
//
//   3. 1600x900 — deck'ning print CSS'i .slide { width: 1600px } deb belgilaydi.
//      Boshqa o'lcham masshtablanishga olib keladi.
//
// Deck'ning o'z print stylesheet'i sahifalashni bajaradi (.deck ni position:fixed dan
// chiqaradi, har slaydga page-break-after qo'yadi va kirish animatsiyalarini o'chiradi —
// aks holda animatsiya o'rtasida ushlangan element sahifada ko'rinmas bo'lib qoladi).

const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');

const OUT = process.argv[2] || path.join(__dirname, 'export');
const PORT = process.argv[3] || 5500;

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });

  for (const lang of ['uz', 'ru', 'en']) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1600, height: 900, deviceScaleFactor: 2 });
    // Kesh-buster: index.html tahrirlangan bo'lsa, eski nusxa chop etilmasin.
    await page.goto(`http://localhost:${PORT}/?lang=${lang}&t=${Date.now()}`, {
      waitUntil: 'networkidle0',
    });

    await page.evaluate(() =>
      Promise.all(
        Array.from(document.images)
          .filter((i) => !i.complete)
          .map((i) => new Promise((r) => { i.onload = i.onerror = r; }))
      )
    );
    const shots = await page.evaluate(() => document.images.length);
    const slides = await page.evaluate(() => document.querySelectorAll('.slide').length);
    await new Promise((r) => setTimeout(r, 1500));

    const file = path.join(OUT, `qosid-flow-deck-${lang}.pdf`);
    await page.pdf({
      path: file,
      width: '1600px',
      height: '900px',
      printBackground: true,
      pageRanges: '1-12',
    });
    console.log(
      `${lang}: ${slides} slayd, ${shots} rasm, ` +
      `${(fs.statSync(file).size / 1024 / 1024).toFixed(2)} MB -> ${path.basename(file)}`
    );
    await page.close();
  }

  await browser.close();
})();
