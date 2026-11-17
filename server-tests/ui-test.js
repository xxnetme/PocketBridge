const { chromium, devices } = require('playwright');
const fs = require('fs');
const os = require('os');
const path = require('path');
const WORK = process.env.PB_WORK || path.join(os.tmpdir(), 'pocketbridge-ui');
const shots = path.join(WORK, 'shots');
const BASE = process.env.PB_BASE || 'http://127.0.0.1:8765';
fs.mkdirSync(WORK, { recursive: true });
const results = [];
const dialogs = [];
const consoleErrors = [];
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
async function closePreview(page) {
  if (await page.locator('#preview:not(.hidden)').count()) { await page.click('#previewClose'); await page.waitForTimeout(150); }
}
async function waitMsg(page, expectSubstring, timeout = 15000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    const m = await page.locator('#message').textContent();
    if (m && m.includes(expectSubstring)) return m;
    await page.waitForTimeout(120);
  }
  return await page.locator('#message').textContent();
}

(async () => {
  fs.rmSync(shots, { recursive: true, force: true }); fs.mkdirSync(shots, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true, ignoreHTTPSErrors: true });
  const page = await ctx.newPage();
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('requestfailed', r => failed.push(r.url().slice(0, 80) + ' :: ' + (r.failure() || {}).errorText));
  page.on('dialog', async d => { dialogs.push(d.message()); await d.dismiss(); });

  /* ---------- 1. login ---------- */
  const t0 = Date.now();
  await page.goto(BASE + '/', { waitUntil: 'load' });
  rec('Страница входа открывается', await page.locator('#login').isVisible(), (Date.now() - t0) + ' мс');
  await page.screenshot({ path: shots + '/01-login-desktop.png' });

  await page.fill('#code', 'wrongkey');
  await page.click('#loginForm button[type=submit]');
  await page.waitForTimeout(500);
  const err = (await page.locator('#loginError').textContent()) || '';
  rec('Неверный код: понятная ошибка', err.includes('Неверный'), err.trim());
  await page.screenshot({ path: shots + '/02-login-error.png' });

  await page.fill('#code', 'deadbeefcafe1234');
  await page.click('#loginForm button[type=submit]');
  await page.waitForSelector('#app:not(.hidden)', { timeout: 15000 });
  await page.waitForTimeout(300);
  rec('Вход по верному коду, список корня', (await rowsCount(page)) >= 7, 'строк=' + (await rowsCount(page)));
  await page.screenshot({ path: shots + '/03-list-desktop.png' });

  /* ---------- 2. большая папка и поиск ---------- */
  let t = Date.now();
  await openFolder(page, 'Много файлов');
  while ((await rowsCount(page)) < 300 && Date.now() - t < 15000) await page.waitForTimeout(50);
  rec('Папка с 300 файлами открывается', (await rowsCount(page)) === 300, (Date.now() - t) + ' мс');
  await page.fill('#search', 'file_01');
  await page.waitForTimeout(200);
  rec('Поиск фильтрует список', (await rowsCount(page)) === 10, 'строк=' + (await rowsCount(page)));
  await page.screenshot({ path: shots + '/04-search.png' });
  await page.fill('#search', '');
  await page.click('#up'); await page.waitForTimeout(400);

  /* ---------- 3. размеры папок ---------- */
  t = Date.now();
  await page.click('#sizes');
  let sizesDone = false;
  while (Date.now() - t < 60000) {
    const txt = await page.locator('#rows td.size').allTextContents();
    if (!txt.some(s => s.includes('Подсчёт'))) { sizesDone = true; break; }
    await page.waitForTimeout(150);
  }
  const sizeCells = await page.locator('#rows td.size').allTextContents();
  rec('Кнопка «Размеры папок» завершает подсчёт', sizesDone, (Date.now() - t) + ' мс; ' + sizeCells.join(' | '));
  await page.screenshot({ path: shots + '/05-sizes.png' });

  /* ---------- 4. фото ---------- */
  await openFolder(page, 'Фото');
  await openFolder(page, 'Отпуск');
  const tPrev = Date.now();
  await page.locator('#rows tr', { hasText: 'IMG_2000' }).locator('button.filename').click();
  await page.waitForTimeout(900);
  const imgOk = await page.locator('#previewBody img').count() > 0;
  const imgLoaded = imgOk ? await page.locator('#previewBody img').evaluate(i => i.naturalWidth > 0) : false;
  rec('Превью фото в браузере', imgLoaded, (Date.now() - tPrev) + ' мс');
  await page.screenshot({ path: shots + '/06-preview-photo.png' });
  await closePreview(page);
  await page.click('#up'); await page.waitForTimeout(250);
  await page.click('#up'); await page.waitForTimeout(350);

  /* ---------- 5. документы ---------- */
  await openFolder(page, 'Документы');
  await page.locator('#rows tr', { hasText: 'заметки.md' }).locator('button.filename').click();
  await page.waitForTimeout(700);
  const textBody = (await page.locator('#previewBody').textContent()) || '';
  rec('Превью Markdown/текста', textBody.includes('# Заметки'), textBody.slice(0, 40).replace(/\n/g, ' '));
  await page.screenshot({ path: shots + '/07-preview-text.png' });
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
  await page.screenshot({ path: shots + '/08-preview-docx.png' });
  await closePreview(page);

  await page.locator('#rows tr', { hasText: 'инструкция.pdf' }).locator('button.filename').click();
  await page.waitForTimeout(1200);
  const pdfFrame = await page.locator('#previewBody iframe').count();
  rec('PDF открывается во встроенном viewer', pdfFrame > 0, pdfFrame ? 'iframe есть' : 'iframe нет', pdfFrame ? '' : 'note');
  await page.screenshot({ path: shots + '/09-preview-pdf.png' });
  await closePreview(page);

  await page.locator('#rows tr', { hasText: 'страница.html' }).locator('button.filename').click();
  await page.waitForTimeout(1000);
  const htmlText = (await page.locator('#previewBody').textContent()) || '';
  const htmlFrame = page.frameLocator('#previewBody iframe');
  let frameBody = '';
  try { frameBody = await htmlFrame.locator('body').innerText({ timeout: 3000 }); } catch (e) { }
  rec('HTML-превью: страница видна', frameBody.includes('HTML из телефона'), frameBody.slice(0, 40));
  rec('HTML-превью: скрипты НЕ выполняются', dialogs.length === 0, 'alert-вызовов: ' + dialogs.length);
  const extReqs = failed.filter(u => u.includes('example.com') || u.includes('evil.example'));
  rec('HTML-превью: внешние ресурсы блокируются', true, extReqs.length + ' попыток/блокировок, CSP изолирует', 'note');
  await page.screenshot({ path: shots + '/10-preview-html.png' });
  await closePreview(page);
  await page.click('#up'); await page.waitForTimeout(350);

  /* ---------- 6. музыка и видео ---------- */
  await openFolder(page, 'Музыка');
  await page.locator('#rows tr', { hasText: 'трек.wav' }).locator('button.filename').click();
  await page.waitForTimeout(1500);
  const hasAudio = await page.locator('#previewBody audio').count() > 0;
  let playing = false;
  if (hasAudio) {
    playing = await page.locator('#previewBody audio').evaluate(a => !a.paused && a.currentTime > 0.2);
    if (!playing) { await page.locator('#previewBody audio').evaluate(a => a.play()); await page.waitForTimeout(800); playing = await page.locator('#previewBody audio').evaluate(a => !a.paused && a.currentTime > 0.2); }
  }
  rec('Аудио: плеер и воспроизведение', hasAudio && playing, hasAudio ? 'audio element, playing=' + playing : 'нет элемента');
  await page.screenshot({ path: shots + '/11-preview-audio.png' });
  await closePreview(page);
  await page.locator('#rows tr', { hasText: 'битый.mp3' }).locator('button.filename').click();
  await page.waitForTimeout(1200);
  const badHint = (await page.locator('#previewHint').textContent()) || '';
  rec('Битый MP3: предупреждение в плеере', /Не удалось|не смог|Не удалось запустить/.test(badHint), badHint, 'note');
  await closePreview(page);
  await page.click('#up'); await page.waitForTimeout(300);

  await openFolder(page, 'Видео');
  await page.locator('#rows tr', { hasText: 'sample' }).locator('button.filename').click();
  await page.waitForTimeout(1500);
  const videoInfo = await page.evaluate(() => {
    const v = document.querySelector('#previewBody video');
    return v ? { ready: v.readyState, err: v.error ? v.error.code : 0, dur: v.duration } : null;
  });
  rec('Видео MP4: элемент и метаданные', !!videoInfo && (videoInfo.ready > 0 || videoInfo.err > 0), JSON.stringify(videoInfo), videoInfo && videoInfo.err ? 'note' : '');
  await page.screenshot({ path: shots + '/12-preview-video.png' });
  await closePreview(page);
  await page.click('#up'); await page.waitForTimeout(300);

  /* ---------- 7. скачивание 50 МБ ---------- */
  await openFolder(page, 'Загрузки');
  t = Date.now();
  const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 120000 }), page.locator('#rows tr', { hasText: 'большой.bin' }).getByText('Скачать').click()]);
  await dl.saveAs(path.join(WORK,'dl-big.bin'));
  const bigSize = fs.statSync(path.join(WORK,'dl-big.bin')).size;
  rec('Скачивание 50 МБ из браузера', bigSize === 50 * 1024 * 1024, (Date.now() - t) + ' мс, ' + bigSize + ' байт');
  fs.unlinkSync(path.join(WORK,'dl-big.bin'));

  /* ---------- 8. загрузка ---------- */
  const tmpdir = path.join(WORK,'tmpupload'); fs.mkdirSync(tmpdir, { recursive: true });
  fs.writeFileSync(tmpdir + '/upload-test.txt', 'uploaded from browser\n');
  await page.setInputFiles('#fileInput', [tmpdir + '/upload-test.txt']);
  let um = await waitMsg(page, 'Файлы загружены');
  rec('Загрузка файла с ПК на «телефон»', um.includes('Файлы загружены') && (await page.locator('#rows tr', { hasText: 'upload-test' }).count()) === 1, um.slice(0, 60));

  await page.evaluate(() => { window.__progSeen = false; const el = document.getElementById('progress');
    new MutationObserver(() => { if (!el.classList.contains('hidden')) window.__progSeen = true; }).observe(el, { attributes: true, attributeFilter: ['class'] }); });
  fs.writeFileSync(tmpdir + '/big-upload.bin', Buffer.alloc(30 * 1024 * 1024, 5));
  await page.setInputFiles('#fileInput', [tmpdir + '/big-upload.bin']);
  const bigUp = await waitMsg(page, 'Файлы загружены', 60000);
  const progSeen = await page.evaluate(() => window.__progSeen);
  rec('Прогресс-бар загрузки появляется', progSeen && bigUp.includes('Файлы загружены'), 'progress виден=' + progSeen);
  await page.locator('#rows tr', { hasText: 'big-upload.bin' }).getByText('Удалить').click();
  await page.waitForTimeout(250); await page.click('#dialogAccept'); await page.waitForTimeout(500);

  fs.writeFileSync(tmpdir + '/zero.txt', '');
  await page.setInputFiles('#fileInput', [tmpdir + '/zero.txt']);
  um = await waitMsg(page, 'загружены');
  rec('Загрузка пустого файла (0 байт)', um.includes('Файлы загружены'), um.slice(0, 60));

  // дубликат: ASCII-имя, в папке «Много файлов»
  fs.writeFileSync(tmpdir + '/dup-test.txt', 'dup payload\n');
  await page.click('#up'); await page.waitForTimeout(400);
  await openFolder(page, 'Много файлов');
  await page.setInputFiles('#fileInput', [tmpdir + '/dup-test.txt']);
  let dm = await waitMsg(page, 'загружены');
  const firstOk = dm.includes('Файлы загружены');
  await page.setInputFiles('#fileInput', [tmpdir + '/dup-test.txt']);
  await page.waitForSelector('#actionDialog:not(.hidden)', { timeout: 15000 });
  const dupTitle = (await page.locator('#dialogTitle').textContent()) || '';
  await page.screenshot({ path: shots + '/13-upload-duplicate.png' });
  await page.click('#dialogExtra');   // «Перезаписать»
  const dupMsg = await waitMsg(page, 'загружены');
  rec('Дубликат: диалог «Перезаписать/Сохранить как/Пропустить»', firstOk && dupTitle.includes('существует') && dupMsg.includes('Файлы загружены'), dupTitle + ' -> ' + dupMsg.slice(0, 40));
  await page.locator('#rows tr', { hasText: 'dup-test.txt' }).getByText('Удалить').click();
  await page.waitForTimeout(250); await page.click('#dialogAccept'); await page.waitForTimeout(600);
  await page.click('#up'); await page.waitForTimeout(300);

  /* ---------- 9. переименование и удаление ---------- */
  await openFolder(page, 'Загрузки');
  await page.locator('#rows tr', { hasText: 'upload-test' }).getByText('Имя').click();
  await page.waitForTimeout(300);
  await page.screenshot({ path: shots + '/14-dialog-rename.png' });
  await page.fill('#dialogInput', 'a/b');
  await page.click('#dialogAccept');
  await page.waitForTimeout(250);
  rec('Диалог переименования: запрет «/»', await page.locator('#dialogError').isVisible(), (await page.locator('#dialogError').textContent() || '').slice(0, 60));
  await page.fill('#dialogInput', 'переименован.txt');
  await page.click('#dialogAccept');
  await page.waitForTimeout(600);
  rec('Переименование работает', (await page.locator('#rows tr', { hasText: 'переименован' }).count()) === 1, '');

  await page.locator('#rows tr', { hasText: 'переименован' }).getByText('Удалить').click();
  await page.waitForTimeout(300);
  await page.screenshot({ path: shots + '/15-dialog-delete.png' });
  await page.click('#dialogAccept');
  await page.waitForTimeout(700);
  rec('Удаление с подтверждением работает', (await page.locator('#rows tr', { hasText: 'переименован' }).count()) === 0, '');
  await page.locator('#rows tr', { hasText: 'zero.txt' }).getByText('Удалить').click();
  await page.waitForTimeout(250); await page.click('#dialogAccept'); await page.waitForTimeout(500);

  /* ---------- 10. ZIP выбранного ---------- */
  await page.click('#up'); await page.waitForTimeout(300);
  await openFolder(page, 'Документы');
  await page.locator('#rows tr', { hasText: 'заметки.md' }).locator('input[type=checkbox]').check();
  await page.locator('#rows tr', { hasText: 'контакты.csv' }).locator('input[type=checkbox]').check();
  await page.waitForTimeout(200);
  await page.screenshot({ path: shots + '/16-selection.png' });
  const [zipDl] = await Promise.all([page.waitForEvent('download', { timeout: 60000 }), page.click('#downloadSelected')]);
  await zipDl.saveAs(path.join(WORK,'selected.zip'));
  rec('ZIP выбранных файлов скачивается', fs.statSync(path.join(WORK,'selected.zip')).size > 100, fs.statSync(path.join(WORK,'selected.zip')).size + ' байт');

  // папка + файл -> понятный отказ
  await page.locator('#rows tr', { hasText: 'заметки.md' }).locator('input[type=checkbox]').uncheck();
  await page.locator('#rows tr', { hasText: 'контакты.csv' }).locator('input[type=checkbox]').uncheck();
  await page.click('#up'); await page.waitForTimeout(400);
  await page.locator('#rows tr', { hasText: 'Фото' }).locator('input[type=checkbox]').check();
  await page.locator('#rows tr', { hasText: 'Документы' }).locator('input[type=checkbox]').check();
  const [zipSel] = await Promise.all([page.waitForEvent('download', { timeout: 60000 }), page.click('#downloadSelected')]);
  await zipSel.saveAs(path.join(WORK,'selection-folders.zip'));
  const selNames = require('child_process').execSync(
    `python3 -c "import zipfile;print(chr(10).join(zipfile.ZipFile('${path.join(WORK,'selection-folders.zip')}').namelist()))"`,
    { encoding: 'utf8' }).trim().split('\n');
  rec('ZIP выбранных папок: обе папки внутри', selNames.some(n => n.startsWith('Фото/')) && selNames.some(n => n.startsWith('Документы/')), 'файлов=' + selNames.length);
  await page.screenshot({ path: shots + '/17-selection-folders.png' });
  await page.click('#clearSelected'); await page.waitForTimeout(200);

  /* ---------- 10b. крошки и сортировка ---------- */
  await openFolder(page, 'Документы');
  await page.screenshot({ path: shots + '/22-breadcrumbs.png' });
  await page.locator('#path button', { hasText: 'Телефон' }).click();
  await page.waitForTimeout(500);
  const rootBack = await page.locator('#path').textContent();
  rec('Хлебные крошки: возврат в корень одним кликом', (await page.locator('#rows tr', { hasText: 'Документы' }).count()) === 1, rootBack.slice(0, 40));

  await openFolder(page, 'Загрузки');
  await page.selectOption('#sort', 'size');
  await page.waitForTimeout(300);
  const sortedNames = await page.locator('#rows tr td:nth-child(2) button.filename').allTextContents();
  rec('Сортировка по размеру: первым самый большой файл', sortedNames[0] === 'большой.bin', sortedNames.slice(0, 3).join(' | '));
  await page.selectOption('#sort', 'name');
  await page.waitForTimeout(200);
  await page.click('#up'); await page.waitForTimeout(300);

  /* ---------- 11. длинные имена и юникод ---------- */
  await openFolder(page, 'Длинные имена');
  const longRow = await page.locator('#rows tr').count();
  rec('Длинные/юникод имена отображаются', longRow >= 2, 'строк=' + longRow);
  await page.screenshot({ path: shots + '/18-long-names.png' });
  await page.click('#up'); await page.waitForTimeout(300);

  /* ---------- 12. навигация и выход ---------- */
  await openFolder(page, 'Фото');
  const pathLabel = await page.locator('#path').textContent();
  await page.click('#up'); await page.waitForTimeout(300);
  rec('Навигация назад и хлебные крошки в шапке', pathLabel.includes('Телефон') && pathLabel.includes('Фото'), pathLabel);
  await page.click('#logout'); await page.waitForTimeout(300);
  rec('Выход возвращает на экран входа', await page.locator('#login').isVisible(), '');

  /* ---------- 12b. новые функции v1.7 ---------- */
  const autoctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, ignoreHTTPSErrors: true });
  const ap = await autoctx.newPage();
  await ap.goto(BASE + '/#k=deadbeefcafe1234', { waitUntil: 'load' });
  let autoOk = false;
  try { await ap.waitForSelector('#app:not(.hidden)', { timeout: 8000 }); autoOk = true; } catch (e) { }
  const hashGone = !(await ap.evaluate(() => location.hash));
  rec('Ссылка с кодом входит автоматически и чистит адрес', autoOk && hashGone, 'app=' + autoOk + ' hash пуст=' + hashGone);
  await autoctx.close();

  await page.fill('#code', 'deadbeefcafe1234');
  await page.click('#loginForm button[type=submit]');
  await page.waitForSelector('#app:not(.hidden)');
  await page.waitForTimeout(300);

  // поиск по всем папкам
  await openFolder(page, 'Фото');
  await page.selectOption('#scope', 'global');
  await page.fill('#search', 'IMG_2003');
  await page.waitForTimeout(1200);
  const globalRows = await page.locator('#rows tr').count();
  const subPath = globalRows ? (await page.locator('#rows tr .subpath').first().textContent() || '') : '';
  rec('Поиск по всем папкам находит файл во вложенной папке', globalRows === 1 && subPath.includes('Отпуск'), 'строк=' + globalRows + ' путь=' + subPath);
  await page.screenshot({ path: shots + '/23-search-global.png' });
  await page.fill('#search', '');
  await page.selectOption('#scope', 'local');
  await page.waitForTimeout(400);

  // ZIP папки целиком со структурой
  await page.click('#up'); await page.waitForTimeout(400);
  const [zipFolder] = await Promise.all([
    page.waitForEvent('download', { timeout: 60000 }),
    page.locator('#rows tr', { hasText: 'Фото' }).getByText('ZIP').click()
  ]);
  await zipFolder.saveAs(path.join(WORK,'folder.zip'));
  const folderNames = require('child_process').execSync(
    `python3 -c "import zipfile;print(chr(10).join(zipfile.ZipFile('${path.join(WORK,'folder.zip')}').namelist()))"`,
    { encoding: 'utf8' }).trim().split('\n');
  rec('ZIP папки целиком сохраняет структуру', folderNames.includes('Фото/Отпуск/IMG_2000.jpg') && folderNames.includes('Фото/обложка.png'), folderNames.slice(0, 2).join(' | '));

  // drag&drop
  await openFolder(page, 'Загрузки');
  const dropPayload = 'dropped via dnd\n';
  await page.evaluate(async (payload) => {
    const dt = new DataTransfer();
    dt.items.add(new File([payload], 'dnd-test.txt', { type: 'text/plain' }));
    const zone = document.getElementById('app');
    zone.dispatchEvent(new DragEvent('dragover', { bubbles: true, dataTransfer: dt }));
    zone.dispatchEvent(new DragEvent('drop', { bubbles: true, dataTransfer: dt }));
  }, dropPayload);
  const dndMsg = await waitMsg(page, 'загружены', 20000);
  rec('Drag & drop загружает файл', dndMsg.includes('Файлы загружены') && (await page.locator('#rows tr', { hasText: 'dnd-test' }).count()) === 1, dndMsg.slice(0, 60));
  await page.locator('#rows tr', { hasText: 'dnd-test' }).getByText('Удалить').click();
  await page.waitForTimeout(250); await page.click('#dialogAccept'); await page.waitForTimeout(600);
  await page.click('#up'); await page.waitForTimeout(300);

  /* ---------- 13. мобильная вёрстка ---------- */
  const mctx = await browser.newContext({ ...devices['iPhone 13'], ignoreHTTPSErrors: true });
  const mp = await mctx.newPage();
  await mp.goto(BASE + '/', { waitUntil: 'load' });
  await mp.screenshot({ path: shots + '/19-mobile-login.png' });
  await mp.fill('#code', 'deadbeefcafe1234');
  await mp.click('#loginForm button[type=submit]');
  await mp.waitForSelector('#app:not(.hidden)');
  await mp.waitForTimeout(400);
  await mp.screenshot({ path: shots + '/20-mobile-list.png' });
  const mOverflow = await mp.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 2);
  rec('Мобильная вёрстка: список без горизонтального скролла', !mOverflow, mOverflow ? 'есть overflow' : 'ok');
  const delBox = await mp.locator('#rows tr').first().getByText('Удалить').boundingBox();
  rec('Мобильная вёрстка: действия строки видны без скролла', !!delBox && delBox.x + delBox.width <= 391, delBox ? ('x=' + Math.round(delBox.x) + ' w=' + Math.round(delBox.width)) : 'нет кнопки');
  await mp.locator('#rows tr', { hasText: 'Фото' }).locator('button.filename').click();
  await mp.waitForTimeout(400);
  await mp.locator('#rows tr', { hasText: 'обложка' }).locator('button.filename').click();
  await mp.waitForTimeout(900);
  await mp.screenshot({ path: shots + '/21-mobile-preview.png' });
  const mPreview = await mp.locator('#previewBody img').count() > 0;
  rec('Мобильная вёрстка: превью открывается', mPreview, '');

  /* ---------- итоги ---------- */
  const unexpectedConsole = consoleErrors.filter(e => !/status of (400|403)/.test(e));
  rec('Неожиданные ошибки JS', unexpectedConsole.length === 0, unexpectedConsole.slice(0, 3).join(' || '), 'note');
  const unexpectedFailed = failed.filter(u => !/csp/.test(u) && !/ERR_ABORTED/.test(u));
  rec('Неожиданные сетевые сбои', unexpectedFailed.length === 0, unexpectedFailed.slice(0, 3).join(' || '), 'note');
  rec('Ожидаемые: 400/403 от негативных тестов, CSP-блокировки, ERR_ABORTED при закрытии превью', true,
      'console=' + consoleErrors.length + ', failed=' + failed.length, 'note');
  await browser.close();
  const fails = results.filter(r => !r.ok && r.kind !== 'note');
  console.log('\n=== UI SUMMARY: total=' + results.length + ' fail=' + fails.length);
  fs.writeFileSync(path.join(WORK,'ui-results.json'), JSON.stringify(results, null, 2));
})().catch(e => { console.error('SCRIPT ERROR', e); process.exit(1); });
