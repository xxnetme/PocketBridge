package com.local.wififiles;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Входящая системная отправка. Никаких сетевых действий до подтверждения пользователя. */
public final class ShareActivity extends Activity {
    private Thread worker;
    private boolean en;

    static final class Payload {
        final List<Uri> files;
        final String text;
        Payload(List<Uri> files, String text) { this.files = files; this.text = text; }
    }

    @SuppressWarnings("deprecation")
    static Payload parse(Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            throw new IllegalArgumentException();
        }
        LinkedHashSet<Uri> files = new LinkedHashSet<>();
        if (Intent.ACTION_SEND.equals(action)) {
            addUri(files, intent.getParcelableExtra(Intent.EXTRA_STREAM));
        } else {
            List<?> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) for (Object item : streams) addUri(files, item);
        }
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item item = clip.getItemAt(i);
                addUri(files, item.getUri());
                if (text == null) text = item.getText();
            }
        }
        String message = "";
        if (files.isEmpty() && text != null) {
            int length = Math.min(text.length(), FileServer.CLIPBOARD_MAX_CHARS);
            if (length > 0 && Character.isHighSurrogate(text.charAt(length - 1))) length--;
            message = text.subSequence(0, length).toString();
        }
        return new Payload(new ArrayList<>(files), message);
    }

    private static void addUri(LinkedHashSet<Uri> files, Object item) {
        if (item == null) return;
        if (!(item instanceof Uri)) throw new IllegalArgumentException();
        Uri uri = (Uri) item;
        if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null || uri.getAuthority().isEmpty()) {
            throw new IllegalArgumentException();
        }
        files.add(uri);
        if (files.size() > ShareInbox.MAX_FILES) throw new IllegalArgumentException();
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) { finish(); return; }
        en = BridgeOptions.english(this);
        Payload payload;
        try { payload = parse(getIntent()); }
        catch (RuntimeException invalid) {
            done(tr("Поделитесь текстом или максимум 32 файлами.", "Share text or up to 32 files."));
            return;
        }
        if (payload.files.isEmpty() && payload.text.trim().isEmpty()) {
            done(tr("Нет файлов или текста для приёма", "Nothing to import"));
            return;
        }
        // An exported activity with All files access must not open a URI just because a sender named it.
        for (Uri uri : payload.files) {
            if (!canReadSharedUri(uri)) {
                done(tr("Приложение не предоставило доступ к файлам", "The sending app did not grant access to these files"));
                return;
            }
        }
        ServerService active = ServerService.active();
        String selection = BridgeOptions.root(this);
        if (payload.files.isEmpty() && active != null) {
            String sessionKey = active.accessKey();
            confirm(tr("Отправить в общий буфер?", "Send to shared clipboard?"),
                    tr("Текст заменит содержимое буфера и будет доступен подключённым клиентам до остановки сервера.",
                            "This replaces the shared clipboard and is visible to connected clients until the server stops."),
                    tr("Отправить", "Send"), () -> {
                        if (ServerService.active() != active || !sessionKey.equals(active.accessKey())) {
                            done(tr("Сессия доступа изменилась. Поделитесь текстом заново.", "The session changed. Share the text again."));
                            return;
                        }
                        active.setPhoneClipboard(payload.text);
                        done(tr("Текст отправлен в общий буфер", "Text sent to the shared clipboard"));
                    });
            return;
        }
        if (!BridgeOptions.hasStorageAccess(this) || selection == null) {
            confirm(tr("Нужна доступная папка", "A shared folder is required"),
                    tr("Разрешите доступ к файлам и проверьте общую папку в PocketBridge, затем поделитесь снова.",
                            "Grant storage access and check the shared folder in PocketBridge, then share again."),
                    tr("Открыть PocketBridge", "Open PocketBridge"), () -> {
                        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        finish();
                    });
            return;
        }
        String destination = "/" + (selection.isEmpty() ? "" : selection + "/") + ShareInbox.DIRECTORY;
        int count = payload.files.size();
        confirm(count == 0 ? tr("Сохранить текст?", "Save shared text?") : tr("Принять файлы?", "Import shared files?"),
                (count == 0 ? tr("Сохранить текст как .txt в", "Save text as .txt in")
                        : tr("Скопировать файлов: " + count + " в", "Copy " + count + " file(s) into")) + "\n" + destination + "\n\n"
                        + tr("Новые файлы видны клиентам активной сессии. Если сервер остановлен, он не запустится.",
                             "New files are visible to clients of the active session. Importing will not start the server."),
                tr("Сохранить", "Save"), () -> importToInbox(payload, selection));
    }

    private boolean canReadSharedUri(Uri uri) {
        try {
            return checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException denied) { return false; }
    }

    private void confirm(String title, String message, String accept, Runnable onAccept) {
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(title).setMessage(message)
                .setNegativeButton(tr("Отмена", "Cancel"), (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .setPositiveButton(accept, (dialog, which) -> onAccept.run()).show();
    }

    private void importToInbox(Payload payload, String selection) {
        if (!Objects.equals(selection, BridgeOptions.root(this))) {
            done(tr("Общая папка изменилась. Поделитесь заново.", "The shared folder changed. Share again."));
            return;
        }
        AlertDialog progress = new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle("PocketBridge").setMessage(tr("Сохраняем…", "Importing…"))
                .setCancelable(false)
                .setNegativeButton(tr("Отмена", "Cancel"), (dialog, which) -> finish()).show();
        File storage = Environment.getExternalStorageDirectory();
        worker = new Thread(() -> {
            int copied = 0;
            int requested = Math.max(1, payload.files.size());
            boolean tooLarge = false;
            try {
                StorageRoot.resolve(storage, selection);
                if (payload.files.isEmpty()) {
                    try (InputStream in = new ByteArrayInputStream(payload.text.getBytes(StandardCharsets.UTF_8))) {
                        ShareInbox.copy(storage, selection, "Shared text.txt", in, ShareInbox.MAX_BYTES);
                        copied = 1;
                    }
                } else {
                    long remaining = ShareInbox.MAX_BYTES;
                    for (Uri uri : payload.files) {
                        if (Thread.currentThread().isInterrupted()) return;
                        if (!canReadSharedUri(uri)) continue;
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            if (in == null) continue;
                            ShareInbox.Imported saved = ShareInbox.copy(storage, selection, displayName(uri), in, remaining);
                            remaining -= saved.bytes;
                            copied++;
                        } catch (ShareInbox.TooLarge limit) {
                            tooLarge = true;
                            break;
                        } catch (IOException | RuntimeException ignored) { /* report incomplete batch */ }
                    }
                }
            } catch (IOException | RuntimeException ignored) { /* user sees result, not provider paths */ }
            String result = tr("Сохранено: " + copied + " из " + requested, "Saved " + copied + " of " + requested);
            if (tooLarge) result += tr(" · лимит 2 ГБ", " · 2 GiB limit");
            String summary = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                done(summary);
            });
        }, "pocketbridge-inbox");
        worker.start();
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{ OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) {}
        return "Shared file";
    }

    private String tr(String ru, String english) { return en ? english : ru; }

    private void done(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override protected void onDestroy() {
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
}
