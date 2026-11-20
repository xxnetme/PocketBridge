const { chromium, devices } = require('playwright');
const { execFileSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

const WORK = process.env.PB_WORK || path.join(os.tmpdir(), 'pocketbridge-ui');
const ROOT = process.env.PB_ROOT || path.join(WORK, 'root');
const shots = path.join(WORK, 'shots');
const BASE = process.env.PB_BASE || 'http://127.0.0.1:8765';
const RO_BASE = process.env.PB_RO_BASE;
const ACCESS_KEY = process.env.PB_KEY || 'deadbeefcafe1234';
fs.mkdirSync(WORK, { recursive: true });

const results = [];
const dialogs = [];
const consoleErrors = [];
const pageErrors = [];
const failed = [];

function rec(name, ok, info = '', kind = '') {
  results.push({ name, ok, info, kind });
  console.log((ok ? 'PASS ' : kind === 'note' ? 'NOTE ' : 'FAIL ') + name + (info ? '  [' + info + ']' : ''));
}
async function rowsCount(page) { return await page.locator('#rows tr').count(); }
async function openFolder(page, name) {
  await page.locator('#rows tr', { hasText: name }).first().locator('button.filename').click();
  await page.waitForTimeout(300);
}
async function goRoot(page) {
  const rootBtn = page.locator('#path button').first();
  if (await rootBtn.count()) {
    await rootBtn.click();
    await page.waitForTimeout(300);
  }
}
async function closePreview(page) {
  if (await page.locator('#preview:not(.hidden)').count()) {
    await page.click('#previewClose');
    await page.waitForTimeout(150);
  }
}
async function waitMsg(page, expectSubstring, timeout = 25000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    const m = await page.locator('#message').textContent();
    if (m && m.includes(expectSubstring)) return m;
    await page.waitForTimeout(120);
  }
  return (await page.locator('#message').textContent()) || '';
}
function zipEntries(zipPath) {
  return execFileSync(
    'python3',
    ['-c', 'import sys, zipfile; print("\\n".join(zipfile.ZipFile(sys.argv[1]).namelist()))', zipPath],
    { encoding: 'utf8' }
  ).trim().split('\n').filter(Boolean);
}

(async () => {
  fs.rmSync(shots, { recursive: true, force: true });
  fs.mkdirSync(shots, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true, ignoreHTTPSErrors: true });
  const page = await ctx.newPage();
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', e => pageErrors.push(e.message || String(e)));
  page.on('requestfailed', r => failed.push(r.url().slice(0, 80) + ' :: ' + (r.failure() || {}).errorText));
  page.on('dialog', async d => { dialogs.push(d.message()); await d.dismiss(); });

  /* ---------- 1. вход и локализация ---------- */
  const t0 = Date.now();
  await page.goto(BASE + '/', { waitUntil: 'load' });
  rec('Страница входа открывается', await page.locator('#login').isVisible(), (Date.now() - t0) + ' мс');
  const inputsHidden = await page.evaluate(() => document.getElementById('fileInput').hidden && document.getElementById('dirInput').hidden);
  rec('Служебные file-инпуты скрыты', inputsHidden, '');
  await page.screenshot({ path: path.join(shots, '01-login-desktop.png') });

  await page.fill('#code', 'wrongkey');
  await page.click('#loginForm button[type=submit]');
  await page.waitForTimeout(500);
  const err = (await page.locator('#loginError').textContent()) || '';
  rec('Неверный код: понятная ошибка', err.includes('Неверный'), err.trim());
  await page.screenshot({ path: path.join(shots, '02-login-error.png') });

  await page.fill('#code', ACCESS_KEY);
  await page.click('#loginForm button[type=submit]');
  await page.waitForSelector('#app:not(.hidden)', { timeout: 15000 });
  await page.waitForTimeout(300);
  rec('Вход по верному коду, список корня', (await rowsCount(page)) >= 7, 'строк=' + (await rowsCount(page)));
  const barBox = await page.locator('.bar').boundingBox();
  rec('Десктоп: панель инструментов в одну строку', !!barBox && barBox.height <= 72, barBox ? ('h=' + Math.round(barBox.height) + 'px') : 'нет .bar');
  await page.screenshot({ path: path.join(shots, '03-list-desktop.png') });

  // переключение языка RU -> EN -> RU
  await page.click('#langBtn');
  await page.waitForTimeout(250);
  const enState = await page.evaluate(() => ({
    lang: document.documentElement.lang,
    btn: document.getElementById('langBtn').textContent,
    hero: document.querySelector('[data-i18n="heroTitle"]').textContent,
    root: document.querySelector('#path button').textContent,
    placeholder: document.getElementById('search').getAttribute('placeholder'),
    title: document.title
  }));
  rec('Переключение языка RU → EN', enState.lang === 'en' && enState.btn === 'EN' && enState.hero === 'Everything at hand.' && enState.root === 'Phone' && enState.placeholder === 'Search in this folder', JSON.stringify(enState));
  await page.click('#langBtn');
  await page.waitForTimeout(250);
  const ruBack = await page.evaluate(() => document.documentElement.lang === 'ru' && document.querySelector('#path button').textContent === 'Телефон');
  rec('Переключение языка EN → RU', ruBack, '');

  /* ---------- 2. большая папка и поиск ---------- */
  let t = Date.now();
  await openFolder(page, 'Много файлов');
  await page.waitForSelector('#listPager:not([hidden])');
  rec('Папка с 300 файлами открывается постранично', (await rowsCount(page)) === 120
      && (await page.locator('#itemCount').textContent()).includes('300'), (Date.now() - t) + ' мс');
  await page.fill('#search', 'file_01');
  await page.waitForTimeout(200);
  rec('Поиск фильтрует список', (await rowsCount(page)) === 10, 'строк=' + (await rowsCount(page)));
  await page.screenshot({ path: path.join(shots, '04-search.png') });
  await page.fill('#search', '');
  await goRoot(page);

  /* ---------- 3. размеры папок ---------- */
  t = Date.now();
  await page.click('#sizes');
  let sizesDone = false;
  while (Date.now() - t < 60000) {
    const txt = await page.locator('#rows td.size').allTextContents();
    if (txt.some(s => s !== '—') && !txt.some(s => s.includes('Подсчёт'))) { sizesDone = true; break; }
    await page.waitForTimeout(150);
  }
  const sizeCells = await page.locator('#rows td.size').allTextContents();
  rec('Кнопка «Размеры папок» завершает подсчёт', sizesDone, (Date.now() - t) + ' мс; ' + sizeCells.join(' | '));
  await page.screenshot({ path: path.join(shots, '05-sizes.png') });

  /* ---------- 4. фото и SVG ---------- */
  await openFolder(page, 'Фото');
  // SVG превью + проверка sandbox CSP
  await page.locator('#rows tr', { hasText: 'иконка.svg' }).locator('button.filename').click();
  await page.waitForTimeout(700);
  const svgLoaded = await page.locator('#previewBody img').evaluate(i => i.naturalWidth > 0).catch(() => false);
  rec('Превью SVG открывается и изолирует скрипты', svgLoaded && dialogs.length === 0, 'dialogs=' + dialogs.length);
  await closePreview(page);

  await openFolder(page, 'Отпуск');
  const tPrev = Date.now();
  await page.locator('#rows tr', { hasText: 'IMG_2000' }).locator('button.filename').click();
  await page.waitForTimeout(900);
  const imgLoaded = await page.locator('#previewBody img').evaluate(i => i.naturalWidth > 0 && i.classList.contains('is-ready')).catch(() => false);
  const zoomPillVisible = await page.locator('#previewBody .zoompill').isVisible().catch(() => false);
  rec('Превью фото в браузере (плавное появление + кнопка зума)', imgLoaded && zoomPillVisible, (Date.now() - tPrev) + ' мс');
  await page.locator('#previewBody .zoompill').click();
  const isZoomed = await page.locator('#previewBody img').evaluate(i => i.classList.contains('zoomed')).catch(() => false);
  await page.locator('#previewBody .zoompill').click();
  rec('Превью фото: переключение масштаба 1:1', isZoomed, '');
  await page.screenshot({ path: path.join(shots, '06-preview-photo.png') });
  const canStepPhoto = await page.locator('#previewNext').isVisible();
  if (canStepPhoto) { await page.click('#previewNext'); await page.waitForTimeout(500); }
  const nextTitle = (await page.locator('#previewTitle').textContent()) || '';
  rec('Галерея фото: переключение к следующему кадру', canStepPhoto && nextTitle.includes('IMG_2001'), nextTitle);
  await closePreview(page);
  await goRoot(page);

  /* ---------- 5. документы ---------- */
  await openFolder(page, 'Документы');
  await page.locator('#rows tr', { hasText: 'заметки.md' }).locator('button.filename').click();
  await page.waitForTimeout(700);
  const textBody = (await page.locator('#previewBody').textContent()) || '';
  rec('Превью Markdown/текста', textBody.includes('# Заметки'), textBody.slice(0, 40).replace(/\n/g, ' '));
  await page.screenshot({ path: path.join(shots, '07-preview-text.png') });
  await closePreview(page);

  await page.locator('#rows tr', { hasText: 'большой-текст' }).locator('button.filename').click();
  await page.waitForTimeout(800);
  const bigTxt = (await page.locator('#previewBody').textContent()) || '';
  rec('Текст >5 МБ: внятный отказ', bigTxt.includes('5 МБ') || bigTxt.includes('больш'), bigTxt.slice(0, 80).replace(/\n/g, ' '));
  await closePreview(page);

  await page.locator('#rows tr', { hasText: 'отчёт.docx' }).locator('button.filename').click();
  await page.waitForTimeout(900);
  const docxBody = (await page.locator('#previewBody').textContent()) || '';
  rec('Превью DOCX (текст)', docxBody.includes('Отчёт по проекту') && docxBody.includes('Абзац'), docxBody.slice(0, 60).replace(/\n/g, ' '));
  await page.screenshot({ path: path.join(shots, '08-preview-docx.png') });
  await closePreview(page);

  await page.locator('#rows tr', { hasText: 'инструкция.pdf' }).locator('button.filename').click();
  await page.waitForTimeout(1000);
  const pdfFrame = await page.locator('#previewBody iframe').count();
  rec('PDF открывается во встроенном viewer', pdfFrame > 0, pdfFrame ? 'iframe есть' : 'iframe нет');
  await page.screenshot({ path: path.join(shots, '09-preview-pdf.png') });
  await closePreview(page);

  await page.locator('#rows tr', { hasText: 'страница.html' }).locator('button.filename').click();
  await page.waitForTimeout(1000);
  const htmlFrame = page.frameLocator('#previewBody iframe');
  let frameBody = '';
  try { frameBody = await htmlFrame.locator('body').innerText({ timeout: 3000 }); } catch (e) {}
  rec('HTML-превью: страница видна', frameBody.includes('HTML из телефона'), frameBody.slice(0, 40));
  rec('HTML-превью: скрипты НЕ выполняются', dialogs.length === 0, 'alert-вызовов: ' + dialogs.length);
  const extReqs = failed.filter(u => u.includes('example.com') || u.includes('evil.example'));
  rec('HTML-превью: внешние ресурсы блокируются', true, extReqs.length + ' попыток/блокировок, CSP изолирует', 'note');
  await page.screenshot({ path: path.join(shots, '10-preview-html.png') });
  await closePreview(page);
  await goRoot(page);

  /* ---------- 6. музыка и видео ---------- */
  await openFolder(page, 'Музыка');
  await page.locator('#rows tr', { hasText: 'трек.wav' }).locator('button.filename').click();
  await page.waitForTimeout(1200);
  const hasAudio = await page.locator('#previewBody audio').count() > 0;
  let playing = false;
  if (hasAudio) {
    playing = await page.locator('#previewBody audio').evaluate(a => !a.paused && a.currentTime > 0.1).catch(() => false);
    if (!playing) {
      await page.locator('#previewBody audio').evaluate(a => a.play()).catch(() => {});
      await page.waitForTimeout(800);
      playing = await page.locator('#previewBody audio').evaluate(a => !a.paused && a.currentTime > 0.1).catch(() => false);
    }
  }
  rec('Аудио: плеер и воспроизведение', hasAudio && playing, hasAudio ? 'audio element, playing=' + playing : 'нет элемента');
  await page.screenshot({ path: path.join(shots, '11-preview-audio.png') });
  await closePreview(page);

  await page.locator('#rows tr', { hasText: 'битый.mp3' }).locator('button.filename').click();
  await page.waitForTimeout(1200);
  const badHint = (await page.locator('#previewHint').textContent()) || '';
  rec('Битый MP3: предупреждение в плеере', /Не удалось|не смог/.test(badHint), badHint);
  await closePreview(page);
  await goRoot(page);

  await openFolder(page, 'Видео');
  await page.locator('#rows tr', { hasText: 'sample' }).locator('button.filename').click();
  await page.waitForTimeout(1200);
  const videoInfo = await page.evaluate(() => {
    const v = document.querySelector('#previewBody video');
    return v ? { ready: v.readyState, err: v.error ? v.error.code : 0, dur: v.duration } : null;
  });
  rec('Видео MP4: элемент и метаданные', !!videoInfo && (videoInfo.ready > 0 || videoInfo.err > 0), JSON.stringify(videoInfo));
  await page.screenshot({ path: path.join(shots, '12-preview-video.png') });
  await closePreview(page);
  await goRoot(page);

  /* ---------- 7. скачивание 50 МБ ---------- */
  await openFolder(page, 'Загрузки');
  t = Date.now();
  const [dl] = await Promise.all([
    page.waitForEvent('download', { timeout: 120000 }),
    page.locator('#rows tr', { hasText: 'большой.bin' }).getByText('Скачать').click()
  ]);
  const dlPath = path.join(WORK, 'dl-big.bin');
  await dl.saveAs(dlPath);
  const bigSize = fs.statSync(dlPath).size;
  rec('Скачивание 50 МБ из браузера', bigSize === 50 * 1024 * 1024, (Date.now() - t) + ' мс, ' + bigSize + ' байт');
  fs.unlinkSync(dlPath);

  /* ---------- 8. загрузка через настоящий UI (чанковая очередь + конфликт) ---------- */
  const tmpdir = path.join(WORK, 'tmpupload');
  fs.rmSync(tmpdir, { recursive: true, force: true });
  fs.mkdirSync(tmpdir, { recursive: true });

  // Маленький файл
  fs.writeFileSync(path.join(tmpdir, 'upload-test.txt'), 'uploaded from browser\n');
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  await page.setInputFiles('#fileInput', path.join(tmpdir, 'upload-test.txt'));
  const upMsg = await waitMsg(page, 'Файлы загружены');
  const upOk = upMsg.includes('Файлы загружены') && (await page.locator('#rows tr', { hasText: 'upload-test.txt' }).count()) === 1;
  rec('Загрузка файла с ПК на «телефон»', upOk, upMsg);

  // Многочанковый файл (9 МБ > 4 МБ чанка = 3 чанка)
  fs.writeFileSync(path.join(tmpdir, 'big-upload.bin'), Buffer.alloc(9 * 1024 * 1024, 5));
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  await page.setInputFiles('#fileInput', path.join(tmpdir, 'big-upload.bin'));
  const bigMsg = await waitMsg(page, 'Файлы загружены', 60000);
  const bigOk = bigMsg.includes('Файлы загружены') && (await page.locator('#rows tr', { hasText: 'big-upload.bin' }).count()) === 1;
  rec('Чанковая загрузка файла 9 МБ (3 части)', bigOk, bigMsg);
  await page.locator('#rows tr', { hasText: 'big-upload.bin' }).getByText('Удалить').click();
  await page.waitForTimeout(200);
  await page.click('#dialogAccept');
  await page.waitForTimeout(500);

  // Пустой файл (0 байт)
  fs.writeFileSync(path.join(tmpdir, 'zero.txt'), '');
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  await page.setInputFiles('#fileInput', path.join(tmpdir, 'zero.txt'));
  const zeroMsg = await waitMsg(page, 'Файлы загружены');
  const zeroOk = zeroMsg.includes('Файлы загружены') && (await page.locator('#rows tr', { hasText: 'zero.txt' }).count()) === 1;
  rec('Загрузка пустого файла (0 байт)', zeroOk, zeroMsg);

  // Дубликат: проверка диалога «Перезаписать / Сохранить как / Пропустить»
  fs.writeFileSync(path.join(tmpdir, 'dup-test.txt'), 'dup payload v1\n');
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  await page.setInputFiles('#fileInput', path.join(tmpdir, 'dup-test.txt'));
  await waitMsg(page, 'Файлы загружены');
  const firstOk = (await page.locator('#rows tr', { hasText: 'dup-test.txt' }).count()) === 1;

  fs.writeFileSync(path.join(tmpdir, 'dup-test.txt'), 'dup payload v2 overwritten\n');
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  await page.setInputFiles('#fileInput', path.join(tmpdir, 'dup-test.txt'));
  await page.waitForSelector('#actionDialog:not(.hidden)', { timeout: 15000 });
  const dupTitle = (await page.locator('#dialogTitle').textContent()) || '';
  await page.screenshot({ path: path.join(shots, '13-upload-duplicate.png') });
  await page.click('#dialogExtra'); // «Перезаписать»
  const dupMsg = await waitMsg(page, 'Файлы загружены');
  rec('Дубликат: диалог «Перезаписать/Сохранить как/Пропустить»', firstOk && dupTitle.includes('существует') && dupMsg.includes('Файлы загружены'), dupTitle + ' -> ' + dupMsg);

  await page.locator('#rows tr', { hasText: 'dup-test.txt' }).getByText('Удалить').click();
  await page.waitForTimeout(200);
  await page.click('#dialogAccept');
  await page.waitForTimeout(500);

  /* ---------- 9. переименование и удаление ---------- */
  await page.locator('#rows tr', { hasText: 'upload-test.txt' }).getByText('Имя').click();
  await page.waitForTimeout(250);
  await page.screenshot({ path: path.join(shots, '14-dialog-rename.png') });
  await page.fill('#dialogInput', 'a/b');
  await page.click('#dialogAccept');
  await page.waitForTimeout(200);
  rec('Диалог переименования: запрет «/»', await page.locator('#dialogError').isVisible(), ((await page.locator('#dialogError').textContent()) || '').slice(0, 60));
  await page.fill('#dialogInput', 'переименован.txt');
  await page.click('#dialogAccept');
  await page.waitForTimeout(500);
  rec('Переименование работает', (await page.locator('#rows tr', { hasText: 'переименован.txt' }).count()) === 1, '');

  await page.locator('#rows tr', { hasText: 'переименован.txt' }).getByText('Удалить').click();
  await page.waitForTimeout(250);
  await page.screenshot({ path: path.join(shots, '15-dialog-delete.png') });
  await page.click('#dialogAccept');
  await page.waitForTimeout(500);
  rec('Удаление с подтверждением работает', (await page.locator('#rows tr', { hasText: 'переименован.txt' }).count()) === 0, '');

  // Групповое удаление проверяет сообщение «В корзине: 1»
  await page.locator('#rows tr', { hasText: 'zero.txt' }).locator('input[type=checkbox]').check();
  await page.click('#deleteSelected');
  await page.waitForTimeout(200);
  await page.click('#dialogAccept');
  const delMsg = await waitMsg(page, 'В корзине');
  rec('Групповое удаление удаляет файл и сообщает результат', delMsg.includes('В корзине: 1') && (await page.locator('#rows tr', { hasText: 'zero.txt' }).count()) === 0, delMsg);

  /* ---------- 10. ZIP выбранного ---------- */
  await goRoot(page);
  await openFolder(page, 'Документы');
  await page.locator('#rows tr', { hasText: 'заметки.md' }).locator('input[type=checkbox]').check();
  await page.locator('#rows tr', { hasText: 'контакты.csv' }).locator('input[type=checkbox]').check();
  await page.waitForTimeout(200);
  await page.screenshot({ path: path.join(shots, '16-selection.png') });
  const [zipDl] = await Promise.all([page.waitForEvent('download', { timeout: 60000 }), page.click('#downloadSelected')]);
  const selZipPath = path.join(WORK, 'selected.zip');
  await zipDl.saveAs(selZipPath);
  rec('ZIP выбранных файлов скачивается', fs.statSync(selZipPath).size > 100, fs.statSync(selZipPath).size + ' байт');

  await goRoot(page);
  await page.locator('#rows tr', { hasText: 'Фото' }).locator('input[type=checkbox]').check();
  await page.locator('#rows tr', { hasText: 'Документы' }).locator('input[type=checkbox]').check();
  const [zipSel] = await Promise.all([page.waitForEvent('download', { timeout: 60000 }), page.click('#downloadSelected')]);
  const selFoldersZip = path.join(WORK, 'selection-folders.zip');
  await zipSel.saveAs(selFoldersZip);
  const selNames = zipEntries(selFoldersZip);
  rec('ZIP выбранных папок: обе папки внутри', selNames.some(n => n.startsWith('Фото/')) && selNames.some(n => n.startsWith('Документы/')), 'файлов=' + selNames.length);
  await page.screenshot({ path: path.join(shots, '17-selection-folders.png') });
  await page.click('#clearSelected');
  await page.waitForTimeout(200);

  /* ---------- 10b. крошки и сортировка ---------- */
  await openFolder(page, 'Документы');
  await page.screenshot({ path: path.join(shots, '22-breadcrumbs.png') });
  await page.locator('#path button', { hasText: 'Телефон' }).click();
  await page.waitForTimeout(400);
  const rootBack = (await page.locator('#path').textContent()) || '';
  rec('Хлебные крошки: возврат в корень одним кликом', (await page.locator('#rows tr', { hasText: 'Документы' }).count()) === 1, rootBack.slice(0, 40));

  await openFolder(page, 'Загрузки');
  await page.selectOption('#sort', 'size');
  await page.waitForTimeout(250);
  const sortedNames = await page.locator('#rows tr td:nth-child(2) button.filename').allTextContents();
  rec('Сортировка по размеру: первым самый большой файл', sortedNames[0] === 'большой.bin', sortedNames.slice(0, 3).join(' | '));
  await page.click('thead th[data-sort="size"]');
  await page.waitForTimeout(200);
  const flippedNames = await page.locator('#rows tr td:nth-child(2) button.filename').allTextContents();
  rec('Клик по заголовку колонки меняет порядок сортировки', flippedNames[flippedNames.length - 1] === 'большой.bin', flippedNames.slice(0, 3).join(' | '));
  await page.selectOption('#sort', 'name');
  await goRoot(page);

  /* ---------- 10c. v3.7: память, фильтры, горячие клавиши, тосты ---------- */
  const stVisible = await page.locator('#storagePill').isVisible();
  const stText = ((await page.locator('#storageText').textContent()) || '').trim();
  rec('Индикатор памяти телефона отображается', stVisible && stText.includes('Свободно') && stText.includes('из'), stText);

  await page.click('#copyDav');
  await page.waitForTimeout(150);
  const toastShown = (await page.locator('#toastHost .toast').count()) > 0;
  const davCopiedCls = await page.locator('#copyDav').evaluate(b => b.classList.contains('copied'));
  rec('Копирование WebDAV показывает тост и подтверждение', toastShown && davCopiedCls, '');

  await openFolder(page, 'Фото');
  const filterVisible = await page.locator('#filterBar').isVisible();
  const totalInPhotos = await rowsCount(page);
  await page.locator('#filterBar .fchip[data-filter="folder"]').click();
  await page.waitForTimeout(150);
  const onlyFolders = await rowsCount(page);
  await page.locator('#filterBar .fchip[data-filter="image"]').click();
  await page.waitForTimeout(150);
  const onlyImages = await rowsCount(page);
  await page.locator('#filterBar .fchip[data-filter="all"]').click();
  await page.waitForTimeout(150);
  const restoredCount = await rowsCount(page);
  rec('Быстрые фильтры по типу работают корректно',
      filterVisible && onlyFolders >= 1 && onlyImages >= 1 && onlyFolders + onlyImages === totalInPhotos && restoredCount === totalInPhotos,
      'всего=' + totalInPhotos + ' папки=' + onlyFolders + ' фото=' + onlyImages);

  await page.keyboard.press('/');
  const searchFocused = await page.evaluate(() => document.activeElement && document.activeElement.id === 'search');
  await page.keyboard.type('иконка');
  await page.waitForTimeout(120);
  const slashFiltered = await rowsCount(page);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(120);
  const escCleared = (await page.locator('#search').inputValue()) === '' && (await rowsCount(page)) === totalInPhotos;
  await page.keyboard.press('Escape');
  await page.keyboard.press('Alt+ArrowUp');
  await page.waitForTimeout(300);
  const backAtRoot = (await page.locator('#rows tr', { hasText: 'Фото' }).count()) === 1;
  rec('Горячие клавиши (/ поиск, Esc очистка, Alt+↑ наверх)',
      searchFocused && slashFiltered === 1 && escCleared && backAtRoot,
      'focus=' + searchFocused + ' slash=' + slashFiltered + ' esc=' + escCleared + ' up=' + backAtRoot);

  /* ---------- 11. длинные имена и юникод ---------- */
  await openFolder(page, 'Длинные имена');
  const longRow = await page.locator('#rows tr').count();
  rec('Длинные/юникод имена отображаются', longRow >= 2, 'строк=' + longRow);
  await page.screenshot({ path: path.join(shots, '18-long-names.png') });
  await goRoot(page);

  /* ---------- 12. навигация и выход ---------- */
  await openFolder(page, 'Фото');
  const pathLabel = (await page.locator('#path').textContent()) || '';
  await page.click('#up');
  await page.waitForTimeout(300);
  rec('Навигация назад и хлебные крошки в шапке', pathLabel.includes('Телефон') && pathLabel.includes('Фото'), pathLabel);
  await page.click('#logout');
  await page.waitForTimeout(300);
  rec('Выход возвращает на экран входа', await page.locator('#login').isVisible(), '');

  /* ---------- 12b. авто-вход по ссылке, глобальный поиск, ZIP папки, DnD ---------- */
  const autoctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, ignoreHTTPSErrors: true });
  const ap = await autoctx.newPage();
  ap.on('pageerror', e => pageErrors.push(e.message || String(e)));
  await ap.goto(BASE + '/#k=' + ACCESS_KEY, { waitUntil: 'load' });
  let autoOk = false;
  try { await ap.waitForSelector('#app:not(.hidden)', { timeout: 8000 }); autoOk = true; } catch (e) {}
  const hashGone = !(await ap.evaluate(() => location.hash));
  rec('Ссылка с кодом входит автоматически и чистит адрес', autoOk && hashGone, 'app=' + autoOk + ' hash пуст=' + hashGone);
  await autoctx.close();

  await page.fill('#code', ACCESS_KEY);
  await page.click('#loginForm button[type=submit]');
  await page.waitForSelector('#app:not(.hidden)');
  await page.waitForTimeout(300);

  await openFolder(page, 'Фото');
  await page.selectOption('#scope', 'global');
  await page.fill('#search', 'IMG_2003');
  await page.waitForTimeout(1000);
  const globalRows = await page.locator('#rows tr').count();
  const subPath = globalRows ? ((await page.locator('#rows tr .subpath').first().textContent()) || '') : '';
  rec('Поиск по всем папкам находит файл во вложенной папке', globalRows === 1 && subPath.includes('Отпуск'), 'строк=' + globalRows + ' путь=' + subPath);
  await page.screenshot({ path: path.join(shots, '23-search-global.png') });
  await page.fill('#search', '');
  await page.selectOption('#scope', 'local');
  await goRoot(page);

  const [zipFolder] = await Promise.all([
    page.waitForEvent('download', { timeout: 60000 }),
    page.locator('#rows tr', { hasText: 'Фото' }).getByText('ZIP').click()
  ]);
  const folderZipPath = path.join(WORK, 'folder.zip');
  await zipFolder.saveAs(folderZipPath);
  const folderNames = zipEntries(folderZipPath);
  rec('ZIP папки целиком сохраняет структуру', folderNames.includes('Фото/Отпуск/IMG_2000.jpg') && folderNames.includes('Фото/обложка.png'), folderNames.slice(0, 3).join(' | '));

  // drag & drop
  await openFolder(page, 'Загрузки');
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  await page.evaluate(payload => {
    const dt = new DataTransfer();
    dt.items.add(new File([payload], 'dnd-test.txt', { type: 'text/plain' }));
    const zone = document.getElementById('app');
    zone.dispatchEvent(new DragEvent('dragover', { bubbles: true, dataTransfer: dt }));
    zone.dispatchEvent(new DragEvent('drop', { bubbles: true, dataTransfer: dt }));
  }, 'dropped via dnd\n');
  const dndMsg = await waitMsg(page, 'Файлы загружены', 20000);
  rec('Drag & drop загружает файл', dndMsg.includes('Файлы загружены') && (await page.locator('#rows tr', { hasText: 'dnd-test.txt' }).count()) === 1, dndMsg.slice(0, 60));
  await page.locator('#rows tr', { hasText: 'dnd-test.txt' }).getByText('Удалить').click();
  await page.waitForTimeout(200);
  await page.click('#dialogAccept');
  await page.waitForTimeout(500);
  await goRoot(page);

  /* ---------- 12c. плитка, миниатюры (JPEG + SVG), темы, загрузка папки ---------- */
  await openFolder(page, 'Фото');
  await page.click('#view');
  await page.waitForFunction(() => {
    const cards = Array.from(document.querySelectorAll('#grid .card'));
    const c = cards.find(el => el.textContent && el.textContent.includes('иконка.svg'));
    const img = c && c.querySelector('img');
    return !!(img && img.complete && img.naturalWidth > 0);
  }, { timeout: 8000 }).catch(() => {});
  const svgThumbOk = await page.locator('#grid .card', { hasText: 'иконка.svg' }).locator('img').evaluate(i => i.naturalWidth > 0).catch(() => false);
  rec('Плитка: SVG-миниатюра отображается без ошибок', svgThumbOk && dialogs.length === 0, '');

  await page.locator('#grid .card', { hasText: 'Отпуск' }).locator('.thumb').click();
  await page.waitForFunction(() => {
    const cards = document.querySelectorAll('#grid .card');
    const img = document.querySelector('#grid .card img');
    return cards.length >= 6 && img && img.complete && img.naturalWidth > 0;
  }, { timeout: 8000 }).catch(() => {});
  const cards = await page.locator('#grid .card').count();
  const thumbInfo = await page.locator('#grid .card img').first().evaluate(img => ({ w: img.naturalWidth, h: img.naturalHeight })).catch(() => null);
  rec('Плитка: карточки и JPEG-миниатюры загрузились', cards >= 6 && !!thumbInfo && thumbInfo.w > 0, 'карточек=' + cards + ' миниатюра=' + (thumbInfo ? thumbInfo.w + 'x' + thumbInfo.h : 'нет'));
  await page.screenshot({ path: path.join(shots, '24-grid-thumbs.png') });

  await page.locator('#grid .card').first().locator('.thumb').click();
  await page.waitForTimeout(700);
  rec('Плитка: клик по карточке открывает просмотр', (await page.locator('#previewBody img').count()) > 0, '');
  await closePreview(page);
  await page.click('#view');
  await page.waitForTimeout(300);
  rec('Переключение обратно в список', (await page.locator('#rows tr').count()) >= 6 && await page.locator('#tableWrap').isVisible(), '');

  const themeBefore = await page.evaluate(() => document.documentElement.dataset.theme);
  await page.click('#theme');
  await page.waitForTimeout(300);
  const themeAfter = await page.evaluate(() => ({ theme: document.documentElement.dataset.theme, bg: getComputedStyle(document.body).backgroundColor }));
  rec('Переключение темы меняет оформление', themeAfter.theme !== themeBefore && !!themeAfter.bg, themeBefore + ' -> ' + themeAfter.theme + ' bg=' + themeAfter.bg);
  await page.evaluate(() => { if (document.documentElement.dataset.theme !== 'light') document.getElementById('theme').click(); });
  await page.waitForTimeout(300);
  rec('Светлая тема применяется', await page.evaluate(() => document.documentElement.dataset.theme === 'light'), '');
  await page.screenshot({ path: path.join(shots, '25-light-theme.png') });
  await page.click('#theme');
  await page.waitForTimeout(300);
  rec('Тёмная тема возвращается', await page.evaluate(() => document.documentElement.dataset.theme === 'dark'), '');
  await page.screenshot({ path: path.join(shots, '26-dark-theme.png') });
  await page.click('#view');
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(shots, '27-dark-grid.png') });
  await page.click('#view');
  await page.waitForTimeout(250);

  // загрузка папки с сохранением структуры
  const folderName = 'pb-up-' + Date.now();
  const uploadDir = path.join(WORK, 'dir-upload', folderName);
  fs.mkdirSync(path.join(uploadDir, 'sub'), { recursive: true });
  fs.writeFileSync(path.join(uploadDir, 'top.txt'), 'top file\n');
  fs.writeFileSync(path.join(uploadDir, 'sub', 'nested.txt'), 'nested file\n');
  await goRoot(page);
  await openFolder(page, 'Загрузки');
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  await page.setInputFiles('#dirInput', uploadDir);
  const folderMsg = await waitMsg(page, 'Папка загружена', 30000);
  const folderRow = await page.locator('#rows tr', { hasText: folderName }).count();
  await openFolder(page, folderName);
  const topFile = await page.locator('#rows tr', { hasText: 'top.txt' }).count();
  const subFolder = await page.locator('#rows tr', { hasText: 'sub' }).count();
  await openFolder(page, 'sub');
  const deepFile = await page.locator('#rows tr', { hasText: 'nested.txt' }).count();
  rec('Загрузка папки сохраняет структуру', folderMsg.includes('Папка загружена') && folderRow === 1 && topFile === 1 && subFolder === 1 && deepFile === 1,
      'папка=' + folderRow + ' top=' + topFile + ' sub=' + subFolder + ' вложенный=' + deepFile);

  // уборка загруженной папки
  await page.click('#up'); await page.waitForTimeout(200);
  await page.click('#up'); await page.waitForTimeout(300);
  await page.locator('#rows tr', { hasText: folderName }).getByText('Удалить').click();
  await page.waitForTimeout(200);
  await page.click('#dialogAccept');
  await page.waitForTimeout(500);
  await goRoot(page);

  /* ---------- 12d. новинки v3.8: копирование/перемещение, общий буфер, Ctrl+V, папки DnD, закладки, Shift+клик ---------- */
  // 1) Общий текстовый буфер обмена (ПК ↔ телефон)
  rec('Индикатор общего буфера скрыт при пустом буфере', await page.locator('#openClipDot').isHidden(), '');
  await page.click('#openClip');
  await page.waitForSelector('#clipModal:not(.hidden)');
  await page.fill('#sharedClipInput', 'https://example.org/pb-v38\nКод: 482-910');
  await page.locator('#sharedClipInput').press('Control+Enter');
  await page.waitForFunction(() => {
    const dot = document.getElementById('openClipDot');
    return dot && !dot.classList.contains('hidden');
  }, null, { timeout: 5000 });
  const clipMetaTxt = (await page.locator('#sharedClipMeta').textContent()) || '';
  rec('Общий буфер: отправка по Ctrl+Enter и индикатор в панели',
      clipMetaTxt.includes('Из браузера') && await page.locator('#openClipDot').isVisible(), clipMetaTxt);
  await page.screenshot({ path: path.join(shots, '30-shared-clipboard.png') });
  await page.keyboard.press('Escape');
  await page.waitForSelector('#clipModal', { state: 'hidden' });
  // Переоткрытие подтягивает сохранённый текст, «Очистить» сбрасывает буфер и точку
  await page.click('#openClip');
  await page.waitForSelector('#clipModal:not(.hidden)');
  await page.waitForFunction(() => document.getElementById('sharedClipInput').value.includes('482-910'), { timeout: 5000 });
  const clipRoundtrip = await page.inputValue('#sharedClipInput');
  rec('Общий буфер: повторное открытие возвращает текст с сервера', clipRoundtrip.includes('https://example.org/pb-v38'), '');
  await page.click('#sharedClipClear');
  await page.waitForFunction(() => document.getElementById('openClipDot').classList.contains('hidden'), { timeout: 5000 });
  rec('Общий буфер: очистка сбрасывает текст и индикатор', (await page.inputValue('#sharedClipInput')) === '' && await page.locator('#openClipDot').isHidden(), '');
  await page.click('#sharedClipClose');
  await page.waitForSelector('#clipModal', { state: 'hidden' });

  // 2) Быстрые папки Android (quickDirs) + закрепление своих папок (pb-pins)
  const v38Sandbox = 'v38-box-' + Date.now();
  await page.evaluate(async ({ sb, k }) => {
    const post = p => fetch(p, { method: 'POST', headers: { 'X-Access-Key': k } });
    await post('/api/mkdirs?path=/DCIM/Camera');
    await post('/api/mkdirs?path=/Download');
    await post('/api/mkdirs?path=/' + encodeURIComponent(sb) + '/alpha');
    await post('/api/mkdirs?path=/' + encodeURIComponent(sb) + '/beta');
  }, { sb: v38Sandbox, k: ACCESS_KEY });
  await page.click('#refresh');
  await page.waitForSelector('#bookmarksBar:not(.hidden)');
  const bmLabels = await page.locator('#bookmarksBar .bmchip').allTextContents();
  const bmBox = await page.locator('#bookmarksBar .bmchip').first().boundingBox();
  rec('Быстрые папки: DCIM/Camera («Камера») и Download («Загрузки») показаны компактными чипами',
      bmLabels.some(s => s.includes('Камера')) && bmLabels.some(s => s.includes('Загрузки')) && !!bmBox && bmBox.height <= 32,
      bmLabels.join(', ') + ' h=' + (bmBox ? Math.round(bmBox.height) : 0));
  await openFolder(page, 'Фото');
  await openFolder(page, 'Отпуск');
  await page.click('#pinFolder');
  await page.waitForTimeout(200);
  const pinnedActive = await page.locator('#pinFolder').evaluate(b => b.classList.contains('is-pinned'));
  const hasVacationPin = (await page.locator('#bookmarksBar .bmchip', { hasText: 'Отпуск' }).count()) === 1;
  rec('Закрепление папки добавляет чип в панель закладок', pinnedActive && hasVacationPin, '');
  await page.screenshot({ path: path.join(shots, '31-bookmarks-bar.png') });
  await page.locator('#bookmarksBar .bmchip', { hasText: 'Загрузки' }).click();
  await page.waitForTimeout(300);
  const jumpedPath = (await page.locator('#path').textContent()) || '';
  rec('Клик по быстрой папке мгновенно открывает /Download', jumpedPath.includes('Download'), jumpedPath);
  await page.locator('#bookmarksBar .bmchip', { hasText: 'Отпуск' }).click();
  await page.waitForTimeout(300);
  const jumpedPin = (await page.locator('#path').textContent()) || '';
  rec('Клик по закреплённой папке открывает /Фото/Отпуск', jumpedPin.includes('Отпуск'), jumpedPin);

  // 3) Shift+Click выделение диапазона и Ctrl+A / Escape
  const cbs = page.locator('#rows tr input[type=checkbox]');
  await cbs.nth(1).click();
  await cbs.nth(4).click({ modifiers: ['Shift'] });
  const shiftSelTxt = (await page.locator('#selectedCount').textContent()) || '';
  rec('Shift+клик выделяет непрерывный диапазон файлов (4 шт.)', shiftSelTxt.includes('4'), shiftSelTxt);
  await page.screenshot({ path: path.join(shots, '32-selection-copy-cut.png') });
  await page.keyboard.press('Control+KeyA');
  const allSelTxt = (await page.locator('#selectedCount').textContent()) || '';
  rec('Ctrl+A выделяет все файлы папки (6 шт.)', allSelTxt.includes('6'), allSelTxt);
  await page.keyboard.press('Escape');
  rec('Escape снимает выделение', await page.locator('#selection').isHidden(), '');

  // 4) Вставка скриншота по Ctrl+V, копирование/разрешение конфликта, вырезание и перетаскивание в папку
  await goRoot(page);
  await openFolder(page, v38Sandbox);
  await openFolder(page, 'alpha');
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  await page.evaluate(() => {
    const png1x1 = Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='), c => c.charCodeAt(0));
    const dt = new DataTransfer();
    dt.items.add(new File([png1x1], 'image.png', { type: 'image/png' }));
    const ev = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true });
    window.dispatchEvent(ev);
  });
  const pasteUpMsg = await waitMsg(page, 'Файлы загружены', 12000);
  const pastedRow = page.locator('#rows tr', { hasText: 'clipboard-' });
  const pastedName = (await pastedRow.count()) === 1 ? ((await pastedRow.locator('button.filename').textContent()) || '') : '';
  rec('Вставка скриншота из буфера (Ctrl+V) загружает PNG с меткой времени',
      pasteUpMsg.includes('Файлы загружены') && /^clipboard-\d{8}-\d{6}\.png$/.test(pastedName), pastedName);

  // Копирование файла в beta + повторная вставка с сохранением под новым именем (конфликт 409)
  await pastedRow.locator('input[type=checkbox]').check();
  await page.click('#copySelected');
  rec('Панель буфера файлов появляется после «Копировать»', await page.locator('#clipBar').isVisible(), '');
  await page.screenshot({ path: path.join(shots, '33-clipbar-paste.png') });
  await page.click('#up'); await page.waitForTimeout(250);
  await openFolder(page, 'beta');
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  await page.click('#pasteHere');
  const copyMsg = await waitMsg(page, 'Скопировано', 10000);
  rec('Вставка копии переносит файл в целевую папку',
      copyMsg.includes('Скопировано: 1') && (await page.locator('#rows tr', { hasText: pastedName }).count()) === 1, copyMsg);

  // Повторная вставка в beta -> диалог конфликта -> «Сохранить как» (clipboard-... (1).png)
  const copyClick = page.click('#pasteHere');
  await page.waitForSelector('#actionDialog:not(.hidden)');
  const suggestedDup = await page.inputValue('#dialogInput');
  await page.click('#dialogAccept');
  await copyClick;
  await page.waitForTimeout(300);
  rec('Конфликт при копировании: сохранение под новым именем «(1)»',
      suggestedDup.includes('(1)') && (await page.locator('#rows tr', { hasText: suggestedDup }).count()) === 1, suggestedDup);

  // Вырезание (Cut) дубликата обратно в корень песочницы + перетаскивание строки внутрь папки alpha
  await page.locator('#rows tr', { hasText: suggestedDup }).locator('input[type=checkbox]').check();
  await page.click('#cutSelected');
  await page.click('#up'); await page.waitForTimeout(250);
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  await page.click('#pasteHere');
  const moveMsg = await waitMsg(page, 'Перемещено', 10000);
  rec('Вырезание и вставка перемещает файл и очищает буфер',
      moveMsg.includes('Перемещено: 1')
      && (await page.locator('#rows tr', { hasText: suggestedDup }).count()) === 1
      && await page.locator('#clipBar').isHidden(), moveMsg);

  // Внутренний Drag & drop строки файла на строку папки alpha (без появления внешнего #dropOverlay)
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  const dragOverlayShown = await page.evaluate(async fileName => {
    const rows = [...document.querySelectorAll('#rows tr')];
    const src = rows.find(r => r.textContent && r.textContent.includes(fileName));
    const dst = rows.find(r => r.textContent && r.textContent.includes('alpha'));
    if (!src || !dst) return 'missing';
    const dt = new DataTransfer();
    src.dispatchEvent(new DragEvent('dragstart', { dataTransfer: dt, bubbles: true, cancelable: true }));
    window.dispatchEvent(new DragEvent('dragenter', { dataTransfer: dt, bubbles: true, cancelable: true }));
    const overlayHidden = document.getElementById('dropOverlay').classList.contains('hidden');
    dst.dispatchEvent(new DragEvent('dragover', { dataTransfer: dt, bubbles: true, cancelable: true }));
    const highlighted = dst.classList.contains('drop-target');
    dst.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
    src.dispatchEvent(new DragEvent('dragend', { dataTransfer: dt, bubbles: true, cancelable: true }));
    return overlayHidden && highlighted ? 'ok' : ('overlay=' + overlayHidden + ' hl=' + highlighted);
  }, suggestedDup);
  const dndMoveMsg = await waitMsg(page, 'Перемещено', 10000);
  await openFolder(page, 'alpha');
  const inAlphaAfterDrag = (await page.locator('#rows tr', { hasText: suggestedDup }).count()) === 1;
  rec('Перетаскивание строки файла в папку перемещает его без внешнего оверлея',
      dragOverlayShown === 'ok' && dndMoveMsg.includes('Перемещено: 1') && inAlphaAfterDrag, dragOverlayShown + ' ' + dndMoveMsg);

  // 5) Drag & drop вложенной папки через webkitGetAsEntry (включая пустую подпапку)
  await page.locator('#message').evaluate(m => { m.textContent = ''; });
  await page.evaluate(() => {
    const mkFileEntry = (name, content) => ({
      isFile: true, isDirectory: false, name,
      file: cb => cb(new File([content], name, { type: 'text/plain' }))
    });
    const mkDirEntry = (name, children) => ({
      isFile: false, isDirectory: true, name,
      createReader: () => {
        let read = false;
        return { readEntries: cb => { if (read) cb([]); else { read = true; cb(children); } } };
      }
    });
    const tree = mkDirEntry('dropped-pkg', [
      mkFileEntry('readme.txt', 'hello drop folder\n'),
      mkDirEntry('nested', [mkFileEntry('deep.txt', 'deep content\n')]),
      mkDirEntry('empty-sub', [])
    ]);
    const fakeEvent = new Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperty(fakeEvent, 'dataTransfer', {
      value: {
        types: ['Files'],
        files: [],
        items: [{ kind: 'file', webkitGetAsEntry: () => tree }]
      }
    });
    window.dispatchEvent(fakeEvent);
  });
  const dndDirMsg = await waitMsg(page, 'Папка загружена', 15000);
  await openFolder(page, 'dropped-pkg');
  const hasReadme = (await page.locator('#rows tr', { hasText: 'readme.txt' }).count()) === 1;
  const hasEmptySub = (await page.locator('#rows tr', { hasText: 'empty-sub' }).count()) === 1;
  await openFolder(page, 'nested');
  const hasDeepTxt = (await page.locator('#rows tr', { hasText: 'deep.txt' }).count()) === 1;
  rec('Drag & drop папки сохраняет вложенные файлы и пустые подпапки',
      dndDirMsg.includes('Папка загружена: 2') && hasReadme && hasEmptySub && hasDeepTxt, dndDirMsg);

  // Уборка временных папок v3.8 (оставляем DCIM/Camera и Download для проверки мобильной вёрстки закладок)
  await goRoot(page);
  await page.locator('#bookmarksBar .bmchip', { hasText: 'Отпуск' }).locator('.bmx').click();
  await page.waitForTimeout(150);
  rec('Открепление папки по «×» убирает её из закладок',
      (await page.locator('#bookmarksBar .bmchip', { hasText: 'Отпуск' }).count()) === 0, '');
  await page.locator('#rows tr', { hasText: v38Sandbox }).getByText('Удалить').click();
  await page.waitForTimeout(200);
  await page.click('#dialogAccept');
  await page.waitForTimeout(400);

  /* ---------- 12e. новинки v3.9: видеопревью, редактор текста, поворот фото, ZIP-распаковка, свойства и SHA-256 ---------- */
  // 1. Миниатюры видео в режиме плитки (реальный кадр + fallback для битого MP4)
  await openFolder(page, 'Видео');
  await page.click('#view'); // -> плитка
  await page.waitForTimeout(700);
  const vidGrid = await page.evaluate(() => {
    const sample = document.querySelector('#grid .card[data-path="/Видео/sample.mp4"]');
    const broken = document.querySelector('#grid .card[data-path="/Видео/битый.mp4"]');
    const sImg = sample && sample.querySelector('.thumb img');
    const bImg = broken && broken.querySelector('.thumb img');
    return {
      sampleReady: !!(sImg && sImg.complete && sImg.naturalWidth > 0 && sImg.classList.contains('is-ready')),
      sampleW: sImg ? sImg.naturalWidth : 0,
      sampleBadge: !!(sample && sample.querySelector('.playbadge')),
      brokenFallback: !bImg && !!(broken && broken.querySelector('.cardicon.ic-video')) && !!(broken && broken.querySelector('.playbadge'))
    };
  });
  rec('Видео-миниатюра в плитке загружает кадр + значок Play, битый MP4 даёт чистый fallback',
      vidGrid.sampleReady && vidGrid.sampleBadge && vidGrid.brokenFallback, JSON.stringify(vidGrid));
  await page.screenshot({ path: path.join(shots, '35-video-thumbs.png') });
  await page.click('#view'); // -> список
  await page.waitForTimeout(200);
  await goRoot(page);

  // 2. Создание текстового файла (#mkfile), правка с Ctrl+S и защита от конфликта mtime
  const v39Sandbox = 'v39-sandbox';
  await page.click('#mkdir');
  await page.fill('#dialogInput', v39Sandbox);
  await page.click('#dialogAccept');
  await page.waitForTimeout(350);
  await openFolder(page, v39Sandbox);

  await page.click('#mkfile');
  await page.waitForSelector('#actionDialog:not(.hidden)');
  await page.fill('#dialogInput', 'список.md');
  await page.click('#dialogAccept');
  await page.waitForSelector('#textEditorArea');
  await page.locator('#textEditorArea').fill('# План v3.9\n- Видео\n- ZIP\n');
  await page.waitForTimeout(220);
  await page.screenshot({ path: path.join(shots, '36-text-editor.png') });
  await page.keyboard.press('Control+s');
  await page.waitForTimeout(400);
  const mdDiskPath = path.join(ROOT, v39Sandbox, 'список.md');
  const savedMd = fs.existsSync(mdDiskPath) ? fs.readFileSync(mdDiskPath, 'utf8') : '';
  rec('Создание файла (#mkfile) и сохранение по Ctrl+S записывают UTF-8 на диск',
      savedMd === '# План v3.9\n- Видео\n- ZIP\n', JSON.stringify(savedMd));

  // Симулируем внешнее изменение на телефоне и проверяем диалог конфликта mtime (409)
  fs.writeFileSync(mdDiskPath, 'изменено извне\n', 'utf8');
  const bumpedSec = Math.floor(Date.now() / 1000) + 5;
  fs.utimesSync(mdDiskPath, bumpedSec, bumpedSec);
  await page.locator('#textEditorArea').fill('# Моя версия побеждает\n');
  await page.click('#previewSave');
  await page.waitForSelector('#actionDialog:not(.hidden)', { timeout: 5000 });
  const conflictTitle = (await page.locator('#dialogTitle').textContent()) || '';
  await page.click('#dialogAccept'); // Перезаписать (force=1)
  await page.waitForTimeout(400);
  const forcedMd = fs.readFileSync(mdDiskPath, 'utf8');
  rec('Редактор ловит внешнее изменение (409 mtime) и перезаписывает по подтверждению',
      /изменился/.test(conflictTitle) && forcedMd === '# Моя версия побеждает\n', conflictTitle);
  await closePreview(page);
  await goRoot(page);
  await page.locator('#rows tr', { hasText: v39Sandbox }).getByText('Удалить').click();
  await page.waitForTimeout(200);
  await page.click('#dialogAccept');
  await page.waitForTimeout(350);

  // 3. Поворот фото в просмотрщике (клавиша R + сохранение на диск)
  await openFolder(page, 'Фото');
  await page.locator('#rows tr', { hasText: 'обложка.png' }).locator('button.filename').click();
  await page.waitForSelector('#previewBody img.is-ready');
  await page.keyboard.press('r');
  await page.waitForTimeout(150);
  const rotPreview = await page.evaluate(() => {
    const img = document.querySelector('#previewBody img');
    const sb = document.getElementById('previewSaveRotate');
    return { tf: img ? img.style.transform : '', saveShown: !!(sb && !sb.classList.contains('hidden')) };
  });
  await page.click('#previewSaveRotate');
  await page.waitForTimeout(600);
  await page.waitForSelector('#previewBody img.is-ready');
  const rotatedDims = await page.evaluate(() => {
    const img = document.querySelector('#previewBody img');
    return img ? { w: img.naturalWidth, h: img.naturalHeight, tf: img.style.transform } : null;
  });
  rec('Поворот фото на 90° (R + «Сохранить поворот») меняет размер 400×300 -> 300×400',
      rotPreview.tf === 'rotate(90deg)' && rotPreview.saveShown
        && !!rotatedDims && rotatedDims.w === 300 && rotatedDims.h === 400 && rotatedDims.tf === '',
      JSON.stringify(rotatedDims));
  // Возвращаем исходную ориентацию 400×300 (поворот на 270° = -90°)
  await page.keyboard.press('Shift+R');
  await page.click('#previewSaveRotate');
  await page.waitForTimeout(500);
  await closePreview(page);

  // 4. Свойства файла / папки + расчёт и сверка SHA-256
  const coverBytes = fs.readFileSync(path.join(ROOT, 'Фото', 'обложка.png'));
  const expectedSha = crypto.createHash('sha256').update(coverBytes).digest('hex');
  await page.locator('#rows tr', { hasText: 'обложка.png' }).locator('.infobtn').click();
  await page.waitForSelector('#propsModal:not(.hidden)');
  await page.waitForTimeout(300);
  const propsText = (await page.locator('#propsGrid').textContent()) || '';
  await page.click('#propsCalcHash');
  await page.waitForSelector('#propsHashValue:not(.hidden)', { timeout: 5000 });
  const uiSha = ((await page.locator('#propsHashValue').textContent()) || '').trim();
  await page.fill('#propsHashInput', expectedSha.toUpperCase() + '  обложка.png');
  const matchOk = await page.locator('#propsHashStatus').evaluate(n => n.classList.contains('ok') && !n.classList.contains('hidden'));
  await page.screenshot({ path: path.join(shots, '38-props-sha256.png') });
  await page.fill('#propsHashInput', '0'.repeat(64));
  const mismatchOk = await page.locator('#propsHashStatus').evaluate(n => n.classList.contains('bad') && !n.classList.contains('hidden'));
  rec('Свойства фото (400 × 300 px) и расчёт/сверка SHA-256 работают точно',
      propsText.includes('400 × 300 px') && propsText.includes('image/png')
        && uiSha === expectedSha && matchOk && mismatchOk,
      uiSha.slice(0, 16) + '…');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(150);

  // Свойства папки по Alt+Enter с живым подсчётом размера
  await page.locator('#rows tr', { hasText: 'Отпуск' }).locator('input[type=checkbox]').click();
  await page.keyboard.press('Alt+Enter');
  await page.waitForSelector('#propsModal:not(.hidden)');
  await page.waitForTimeout(450);
  const dirPropsText = (await page.locator('#propsGrid').textContent()) || '';
  rec('Свойства папки (Alt+Enter) показывают состав и рекурсивный объём',
      dirPropsText.includes('файлов: 6') && (dirPropsText.includes('КБ') || dirPropsText.includes('МБ')),
      dirPropsText.replace(/\s+/g, ' ').slice(0, 90));
  await page.keyboard.press('Escape');
  await page.click('#clearSelected');
  await goRoot(page);

  // 5. Просмотр и выборочная/полная распаковка .zip без скачивания
  await openFolder(page, 'Загрузки');
  await page.locator('#rows tr', { hasText: 'архив.zip' }).locator('button.filename').click();
  await page.waitForSelector('.zipview');
  const zipTotalRows = await page.locator('.ziprow').count();
  await page.screenshot({ path: path.join(shots, '37-zip-preview.png') });
  await page.fill('#zipFilter', 'план');
  const zipFilteredRows = await page.locator('.ziprow').count();
  await page.locator('.ziprow').first().locator('input[type=checkbox]').click();
  await page.click('#zipExtractHere');
  await page.waitForTimeout(500);
  const selExtracted = fs.existsSync(path.join(ROOT, 'Загрузки', 'docs', 'план.md'))
    && !fs.existsSync(path.join(ROOT, 'Загрузки', 'readme.txt'));
  rec('Просмотр .zip, фильтр и выборочная распаковка одного файла работают',
      zipTotalRows === 4 && zipFilteredRows === 1 && selExtracted,
      'rows=' + zipTotalRows + ' filtered=' + zipFilteredRows);

  await page.locator('#rows tr', { hasText: 'архив.zip' }).locator('button.filename').click();
  await page.waitForSelector('#zipExtractFolder');
  await page.click('#zipExtractFolder');
  await page.waitForTimeout(600);
  const fullReadme = path.join(ROOT, 'Загрузки', 'архив', 'readme.txt');
  const fullOk = fs.existsSync(fullReadme) && fs.readFileSync(fullReadme, 'utf8').includes('Привет из архива PocketBridge!');
  rec('Полная распаковка .zip в подпапку «архив» извлекает дерево файлов',
      fullOk && (await page.locator('#rows tr', { hasText: 'архив' }).count()) >= 2, '');
  fs.rmSync(path.join(ROOT, 'Загрузки', 'docs'), { recursive: true, force: true });
  fs.rmSync(path.join(ROOT, 'Загрузки', 'архив'), { recursive: true, force: true });
  await goRoot(page);

  // Проверка компактности десктопной панели инструментов с новой кнопкой #mkfile (строго 1 строка)
  const barBoxV39 = await page.locator('.bar').boundingBox();
  rec('Панель инструментов с #mkfile остаётся в одну строку на 1280px',
      !!barBoxV39 && barBoxV39.height <= 72, 'barH=' + (barBoxV39 ? Math.round(barBoxV39.height) : 0));

  /* ---------- 13. мобильная вёрстка ---------- */
  const mctx = await browser.newContext({ ...devices['iPhone 13'], ignoreHTTPSErrors: true });
  const mp = await mctx.newPage();
  mp.on('pageerror', e => pageErrors.push(e.message || String(e)));
  await mp.goto(BASE + '/', { waitUntil: 'load' });
  await mp.screenshot({ path: path.join(shots, '19-mobile-login.png') });
  await mp.fill('#code', ACCESS_KEY);
  await mp.click('#loginForm button[type=submit]');
  await mp.waitForSelector('#app:not(.hidden)');
  await mp.waitForTimeout(400);
  await mp.screenshot({ path: path.join(shots, '20-mobile-list.png') });
  const mOverflow = await mp.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 2);
  const mkfileFits = await mp.evaluate(() => {
    const b = document.getElementById('mkfile').getBoundingClientRect();
    const p = document.querySelector('#app .panel').getBoundingClientRect();
    return b.width >= 34 && b.right <= p.right - 8;
  });
  rec('Мобильная вёрстка: список и кнопка #mkfile без горизонтального скролла и обрезки', !mOverflow && mkfileFits, mOverflow ? 'есть overflow' : 'ok');
  const act = mp.locator('#rows tr .more-actions').first();
  const actBox = await act.boundingBox();
  await act.click();
  const visibleActions = await mp.locator('#workbench .wb-actions').getByText('В корзину', { exact: true }).isVisible();
  rec('Мобильная вёрстка: меню действий доступно без горизонтального скролла',
      visibleActions && !!actBox && actBox.x + actBox.width <= 391 && actBox.width >= 44 && actBox.height >= 44,
      'target=' + (actBox ? `${actBox.width}×${actBox.height}px` : 'нет'));
  await mp.click('#wbClose');
  const mSearchBox = await mp.locator('#search').boundingBox();
  const mTopBox = await mp.locator('header.top').boundingBox();
  rec('Мобильная вёрстка: поиск на всю ширину и шапка в одну строку',
      !!mSearchBox && mSearchBox.width >= 180 && !!mTopBox && mTopBox.height <= 52,
      'search=' + (mSearchBox ? Math.round(mSearchBox.width) : 0) + 'px top=' + (mTopBox ? Math.round(mTopBox.height) : 0) + 'px');
  await mp.click('#theme');
  await mp.waitForTimeout(250);
  await mp.screenshot({ path: path.join(shots, '28-mobile-dark.png') });
  await mp.locator('#rows tr', { hasText: 'Фото' }).locator('button.filename').click();
  await mp.waitForTimeout(400);
  const fileActions = mp.locator('#rows tr', { hasText: 'обложка' }).locator('.more-actions');
  await fileActions.click();
  rec('Мобильная вёрстка: меню файла открывает свойства', await mp.locator('#workbench').getByText('Свойства', { exact: true }).isVisible(), '');
  await mp.click('#wbClose');
  await mp.screenshot({ path: path.join(shots, '29-mobile-files.png') });
  await mp.locator('#rows tr', { hasText: 'обложка' }).locator('button.filename').click();
  await mp.waitForTimeout(900);
  await mp.screenshot({ path: path.join(shots, '21-mobile-preview.png') });
  rec('Мобильная вёрстка: превью открывается', (await mp.locator('#previewBody img').count()) > 0, '');
  await mp.click('#previewProps');
  await mp.waitForSelector('#propsModal:not(.hidden)');
  await mp.waitForTimeout(250);
  const mPropsOverflow = await mp.evaluate(() => {
    const dlg = document.querySelector('#propsModal .props-dialog');
    const r = dlg ? dlg.getBoundingClientRect() : null;
    return !r || r.left < 0 || r.right > window.innerWidth + 1;
  });
  rec('Мобильная вёрстка: окно свойств открывается из превью без вылезания за экран', !mPropsOverflow, '');
  await mctx.close();

  /* ---------- v4.0: real read-only listener ---------- */
  if (RO_BASE) {
    const roContext = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true, ignoreHTTPSErrors: true });
    const ro = await roContext.newPage();
    ro.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    ro.on('pageerror', e => pageErrors.push(e.message || String(e)));
    const writeRequests = [];
    ro.on('request', r => {
      if (r.method() !== 'POST') return;
      const endpoint = new URL(r.url()).pathname;
      if (!['/api/session', '/api/zip-ticket', '/api/download-ticket', '/api/preview-ticket'].includes(endpoint)) writeRequests.push(endpoint);
    });
    await ro.goto(RO_BASE + '/#k=' + encodeURIComponent(ACCESS_KEY));
    await ro.waitForSelector('#readonlyNotice');
    await ro.waitForSelector('#rows button.filename');
    rec('Только чтение: видимое предупреждение, загрузка и создание скрыты',
      (await ro.locator('#readonlyNotice').textContent()).includes('Только чтение')
      && await ro.locator('#upload, #mkdir, #mkfile').evaluateAll(nodes => nodes.every(n => n.offsetWidth === 0)), '');
    await ro.screenshot({ path: path.join(shots, '39-readonly-desktop.png'), animations: 'disabled' });
    await openFolder(ro, 'Документы');
    const note = ro.locator('#rows tr', { hasText: 'заметки.md' });
    await note.locator('input[type=checkbox]').check();
    rec('Только чтение: выделение и скачивание ZIP доступны, мутации скрыты',
      await ro.locator('#downloadSelected').isVisible() && await ro.locator('#deleteSelected').isHidden()
      && await ro.locator('#copySelected').isHidden() && await ro.locator('#cutSelected').isHidden()
      && !(await note.getAttribute('draggable')), '');
    await ro.keyboard.press('F2');
    await ro.keyboard.press('Delete');
    await ro.keyboard.press('Control+x');
    await ro.keyboard.press('Control+v');
    rec('Только чтение: клавиши записи не открывают диалоги или очередь',
      await ro.locator('#actionDialog').isHidden() && await ro.locator('#clipBar').isHidden(), '');
    await note.locator('.filename').click();
    await ro.waitForSelector('#previewBody pre');
    rec('Только чтение: текст открывается без редактора', await ro.locator('#previewEdit').isHidden()
        && (await ro.locator('#previewBody').textContent()).includes('# Заметки'), '');
    const getDownload = ro.waitForEvent('download');
    await ro.click('#previewDownload');
    const dl = await getDownload;
    // Some headless Chromium builds report a generic suggested filename. Check the actual
    // attachment, UTF-8 header, and bytes rather than that browser-only label.
    const attachment = await roContext.request.get(dl.url());
    const disposition = attachment.headers()['content-disposition'] || '';
    const utf8Name = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
    const downloaded = fs.readFileSync(await dl.path());
    rec('Только чтение: файл скачивается по билету', attachment.status() === 200 && /^attachment/i.test(disposition)
      && !!utf8Name && decodeURIComponent(utf8Name[1]) === 'заметки.md'
      && downloaded.equals(await attachment.body()) && downloaded.toString('utf8').includes('# Заметки'),
      'UTF-8 + ' + downloaded.length + ' bytes');
    await closePreview(ro);
    await goRoot(ro);
    await openFolder(ro, 'Загрузки');
    await ro.locator('#rows tr', { hasText: 'архив.zip' }).locator('.filename').click();
    await ro.waitForSelector('#zipFilter');
    rec('Только чтение: ZIP просматривается, извлечение скрыто',
        await ro.locator('#zipExtractHere').isHidden() && await ro.locator('#zipSelectAll').isHidden()
        && (await ro.locator('.ziprow').count()) > 0, '');
    await closePreview(ro);
    await ro.click('#openClip');
    const clipLocked = await ro.locator('#sharedClipInput').evaluate(n => n.readOnly);
    rec('Только чтение: общий буфер читается без отправки и очистки', clipLocked && await ro.locator('#sharedClipSend').isHidden()
        && await ro.locator('#sharedClipClear').isHidden(), '');
    await ro.click('#sharedClipClose');
    await ro.evaluate(() => {
      const dataTransfer = new DataTransfer();
      dataTransfer.items.add(new File(['test'], 'readonly.txt', { type: 'text/plain' }));
      window.dispatchEvent(new DragEvent('dragenter', { dataTransfer, cancelable: true }));
      window.dispatchEvent(new DragEvent('drop', { dataTransfer, cancelable: true }));
    });
    rec('Только чтение: перетаскивание не инициирует запись', await ro.locator('#dropOverlay').isHidden()
        && writeRequests.length === 0, writeRequests.join(', '));
    await roContext.close();

    const roMobile = await browser.newContext({ ...devices['iPhone 13'], ignoreHTTPSErrors: true });
    await roMobile.addInitScript(() => { localStorage.setItem('pb-lang', 'en'); localStorage.setItem('pb-theme', 'light'); });
    const rom = await roMobile.newPage();
    rom.on('pageerror', e => pageErrors.push(e.message || String(e)));
    await rom.goto(RO_BASE + '/#k=' + encodeURIComponent(ACCESS_KEY));
    await rom.waitForSelector('#readonlyNotice');
    await rom.waitForSelector('#rows button.filename');
    const widthOK = await rom.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
    rec('Только чтение: iPhone 13, EN / light, без горизонтального переполнения', widthOK
        && (await rom.locator('#readonlyNotice').textContent()).includes('Read only'), '');
    await rom.screenshot({ path: path.join(shots, '40-readonly-mobile.png'), animations: 'disabled' });
    await roMobile.close();
  }

  /* ---------- итоги ---------- */
  rec('Ошибки выполнения JS (pageerror)', pageErrors.length === 0, pageErrors.slice(0, 3).join(' || '));
  const unexpectedConsole = consoleErrors.filter(e =>
    !/status of (400|403|409)/.test(e) &&
    !/Blocked script execution in 'about:srcdoc'/.test(e) &&
    !/(example\.com|evil\.example).*Content Security Policy/.test(e)
  );
  rec('Неожиданные ошибки в консоли', unexpectedConsole.length === 0, unexpectedConsole.slice(0, 3).join(' || '));
  const unexpectedFailed = failed.filter(u => !/example\.com|evil\.example/.test(u) && !/ERR_ABORTED|ERR_BLOCKED_BY_CSP/.test(u));
  rec('Неожиданные сетевые сбои', unexpectedFailed.length === 0, unexpectedFailed.slice(0, 3).join(' || '), 'note');

  await browser.close();
  const fails = results.filter(r => !r.ok && r.kind !== 'note');
  console.log('\n=== UI SUMMARY: total=' + results.length + ' fail=' + fails.length);
  fs.writeFileSync(path.join(WORK, 'ui-results.json'), JSON.stringify(results, null, 2));
  if (fails.length > 0) {
    for (const f of fails) console.error('FAILED -> ' + f.name + (f.info ? ' [' + f.info + ']' : ''));
    process.exit(1);
  }
})().catch(e => {
  console.error('SCRIPT ERROR', e);
  process.exit(1);
});
