package com.local.wififiles;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Проверяет реальный запуск Android-интерфейса PocketBridge (MainActivity, AppTheme,
 * AppDialogTheme, ServerService) на нескольких поколениях Android (API 26, 30, 34, 36).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 30, 34, 36})
public class MainActivityLaunchTest {

    @Test
    public void launchesCleanlyAcrossFullLifecycle() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        assertNotNull(activity);

        // Тема обязана ссылаться на @color/@drawable ресурс для windowBackground, а не inline #hex.
        TypedValue tv = new TypedValue();
        assertTrue(activity.getTheme().resolveAttribute(android.R.attr.windowBackground, tv, true));
        assertNotEquals("windowBackground must be a @color/@drawable resource reference", 0, tv.resourceId);
        Drawable winBg = activity.getResources().getDrawable(tv.resourceId, activity.getTheme());
        assertNotNull(winBg);

        // Тема диалогов также обязана корректно разрешать фон и цвета.
        ContextThemeWrapper dlgCtx = new ContextThemeWrapper(activity, R.style.AppDialogTheme);
        TypedValue dlgBg = new TypedValue();
        assertTrue(dlgCtx.getTheme().resolveAttribute(android.R.attr.windowBackground, dlgBg, true));
        assertNotEquals(0, dlgBg.resourceId);
        assertNotNull(dlgCtx.getResources().getDrawable(dlgBg.resourceId, dlgCtx.getTheme()));

        TypedValue dlgColorBg = new TypedValue();
        assertTrue(dlgCtx.getTheme().resolveAttribute(android.R.attr.colorBackground, dlgColorBg, true));
        assertNotEquals(0, dlgColorBg.resourceId);

        // Полный проход measure + layout корневого окна без исключений.
        View decor = activity.getWindow().getDecorView();
        assertNotNull(decor);
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 2400);

        List<String> texts = collectTexts(decor);
        assertTrue("Brand title must be present", texts.contains("PocketBridge"));
        assertTrue("Default port 8765 must be shown", texts.contains("8765"));
        assertTrue("Version 3.8 must be shown in subtitle",
                texts.stream().anyMatch(s -> s.contains("v3.9")));
        assertTrue("Default port hint must mention 8765",
                texts.stream().anyMatch(s -> s.contains("8765") && (s.contains("Default") || s.contains("По умолчанию"))));

        // Проверяем подсказку 1-2-3, индикатор запуска и состояние с выданным разрешением на файлы.
        try {
            assertEquals(View.VISIBLE, getField(activity, "stepsRow", View.class).getVisibility());
            assertEquals(View.GONE, getField(activity, "telemetryRow", View.class).getVisibility());
            assertEquals(View.GONE, getField(activity, "statusSpinner", View.class).getVisibility());
            if (android.os.Build.VERSION.SDK_INT < 30) {
                Shadows.shadowOf(RuntimeEnvironment.getApplication())
                        .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                controller.resume();
                assertEquals(View.GONE, getField(activity, "permissionCard", View.class).getVisibility());
                assertEquals(View.VISIBLE, getField(activity, "permissionGrantedRow", View.class).getVisibility());
                getField(activity, "mainButton", TextView.class).performClick();
                // Сразу после нажатия «Запустить» должен загореться спиннер запуска.
                assertEquals(View.VISIBLE, getField(activity, "statusSpinner", View.class).getVisibility());
                assertFalse(getField(activity, "mainButton", TextView.class).isEnabled());
            } else {
                assertEquals(View.VISIBLE, getField(activity, "permissionCard", View.class).getVisibility());
                assertEquals(View.GONE, getField(activity, "permissionGrantedRow", View.class).getVisibility());
                getField(activity, "mainButton", TextView.class).performClick();
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        controller.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34)
    public void togglesLanguageAndOpensSettingsDialogs() throws Exception {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        TextView langButton = getField(activity, "langButton", TextView.class);
        TextView heroTitle = getField(activity, "heroTitle", TextView.class);
        String before = heroTitle.getText().toString();
        assertFalse(before.isEmpty());

        langButton.performClick();
        String after = heroTitle.getText().toString();
        assertNotEquals("Language toggle must switch headline", before, after);

        langButton.performClick();
        assertEquals("Second toggle must restore original headline", before, heroTitle.getText().toString());

        // Диалог порта HTTPS (AppDialogTheme) открывается без сбоев и сохраняет порт.
        TextView portName = getField(activity, "portName", TextView.class);
        ((View) portName.getParent().getParent()).performClick();
        AlertDialog portDialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("Port AlertDialog must open", portDialog);
        assertTrue(portDialog.isShowing());
        EditText input = findFirst(portDialog.getWindow().getDecorView(), EditText.class);
        assertNotNull(input);
        input.setText("9443");
        portDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        TextView settingsPort = getField(activity, "settingsPort", TextView.class);
        assertEquals("9443", settingsPort.getText().toString());

        // Диалог автостопа (AppDialogTheme) открывается без сбоев и меняет интервал.
        TextView autoName = getField(activity, "autoName", TextView.class);
        ((View) autoName.getParent().getParent()).performClick();
        AlertDialog autoDialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("Auto-stop AlertDialog must open", autoDialog);
        assertTrue(autoDialog.isShowing());
        autoDialog.getListView().performItemClick(
                autoDialog.getListView().getAdapter().getView(0, null, autoDialog.getListView()), 0, 0);
        ShadowLooper.idleMainLooper();
        TextView settingsAuto = getField(activity, "settingsAutoStop", TextView.class);
        assertTrue(settingsAuto.getText().toString().startsWith("15"));

        // Переключатель «Не гасить экран».
        TextView keepName = getField(activity, "keepName", TextView.class);
        TextView settingsKeep = getField(activity, "settingsKeepScreen", TextView.class);
        String keepBefore = settingsKeep.getText().toString();
        ((View) keepName.getParent().getParent()).performClick();
        assertNotEquals(keepBefore, settingsKeep.getText().toString());

        controller.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34)
    public void toleratesMismatchedSharedPreferencesTypes() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = app.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(ServerService.PREF_PORT, "not-an-int")
                .putString(ServerService.PREF_AUTOSTOP, "bad")
                .putString(ServerService.PREF_KEEP_SCREEN, "bad")
                .putInt("ui_lang", 42)
                .commit();

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        List<String> texts = collectTexts(activity.getWindow().getDecorView());
        assertTrue(texts.contains("8765"));
        controller.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34)
    public void cleansStaleCrashLogAndHandlesCurrentCrashCard() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File crashFile = new File(app.getFilesDir(), "last-crash.txt");

        // 1. Устаревший отчёт от v3.8 должен тихо очищаться при первом входе в v3.9.
        writeUtf8(crashFile, "PocketBridge 3.8 / Android 15 (API 35) / Pixel\nThread: main\njava.lang.NullPointerException\n");
        ActivityController<MainActivity> c1 = Robolectric.buildActivity(MainActivity.class).setup();
        assertFalse("Stale v3.8 crash report must be deleted", crashFile.exists());
        assertEquals(null, getField(c1.get(), "errorCard", View.class));
        c1.pause().stop().destroy();

        // 2. Актуальный отчёт текущей версии отображается, копируется и скрывается без сбоев.
        writeUtf8(crashFile, "PocketBridge 3.9 / Android 15 (API 35) / Pixel\nThread: main\nTestTrace\n");
        ActivityController<MainActivity> c2 = Robolectric.buildActivity(MainActivity.class).setup();
        View errorCard = getField(c2.get(), "errorCard", View.class);
        assertNotNull("Current-version crash card must render", errorCard);
        assertEquals(View.VISIBLE, errorCard.getVisibility());
        getField(c2.get(), "crashCopy", TextView.class).performClick();
        getField(c2.get(), "crashClear", TextView.class).performClick();
        assertFalse(crashFile.exists());
        assertEquals(View.GONE, errorCard.getVisibility());
        c2.pause().stop().destroy();
    }

    @Test
    @Config(sdk = 34)
    public void serverServiceStartsBindsRendersQrAndStopsCleanly() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File ext = android.os.Environment.getExternalStorageDirectory();
        if (ext != null && !ext.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            ext.mkdirs();
        }
        app.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE)
                .edit().putInt(ServerService.PREF_PORT, 18765).commit();

        Intent startIntent = new Intent(app, ServerService.class)
                .setAction(ServerService.ACTION_START)
                .putExtra(ServerService.EXTRA_KEY, "0123456789abcdef");
        ServiceController<ServerService> sc = Robolectric.buildService(ServerService.class, startIntent).create().startCommand(0, 1);
        ServerService svc = sc.get();
        try {
            assertTrue("ServerService must start HTTPS listener", svc.isRunning());
            assertTrue("Bound port must be valid", svc.port() >= 1024);
            assertEquals("0123456789abcdef", svc.accessKey());

            ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
            MainActivity activity = ac.get();
            ServiceConnection conn = getField(activity, "connection", ServiceConnection.class);
            IBinder binder = svc.onBind(new Intent(app, ServerService.class));
            conn.onServiceConnected(new ComponentName(app, ServerService.class), binder);
            ShadowLooper.idleMainLooper();

            View connectionCard = getField(activity, "connectionCard", View.class);
            View telemetryRow = getField(activity, "telemetryRow", View.class);
            View stepsRow = getField(activity, "stepsRow", View.class);
            TextView telemetryUptime = getField(activity, "telemetryUptime", TextView.class);
            TextView codeText = getField(activity, "codeText", TextView.class);
            TextView copyCode = getField(activity, "copyCode", TextView.class);
            TextView newKey = getField(activity, "newKey", TextView.class);
            View statusSpinner = getField(activity, "statusSpinner", View.class);

            assertEquals(View.VISIBLE, connectionCard.getVisibility());
            assertEquals(View.VISIBLE, telemetryRow.getVisibility());
            assertEquals(View.GONE, stepsRow.getVisibility());
            assertTrue("Uptime must be formatted mm:ss", telemetryUptime.getText().toString().contains(":"));
            assertEquals("0123456789abcdef", codeText.getText().toString());
            assertTrue("ServerService uptime must be non-negative", svc.uptimeMillis() >= 0L);
            assertEquals(0, svc.activeUploads());

            // Кнопка копирования кода показывает мгновенное подтверждение «✓» и возвращает подпись.
            copyCode.performClick();
            assertTrue("Copy feedback must show checkmark", copyCode.getText().toString().startsWith("✓"));
            Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(1600, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertFalse("Copy feedback must restore original label", copyCode.getText().toString().startsWith("✓"));
            assertTrue(telemetryUptime.getText().toString().contains(":"));

            // Нажатие «Сменить код» включает индикатор обновления до прихода нового ключа.
            newKey.performClick();
            assertEquals(View.VISIBLE, statusSpinner.getVisibility());
            assertFalse(newKey.isEnabled());

            // Повторный START с тем же кодом и смена кода работают без сбоев.
            svc.onStartCommand(startIntent, 0, 2);
            assertTrue(svc.isRunning());
            Intent rotateIntent = new Intent(app, ServerService.class)
                    .setAction(ServerService.ACTION_START)
                    .putExtra(ServerService.EXTRA_KEY, "fedcba9876543210");
            svc.onStartCommand(rotateIntent, 0, 3);
            ShadowLooper.idleMainLooper();
            assertEquals("fedcba9876543210", codeText.getText().toString());
            assertEquals(View.GONE, statusSpinner.getVisibility());
            assertTrue(newKey.isEnabled());

            // Генерация и отображение QR-кода (ZXing + Bitmap) на живом экране.
            Method updateQr = MainActivity.class.getDeclaredMethod("updateQrCode", String.class);
            updateQr.setAccessible(true);
            updateQr.invoke(activity, "https://192.168.1.10:" + svc.port() + "/#k=fedcba9876543210");
            View qrSection = getField(activity, "qrSection", View.class);
            Bitmap qr = getField(activity, "currentQrBitmap", Bitmap.class);
            assertEquals(View.VISIBLE, qrSection.getVisibility());
            assertNotNull(qr);
            assertEquals(512, qr.getWidth());
            assertFalse(qr.isRecycled());

            // Общий буфер обмена (ПК ↔ телефон): диалог, вставка, URL-кнопка, копирование, очистка.
            TextView clipPreview = getField(activity, "clipPreview", TextView.class);
            TextView clipCopyBtn = getField(activity, "clipCopyBtn", TextView.class);
            TextView clipSendBtn = getField(activity, "clipSendBtn", TextView.class);
            TextView clipOpenUrlBtn = getField(activity, "clipOpenUrlBtn", TextView.class);
            TextView clipClearBtn = getField(activity, "clipClearBtn", TextView.class);
            View clipExtraRow = getField(activity, "clipExtraRow", View.class);
            View clipMetaBadge = getField(activity, "clipMetaBadge", View.class);

            assertFalse("Copy disabled when shared clipboard is empty", clipCopyBtn.isEnabled());
            assertEquals(View.GONE, clipExtraRow.getVisibility());
            assertEquals(View.GONE, clipMetaBadge.getVisibility());

            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", "https://example.com/note?x=1"));
            clipSendBtn.performClick();
            AlertDialog clipDlg = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("Shared clipboard dialog must open", clipDlg);
            assertTrue(clipDlg.isShowing());
            clipDlg.getButton(DialogInterface.BUTTON_NEUTRAL).performClick(); // Вставить из буфера
            EditText clipEdit = findFirst(clipDlg.getWindow().getDecorView(), EditText.class);
            assertNotNull(clipEdit);
            assertEquals("https://example.com/note?x=1", clipEdit.getText().toString());
            clipDlg.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();

            assertEquals("https://example.com/note?x=1", svc.clipboardText());
            assertEquals("phone", svc.clipboardSource());
            assertEquals("https://example.com/note?x=1", clipPreview.getText().toString());
            assertTrue(clipCopyBtn.isEnabled());
            assertEquals(View.VISIBLE, clipMetaBadge.getVisibility());
            assertEquals(View.VISIBLE, clipExtraRow.getVisibility());
            assertEquals(View.VISIBLE, clipOpenUrlBtn.getVisibility());

            clipOpenUrlBtn.performClick();
            Intent startedView = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("Open link button must fire ACTION_VIEW", startedView);
            assertEquals(Intent.ACTION_VIEW, startedView.getAction());
            assertEquals("https://example.com/note?x=1", String.valueOf(startedView.getData()));

            clipCopyBtn.performClick();
            assertTrue("Clip copy button must flash checkmark", clipCopyBtn.getText().toString().startsWith("✓"));

            // Обычный текст скрывает кнопку «Открыть ссылку», но оставляет «Очистить».
            svc.setPhoneClipboard("Код 482-910");
            ShadowLooper.idleMainLooper();
            assertEquals(View.VISIBLE, clipExtraRow.getVisibility());
            assertEquals(View.GONE, clipOpenUrlBtn.getVisibility());

            clipClearBtn.performClick();
            ShadowLooper.idleMainLooper();
            assertEquals("", svc.clipboardText());
            assertEquals(View.GONE, clipExtraRow.getVisibility());
            assertFalse(clipCopyBtn.isEnabled());

            // Остановка сервиса скрывает карточку подключения, destroy освобождает QR Bitmap.
            svc.onStartCommand(new Intent(app, ServerService.class).setAction(ServerService.ACTION_STOP), 0, 4);
            ShadowLooper.idleMainLooper();
            assertFalse(svc.isRunning());
            assertEquals(View.GONE, connectionCard.getVisibility());

            ac.pause().stop().destroy();
            assertTrue("onDestroy must recycle QR bitmap", qr.isRecycled());
        } finally {
            sc.destroy();
        }
    }

    private static void writeUtf8(File f, String s) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static <T> T getField(Object target, String name, Class<T> type) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return type.cast(f.get(target));
    }

    private static <T extends View> T findFirst(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                T found = findFirst(vg.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<String> collectTexts(View root) {
        List<String> out = new ArrayList<>();
        if (root instanceof TextView) {
            CharSequence cs = ((TextView) root).getText();
            if (cs != null && cs.length() > 0) out.add(cs.toString());
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                out.addAll(collectTexts(vg.getChildAt(i)));
            }
        }
        return out;
    }
}
