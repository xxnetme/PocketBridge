# CHANGELOG

## 3.5 — 18.11.2026 «Стабильность, безопасность и Android 16»

### Сборка и платформа
- **`targetSdk 36` / `compileSdk 36`** (Android 16), AGP `8.11.1`, Gradle `8.13` — актуальные требования Google Play.
- Поддержка отступов системных панелей и выреза экрана (edge-to-edge `WindowInsets`) на Android 15/16.
- Корректная обработка `Service.onTimeout()` для `foregroundServiceType="dataSync"` на Android 15+.
- В `AndroidManifest.xml` добавлено разрешение `WAKE_LOCK` (необходимо для `WifiLock.acquire()`), удалено неиспользуемое `CHANGE_WIFI_STATE`, подключён `android:roundIcon`, исправлен `network_security_config.xml`.
- Настроена условная подпись `release` (схемы v1/v2/v3, поддержка `assembleRelease` и `bundleRelease` через `PB_KEYSTORE` / `PB_KEYSTORE_PASSWORD`).

### Приложение (Android)
- **Настоящий QR-код** (`com.google.zxing:core:3.5.3`) вместо прежней заглушки; QR-блок обновляется без дублирования элементов на экране и освобождает `Bitmap` при пересоздании.
- **Уведомление сервиса:** код доступа скрыт на экране блокировки (`VISIBILITY_PRIVATE` + `publicVersion`).
- **`ServerService`:** предотвращена утечка потоков `watchdog` при смене кода, используется `WIFI_MODE_FULL_LOW_LATENCY` на Android 10+, запуск сервиса переведён на `startForegroundService()`.
- **`Thumbnails`:** устранены утечки `Bitmap` при масштабировании миниатюр и HEIC/RAW-превью; `MediaMetadataRetriever.release()` безопасен на всех версиях API.
- **`MainActivity`:** описание статуса отображает реально выбранный таймаут автостопа; исправлена версия в журнале сбоев.

### Сервер, безопасность и WebDAV
- **X.509 v3 сертификат (`SelfSignedCertificate`):** добавлены `BasicConstraints` (`CA:FALSE`), `KeyUsage`, `ExtendedKeyUsage` (`serverAuth`), `SubjectAlternativeName` (`localhost`, `127.0.0.1`, локальные IPv4), корректный `PrintableString` и автоперевыпуск сертификата при истечении срока.
- **Защита от XSS в SVG и HTML:** добавлен `Content-Security-Policy` с `sandbox` для inline-SVG в `/preview`, `/thumb` и для HTML/SVG в `/dav`, а также CSP для главной страницы `/`. Миниатюры `.svg` в `/thumb` теперь корректно возвращают `200 image/svg+xml`.
- **Разделение тикетов:** тикеты `/preview` нельзя использовать на `/dl` и наоборот.
- **Потокобезопасная докачка:** синхронизация `uploadChunk`/`uploadFinish`/`uploadAbort`, новый эндпоинт `GET /api/upload-status`, заголовок `X-Upload-Received` при `409 Conflict`, троттлинг очистки кеша миниатюр, исправлена проверка префикса корневого каталога.
- **WebDAV (`/dav`):** `OPTIONS` работает без авторизации (совместимость с Windows Explorer и Finder), поддержаны `Range` и `Transfer-Encoding: chunked` для `PUT`, корректно декодируются относительные и абсолютные кириллические `Destination` в `MOVE`/`COPY`, исправлен `HEAD` для каталогов, запрещены `Depth: infinity` и копирование папки внутрь самой себя, симлинки не обходятся при `COPY`, возвращается `409` при отсутствии родительской папки.

### Веб-интерфейс (`index.html`)
- Блок локализации (`LANG`, `t()`, `setLang()`, `populateI18n()`, `applyTheme()`) перенесён внутрь `<script>` — полностью восстановлена работа интерфейса, переключения тем и языков **RU / EN** (включая `placeholder`, `title`, `aria-label`, единицы измерения и сообщения без нелокализованных строк).
- Определены все CSS-переменные для тёмной и светлой тем; скрыты служебные `#fileInput` и `#dirInput`.
- Устранено затенение функции `t()` в скачивании файлов и ZIP, исправлено сообщение при групповом удалении, добавлена функция `joinPath` для загрузки папок, убраны дублирующиеся `bytes()` и обработчики drag & drop.
- Переработана очередь чанковой загрузки: реальная пауза и докачка с серверного смещения (`upload-status` / `X-Upload-Received`), очистка `.part` через `upload-abort` при отмене, работающая кнопка «Отмена» в прогресс-баре, диалог конфликта имён («Перезаписать / Сохранить как / Пропустить»), автообновление сессии миниатюр.

### CI/CD, тесты и документация
- Серверные тесты расширены до **184 проверок** (полный набор тестов WebDAV, SVG CSP, разделение тикетов, докачка `upload-status`, X.509 v3 SAN).
- Починены браузерные тесты Playwright (`ui-test.js` и `make-ui-tree.py`) с проверкой реальной UI-загрузки, конфликтов, локализации RU/EN, SVG и `pageerror`.
- Полностью исправлен `.github/workflows/build.yml` (триггер `tags`, права `contents: write`, Android SDK 36, запуск Playwright против живого HTTPS-сервера, сборка APK/AAB и публикация релиза, actions на Node 24).
- Актуализированы `README.md`, `CHANGELOG.md`, `docs/API.md`, `docs/WEBDAV.md`.

### Версия
- `versionCode 19`, `versionName 3.5`

---

## 3.4 — 17.11.2026 «Медиа и воспроизведение»

- Добавлена поддержка `.svg` в `mediaMime()` и потокового превью видео (`.mp4/.m4v/.webm/.ogv/.mov`).
- Добавлены `svgPreviewTests()` и `videoFrameTests()` (141 проверка).
- `versionCode 18`, `versionName 3.4`.

---

## 3.3 — 17.11.2026 «QR-код и UX»

- Добавлен блок QR-кода на экране приложения и раздел FAQ в `README.md`.
- Обновлён workflow GitHub Actions (кэш Gradle, заготовка для Playwright и релизов).
- `versionCode 17`, `versionName 3.3`.

---

## 3.2 — 17.11.2026 «Готовность к Google Play»

- Добавлен `android:networkSecurityConfig`, убран `requestLegacyExternalStorage`.
- APK подписан схемой APK Signature Scheme v2 (сертификат действителен до 2054 года).
- `versionCode 16`, `versionName 3.2`.

---

## 3.1 — 17.11.2026 «Полировка и стабильность»

- Добавлен лимит 50 МБ на серверное перекодирование файлов в превью (`previewSizeLimitTests()`).
- Расширен словарь локализации RU/EN в веб-интерфейсе.
- `versionCode 15`, `versionName 3.1`.

---

## 3.0 — 17.11.2026 «Просмотр и стабильность»

- Полноразмерный просмотр HEIC/RAW (`.heic/.heif/.dng/.cr2/.nef/.arw/.raf`) с перекодированием в JPEG на сервере (`Thumbnails.previewJpeg`).
- Автоматическая очистка кеша миниатюр при превышении 200 МБ.
- Удержание Wi-Fi через `WifiManager.WifiLock` во время работы сервера.
- `versionCode 14`, `versionName 3.0`.

---

## 2.1 – 2.3 — 17.11.2026 «Докачка, WebDAV и локализация»

- **Чанковая загрузка файлов:** эндпоинты `/api/upload-init`, `/api/upload-chunk`, `/api/upload-finish`, `/api/upload-abort` (до 20 ГБ, чанки до 32 МБ) и очередь загрузки в браузере.
- **WebDAV-сервер (`WebDav.java`):** монтирование хранилища по адресу `https://<IP>:<порт>/dav/` для Windows Explorer, macOS Finder и `rclone`.
- **NFC-нормализация поиска** для корректной работы с именами файлов из macOS (NFD/NFC).
- **Заготовка локализации RU/EN** в веб-интерфейсе.
- `versionCode 11–13`, `versionName 2.1–2.3`.

---

## 2.0 — 17.11.2026

- Плитка с миниатюрами фото (`GET /thumb`, сессии `POST /api/session`, дисковый кеш по SHA-256).
- Загрузка папок с сохранением структуры (`POST /api/mkdirs`, `webkitdirectory`).
- Светлая и тёмная темы веб-интерфейса.
- Экран настроек на телефоне: порт (`1024–65000`), автостоп (`15 / 30 / 60 мин / никогда`), удержание экрана.
- Первая подписанная release-сборка (`versionCode 10`, `versionName 2.0`).

---

## 1.7 — 17.11.2026

- HTTPS с самоподписанным сертификатом RSA-2048 (`SelfSignedCertificate.java`).
- Смена кода доступа без перезапуска приложения, ссылка автовхода `/#k=<код>`, автостоп после 30 минут простоя.
- ZIP со структурой папок и скачивание целой папки в ZIP, глобальный поиск по подпапкам, drag & drop.
- Переименование в **PocketBridge**, лицензия MIT, автономный набор тестов `server-tests/` (`versionCode 9`, `versionName 1.7`).

---

## 1.6 — 17.11.2026

- Foreground-сервис `ServerService` (`dataSync`), потоковое скачивание через тикеты `/dl` и поддержка `Range` (`206 Partial Content`).
- Защита от «молчащих» соединений (таймаут заголовков 15 с, пул до 32 потоков, `503`), поддержка CGNAT и IPv6 ULA/link-local.
- Прогресс загрузки, диалог конфликта имён, хлебные крошки, сортировка, адаптивная мобильная вёрстка (`versionCode 8`, `versionName 1.6`).
