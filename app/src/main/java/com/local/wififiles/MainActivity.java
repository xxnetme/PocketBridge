package com.local.wififiles;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int BG = 0xff0a1419, PANEL = 0xff14252b, PANEL2 = 0xff1b3035, BORDER = 0xff2a4144,
            WHITE = 0xffecf6f2, MUTED = 0xff92aaa7, MINT = 0xff83e5c2, DARK = 0xff0d3028, RED = 0xffffb6ab;
    private static final String APP_VERSION = "3.5";

    private TextView statusTitle, statusDescription, addressText, codeText, permissionStatus, permissionButton, mainButton, copyAddress, copyLink;
    private LinearLayout connectionCard, qrSection;
    private ImageView qrImage;
    private Bitmap currentQrBitmap;
    private String lastQrLink = "";
    private ServerService service;
    private boolean bound;
    private String key = "", firstAddress = "";
    private TextView settingsPort, settingsAutoStop, settingsKeepScreen;
    private SharedPreferences prefs;
    private int port;

    private final Runnable serverListener = () -> runOnUiThread(this::refreshView);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ServerService.LocalBinder) binder).service();
            bound = true;
            service.addListener(serverListener);
            refreshView();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            refreshView();
        }
    };

    @Override @SuppressWarnings("deprecation")
    public void onCreate(Bundle state) {
        super.onCreate(state);
        installCrashRecorder();
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        }
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BG);
        LinearLayout page = column();
        int padH = dp(22), padTop = dp(22), padBottom = dp(32);
        page.setPadding(padH, padTop, padH, padBottom);
        // Поддержка edge-to-edge на Android 15/16 (targetSdk 36)
        scroller.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            page.setPadding(padH + left, padTop + top, padH + right, padBottom + bottom);
            return insets;
        });
        scroller.addView(page);
        setContentView(scroller);

        LinearLayout brand = row(); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = label("▤", 22, DARK, true); mark.setGravity(Gravity.CENTER); mark.setBackground(shape(MINT, 14, 0));
        brand.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout brandWords = column(); brandWords.setPadding(dp(12), 0, 0, 0);
        brandWords.addView(label("PocketBridge", 15, WHITE, true));
        TextView brandSub = label("WI-FI  ·  ЛОКАЛЬНО", 10, MUTED, true); brandSub.setLetterSpacing(.11f);
        brandWords.addView(brandSub);
        brand.addView(brandWords, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(brand); space(page, 35);

        TextView eyebrow = label("ВАШ ТЕЛЕФОН  /  ВАШ ПК", 10, MINT, true); eyebrow.setLetterSpacing(.14f);
        page.addView(eyebrow); space(page, 10);
        TextView headline = label("Файлы рядом.\nБез проводов.", 32, WHITE, true); headline.setLineSpacing(dp(1), 1.0f);
        page.addView(headline); space(page, 10);
        TextView subtitle = label("Откройте файлы телефона в браузере компьютера через общую сеть Wi-Fi.", 15, MUTED, false); subtitle.setLineSpacing(dp(3), 1.0f);
        page.addView(subtitle); space(page, 26);

        LinearLayout statusCard = card(); statusCard.setPadding(dp(18), dp(17), dp(18), dp(17));
        statusTitle = label("", 14, WHITE, true);
        statusDescription = label("", 13, MUTED, false); statusDescription.setLineSpacing(dp(2), 1.0f);
        statusCard.addView(statusTitle); space(statusCard, 7); statusCard.addView(statusDescription);
        page.addView(statusCard); space(page, 14);

        connectionCard = card(); connectionCard.setPadding(dp(18), dp(19), dp(18), dp(18));
        TextView accessHeading = label("ДАННЫЕ ДЛЯ ПОДКЛЮЧЕНИЯ", 10, MINT, true); accessHeading.setLetterSpacing(.12f);
        connectionCard.addView(accessHeading); space(connectionCard, 17);
        connectionCard.addView(caption("АДРЕС В БРАУЗЕРЕ")); space(connectionCard, 6);
        addressText = label("", 18, WHITE, true); addressText.setTextIsSelectable(true);
        connectionCard.addView(addressText); space(connectionCard, 10);
        copyAddress = button("Скопировать адрес", false);
        copyAddress.setOnClickListener(v -> copyValue("Адрес", firstAddress));
        connectionCard.addView(copyAddress, new LinearLayout.LayoutParams(-1, dp(43))); space(connectionCard, 18);

        View divider = new View(this); divider.setBackgroundColor(BORDER);
        connectionCard.addView(divider, new LinearLayout.LayoutParams(-1, dp(1))); space(connectionCard, 18);

        connectionCard.addView(caption("КОД ДОСТУПА")); space(connectionCard, 6);
        codeText = label("", 20, WHITE, true); codeText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); codeText.setTextIsSelectable(true);
        connectionCard.addView(codeText); space(connectionCard, 10);
        TextView copyCode = button("Скопировать код", false);
        copyCode.setOnClickListener(v -> copyValue("Код", key));
        connectionCard.addView(copyCode, new LinearLayout.LayoutParams(-1, dp(43))); space(connectionCard, 10);

        LinearLayout linkRow = row();
        copyLink = button("Ссылка с кодом", false);
        copyLink.setOnClickListener(v -> copyValue("Ссылка", linkWithKey()));
        linkRow.addView(copyLink, new LinearLayout.LayoutParams(0, dp(43), 1));
        spaceH(linkRow, 10);
        TextView newKey = button("Сменить код", false);
        newKey.setOnClickListener(v -> changeCode());
        linkRow.addView(newKey, new LinearLayout.LayoutParams(0, dp(43), 1));
        connectionCard.addView(linkRow);

        // Блок QR-кода создаётся один раз и обновляется в refreshView() без дублирования view
        qrSection = column();
        space(qrSection, 18);
        View qrDivider = new View(this); qrDivider.setBackgroundColor(BORDER);
        qrSection.addView(qrDivider, new LinearLayout.LayoutParams(-1, dp(1)));
        space(qrSection, 18);
        qrSection.addView(caption("QR-КОД (ССЫЛКА С АВТОВХОДОМ)"));
        space(qrSection, 10);
        LinearLayout qrCenter = column();
        qrCenter.setGravity(Gravity.CENTER_HORIZONTAL);
        qrImage = new ImageView(this);
        qrImage.setAdjustViewBounds(true);
        qrImage.setBackground(shape(0xffffffff, 12, 0));
        qrImage.setPadding(dp(10), dp(10), dp(10), dp(10));
        qrImage.setContentDescription("QR-код для подключения");
        qrCenter.addView(qrImage, new LinearLayout.LayoutParams(dp(200), dp(200)));
        qrSection.addView(qrCenter, new LinearLayout.LayoutParams(-1, -2));
        qrSection.setVisibility(View.GONE);
        connectionCard.addView(qrSection);

        connectionCard.setVisibility(View.GONE);
        page.addView(connectionCard); space(page, 14);

        mainButton = button("Запустить доступ  →", true);
        mainButton.setOnClickListener(v -> { if (!isRunning()) startServer(); else stopServer(); });
        page.addView(mainButton, new LinearLayout.LayoutParams(-1, dp(54))); space(page, 24);

        LinearLayout permissionCard = card(); permissionCard.setPadding(dp(18), dp(17), dp(18), dp(17));
        permissionCard.addView(label("Доступ к файлам", 16, WHITE, true)); space(permissionCard, 6);
        permissionStatus = label("", 13, MUTED, false); permissionStatus.setLineSpacing(dp(2), 1.0f);
        permissionCard.addView(permissionStatus); space(permissionCard, 14);
        permissionButton = button("Разрешить доступ", false);
        permissionButton.setOnClickListener(v -> requestAccess());
        permissionCard.addView(permissionButton, new LinearLayout.LayoutParams(-1, dp(46)));
        page.addView(permissionCard); space(page, 14);

        prefs = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        LinearLayout settingsCard = card(); settingsCard.setPadding(dp(18), dp(17), dp(18), dp(8));
        settingsCard.addView(label("Настройки", 16, WHITE, true)); space(settingsCard, 6);
        settingsCard.addView(label("Применяются сразу; запущенный сервер перезапустится.", 12, MUTED, false)); space(settingsCard, 12);
        settingsPort = settingsRow(settingsCard, "Порт", this::editPort);
        settingsAutoStop = settingsRow(settingsCard, "Автостоп", this::editAutoStop);
        settingsKeepScreen = settingsRow(settingsCard, "Экран не гаснет", this::toggleKeepScreen);
        page.addView(settingsCard); space(page, 20);

        TextView security = label("БЕЗОПАСНОСТЬ", 10, MUTED, true); security.setLetterSpacing(.13f);
        page.addView(security); space(page, 8);
        TextView warning = label("Соединение шифруется (HTTPS) самоподписанным сертификатом: браузер один раз попросит подтвердить исключение — это нормально. Пользуйтесь доверенной сетью. Некоторые системные папки Android недоступны.", 13, MUTED, false);
        warning.setLineSpacing(dp(3), 1.0f);
        page.addView(warning);

        if (new File(getFilesDir(), "last-crash.txt").exists()) {
            space(page, 22);
            LinearLayout errorCard = card(); errorCard.setPadding(dp(18), dp(16), dp(18), dp(16));
            errorCard.addView(label("Журнал последнего сбоя", 15, WHITE, true)); space(errorCard, 8);
            TextView detail = label("Скопируйте журнал, если ошибка повторилась. Он может содержать пути к файлам.", 12, MUTED, false);
            errorCard.addView(detail); space(errorCard, 10);
            TextView copy = button("Скопировать журнал", false);
            copy.setOnClickListener(v -> copyCrashReport());
            errorCard.addView(copy, new LinearLayout.LayoutParams(-1, dp(44)));
            page.addView(errorCard);
        }
        refreshView();
    }

    @Override protected void onStart() {
        super.onStart();
        bindService(new Intent(this, ServerService.class), connection, BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (bound) {
            try { service.removeListener(serverListener); } catch (Exception ignored) {}
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    @Override protected void onResume() { super.onResume(); refreshView(); }

    @Override protected void onDestroy() {
        if (currentQrBitmap != null && !currentQrBitmap.isRecycled()) {
            qrImage.setImageBitmap(null);
            currentQrBitmap.recycle();
            currentQrBitmap = null;
        }
        super.onDestroy();
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout card() { LinearLayout l = column(); l.setBackground(shape(PANEL, 18, BORDER)); return l; }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(size); t.setTextColor(color); t.setIncludeFontPadding(true);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private TextView caption(String text) { TextView t = label(text, 10, MUTED, true); t.setLetterSpacing(.11f); return t; }
    private TextView button(String text, boolean primary) {
        TextView t = label(text, 14, primary ? DARK : WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setBackground(shape(primary ? MINT : PANEL2, 12, primary ? 0 : BORDER));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }
    private void space(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private void spaceH(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(dp(size), 1)); }

    private boolean isRunning() { return service != null && service.isRunning(); }

    private void refreshView() {
        if (statusTitle == null) return;
        boolean running = isRunning(), allowed = hasAccess();
        statusTitle.setText(running ? "●  Сервер запущен" : "○  Сервер остановлен");
        statusTitle.setTextColor(running ? MINT : WHITE);
        String lastError = service != null ? service.lastError() : "";
        int autoStopMin = prefs != null ? prefs.getInt(ServerService.PREF_AUTOSTOP, ServerService.DEFAULT_AUTOSTOP) : ServerService.DEFAULT_AUTOSTOP;
        String autoStopNote = autoStopMin <= 0
                ? "Автостоп отключён."
                : "Остановится сам после " + autoStopMin + " мин без подключений.";
        statusDescription.setText(running
                ? "Шифрование включено (HTTPS). Работает, даже если свернуть приложение. " + autoStopNote
                : (lastError.isEmpty() ? "Подключите телефон и компьютер к одной сети Wi-Fi." : lastError));
        permissionStatus.setText(allowed
                ? "Доступ к общему хранилищу разрешён."
                : "Разрешение нужно, чтобы просматривать и изменять файлы.");
        permissionButton.setText(allowed ? "✓  Доступ разрешён" : "Разрешить доступ к файлам");
        mainButton.setText(running ? "Остановить доступ" : "Запустить доступ  →");
        mainButton.setTextColor(running ? RED : DARK);
        mainButton.setBackground(shape(running ? 0xff372b2c : MINT, 12, running ? 0xff694241 : 0));
        connectionCard.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) {
            key = service.accessKey();
            port = service.port();
            List<String> ips = ipAddresses();
            firstAddress = ips.isEmpty() ? "" : "https://" + ips.get(0) + ":" + port;
            if (ips.isEmpty()) {
                addressText.setText("Нет адреса Wi-Fi. Проверьте подключение.");
            } else {
                List<String> urls = new ArrayList<>();
                for (String ip : ips) urls.add("https://" + ip + ":" + port);
                addressText.setText(String.join("\n", urls));
            }
            codeText.setText(key);
            boolean hasIp = !ips.isEmpty();
            copyAddress.setAlpha(hasIp ? 1f : 0.45f);
            copyAddress.setEnabled(hasIp);
            copyLink.setAlpha(hasIp ? 1f : 0.45f);
            copyLink.setEnabled(hasIp);
            updateQrCode(hasIp ? linkWithKey() : "");
            if (prefs == null || prefs.getBoolean(ServerService.PREF_KEEP_SCREEN, true)) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } else {
            updateQrCode("");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        refreshSettings();
    }

    private void updateQrCode(String link) {
        if (link == null || link.isEmpty()) {
            qrSection.setVisibility(View.GONE);
            lastQrLink = "";
            return;
        }
        if (!link.equals(lastQrLink)) {
            Bitmap fresh = renderQrBitmap(link, 512);
            if (fresh != null) {
                Bitmap old = currentQrBitmap;
                currentQrBitmap = fresh;
                qrImage.setImageBitmap(fresh);
                if (old != null && !old.isRecycled()) old.recycle();
                lastQrLink = link;
                qrSection.setVisibility(View.VISIBLE);
            } else {
                qrSection.setVisibility(View.GONE);
            }
        } else {
            qrSection.setVisibility(View.VISIBLE);
        }
    }

    private static Bitmap renderQrBitmap(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int w = matrix.getWidth(), h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int rowOffset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[rowOffset + x] = matrix.get(x, y) ? 0xff0a1419 : 0xffffffff;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private String linkWithKey() {
        if (firstAddress == null || firstAddress.isEmpty()) return "";
        return firstAddress + "/#k=" + key;
    }

    private static String randomHexKey() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder b = new StringBuilder(16);
        for (byte x : bytes) b.append(String.format(Locale.US, "%02x", x & 0xff));
        return b.toString();
    }

    private void dispatchStartService(String accessKey) {
        Intent intent = new Intent(this, ServerService.class)
                .setAction(ServerService.ACTION_START)
                .putExtra(ServerService.EXTRA_KEY, accessKey);
        startForegroundService(intent);
    }

    private void changeCode() {
        if (!isRunning()) { Toast.makeText(this, "Сначала запустите доступ", Toast.LENGTH_SHORT).show(); return; }
        try {
            dispatchStartService(randomHexKey());
            Toast.makeText(this, "Новый код создан. Откройте страницу заново.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось сменить код", Toast.LENGTH_SHORT).show();
        }
    }

    private TextView settingsRow(LinearLayout parent, String caption, Runnable action) {
        LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(9), 0, dp(9));
        TextView name = label(caption, 14, WHITE, false); row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = label("", 14, MINT, true); value.setGravity(Gravity.END); row.addView(value);
        TextView chevron = label("  ›", 16, MUTED, false); row.addView(chevron);
        row.setClickable(true); row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        parent.addView(row);
        View divider = new View(this); divider.setBackgroundColor(BORDER);
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        return value;
    }

    private void refreshSettings() {
        if (settingsPort == null) return;
        settingsPort.setText(String.valueOf(prefs.getInt(ServerService.PREF_PORT, ServerService.DEFAULT_PORT)));
        int minutes = prefs.getInt(ServerService.PREF_AUTOSTOP, ServerService.DEFAULT_AUTOSTOP);
        settingsAutoStop.setText(minutes <= 0 ? "Никогда" : minutes + " мин");
        settingsKeepScreen.setText(prefs.getBoolean(ServerService.PREF_KEEP_SCREEN, true) ? "Вкл" : "Выкл");
    }

    private void editPort() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(prefs.getInt(ServerService.PREF_PORT, ServerService.DEFAULT_PORT)));
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Порт сервера")
                .setMessage("Введите порт от 1024 до 65000. Если он занят, используется следующий свободный.")
                .setView(input)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString().trim());
                        if (value < 1024 || value > 65000) throw new NumberFormatException();
                        prefs.edit().putInt(ServerService.PREF_PORT, value).apply();
                        refreshSettings();
                        restartIfRunning();
                    } catch (Exception e) {
                        Toast.makeText(this, "Некорректный порт", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void editAutoStop() {
        final int[] values = {15, 30, 60, 0};
        String[] labels = {"15 минут", "30 минут", "1 час", "Никогда"};
        int current = prefs.getInt(ServerService.PREF_AUTOSTOP, ServerService.DEFAULT_AUTOSTOP);
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == current) checked = i;
        new AlertDialog.Builder(this)
                .setTitle("Останавливать сервер без подключений")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs.edit().putInt(ServerService.PREF_AUTOSTOP, values[which]).apply();
                    dialog.dismiss();
                    refreshSettings();
                    restartIfRunning();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void toggleKeepScreen() {
        boolean value = !prefs.getBoolean(ServerService.PREF_KEEP_SCREEN, true);
        prefs.edit().putBoolean(ServerService.PREF_KEEP_SCREEN, value).apply();
        refreshSettings();
        refreshView();
    }

    private void restartIfRunning() {
        if (!isRunning()) return;
        startService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_STOP));
        final String current = key;
        new Handler(getMainLooper()).postDelayed(() -> {
            try { dispatchStartService(current); } catch (Exception ignored) {}
        }, 400);
    }

    private void copyValue(String label, String value) {
        if (value == null || value.isEmpty()) return;
        ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clip != null) clip.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, label + " скопирован", Toast.LENGTH_SHORT).show();
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        if (hasAccess()) { Toast.makeText(this, "Доступ уже разрешён", Toast.LENGTH_SHORT).show(); return; }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                catch (Exception ex) { Toast.makeText(this, "Откройте настройки доступа ко всем файлам", Toast.LENGTH_LONG).show(); }
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
        }
    }

    private void startServer() {
        if (!hasAccess()) {
            Toast.makeText(this, "Сначала разрешите доступ к файлам", Toast.LENGTH_LONG).show();
            requestAccess();
            return;
        }
        if (isRunning()) { refreshView(); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
        key = randomHexKey();
        dispatchStartService(key);
    }

    private void stopServer() {
        startService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_STOP));
        Toast.makeText(this, "Доступ остановлен", Toast.LENGTH_SHORT).show();
    }

    private List<String> ipAddresses() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress ip : Collections.list(ni.getInetAddresses())) {
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && isLocalIpv4(ip)) {
                        ips.add(ip.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        return ips;
    }

    private static boolean isLocalIpv4(InetAddress ip) {
        if (ip.isSiteLocalAddress()) return true;
        byte[] b = ip.getAddress();
        if (b.length == 4) {
            int second = b[1] & 0xff;
            return (b[0] & 0xff) == 100 && second >= 64 && second <= 127;
        }
        return false;
    }

    private void installCrashRecorder() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final File report = new File(getFilesDir(), "last-crash.txt");
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(report), StandardCharsets.UTF_8))) {
                out.println("PocketBridge " + APP_VERSION + " / Android " + Build.VERSION.RELEASE
                        + " (API " + Build.VERSION.SDK_INT + ") / " + Build.MANUFACTURER + " " + Build.MODEL);
                out.println("Thread: " + thread.getName());
                error.printStackTrace(out);
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    private void copyCrashReport() {
        try (InputStream in = openFileInput("last-crash.txt"); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1 && bytes.size() < 32000) bytes.write(b, 0, n);
            ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clip != null) clip.setPrimaryClip(ClipData.newPlainText("Журнал ошибки", bytes.toString("UTF-8")));
            Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось прочитать журнал", Toast.LENGTH_LONG).show();
        }
    }
}
