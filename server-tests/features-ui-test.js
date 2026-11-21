// Standalone HTTPS browser checks for the selected 4.x features. No cloud/PC service involved.
const { chromium, devices } = require('playwright');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');

const base = process.env.PB_FEATURES_WORK || os.tmpdir();
fs.mkdirSync(base, { recursive: true });
const work = fs.mkdtempSync(path.join(base, 'pb-features-'));
const root = path.join(work, 'root');
const events = [];
let child, browser, serverUrl;
const oldKey = '01aa23bb45cc67dd', newKey = '42bb31cc526ca546';
const port = Number(process.env.PB_FEATURES_PORT || 18771);
const report = (name, info) => console.log('PASS ' + name + (info ? ' · ' + info : ''));

async function start(key) {
  const cwd = path.resolve(__dirname, '..');
  const run = spawn('bash', ['server-tests/run.sh', '--serve', root, key, 'tls'], {
    cwd, detached: true,
    env: { ...process.env, PB_SERVE_PORT: String(port), PB_FILES_DIR: path.join(work, 'private') },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  child = run;
  await new Promise((resolve, reject) => {
    let stdout = '', stderr = '';
    const timeout = setTimeout(() => reject(new Error('Server start timeout: ' + stdout + stderr)), 60_000);
    run.stderr.on('data', data => stderr += data);
    run.stdout.on('data', data => {
      stdout += data;
      if (!/^READY$/m.test(stdout)) return;
      const match = /^PORT=(\d+)/m.exec(stdout);
      if (!match || Number(match[1]) !== port) { clearTimeout(timeout); reject(new Error('The test port is occupied')); return; }
      serverUrl = `https://127.0.0.1:${match[1]}`;
      clearTimeout(timeout); resolve();
    });
    run.once('error', e => { clearTimeout(timeout); reject(e); });
    run.once('exit', code => { clearTimeout(timeout); reject(new Error(`Server exited ${code}\n${stdout}\n${stderr}`)); });
  });
}
async function stop() {
  const run = child; child = null;
  if (!run || run.exitCode !== null || run.signalCode !== null) return;
  await new Promise(resolve => {
    run.once('exit', resolve);
    try { process.kill(-run.pid, 'SIGTERM'); } catch (_) { resolve(); }
  });
}
async function login(context, key) {
  const page = await context.newPage();
  page.setDefaultTimeout(12_000);
  page.on('pageerror', e => events.push(e.message));
  await page.goto(`${serverUrl}/#k=${key}`);
  await page.locator('#app:not(.hidden) .bar').waitFor();
  await page.locator('#rows .filename').first().waitFor();
  return page;
}
async function folder(page, name) {
  await page.locator('#rows button.filename').filter({ hasText: new RegExp('^' + name + '$') }).click();
  await page.locator('#path').getByRole('button', { name, exact: true }).waitFor();
}
const tools = page => page.locator('#workbench');
const button = (page, name) => tools(page).getByRole('button', { name, exact: true });
async function waitCount(page, expected) {
  await page.waitForFunction(n => document.querySelectorAll('#rows tr').length === n, expected);
}

async function uploadAndRestart(context, page) {
  const data = crypto.randomBytes(9 * 1024 * 1024 + 7), name = 'restart.bin';
  let tell;
  const committed = new Promise(resolve => { tell = resolve; });
  let first = true;
  await page.route('**/api/upload-chunk?**', async route => {
    if (!first) { await route.abort('internetdisconnected'); return; }
    first = false;
    const res = await route.fetch();
    const rd = await res.json();
    await route.abort('internetdisconnected');  // phone committed; browser never gets the response
    tell(rd);
  });
  await page.locator('#fileInput').setInputFiles({ name, mimeType: 'application/octet-stream', buffer: data });
  const watchdog = setTimeout(() => tell(null), 16_000);
  const rd = await committed;
  clearTimeout(watchdog);
  assert.ok(rd, 'The first chunk did not reach the server: ' + await page.locator('#queueList').innerText());
  assert.equal(rd.received, 4 * 1024 * 1024);
  await context.setOffline(true);
  const record = await page.evaluate(async () => (await Drafts.all())[0]);
  assert.equal(record.id, rd.uploadId);
  assert.equal(record.sha256, crypto.createHash('sha256').update(data).digest('hex'));
  assert.ok(!Object.keys(record).some(k => /^(key|file|blob|token|auth)$/i.test(k)));
  assert.ok(!fs.existsSync(path.join(root, name)));
  await page.close();
  await stop();
  await start(newKey);
  await context.setOffline(false);
  const stale = await context.request.get(`${serverUrl}/api/upload-status?id=${rd.uploadId}`, { headers: { 'X-Access-Key': oldKey } });
  assert.equal(stale.status(), 403);
  page = await login(context, newKey);
  await page.locator('#queueList').getByText('Source file needed', { exact: true }).waitFor();
  assert.match(await page.locator('.qinfo').first().innerText(), /4\.0 MB/);
  report('Lost acknowledgement + offline, tab closed, server restarted with new code', `${rd.received} confirmed bytes`);
  const pick = async buffer => {
    const chooser = page.waitForEvent('filechooser');
    await page.locator('#queueList').getByRole('button', { name: 'Select file', exact: true }).click();
    await (await chooser).setFiles({ name, mimeType: 'application/octet-stream', buffer });
  };
  const bad = Buffer.from(data); bad[12] ^= 1;
  await pick(bad);
  await page.locator('#queueList').getByText(/SHA-256 does not match/).waitFor();
  assert.ok(!fs.existsSync(path.join(root, name)));
  let firstOffset;
  page.on('request', req => {
    if (firstOffset === undefined && req.url().includes('/api/upload-chunk?')) firstOffset = Number(new URL(req.url()).searchParams.get('offset'));
  });
  await pick(data);
  await page.locator('#rows').getByRole('button', { name, exact: true }).waitFor({ timeout: 20_000 });
  assert.equal(firstOffset, rd.received);
  assert.ok(data.equals(fs.readFileSync(path.join(root, name))));
  report('Same name and size with different bytes rejected; original resumes from saved offset');
  return page;
}

const CHUNK = 4 * 1024 * 1024;
const chunkRoute = '**/api/upload-chunk?**';
const queueRow = (page, name) => page.locator('#queueList .qrow').filter({ has: page.getByText(name, { exact: true }) });
const params = url => new URL(url).searchParams;
const uploadFile = (page, name, buffer) => page.locator('#fileInput').setInputFiles({ name, mimeType: 'application/octet-stream', buffer });
function draftStatus(context, id) {
  return context.request.get(`${serverUrl}/api/upload-status?id=${id}`, { headers: { 'X-Access-Key': newKey } });
}

async function discardViaButton(page, row, id, control) {
  const [response] = await Promise.all([
    page.waitForResponse(r => {
      const url = new URL(r.url());
      return r.request().method() === 'POST' && url.pathname === '/api/upload-abort' && url.searchParams.get('id') === id;
    }),
    control.click()
  ]);
  assert.equal(response.status(), 200, 'The phone should acknowledge the discard');
  assert.equal((await response.json()).ok, true);
  // This label is rendered after the browser's IndexedDB deletion commits.
  // waitForFunction treats a returned Promise as truthy, not as a polled result.
  await row.getByText('cancelled', { exact: true }).waitFor();
  assert.equal(await page.evaluate(id => Drafts.all().then(rs => rs.some(r => r.id === id)), id), false,
    'The acknowledged draft must also be removed from IndexedDB');
}

async function pauseAfterPart(context, page, name, data) {
  // Stop the second request before it reaches the live server; its XHR is aborted by Pause.
  let parked;
  const hold = route => {
    if (Number(params(route.request().url()).get('offset')) !== CHUNK) return route.continue();
    parked = route;
  };
  await page.route(chunkRoute, hold);
  const second = page.waitForRequest(req => req.url().includes('/api/upload-chunk?') && params(req.url()).get('offset') === String(CHUNK));
  await uploadFile(page, name, data);
  const id = params((await second).url()).get('id');
  const row = queueRow(page, name);
  await row.getByRole('button', { name: 'Pause', exact: true }).click();
  await row.getByRole('button', { name: 'Resume', exact: true }).waitFor();
  // The intercepted request may have already been cancelled by Chromium.
  if (parked) await parked.abort().catch(() => {});
  await page.unroute(chunkRoute, hold);
  const status = await draftStatus(context, id);
  assert.equal(status.status(), 200);
  assert.equal((await status.json()).received, CHUNK);
  assert.ok(!fs.existsSync(path.join(root, name)));
  return { id, row };
}

async function transferControls(context, page) {
  const bytes = crypto.randomBytes(CHUNK + 1067);
  let name = 'pause-retry.bin';
  const paused = await pauseAfterPart(context, page, name, bytes);
  const offline = route => route.abort('internetdisconnected');
  await page.route(chunkRoute, offline);
  await paused.row.getByRole('button', { name: 'Resume', exact: true }).click();
  const retry = paused.row.getByRole('button', { name: 'Retry', exact: true });
  await retry.waitFor({ timeout: 16_000 });
  await page.unroute(chunkRoute, offline);
  assert.equal((await (await draftStatus(context, paused.id)).json()).received, CHUNK);
  const resumed = page.waitForRequest(req => req.url().includes('/api/upload-chunk?'));
  await retry.click();
  const request = params((await resumed).url());
  assert.equal(request.get('id'), paused.id);
  assert.equal(request.get('offset'), String(CHUNK));
  await page.locator('#rows').getByRole('button', { name, exact: true }).waitFor();
  assert.ok(bytes.equals(fs.readFileSync(path.join(root, name))));
  report('Pause and exhausted network retries keep confirmed bytes; Retry continues the same draft');

  name = 'discard.bin';
  const discard = await pauseAfterPart(context, page, name, bytes);
  await page.route('**/api/upload-abort?**', offline, { times: 1 });
  await discard.row.getByRole('button', { name: 'Cancel', exact: true }).click();
  const again = discard.row.getByRole('button', { name: 'Retry discard', exact: true });
  await again.waitFor();
  assert.equal((await draftStatus(context, discard.id)).status(), 200);
  assert.equal(await page.evaluate(id => Drafts.all().then(rs => rs.find(r => r.id === id)?.cancelPending), discard.id), true);
  await discardViaButton(page, discard.row, discard.id, again);
  assert.equal((await draftStatus(context, discard.id)).status(), 404);
  assert.ok(!fs.existsSync(path.join(root, '.pocketbridge-uploads', discard.id + '.part')));
  assert.ok(!fs.existsSync(path.join(root, name)));
  report('Lost discard request gives a retry; retry removes the phone part and browser record');

  name = 'late-conflict.bin';
  const occupied = path.join(root, name), alternate = 'late-kept.bin', occupiedBytes = Buffer.from('new file on phone');
  const small = bytes.subarray(0, 5000);
  let finalId;
  await page.route('**/api/upload-finish?**', route => {
    finalId = params(route.request().url()).get('id');
    fs.writeFileSync(occupied, occupiedBytes, { flag: 'wx' });
    return route.continue();
  }, { times: 1 });
  await uploadFile(page, name, small);
  await page.getByRole('heading', { name: 'File already exists', exact: true }).waitFor();
  assert.equal((await (await draftStatus(context, finalId)).json()).received, small.length);
  const retarget = page.waitForRequest(req => req.url().includes('/api/upload-retarget?'));
  await page.fill('#dialogInput', alternate);
  await page.click('#dialogAccept');
  assert.equal(params((await retarget).url()).get('id'), finalId);
  await page.locator('#rows').getByRole('button', { name: alternate, exact: true }).waitFor();
  assert.ok(small.equals(fs.readFileSync(path.join(root, alternate))));
  assert.ok(occupiedBytes.equals(fs.readFileSync(occupied)));
  report('Collision at finalization keeps the occupied file and retargets the verified draft');

  name = 'missing-draft.bin';
  const missing = await pauseAfterPart(context, page, name, bytes);
  const abort = await context.request.post(`${serverUrl}/api/upload-abort?id=${missing.id}`, { headers: { 'X-Access-Key': newKey } });
  assert.equal(abort.status(), 200);
  const resent = [];
  const record = req => { if (/\/api\/upload-(init|chunk)\?/.test(req.url())) resent.push(req.url()); };
  context.on('request', record);
  await page.close();
  page = await login(context, newKey);
  const missingRow = queueRow(page, name);
  await missingRow.getByText(/Draft is missing or expired/).waitFor();
  assert.equal(await missingRow.getByRole('button', { name: /^(Retry|Select file)$/ }).count(), 0);
  await discardViaButton(page, missingRow, missing.id,
    missingRow.getByRole('button', { name: 'Remove from queue', exact: true }));
  context.off('request', record);
  assert.deepEqual(resent, []);
  assert.ok(!fs.existsSync(path.join(root, name)));
  report('A missing initialized draft is shown as missing after reload, never silently restarted');
  return page;
}

async function toolsTest(context, page) {
  await folder(page, 'Tools');
  await waitCount(page, 2);
  for (const cb of await page.locator('#rows input[type=checkbox]').all()) await cb.check();
  await page.click('#pbBatch');
  await page.fill('#renameTemplate', 'x{ext}');
  await page.click('#renamePreview');
  await tools(page).getByText('Повтор нового имени').first().waitFor();
  assert.equal(await page.locator('#renameApply').isVisible(), false);
  await page.fill('#renameTemplate', 'Photo_{n:3}{ext}');
  await page.click('#renamePreview');
  await tools(page).getByText('a.txt → Photo_001.txt', { exact: true }).waitFor();
  assert.ok(fs.existsSync(path.join(root, 'Tools/a.txt')) && !fs.existsSync(path.join(root, 'Tools/Photo_001.txt')));
  await page.click('#renameApply');
  await tools(page).getByText(/Renamed: 2.*Failed: 0/).waitFor();
  await page.click('#wbClose');
  report('Batch rename gates changes behind collision-checked preview');

  await page.locator('#rows tr', { hasText: 'Photo_001.txt' }).getByRole('button', { name: 'Delete', exact: true }).click();
  await page.click('#dialogAccept');
  await page.waitForFunction(() => !Array.from(document.querySelectorAll('#rows button.filename')).some(b => b.textContent === 'Photo_001.txt'));
  fs.writeFileSync(path.join(root, 'Tools/Photo_001.txt'), 'new content');
  await page.click('#pbTrash');
  await tools(page).getByText('/Tools/Photo_001.txt', { exact: true }).waitFor();
  await button(page, 'Restore').click();
  await button(page, 'Restore').click();
  await page.getByText(/Имя занято/).waitFor();
  await page.fill('#trashName', 'Recovered.txt');
  await button(page, 'Restore').click();
  await tools(page).getByText('Trash is empty.', { exact: true }).waitFor();
  assert.equal(fs.readFileSync(path.join(root, 'Tools/Recovered.txt'), 'utf8'), 'one');
  assert.equal(fs.readFileSync(path.join(root, 'Tools/Photo_001.txt'), 'utf8'), 'new content');
  await page.click('#wbClose');
  report('Trash restore exposes collision and supports a new name without overwriting');

  await page.click('#up');
  await folder(page, 'Search');
  await page.click('#pbFilters');
  await page.selectOption('#filterType', 'image');
  await page.fill('#filterExt', '.jpg');
  await page.fill('#filterMin', '0.01');
  await page.fill('#filterMax', '1');
  await page.fill('#filterFrom', '2025-04-01');
  await page.fill('#filterUntil', '2025-04-30');
  await page.fill('#filterSaveName', 'April photos');
  await page.click('#filterSave');
  await page.locator('#filterPresets').waitFor();
  await page.click('#filterApply');
  await waitCount(page, 1);
  assert.equal(await page.locator('#rows button.filename').innerText(), 'good.jpg');
  await page.close();
  page = await login(context, newKey);
  await folder(page, 'Search');
  await page.click('#pbFilters');
  assert.equal(await page.locator('#filterPresets option').first().innerText(), 'April photos');
  await button(page, 'Apply').click();
  await waitCount(page, 1);
  assert.equal(await page.locator('#rows button.filename').innerText(), 'good.jpg');
  report('Saved filter persists and combines date, type/extension and size');

  await page.click('#up');
  await folder(page, 'CompareA');
  await page.click('#pbVerify');
  await page.fill('#scanOther', '/CompareB');
  await page.click('#scanCompare');
  await tools(page).getByText('Missing from copy', { exact: true }).waitFor();
  assert.ok(await tools(page).getByText('Changed', { exact: true }).isVisible());
  assert.ok(await tools(page).getByText('Only in copy', { exact: true }).isVisible());
  await page.click('#scanStart');
  await button(page, 'Download JSON manifest').waitFor();
  const dl = page.waitForEvent('download');
  await button(page, 'Download JSON manifest').click();
  const manifest = JSON.parse(fs.readFileSync(await (await dl).path(), 'utf8'));
  assert.equal(manifest.format, 'pocketbridge-sha256-v1');
  assert.equal(manifest.files.length, 3);
  await page.locator('#workbench input[type=file]').setInputFiles({ name: 'manifest.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(manifest)) });
  await tools(page).getByText('Identical: 3 · Differences: 0', { exact: true }).waitFor();
  await page.click('#wbClose');
  report('Folder compare + SHA-256 manifest export and import');

  await page.click('#pbDuplicates');
  await page.click('#scanStart');
  await tools(page).getByText('Matching groups: 1', { exact: true }).waitFor();
  assert.ok(fs.existsSync(path.join(root, 'CompareA/copy.txt')));
  await tools(page).getByLabel('/CompareA/copy.txt', { exact: true }).check();
  await tools(page).getByLabel('/CompareA/same.txt', { exact: true }).click();
  assert.equal(await tools(page).getByLabel('/CompareA/same.txt', { exact: true }).isChecked(), false);
  await page.click('#duplicateTrash');
  assert.ok(fs.existsSync(path.join(root, 'CompareA/copy.txt')));
  await page.click('#duplicateConfirm');
  await tools(page).getByText('Moved to trash: 1', { exact: true }).waitFor();
  assert.ok(!fs.existsSync(path.join(root, 'CompareA/copy.txt')) && fs.existsSync(path.join(root, 'CompareA/same.txt')));
  report('Duplicates require selection, a surviving copy and explicit confirmation');
  await page.close();
}

async function mobile() {
  const context = await browser.newContext({ ...devices['iPhone 13'], viewport: { width: 360, height: 780 }, ignoreHTTPSErrors: true });
  await context.addInitScript(() => localStorage.setItem('pb-lang', 'en'));
  const page = await login(context, newKey);
  const started = performance.now();
  await folder(page, 'Large');
  await waitCount(page, 120);
  const duration = Math.round(performance.now() - started);
  assert.match(await page.locator('#itemCount').innerText(), /10000/);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  await page.click('#view');
  await page.waitForFunction(() => document.querySelectorAll('#grid .card').length === 120);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  await page.click('#view');
  await waitCount(page, 120);
  const checkbox = name => page.locator('#rows tr').filter({
    has: page.getByRole('button', { name, exact: true })
  }).getByRole('checkbox');
  await checkbox('item_00000.txt').check();
  await page.fill('#pageJump', '84');
  await page.click('#pageGo');
  await waitCount(page, 40);
  await checkbox('item_09999.txt').check();
  await page.click('#pbBatch');
  await page.fill('#renameTemplate', 'Page_{n:3}{ext}');
  await page.click('#renamePreview');
  await tools(page).getByText(/^item_00000\.txt → Page_/).waitFor();
  await tools(page).getByText(/^item_09999\.txt → Page_/).waitFor();
  await page.click('#wbClose');
  assert.ok(fs.existsSync(path.join(root, 'Large/item_00000.txt')) && fs.existsSync(path.join(root, 'Large/item_09999.txt')));
  await page.click('#clearSelected');
  report('Selection across pages survives into batch preview without altering unconfirmed files');
  const more = page.locator('#rows .more-actions').last();
  const box = await more.boundingBox();
  assert.ok(box && box.width >= 44 && box.height >= 44);
  await more.click();
  await button(page, 'Properties').waitFor();
  assert.ok(await tools(page).evaluate(d => d.getBoundingClientRect().width <= innerWidth));
  await page.click('#wbClose');
  await page.fill('#search', 'item_07001');
  await waitCount(page, 1);
  assert.equal(await page.locator('#rows button.filename').innerText(), 'item_07001.txt');
  report('360 px / 10,000 files: page jump, compact action menu, search over unloaded pages', `${duration} ms to first page`);
  await context.close();
}

async function thumbnailCapacity() {
  const freshContext = async () => {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    await context.addInitScript(() => localStorage.setItem('pb-lang', 'en'));
    return context;
  };
  const existing = await freshContext();
  try {
    const page = await login(existing, newKey);
    await folder(page, 'Thumbnails');
    let full = false;
    for (let i = 0; i <= 16; i++) {
      const r = await existing.request.post(`${serverUrl}/api/session`, { headers: { 'X-Access-Key': newKey } });
      if (r.status() === 429) {
        assert.ok(Number(r.headers()['retry-after']) > 0);
        full = true;
        break;
      }
      assert.equal(r.status(), 200);
    }
    assert.ok(full, 'Thumbnail slots should be full');
    const renewals = [], firstTokens = [], retryTokens = [];
    page.on('request', r => {
      if (new URL(r.url()).pathname === '/api/session') renewals.push(r.headers()['x-pocketbridge-session']);
    });
    await page.route('**/thumb?**', async route => {
      const query = new URL(route.request().url()).searchParams;
      if (!query.has('retry')) {
        firstTokens.push(query.get('token'));
        await route.fulfill({ status: 403, body: 'Synthetic failed image request' });
      } else {
        retryTokens.push(query.get('token'));
        await route.continue();
      }
    });
    await page.click('#view');
    await page.waitForFunction(() => document.querySelectorAll('#grid img.is-ready').length === 2);
    assert.equal(firstTokens.length, 2);
    assert.deepEqual(renewals, [firstTokens[0]]);
    assert.deepEqual(retryTokens, firstTokens);
    report('Two failed thumbnails share one renewal at capacity and keep the same token');

    const extra = await freshContext();
    try {
      let requests = 0;
      extra.on('request', r => { if (new URL(r.url()).pathname === '/api/session') requests++; });
      const newPage = await login(extra, newKey);
      await folder(newPage, 'Thumbnails');
      await newPage.click('#view');
      await newPage.waitForFunction(() => document.querySelectorAll('#grid .cardicon').length === 2);
      assert.equal(await newPage.locator('#grid img').count(), 0);
      assert.equal(requests, 1, '429 backoff should suppress duplicate session requests');
      report('A new tab at capacity still browses files with icon fallbacks');
    } finally { await extra.close(); }
  } finally { await existing.close(); }
}

(async () => {
  for (const dir of ['Tools', 'Search', 'CompareA', 'CompareB', 'Large', 'Thumbnails']) fs.mkdirSync(path.join(root, dir), { recursive: true });
  const put = (p, data) => fs.writeFileSync(path.join(root, p), data);
  put('Tools/a.txt', 'one'); put('Tools/b.txt', 'two');
  const icon = '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="32" height="32" fill="green"/></svg>';
  put('Thumbnails/one.svg', icon); put('Thumbnails/two.svg', icon);
  for (const [f, size, date] of [['good.jpg', 20000, '2025-04-15'], ['bad.png', 20000, '2025-04-15'],
    ['small.jpg', 100, '2025-04-15'], ['old.jpg', 20000, '2025-02-15']]) {
    put('Search/' + f, Buffer.alloc(size));
    fs.utimesSync(path.join(root, 'Search', f), new Date(date + 'T12:00Z'), new Date(date + 'T12:00Z'));
  }
  put('CompareA/copy.txt', 'abc'); put('CompareA/same.txt', 'abc'); put('CompareB/same.txt', 'abc');
  put('CompareA/changed.txt', 'qqq'); put('CompareB/changed.txt', 'rrr'); put('CompareB/added.txt', 'added');
  for (let i = 0; i < 10000; i++) put(`Large/item_${String(i).padStart(5, '0')}.txt`, 'x');
  await start(oldKey);
  browser = await chromium.launch();
  const context = await browser.newContext({ ignoreHTTPSErrors: true, acceptDownloads: true });
  await context.addInitScript(() => localStorage.setItem('pb-lang', 'en'));
  const page = await uploadAndRestart(context, await login(context, oldKey));
  await toolsTest(context, await transferControls(context, page));
  await context.close();
  await mobile();
  await thumbnailCapacity();
  assert.deepEqual(events, [], 'browser JS errors');
  report('No unhandled JavaScript errors');
})().catch(e => { console.error(e.stack || e); process.exitCode = 1; }).finally(async () => {
  if (browser) await browser.close();
  await stop();
  fs.rmSync(work, { recursive: true, force: true });
});
