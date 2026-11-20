'use strict';

/* Only metadata: no access code, file bytes, or persisted browser file handles. */
const Drafts = {
  open() {
    if (!this.db) this.db = new Promise((resolve, reject) => {
      const req = indexedDB.open('pocketbridge-transfers', 1);
      req.onupgradeneeded = () => req.result.createObjectStore('drafts', { keyPath: 'id' });
      req.onsuccess = () => {
        req.result.onversionchange = () => { req.result.close(); this.db = null; };
        resolve(req.result);
      };
      req.onerror = () => reject(req.error);
      req.onblocked = () => reject(new Error('IndexedDB is blocked'));
    }).catch(err => { this.db = null; throw err; });
    return this.db;
  },
  async access(mode, action) {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('drafts', mode);
      const req = action(tx.objectStore('drafts'));
      tx.oncomplete = () => resolve(req.result);
      tx.onerror = tx.onabort = () => reject(tx.error || new Error('IndexedDB transaction failed'));
    });
  },
  async save(item) {
    const record = { id: item.uploadId, scope: item.scope, parent: item.parent,
      name: item.name, requested: item.requested, size: item.total, sha256: item.digest, expiresAt: item.expiresAt,
      initialized: item.initialized, overwrite: item.overwrite, unique: item.unique,
      cancelPending: !!item.cancelPending };
    await this.access('readwrite', store => store.put(record));
  },
  remove(id) { return this.access('readwrite', store => store.delete(id)); },
  all() { return this.access('readonly', store => store.getAll()); }
};

function trTransfer(ru, en) { return currentLang === 'en' ? en : ru; }
function draftId() {
  return 'r' + Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('');
}
function queueOwns(item) { return !item.detached && !!key && item.auth === key && (!item.durable || item.scope === uploadScope); }
function uploadInterrupted() { return Object.assign(new Error('Upload interrupted'), { name: 'AbortError' }); }
function assertQueueItem(item) {
  if (!queueOwns(item) || item.aborted || item.paused || globalPaused) throw uploadInterrupted();
}
function hashUpload(item) {
  return new Promise((resolve, reject) => {
    const worker = new Worker('/hash-worker.js');
    const finish = (fn, value) => { worker.terminate(); item.controller = null; fn(value); };
    item.controller = { abort: () => finish(reject, uploadInterrupted()) };
    item.verifying = true;
    item.hashed = 0;
    renderQueue();
    worker.onmessage = ({ data }) => {
      if (data.digest) finish(resolve, data.digest);
      else if (data.error) finish(reject, new Error(trTransfer('Не удалось прочитать исходный файл', 'Could not read the source file')));
      else { item.hashed = data.bytes; updateLiveItemRow(item); }
    };
    worker.onerror = () => finish(reject, new Error(trTransfer('Не удалось запустить проверку SHA-256', 'Could not start SHA-256 verification')));
    worker.postMessage(item.file);
  }).finally(() => { item.verifying = false; });
}

async function draftApi(item, route, params = {}, method = 'GET', passive = false) {
  if (passive && (!queueOwns(item) || item.aborted)) throw uploadInterrupted();
  if (!passive) assertQueueItem(item);
  const opts = new AbortController();
  item.controller = opts;
  try { return await (await api(route, params, { method, signal: opts.signal, silent: true })).json(); }
  finally { if (item.controller === opts) item.controller = null; }
}

function confirmed(item, data) {
  const n = data.received;
  if (data.uploadId !== item.uploadId || data.size !== item.total || !Number.isSafeInteger(n) || n < 0 || n > item.total) {
    throw new Error(trTransfer('Некорректный ответ о черновике', 'Invalid draft response'));
  }
  item.loaded = n;
  item.name = data.name;
  item.expiresAt = data.expiresAt;
  item.initialized = true;
  item.published = data.done === true;
}

async function initRetained(item) {
  assertQueueItem(item);
  if (item.file.size > 20 * 1024 ** 3) throw new Error(trTransfer('Лимит файла — 20 ГиБ', 'The file limit is 20 GiB'));
  if (!item.scope) throw new Error(trTransfer('Хранилище черновиков на телефоне недоступно', 'Draft storage on the phone is unavailable'));
  if (!item.verified) {
    const hash = await hashUpload(item);
    assertQueueItem(item);
    if (item.digest && item.digest !== hash) {
      item.file = null;
      throw Object.assign(new Error(trTransfer('SHA-256 не совпадает. Выберите исходный файл.', 'SHA-256 does not match. Select the original file.')), { status: 422 });
    }
    item.verified = true;
    item.digest = hash;
  }
  if (item.initialized) {
    confirmed(item, await draftApi(item, 'upload-status', { id: item.uploadId }));
    if (!item.aborted) await Drafts.save(item);
    return true;
  }
  if (!item.uploadId) item.uploadId = draftId();
  if (!item.expiresAt) item.expiresAt = Date.now() + 24 * 60 * 60 * 1000;
  while (true) {
    // Save the ID before init. A lost response must not create another server draft.
    await Drafts.save(item);
    assertQueueItem(item);
    try {
      const data = await draftApi(item, 'upload-init', { id: item.uploadId, scope: item.scope,
        path: item.parent, name: item.requested, sha256: item.digest, size: item.total,
        overwrite: item.overwrite ? '1' : '0', unique: item.unique ? '1' : '0' }, 'POST');
      confirmed(item, data);
      if (!item.aborted) await Drafts.save(item);
      return true;
    } catch (err) {
      if (err.status !== 409 || err.headers?.get('X-PocketBridge-Upload-Conflict') !== '1' || !item.interactive || item.overwrite || item.unique) throw err;
      assertQueueItem(item);
      const choice = await askConflict(item.name);
      assertQueueItem(item);
      if (choice === null) {
        await Drafts.remove(item.uploadId);
        item.status = 'skipped'; item.settle({ status: 'skipped' });
        return false;
      }
      if (choice === 'overwrite') item.overwrite = true;
      else item.name = item.requested = choice;
      renderQueue();
    }
  }
}

async function finishRetained(item) {
  while (!item.published) {
    try {
      item.finalizing = true;
      renderQueue();
      confirmed(item, await draftApi(item, 'upload-finish', { id: item.uploadId }, 'POST'));
    } catch (e) {
      if (e.headers?.get('X-PocketBridge-Upload-Conflict') !== '1') throw e;
      item.finalizing = false;
      assertQueueItem(item);
      const choice = await askConflict(item.name);
      assertQueueItem(item);
      if (choice === null) { item.paused = true; throw uploadInterrupted(); }
      confirmed(item, await draftApi(item, 'upload-retarget', { id: item.uploadId,
        name: choice === 'overwrite' ? item.name : choice, overwrite: choice === 'overwrite' ? '1' : '0' }, 'POST'));
      if (!item.aborted) await Drafts.save(item);
    } finally { item.finalizing = false; }
  }
  await Drafts.remove(item.uploadId).catch(() => {});
}

function chooseDraftFile(item) {
  const pick = document.createElement('input');
  pick.type = 'file';
  pick.hidden = true;
  document.getElementById('draftFilePicker')?.remove();
  pick.id = 'draftFilePicker';
  document.body.append(pick);
  pick.oncancel = () => pick.remove();
  pick.setAttribute('aria-label', trTransfer('Выберите исходный файл', 'Select the original file'));
  pick.onchange = () => {
    const f = pick.files[0];
    pick.remove();
    if (!f || !queueOwns(item)) return;
    if (f.size !== item.total) {
      item.error = trTransfer('Размер не совпадает. Выберите исходный файл.', 'Size does not match. Select the original file.');
      renderQueue(); return;
    }
    item.file = f;
    item.verified = false;
    item.error = null;
    item.status = 'waiting';
    item.paused = false;
    item.retries = 0;
    globalPaused = false;
    renderQueue();
    drain();
  };
  pick.click();
}

async function discardDraft(item) {
  item.aborted = true;
  item.controller?.abort();
  if (!item.uploadId) {
    item.status = 'cancelled';
    item.settle({ status: 'cancelled' });
    renderQueue();
    return;
  }
  item.cancelPending = true;
  item.status = 'discarding';
  item.error = null;
  renderQueue();
  try {
    await Drafts.save(item).catch(() => {});
    if (!queueOwns(item)) return;
    const result = await (await api('upload-abort', { id: item.uploadId }, { method: 'POST', silent: true })).json();
    await Drafts.remove(item.uploadId);
    item.cancelPending = false;
    if (result.done) {
      item.name = result.name || item.name;
      item.loaded = item.total;
      item.status = 'done';
      item.settle({ status: 'done', name: item.name });
      if (queueOwns(item)) { showToast(trTransfer('Файл уже сохранён', 'The file was already saved')); void refreshListing(); }
    } else {
      item.status = 'cancelled';
      item.settle({ status: 'cancelled' });
    }
  } catch (_) {
    item.status = 'discardError';
    item.error = trTransfer('Не удалось удалить на телефоне. Подключитесь и повторите.', 'Could not discard on the phone. Reconnect and retry.');
  } finally { if (queueOwns(item)) renderQueue(); }
}

async function restoreDraftQueue() {
  const auth = key, scope = uploadScope;
  if (readOnly || !auth || !scope) return;
  const records = await Drafts.all().catch(() => []);
  if (auth !== key || scope !== uploadScope) return;
  for (const r of records) {
    if (r.scope !== scope || !/^r[0-9a-f]{32}$/.test(r.id) || !/^[0-9a-f]{64}$/.test(r.sha256)) continue;
    if (queue.some(i => i.uploadId === r.id)) continue;
    const item = qItem(null, r.parent, r.name, { durable: true, overwrite: r.overwrite, unique: r.unique });
    Object.assign(item, { uploadId: r.id, total: r.size, digest: r.sha256, requested: r.requested,
      initialized: r.initialized, expiresAt: r.expiresAt, cancelPending: r.cancelPending, restored: true, status: 'source' });
    queue.push(item);
    queueShow();
    if (r.cancelPending) { await discardDraft(item); continue; }
    try {
      const received = await draftApi(item, 'upload-status', { id: item.uploadId }, 'GET', true);
      if (!queueOwns(item) || item.aborted) return;
      confirmed(item, received);
      if (item.published) {
        await Drafts.remove(r.id).catch(() => {});
        item.status = 'done';
        await refreshListing();
      } else await Drafts.save(item);
    } catch (e) {
      if (!queueOwns(item) || item.aborted) return;
      if (e.status === 404 && (r.initialized || r.expiresAt < Date.now())) {
        item.status = 'missing';
        item.error = trTransfer('Черновик отсутствует или истёк. Прогресс не восстановлен.', 'Draft is missing or expired. No progress was restored.');
      } else if (e.status !== 404) item.error = e.message;
    }
  }
  if (auth === key) renderQueue();
}

function detachUploadQueue() {
  const active = queue.splice(0);
  for (const item of active) {
    item.detached = true;
    item.controller?.abort();
    if (!item.durable) abortUploadOnServer(item.uploadId);
    item.settle({ status: 'cancelled' });
  }
  globalPaused = false;
  queueHide();
}
