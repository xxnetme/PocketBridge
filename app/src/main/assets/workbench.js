'use strict';

/* Additional local tools. Render untrusted filenames as text, never as markup. */
const wb = (ru, en) => currentLang === 'en' ? en : ru;
let wbRevision = 0, wbCleanup = null;
let quickCriteria = {}, remoteRevision = 0;
const FILTER_KEY = 'pb-search-filters-v1';
const WB_LIMIT = 60;

function wbNode(tag, text = '', className = '') {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== '') node.textContent = text;
  return node;
}
function wbButton(text, action, classes = '') {
  const button = wbNode('button', text, 'btn ' + classes);
  button.type = 'button';
  button.onclick = () => action(button);
  return button;
}
function wbError(text) { el('wbStatus').textContent = text || ''; }
async function wbRun(button, task) {
  const rev = wbRevision;
  wbError('');
  if (button) button.disabled = true;
  try { await task(() => wbRevision === rev && !!key); }
  catch (error) { if (wbRevision === rev) wbError(error.message); }
  finally { if (button) button.disabled = false; }
}
function wbShow(title) {
  const dlg = el('workbench');
  if (wbCleanup) { wbCleanup(); wbCleanup = null; }
  wbRevision++;
  el('wbTitle').textContent = title;
  el('wbClose').textContent = wb('Закрыть', 'Close');
  el('wbBody').replaceChildren();
  el('wbBody').scrollTop = 0;
  wbError('');
  if (!dlg.open) dlg.showModal();
  return el('wbBody');
}
function wbClose() {
  const dialog = el('workbench');
  if (dialog.open) dialog.close();
}
function wbField(label, input) {
  const field = wbNode('label', '', 'wb-field');
  field.append(wbNode('span', label), input);
  return field;
}
function wbInput(type, value = '', placeholder = '') {
  const input = document.createElement('input');
  input.type = type; input.value = value;
  if (placeholder) input.placeholder = placeholder;
  return input;
}
function wbSelect(options, value) {
  const select = document.createElement('select');
  for (const [id, label] of options) {
    const option = wbNode('option', label); option.value = id; select.append(option);
  }
  select.value = value;
  return select;
}
function wbActions(...actions) {
  const row = wbNode('div', '', 'wb-actions'); row.append(...actions); return row;
}
function wbNote(text) { return wbNode('p', text, 'wb-note'); }
function wbPage(entries, draw, pageSize = WB_LIMIT, onChange = null) {
  const frame = wbNode('div', '', 'wb-paged');
  const list = wbNode('div', '', 'wb-entries');
  const heading = wbNode('span'); heading.setAttribute('aria-live', 'polite');
  let page = 0;
  const previous = wbButton(wb('Назад', 'Previous'), () => { if (page > 0) { page--; paint(); } });
  const next = wbButton(wb('Далее', 'Next'), () => { if ((page + 1) * pageSize < entries.length) { page++; paint(); } });
  const nav = wbActions(previous, heading, next);
  function paint() {
    list.replaceChildren(...entries.slice(page * pageSize, (page + 1) * pageSize).map(draw));
    heading.textContent = (page + 1) + ' / ' + Math.max(1, Math.ceil(entries.length / pageSize));
    previous.disabled = page === 0;
    next.disabled = (page + 1) * pageSize >= entries.length;
    nav.hidden = entries.length <= pageSize;
    if (onChange) onChange();
  }
  frame.append(nav, list); paint(); return frame;
}
function wbRow(title, subtitle = '') {
  const row = wbNode('div', '', 'wb-row');
  row.append(wbNode('strong', title, 'wb-path'));
  if (subtitle) row.append(wbNode('small', subtitle, 'wb-note wb-path'));
  return row;
}
function wbFetch(route, params = {}, method = 'GET', body = undefined) {
  return api(route, params, { method, silent: true,
    ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}) }).then(r => r.json());
}

function openTrash() {
  if (readOnly) return;
  const body = wbShow(wb('Корзина', 'Trash'));
  const load = wbNote(wb('Загрузка корзины…', 'Loading trash…')); body.append(load);
  void wbRun(null, async alive => {
    const data = await wbFetch('trash');
    if (!alive()) return;
    load.textContent = wb('Хранение: до 7 дней. ', 'Retained for up to 7 days. ') + bytes(data.usedBytes) + ' / ' + bytes(data.limitBytes);
    if (!data.items.length) { body.append(wbNote(wb('Корзина пуста.', 'Trash is empty.'))); return; }
    const fmt = new Intl.DateTimeFormat(currentLang === 'en' ? 'en-GB' : 'ru', { dateStyle: 'medium', timeStyle: 'short' });
    body.append(wbPage(data.items, item => {
      const row = wbRow(item.path, bytes(item.size) + ' · ' + wb('До ', 'Until ') + fmt.format(item.expiresAt));
      row.append(wbActions(wbButton(wb('Восстановить', 'Restore'), () => restoreTrash(item)),
        wbButton(wb('Удалить навсегда', 'Delete permanently'), () => purgeTrash(item), 'danger')));
      return row;
    }));
  });
}
function restoreTrash(item) {
  const body = wbShow(wb('Восстановить из корзины', 'Restore from trash'));
  const name = wbInput('text', item.name), parent = wbInput('text', parentOf(item.path));
  name.id = 'trashName'; parent.id = 'trashDestination';
  const go = wbButton(wb('Восстановить', 'Restore'), button => wbRun(button, async alive => {
    await wbFetch('trash-restore', { id: item.id, path: parent.value, ...(name.value !== item.name ? { name: name.value } : {}) }, 'POST');
    if (!alive()) return;
    openTrash();
    void refreshListing();
  }), 'primary');
  body.append(wbNote(wb('Существующие файлы не заменяются. Если имя занято, измените имя или папку назначения.',
    'Existing files are never replaced. If the name is taken, choose another name or destination.')),
    wbField(wb('Имя', 'Name'), name), wbField(wb('Папка в общей области', 'Folder in shared storage'), parent),
    wbActions(go, wbButton(wb('Назад', 'Back'), openTrash)));
}
function purgeTrash(item) {
  const body = wbShow(wb('Удалить навсегда?', 'Delete permanently?'));
  body.append(wbRow(item.path, wb('Это действие нельзя отменить.', 'This action cannot be undone.')),
    wbActions(wbButton(wb('Удалить навсегда', 'Delete permanently'), button => wbRun(button, async alive => {
      await wbFetch('trash-purge', { id: item.id }, 'POST');
      if (alive()) openTrash();
    }), 'danger'), wbButton(wb('Отмена', 'Cancel'), openTrash)));
}

function openBatchRename() {
  if (readOnly) return;
  const byPath = new Map(chosenSnapshot().map(x => [x.path, x]));
  const paths = [];
  for (const x of viewSorted) if (byPath.delete(x.path)) paths.push(x.path);
  paths.push(...byPath.keys());
  if (paths.length < 2 || paths.length > 200) { msg(wb('Выберите 2–200 объектов.', 'Select 2–200 entries.')); return; }
  const body = wbShow(wb('Пакетное переименование', 'Batch rename'));
  const template = wbInput('text', '{name}-{n:3}{ext}'); template.id = 'renameTemplate'; template.maxLength = 200;
  const initial = wbInput('number', '1'); initial.min = '0'; initial.max = '999999'; initial.step = '1';
  const result = wbNode('div');
  let token = null, revision = 0;
  const apply = wbButton(wb('Применить', 'Apply'), button => wbRun(button, async alive => {
    if (!token) return;
    const id = token; token = null;
    const r = await wbFetch('rename-apply', { id }, 'POST');
    if (!alive()) return;
    apply.hidden = true;
    for (const x of r.items) if (x.status === 'renamed') prunePathRefs(x.path, x.newPath);
    result.replaceChildren(wbNote(wb('Переименовано: ', 'Renamed: ') + r.renamed + ' · ' + wb('Ошибок: ', 'Failed: ') + r.failed),
      wbPage(r.items, x => wbRow(x.name + ' → ' + x.newName, x.error ||
        (x.status === 'unchanged' ? wb('Без изменений', 'Unchanged') : wb('Готово', 'Done')))));
    await refreshListing();
  }), 'primary');
  apply.id = 'renameApply'; apply.hidden = true;
  const preview = wbButton(wb('Предпросмотр', 'Preview'), button => wbRun(button, async alive => {
    if (!initial.checkValidity() || initial.value === '' || !template.value) throw new Error(wb('Проверьте шаблон и номер.', 'Check the template and starting number.'));
    const id = ++revision;
    const r = await wbFetch('rename-preview', {}, 'POST', { paths, template: template.value, start: Number(initial.value) });
    if (!alive() || id !== revision) return;
    token = r.previewId;
    apply.hidden = !token;
    result.replaceChildren(wbPage(r.items, x => wbRow(x.name + ' → ' + (x.newName || '—'), x.error || parentOf(x.path))));
  })); preview.id = 'renamePreview';
  template.oninput = initial.oninput = () => { revision++; token = null; apply.hidden = true; result.replaceChildren(); };
  body.append(wbNote(wb('{name} — имя; {ext} — расширение с точкой; {n:3} — номер из трёх цифр. Занятые имена не заменяются.',
    '{name} is the stem, {ext} includes the dot, {n:3} pads the number to three digits. Existing names are never replaced.')),
    wbField(wb('Шаблон', 'Template'), template), wbField(wb('Начальный номер', 'Start number'), initial), wbActions(preview, apply), result);
}

function filtersSaved() {
  try { const value = JSON.parse(localStorage.getItem(FILTER_KEY) || '[]'); return Array.isArray(value) ? value.slice(0, 12) : []; }
  catch (_) { return []; }
}
function toFilterQuery(criteria) {
  const params = {};
  if (criteria.type && criteria.type !== 'all') params.type = criteria.type;
  if (criteria.ext) {
    params.ext = criteria.ext.trim().replace(/^\./, '');
    if (params.ext && !/^[a-z0-9_-]{1,24}$/i.test(params.ext)) throw new Error(wb('Укажите расширение, например jpg.', 'Enter an extension such as jpg.'));
  }
  for (const [field, edge] of [['min', 'minBytes'], ['max', 'maxBytes']]) {
    if (criteria[field] !== '' && criteria[field] != null) {
      const n = Number(criteria[field]) * 1024 * 1024;
      if (!Number.isFinite(n) || n < 0 || n > Number.MAX_SAFE_INTEGER) throw new Error(wb('Некорректный размер', 'Invalid file size'));
      params[edge] = field === 'min' ? Math.ceil(n) : Math.floor(n);
    }
  }
  if (params.minBytes > params.maxBytes) throw new Error(wb('Минимум больше максимума.', 'Minimum exceeds maximum.'));
  for (const [field, edge] of [['from', 'after'], ['until', 'before']]) {
    if (!criteria[field]) continue;
    const date = new Date(criteria[field] + 'T00:00:00');
    if (field === 'until') date.setDate(date.getDate() + 1);
    const time = +date;
    if (!Number.isFinite(time)) throw new Error(wb('Некорректная дата', 'Invalid date'));
    params[edge] = time;
  }
  if (params.after >= params.before) throw new Error(wb('Дата начала позже окончания.', 'The start date is later than the end date.'));
  return params;
}
function isPreciseSearch() { return Object.keys(toFilterQuery(quickCriteria)).length > 0; }
function updateFilterBadge() {
  const active = isPreciseSearch();
  el('clearPrecise').hidden = !active;
  el('pbFilters').setAttribute('aria-pressed', String(active));
}
function setPrecise(criteria, query, scope) {
  toFilterQuery(criteria);
  quickCriteria = criteria;
  el('search').value = query;
  el('scope').value = scope === 'global' ? 'global' : 'local';
  filterCat = 'all';
  listPage = 0;
  updateFilterBadge(); syncSearchBox();
  if (isPreciseSearch() || el('scope').value === 'global') scheduleRemote();
  else { remoteResults = null; render(); }
}
function openFilters() {
  const body = wbShow(wb('Точный поиск', 'Advanced search'));
  const query = wbInput('search', el('search').value); query.id = 'filterQuery'; query.maxLength = 200;
  const scope = wbSelect([['local', wb('В этой папке', 'This folder')], ['global', wb('В папке и подпапках', 'This folder and subfolders')]], el('scope').value);
  const type = wbSelect([['all', wb('Любой', 'Any')], ['folder', wb('Папки', 'Folders')], ['file', wb('Все файлы', 'All files')],
    ['image', wb('Изображения', 'Images')], ['video', wb('Видео', 'Video')], ['audio', wb('Аудио', 'Audio')],
    ['document', wb('Документы', 'Documents')], ['archive', wb('Архивы', 'Archives')], ['other', wb('Другие', 'Other')]], quickCriteria.type || 'all');
  type.id = 'filterType';
  const ext = wbInput('text', quickCriteria.ext || '', 'jpg'); ext.id = 'filterExt'; ext.maxLength = 25;
  const min = wbInput('number', quickCriteria.min ?? ''); min.id = 'filterMin'; min.min = '0'; min.step = 'any';
  const max = wbInput('number', quickCriteria.max ?? ''); max.id = 'filterMax'; max.min = '0'; max.step = 'any';
  const from = wbInput('date', quickCriteria.from || ''); from.id = 'filterFrom';
  const until = wbInput('date', quickCriteria.until || ''); until.id = 'filterUntil';
  const form = wbNode('div', '', 'wb-fields');
  form.append(wbField(wb('Имя содержит', 'Name contains'), query), wbField(wb('Где искать', 'Search in'), scope),
    wbField(wb('Тип', 'Type'), type), wbField(wb('Расширение', 'Extension'), ext),
    wbField(wb('Размер от, МиБ', 'Min size, MiB'), min), wbField(wb('Размер до, МиБ', 'Max size, MiB'), max),
    wbField(wb('Изменён с', 'Modified from'), from), wbField(wb('Изменён по (включительно)', 'Modified through (inclusive)'), until));
  const capture = () => {
    for (const input of [min, max, from, until]) if (!input.checkValidity()) throw new Error(wb('Проверьте поля фильтра.', 'Check the filter fields.'));
    const c = { type: type.value, ext: ext.value.trim(), min: min.value, max: max.value, from: from.value, until: until.value };
    const params = toFilterQuery(c);
    if (!Object.keys(params).length && query.value.trim().length < (scope.value === 'global' ? 2 : 1)) {
      throw new Error(wb('Введите имя или добавьте фильтр.', 'Enter a name or add a filter.'));
    }
    return { c, q: query.value.trim(), scope: scope.value };
  };
  const apply = wbButton(wb('Искать', 'Search'), button => wbRun(button, async () => {
    const f = capture(); setPrecise(f.c, f.q, f.scope); wbClose();
  }), 'primary'); apply.id = 'filterApply';
  const title = wbInput('text', '', wb('Имя фильтра', 'Filter name')); title.id = 'filterSaveName'; title.maxLength = 48;
  let saved = filtersSaved();
  const presets = wbNode('div');
  const display = () => {
    presets.replaceChildren();
    if (!saved.length) return;
    const select = wbSelect(saved.map((f, i) => [String(i), f.title]), '0'); select.id = 'filterPresets';
    presets.append(wbField(wb('Сохранённые в этом браузере', 'Saved in this browser'), select), wbActions(
      wbButton(wb('Применить', 'Apply'), button => wbRun(button, async () => {
        const f = saved[Number(select.value)]; setPrecise(f.c, f.q, f.scope); wbClose();
      })), wbButton(wb('Удалить фильтр', 'Remove filter'), button => wbRun(button, async () => {
        const next = saved.filter((_, i) => i !== Number(select.value));
        localStorage.setItem(FILTER_KEY, JSON.stringify(next)); saved = next; display();
      }))));
  };
  const save = wbButton(wb('Сохранить фильтр', 'Save filter'), button => wbRun(button, async () => {
    const name = title.value.trim();
    if (!name) throw new Error(wb('Введите название фильтра.', 'Enter a filter name.'));
    const next = [{ ...capture(), title: name }, ...saved.filter(f => f.title !== name)].slice(0, 12);
    localStorage.setItem(FILTER_KEY, JSON.stringify(next)); saved = next; title.value = ''; display();
  })); save.id = 'filterSave';
  body.append(form, wbActions(apply), wbField(wb('Сохранить текущий фильтр как', 'Save current filter as'), title), wbActions(save), presets);
  display();
}

function reportDiff(before, after) {
  const validate = manifest => {
    const map = new Map();
    if (manifest?.format !== 'pocketbridge-sha256-v1' || !Array.isArray(manifest.files) || manifest.files.length > 20000) {
      throw new Error(wb('Неверный формат отчёта PocketBridge', 'Invalid PocketBridge manifest'));
    }
    for (const f of manifest.files) {
      if (!f || typeof f.path !== 'string' || !f.path || f.path.length > 4096 || f.path.includes('\0') ||
        f.path.split('/').some(p => !p || p === '.' || p === '..') || map.has(f.path) ||
        typeof f.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(f.sha256) || !Number.isSafeInteger(f.size) || f.size < 0) {
        throw new Error(wb('Некорректная запись в отчёте', 'Invalid entry in the manifest'));
      }
      map.set(f.path, f);
    }
    return map;
  };
  const remaining = validate(after);
  let same = 0;
  const items = [];
  for (const [path, f] of validate(before)) {
    const rhs = remaining.get(path);
    if (!rhs) items.push({ path, state: 'missing' });
    else if (rhs.sha256 !== f.sha256 || rhs.size !== f.size) items.push({ path, state: 'changed' });
    else same++;
    remaining.delete(path);
  }
  for (const path of remaining.keys()) items.push({ path, state: 'added' });
  return { same, items };
}
function compareRows(result) {
  const labels = { missing: wb('Отсутствует в копии', 'Missing from copy'), changed: wb('Изменён', 'Changed'), added: wb('Есть только в копии', 'Only in copy') };
  const div = wbNode('div');
  div.append(wbNote(wb('Совпало: ', 'Identical: ') + result.same + ' · ' + wb('Отличий: ', 'Differences: ') + result.items.length));
  if (result.items.length) div.append(wbPage(result.items, r => wbRow(r.path, labels[r.state])));
  return div;
}
function saveManifest(result) {
  const blob = new Blob([JSON.stringify(result, null, 2)], { type: 'application/json' });
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a'); a.href = href; a.download = 'PocketBridge-SHA256.json';
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(href), 20_000);
}
async function scanJob(mode, path, other, mount, alive) {
  const owner = key, delay = ms => new Promise(resolve => setTimeout(resolve, ms));
  const start = await wbFetch('scan-start', { mode, path, other }, 'POST');
  const cancel = () => { if (key === owner) void wbFetch('scan-cancel', { id: start.id }, 'POST').catch(() => {}); };
  if (!alive()) { cancel(); return; }
  wbCleanup = cancel;
  const progress = wbNote(wb('Обход папок…', 'Walking folders…')); progress.setAttribute('role', 'status');
  mount.replaceChildren(progress, wbActions(wbButton(wb('Отменить проверку', 'Cancel scan'), button => wbRun(button, async () => cancel()))));
  try {
    for (;;) {
      await delay(500);
      if (!alive()) return;
      const data = await wbFetch('scan', { id: start.id });
      if (!alive()) return;
      if (data.state === 'error') throw new Error(data.error);
      if (data.state === 'cancelled') { mount.replaceChildren(wbNote(wb('Проверка отменена.', 'Scan cancelled.'))); return; }
      if (data.state === 'done') {
        const done = await wbFetch('scan', { id: start.id, result: 1 });
        if (!alive()) return;
        wbCleanup = null;
        mount.replaceChildren(wbNote(wb('Проверено: ', 'Hashed: ') + done.checked + ' · ' + bytes(done.bytes) + ' · ' + wb('Пропущено ссылок/спецфайлов: ', 'Links/special files skipped: ') + done.skipped));
        if (mode === 'compare') mount.append(compareRows(done.result));
        else if (mode === 'duplicates') mount.append(duplicateRows(start.id, done.result.groups));
        else {
          const diff = wbNode('div');
          const manifest = done.result;
          const choose = wbInput('file'); choose.accept = '.json,application/json'; choose.hidden = true;
          choose.onchange = () => wbRun(null, async stillOpen => {
            const file = choose.files[0]; choose.value = '';
            if (!file) return;
            if (file.size > 16 * 1024 * 1024) throw new Error(wb('Отчёт больше 16 МиБ.', 'Manifest exceeds 16 MiB.'));
            const before = JSON.parse(await file.text());
            if (stillOpen()) diff.replaceChildren(compareRows(reportDiff(before, manifest)));
          });
          mount.append(wbActions(wbButton(wb('Скачать отчёт JSON', 'Download JSON manifest'), () => saveManifest(manifest)),
            wbButton(wb('Сравнить с сохранённым отчётом', 'Compare with a saved manifest'), () => choose.click())), choose, diff,
            wbPage(manifest.files, f => wbRow(f.path, f.sha256 + ' · ' + bytes(f.size))));
        }
        return;
      }
      progress.textContent = (data.phase === 'hash' ? 'SHA-256' : wb('Обход папок', 'Walking folders')) + ' · ' + data.checked + ' / ' + data.found + ' · ' + bytes(data.bytes);
    }
  } catch (error) {
    if (alive()) { cancel(); mount.replaceChildren(); }
    throw error;
  } finally { if (alive()) wbCleanup = null; }
}
function openScan(duplicates) {
  const path = current;
  const title = duplicates ? wb('Поиск дублей', 'Find duplicates') : wb('Проверка папки', 'Verify folder');
  const body = wbShow(title), mount = wbNode('div');
  const compare = wbInput('text', '', '/Backup'); compare.id = 'scanOther';
  const run = (mode, button) => wbRun(button, async alive => {
    for (const b of [start, otherStart]) b.disabled = true;
    try { await scanJob(mode, path, mode === 'compare' ? compare.value.trim() : undefined, mount, alive); }
    finally { start.disabled = otherStart.disabled = false; }
  });
  const start = wbButton(duplicates ? wb('Найти совпадения', 'Find matches') : wb('Рассчитать SHA-256', 'Calculate SHA-256'), button => run(duplicates ? 'duplicates' : 'manifest', button), 'primary');
  start.id = 'scanStart';
  const otherStart = wbButton(wb('Сравнить две папки', 'Compare folders'), button => run('compare', button)); otherStart.id = 'scanCompare';
  body.append(wbRow(path, duplicates ? wb('Файлы группируются по размеру и SHA-256. Ничего не удаляется автоматически.',
    'Files are grouped by size and SHA-256. Nothing is deleted automatically.') : wb('Файлы проверяются по SHA-256 с учётом относительных путей.', 'Files are verified by SHA-256 and relative path.')), wbActions(start));
  if (!duplicates) body.append(wbField(wb('Путь ко второй папке в общей области', 'Path to the second folder in shared storage'), compare), wbActions(otherStart));
  body.append(mount);
}

function duplicateRows(id, input) {
  const frame = wbNode('div');
  const groups = input.map(g => ({ ...g, paths: g.paths.slice() }));
  const selectedCopies = new Set();
  const view = wbNode('div');
  let pending = false;
  const trash = wbButton(wb('В корзину выбранные', 'Trash selected copies'), () => {
    pending = true; guard.replaceChildren(wbNote(wb('Переместить выбранные копии в корзину?', 'Move selected copies to trash?')), wbActions(confirm, undo));
    trash.disabled = true;
  }, 'danger'); trash.id = 'duplicateTrash';
  const guard = wbNode('div');
  const confirm = wbButton(wb('Переместить в корзину', 'Move to trash'), button => wbRun(button, async alive => {
    undo.disabled = true;
    let moved = 0;
    const errors = [];
    try {
      for (const group of groups) {
        const keep = group.paths.find(p => !selectedCopies.has(p));
        if (!keep) continue;
        for (const path of group.paths.filter(p => selectedCopies.has(p))) {
          if (!alive()) return;
          try {
            await wbFetch('duplicate-trash', { id, path, keep }, 'POST');
            moved++; group.paths.splice(group.paths.indexOf(path), 1);
          } catch (err) { errors.push(path + ': ' + err.message); }
        }
      }
      if (!alive()) return;
      selectedCopies.clear(); pending = false;
      guard.replaceChildren(wbNote(wb('В корзине: ', 'Moved to trash: ') + moved));
      if (errors.length) guard.append(wbPage(errors, e => wbRow(e)));
      paint();
      if (moved) await refreshListing();
    } finally { undo.disabled = false; }
  }), 'danger'); confirm.id = 'duplicateConfirm';
  const undo = wbButton(wb('Отмена', 'Cancel'), () => { pending = false; guard.replaceChildren(); update(); });
  const count = wbNote(''); count.setAttribute('aria-live', 'polite');
  function update() {
    count.textContent = wb('Выбрано копий: ', 'Copies selected: ') + selectedCopies.size;
    trash.disabled = pending || !selectedCopies.size;
    for (const input of view.querySelectorAll('input[type=checkbox]')) input.disabled = pending;
  }
  function paint() {
    const remaining = groups.filter(g => g.paths.length > 1);
    view.replaceChildren();
    if (!remaining.length) { view.append(wbNote(wb('Дубликаты не найдены.', 'No duplicates found.'))); update(); return; }
    view.append(wbNote(wb('Групп совпадений: ', 'Matching groups: ') + remaining.length), wbPage(remaining, group => {
      const g = wbRow(group.paths.length + ' × ' + bytes(group.size), group.sha256);
      g.append(wbPage(group.paths, p => {
        if (readOnly) return wbNode('span', p, 'wb-path');
        const input = wbInput('checkbox'); input.checked = selectedCopies.has(p); input.disabled = pending;
        input.onchange = () => {
          if (input.checked && group.paths.filter(x => !selectedCopies.has(x)).length <= 1) {
            input.checked = false; wbError(wb('Оставьте хотя бы одну копию в каждой группе.', 'Keep at least one copy in each group.')); return;
          }
          if (input.checked) selectedCopies.add(p); else selectedCopies.delete(p);
          wbError(''); update();
        };
        const row = wbNode('label', '', 'wb-copy'); row.append(input, wbNode('span', p, 'wb-path')); return row;
      }, 40));
      return g;
    }, 4));
    update();
  }
  frame.append(view);
  if (!readOnly) frame.append(wbActions(count, trash), guard);
  paint();
  return frame;
}

function openMobileActions(item) {
  const body = wbShow(item.name);
  const use = (label, action, cls) => wbButton(label, () => { wbClose(); action(); }, cls);
  const actions = [];
  if (item.dir) actions.push(use(t('btnZipFolder'), () => downloadFolderZip(item)));
  else {
    if (kind(item.name)) actions.push(use(t('btnPreview'), () => openPreview(item)));
    actions.push(use(t('btnDownload'), () => download(item)));
  }
  actions.push(use(t('btnProps'), () => openProps(item)));
  if (!readOnly) actions.push(use(t('btnRename'), () => rename(item)), use(wb('В корзину', 'Move to trash'), () => removeItem(item), 'danger'));
  body.append(wbActions(...actions));
}
function mobileActionsButton(item) {
  const b = wbButton('⋯', () => openMobileActions(item), 'more-actions');
  b.setAttribute('aria-label', wb('Действия: ', 'Actions: ') + item.name);
  return b;
}

function initWorkbench() {
  el('wbClose').onclick = wbClose;
  el('workbench').addEventListener('close', () => {
    if (el('workbench').open) return;
    wbRevision++;
    if (wbCleanup) { wbCleanup(); wbCleanup = null; }
    el('wbBody').replaceChildren();
  });
  el('pbTrash').onclick = openTrash;
  el('pbFilters').onclick = openFilters;
  el('pbVerify').onclick = () => openScan(false);
  el('pbDuplicates').onclick = () => openScan(true);
  el('pbBatch').onclick = openBatchRename;
  el('clearPrecise').onclick = () => setPrecise({}, '', 'local');
}
