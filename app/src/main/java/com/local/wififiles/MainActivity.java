package com.local.wififiles;

import android.Manifest;
import android.annotation.TargetApi;
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
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int BG = 0xff071015;
    private static final int PANEL = 0xff0d1920;
    private static final int PANEL2 = 0xff112028;
    private static final int PANEL3 = 0xff152730;
    private static final int BORDER = 0xff1e333b;
    private static final int BORDER_STRONG = 0xff29454e;
    private static final int WHITE = 0xffecf6f4;
    private static final int SOFT = 0xffb8cecb;
    private static final int MUTED = 0xff7e9a98;
    private static final int MINT = 0xff6ee7c0;
    private static final int MINT_HALO = 0x556ee7c0;
    private static final int MINT_SOFT = 0xff12322c;
    private static final int MINT_BORDER = 0xff23584d;
    private static final int DARK_INK = 0xff08271f;
    private static final int RED = 0xffff9b90;
    private static final int RED_BG = 0xff2d1d1e;
    private static final int RED_BORDER = 0xff593334;
    private static final int AMBER = 0xfff3c969;
    private static final int AMBER_BG = 0xff262114;
    private static final int AMBER_BORDER = 0xff4c3f1f;
    private static final int RIPPLE_LIGHT = 0x26ffffff;
    private static final int RIPPLE_DARK = 0x29071015;

    private static final String APP_VERSION = "3.8";
    private static final String PREF_LANG = "ui_lang";

    private static final int PENDING_NONE = 0;
    private static final int PENDING_STARTING = 1;
    private static final int PENDING_STOPPING = 2;
    private static final int PENDING_ROTATING = 3;

    private LinearLayout pageRoot;
    private View statusDot, statusRing;
    private ProgressBar statusSpinner;
    private TextView brandSub, liveBadge, langButton;
    private TextView heroTitle, heroSub;
    private TextView statusTitle, statusDescription, mainButton;
    private LinearLayout telemetryRow, stepsRow;
    private View telemetryDot;
    private TextView telemetryState, telemetryUptime;
    private TextView stepOne, stepTwo, stepThree;
    private LinearLayout storageCard;
    private TextView storageTitle, storageValue, storagePctBadge;
    private View storageFill, storageRest;
    private LinearLayout permissionCard, permissionGrantedRow;
    private TextView permissionTitle, permissionStatus, permissionButton, permissionOkText;
    private LinearLayout connectionCard, qrSection, errorCard;
    private TextView accessHeading, qrCaption, copyLink, shareLink;
    private TextView addrCaption, addressText, copyAddress;
    private TextView codeCaption, codeText, copyCode, newKey;
    private TextView davCaption, davText, davHint, copyDav;
    private LinearLayout clipExtraRow;
    private View clipOpenSpacer;
    private TextView clipCaption, clipMetaBadge, clipPreview, clipCopyBtn, clipSendBtn, clipOpenUrlBtn, clipClearBtn;
    private TextView settingsTitle, settingsSub;
    private TextView portName, portHint, settingsPort;
    private TextView autoName, autoHint, settingsAutoStop;
    private TextView keepName, keepHint, settingsKeepScreen;
    private TextView securityTitle, securityBody;
    private TextView crashTitle, crashBody, crashCopy, crashClear;

    private ImageView qrImage;
    private Bitmap currentQrBitmap;
    private String lastQrLink = "";
    private ServerService service;
    private boolean bound;
    private boolean resumed;
    private Boolean lastRunningState;
    private int pendingState = PENDING_NONE;
    private String pendingPrevKey = "";
    private TextView flashedCopyView;
    private long flashedCopyUntil;
    private String key = "", firstAddress = "";
    private SharedPreferences prefs;
    private int port;
    private boolean en;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final DecelerateInterpolator easeOut = new DecelerateInterpolator(1.6f);

    private final Runnable clearPendingTask = () -> {
        if (pendingState != PENDING_NONE) {
            pendingState = PENDING_NONE;
            refreshView();
        }
    };

    private final Runnable restoreCopyTask = () -> {
        flashedCopyView = null;
        flashedCopyUntil = 0L;
        refreshView();
    };

    private final Runnable telemetryTickTask = new Runnable() {
        @Override public void run() {
            if (!resumed || isFinishing() || isDestroyed() || !isRunning()) return;
            boolean busy = updateTelemetryStrip();
            pulseStatusRing(busy);
            uiHandler.postDelayed(this, busy ? 850L : 1200L);
        }
    };

    private final Runnable serverListener = () -> runOnUiThread(this::refreshView);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!(binder instanceof ServerService.LocalBinder)) return;
            service = ((ServerService.LocalBinder) binder).service();
            bound = true;
            service.addListener(serverListener);
            refreshView();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            pendingState = PENDING_NONE;
            refreshView();
        }
    };

    @Override @SuppressWarnings("deprecation")
    public void onCreate(Bundle state) {
        super.onCreate(state);
        installCrashRecorder();
        prefs = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        String savedLang = prefString(PREF_LANG, "");
        if ("en".equals(savedLang) || "ru".equals(savedLang)) {
            en = "en".equals(savedLang);
        } else {
            String sys = Locale.getDefault().getLanguage();
            en = !("ru".equals(sys) || "uk".equals(sys) || "be".equals(sys) || "kk".equals(sys));
        }

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setBackgroundColor(BG);

        LinearLayout page = column();
        pageRoot = page;
        int padH = dp(20), padTop = dp(18), padBottom = dp(34);
        page.setPadding(padH, padTop, padH, padBottom);
        scroller.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Api30.applySafePadding(page, insets, padH, padTop, padBottom);
            } else {
                page.setPadding(
                        padH + insets.getSystemWindowInsetLeft(),
                        padTop + insets.getSystemWindowInsetTop(),
                        padH + insets.getSystemWindowInsetRight(),
                        padBottom + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        scroller.addView(page);
        setContentView(scroller);
        applySystemBars(getWindow());

        /* ---------- верхняя панель бренда ---------- */
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        try {
            logo.setImageResource(R.drawable.ic_launcher_foreground);
        } catch (Throwable ignored) {}
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackground(shape(MINT_SOFT, 13, MINT_BORDER));
        top.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout brandWords = column();
        brandWords.setPadding(dp(11), 0, dp(8), 0);
        brandWords.addView(label("PocketBridge", 15, WHITE, true));
        brandSub = label("", 10, MUTED, true);
        brandSub.setLetterSpacing(.11f);
        brandWords.addView(brandSub);
        top.addView(brandWords, new LinearLayout.LayoutParams(0, -2, 1));

        liveBadge = label("", 11, MUTED, true);
        liveBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        liveBadge.setBackground(shape(PANEL, 100, BORDER));
        top.addView(liveBadge);
        spaceH(top, 8);

        langButton = label("", 11, SOFT, true);
        langButton.setGravity(Gravity.CENTER);
        langButton.setPadding(dp(10), dp(6), dp(10), dp(6));
        langButton.setBackground(ripple(PANEL, 10, BORDER, RIPPLE_LIGHT));
        langButton.setClickable(true);
        langButton.setFocusable(true);
        attachPressScale(langButton);
        langButton.setOnClickListener(v -> {
            en = !en;
            prefs.edit().putString(PREF_LANG, en ? "en" : "ru").apply();
            hapticTick(v);
            refreshView();
        });
        top.addView(langButton);
        page.addView(top);
        space(page, 22);

        /* ---------- компактный заголовок ---------- */
        heroTitle = label("", 26, WHITE, true);
        heroTitle.setLineSpacing(dp(1), 1.0f);
        page.addView(heroTitle);
        space(page, 6);
        heroSub = label("", 14, MUTED, false);
        heroSub.setLineSpacing(dp(2), 1.0f);
        page.addView(heroSub);
        space(page, 18);

        /* ---------- карточка статуса, телеметрии и главной кнопки ---------- */
        LinearLayout statusCard = card(PANEL, BORDER);
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(18));

        LinearLayout statusHead = row();
        statusHead.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout beacon = new FrameLayout(this);
        beacon.setClipChildren(false);
        statusRing = new View(this);
        statusRing.setBackground(shape(MINT_HALO, 100, 0));
        statusRing.setAlpha(0f);
        FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER);
        beacon.addView(statusRing, ringLp);
        statusDot = new View(this);
        statusDot.setBackground(shape(MUTED, 100, 0));
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(9), dp(9), Gravity.CENTER);
        beacon.addView(statusDot, dotLp);
        statusHead.addView(beacon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        spaceH(statusHead, 8);
        statusTitle = label("", 16, WHITE, true);
        statusHead.addView(statusTitle, new LinearLayout.LayoutParams(0, -2, 1));

        statusSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        statusSpinner.setIndeterminate(true);
        try { statusSpinner.setIndeterminateTintList(ColorStateList.valueOf(MINT)); } catch (Throwable ignored) {}
        statusSpinner.setVisibility(View.GONE);
        statusHead.addView(statusSpinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
        statusCard.addView(statusHead);

        space(statusCard, 7);
        statusDescription = label("", 13, MUTED, false);
        statusDescription.setLineSpacing(dp(2), 1.0f);
        statusCard.addView(statusDescription);

        // Живая полоса телеметрии (видна при активном сервере)
        telemetryRow = row();
        telemetryRow.setGravity(Gravity.CENTER_VERTICAL);
        telemetryRow.setPadding(dp(12), dp(9), dp(12), dp(9));
        telemetryRow.setBackground(shape(PANEL2, 11, BORDER));
        LinearLayout.LayoutParams teleLp = new LinearLayout.LayoutParams(-1, -2);
        teleLp.topMargin = dp(12);
        telemetryDot = new View(this);
        telemetryDot.setBackground(shape(MINT, 100, 0));
        telemetryRow.addView(telemetryDot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        spaceH(telemetryRow, 8);
        telemetryState = label("", 12, SOFT, true);
        telemetryRow.addView(telemetryState, new LinearLayout.LayoutParams(0, -2, 1));
        spaceH(telemetryRow, 8);
        telemetryUptime = label("", 11, MUTED, true);
        telemetryUptime.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        telemetryRow.addView(telemetryUptime);
        telemetryRow.setVisibility(View.GONE);
        statusCard.addView(telemetryRow, teleLp);

        // Короткая подсказка 1-2-3 в режиме ожидания
        stepsRow = row();
        stepsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams stepsLp = new LinearLayout.LayoutParams(-1, -2);
        stepsLp.topMargin = dp(12);
        stepOne = stepChip();
        stepTwo = stepChip();
        stepThree = stepChip();
        stepsRow.addView(stepOne, new LinearLayout.LayoutParams(0, -2, 1));
        spaceH(stepsRow, 6);
        stepsRow.addView(stepTwo, new LinearLayout.LayoutParams(0, -2, 1));
        spaceH(stepsRow, 6);
        stepsRow.addView(stepThree, new LinearLayout.LayoutParams(0, -2, 1));
        statusCard.addView(stepsRow, stepsLp);

        space(statusCard, 15);
        mainButton = button("", true);
        mainButton.setOnClickListener(v -> {
            hapticTick(v);
            if (!isRunning()) startServer(); else stopServer();
        });
        statusCard.addView(mainButton, new LinearLayout.LayoutParams(-1, dp(50)));
        page.addView(statusCard);
        space(page, 12);

        /* ---------- разрешение на доступ к файлам ---------- */
        permissionCard = card(AMBER_BG, AMBER_BORDER);
        permissionCard.setPadding(dp(18), dp(15), dp(18), dp(16));
        permissionTitle = label("", 15, AMBER, true);
        permissionCard.addView(permissionTitle);
        space(permissionCard, 5);
        permissionStatus = label("", 13, SOFT, false);
        permissionStatus.setLineSpacing(dp(2), 1.0f);
        permissionCard.addView(permissionStatus);
        space(permissionCard, 12);
        permissionButton = button("", false);
        permissionButton.setOnClickListener(v -> { hapticTick(v); requestAccess(); });
        permissionCard.addView(permissionButton, new LinearLayout.LayoutParams(-1, dp(44)));
        page.addView(permissionCard);

        permissionGrantedRow = row();
        permissionGrantedRow.setGravity(Gravity.CENTER_VERTICAL);
        permissionGrantedRow.setPadding(dp(14), dp(10), dp(14), dp(10));
        permissionGrantedRow.setBackground(shape(MINT_SOFT, 14, MINT_BORDER));
        permissionOkText = label("", 12, MINT, true);
        permissionGrantedRow.addView(permissionOkText, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(permissionGrantedRow);
        space(page, 12);

        /* ---------- карточка подключения (когда сервер активен) ---------- */
        connectionCard = card(PANEL, MINT_BORDER);
        connectionCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        accessHeading = caption("");
        accessHeading.setTextColor(MINT);
        connectionCard.addView(accessHeading);
        space(connectionCard, 12);

        // 1. Блок QR + быстрая ссылка и «Поделиться»
        qrSection = column();
        qrSection.setBackground(shape(PANEL2, 14, BORDER));
        qrSection.setPadding(dp(14), dp(16), dp(14), dp(14));
        qrSection.setGravity(Gravity.CENTER_HORIZONTAL);
        qrImage = new ImageView(this);
        qrImage.setAdjustViewBounds(true);
        qrImage.setBackground(shape(0xffffffff, 14, 0));
        qrImage.setPadding(dp(10), dp(10), dp(10), dp(10));
        qrSection.addView(qrImage, new LinearLayout.LayoutParams(dp(188), dp(188)));
        space(qrSection, 10);
        qrCaption = label("", 12, MUTED, false);
        qrCaption.setGravity(Gravity.CENTER);
        qrSection.addView(qrCaption);
        space(qrSection, 12);
        LinearLayout shareRow = row();
        copyLink = chipButton("");
        copyLink.setOnClickListener(v -> copyValue(tr("Ссылка", "Link"), linkWithKey(), copyLink));
        shareRow.addView(copyLink, new LinearLayout.LayoutParams(0, dp(40), 1));
        spaceH(shareRow, 8);
        shareLink = chipButton("");
        shareLink.setOnClickListener(v -> { hapticTick(v); shareLoginLink(); });
        shareRow.addView(shareLink, new LinearLayout.LayoutParams(0, dp(40), 1));
        qrSection.addView(shareRow, new LinearLayout.LayoutParams(-1, -2));
        qrSection.setVisibility(View.GONE);
        connectionCard.addView(qrSection);
        space(connectionCard, 10);

        // 2. Блок адреса в браузере
        LinearLayout addrTile = tile();
        addrCaption = caption("");
        addrTile.addView(addrCaption);
        space(addrTile, 6);
        LinearLayout addrLine = row();
        addrLine.setGravity(Gravity.CENTER_VERTICAL);
        addressText = label("", 15, WHITE, true);
        addressText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        addressText.setTextIsSelectable(true);
        addrLine.addView(addressText, new LinearLayout.LayoutParams(0, -2, 1));
        spaceH(addrLine, 8);
        copyAddress = smallPill("");
        copyAddress.setOnClickListener(v -> copyValue(tr("Адрес", "Address"), firstAddress, copyAddress));
        addrLine.addView(copyAddress);
        addrTile.addView(addrLine);
        connectionCard.addView(addrTile);
        space(connectionCard, 10);

        // 3. Блок кода доступа
        LinearLayout codeTile = tile();
        codeCaption = caption("");
        codeTile.addView(codeCaption);
        space(codeTile, 6);
        codeText = label("", 19, MINT, true);
        codeText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeText.setLetterSpacing(.06f);
        codeText.setTextIsSelectable(true);
        codeTile.addView(codeText);
        space(codeTile, 10);
        LinearLayout codeBtns = row();
        copyCode = chipButton("");
        copyCode.setOnClickListener(v -> copyValue(tr("Код", "Code"), key, copyCode));
        codeBtns.addView(copyCode, new LinearLayout.LayoutParams(0, dp(38), 1));
        spaceH(codeBtns, 8);
        newKey = chipButton("");
        newKey.setOnClickListener(v -> { hapticTick(v); changeCode(); });
        codeBtns.addView(newKey, new LinearLayout.LayoutParams(0, dp(38), 1));
        codeTile.addView(codeBtns);
        connectionCard.addView(codeTile);
        space(connectionCard, 10);

        // 4. Блок WebDAV (сетевой диск)
        LinearLayout davTile = tile();
        davCaption = caption("");
        davTile.addView(davCaption);
        space(davTile, 6);
        LinearLayout davLine = row();
        davLine.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout davTexts = column();
        davText = label("", 13, SOFT, true);
        davText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        davText.setTextIsSelectable(true);
        davTexts.addView(davText);
        space(davTexts, 2);
        davHint = label("", 11, MUTED, false);
        davTexts.addView(davHint);
        davLine.addView(davTexts, new LinearLayout.LayoutParams(0, -2, 1));
        spaceH(davLine, 8);
        copyDav = smallPill("");
        copyDav.setOnClickListener(v -> copyValue("WebDAV", davUrl(), copyDav));
        davLine.addView(copyDav);
        davTile.addView(davLine);
        connectionCard.addView(davTile);
        space(connectionCard, 10);

        // 5. Блок общего текстового буфера (ПК ↔ телефон)
        LinearLayout clipTile = tile();
        LinearLayout clipHead = row();
        clipHead.setGravity(Gravity.CENTER_VERTICAL);
        clipCaption = caption("");
        clipHead.addView(clipCaption, new LinearLayout.LayoutParams(0, -2, 1));
        clipMetaBadge = label("", 10, MINT, true);
        clipMetaBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        clipMetaBadge.setBackground(shape(MINT_SOFT, 100, MINT_BORDER));
        clipMetaBadge.setVisibility(View.GONE);
        clipHead.addView(clipMetaBadge);
        clipTile.addView(clipHead);
        space(clipTile, 7);
        clipPreview = label("", 13, MUTED, false);
        clipPreview.setLineSpacing(dp(2), 1.0f);
        clipPreview.setMaxLines(4);
        clipPreview.setEllipsize(TextUtils.TruncateAt.END);
        clipTile.addView(clipPreview);
        space(clipTile, 10);
        LinearLayout clipBtns = row();
        clipCopyBtn = chipButton("");
        clipCopyBtn.setOnClickListener(v -> {
            String cur = service != null ? service.clipboardText() : "";
            copyValue(tr("Буфер", "Clipboard"), cur, clipCopyBtn);
        });
        clipBtns.addView(clipCopyBtn, new LinearLayout.LayoutParams(0, dp(38), 1));
        spaceH(clipBtns, 8);
        clipSendBtn = chipButton("");
        clipSendBtn.setOnClickListener(v -> { hapticTick(v); openSharedClipboardDialog(); });
        clipBtns.addView(clipSendBtn, new LinearLayout.LayoutParams(0, dp(38), 1));
        clipTile.addView(clipBtns);

        clipExtraRow = row();
        LinearLayout.LayoutParams clipExtraLp = new LinearLayout.LayoutParams(-1, -2);
        clipExtraLp.topMargin = dp(8);
        clipOpenUrlBtn = smallPill("");
        clipOpenUrlBtn.setOnClickListener(v -> { hapticTick(v); openSharedClipboardUrl(); });
        clipExtraRow.addView(clipOpenUrlBtn, new LinearLayout.LayoutParams(0, dp(36), 1));
        clipOpenSpacer = new View(this);
        clipExtraRow.addView(clipOpenSpacer, new LinearLayout.LayoutParams(dp(8), 1));
        clipClearBtn = chipButton("");
        clipClearBtn.setOnClickListener(v -> {
            hapticTick(v);
            if (service != null) {
                service.setPhoneClipboard("");
                Toast.makeText(this, tr("Общий буфер очищен", "Shared clipboard cleared"), Toast.LENGTH_SHORT).show();
            }
        });
        clipExtraRow.addView(clipClearBtn, new LinearLayout.LayoutParams(0, dp(36), 1));
        clipExtraRow.setVisibility(View.GONE);
        clipTile.addView(clipExtraRow, clipExtraLp);
        connectionCard.addView(clipTile);

        connectionCard.setVisibility(View.GONE);
        page.addView(connectionCard);
        space(page, 12);

        /* ---------- индикатор памяти телефона ---------- */
        storageCard = card(PANEL, BORDER);
        storageCard.setPadding(dp(18), dp(14), dp(18), dp(15));
        LinearLayout stHead = row();
        stHead.setGravity(Gravity.CENTER_VERTICAL);
        storageTitle = label("", 13, WHITE, true);
        stHead.addView(storageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        storagePctBadge = label("", 11, MINT, true);
        storagePctBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        storagePctBadge.setBackground(shape(MINT_SOFT, 100, MINT_BORDER));
        stHead.addView(storagePctBadge);
        storageCard.addView(stHead);
        space(storageCard, 9);
        LinearLayout stTrack = row();
        stTrack.setBackground(shape(PANEL3, 100, BORDER));
        stTrack.setPadding(dp(1), dp(1), dp(1), dp(1));
        storageFill = new View(this);
        storageFill.setBackground(shape(MINT, 100, 0));
        storageRest = new View(this);
        stTrack.addView(storageFill, new LinearLayout.LayoutParams(0, -1, 0f));
        stTrack.addView(storageRest, new LinearLayout.LayoutParams(0, -1, 1000f));
        storageCard.addView(stTrack, new LinearLayout.LayoutParams(-1, dp(8)));
        space(storageCard, 7);
        storageValue = label("", 12, MUTED, false);
        storageCard.addView(storageValue);
        page.addView(storageCard);
        space(page, 12);

        /* ---------- карточка настроек ---------- */
        LinearLayout settingsCard = card(PANEL, BORDER);
        settingsCard.setPadding(dp(18), dp(16), dp(18), dp(10));
        settingsTitle = label("", 16, WHITE, true);
        settingsCard.addView(settingsTitle);
        space(settingsCard, 3);
        settingsSub = label("", 12, MUTED, false);
        settingsCard.addView(settingsSub);
        space(settingsCard, 10);

        TextView[] r1 = addSettingRow(settingsCard, this::editPort, true);
        portName = r1[0]; portHint = r1[1]; settingsPort = r1[2];

        TextView[] r2 = addSettingRow(settingsCard, this::editAutoStop, true);
        autoName = r2[0]; autoHint = r2[1]; settingsAutoStop = r2[2];

        TextView[] r3 = addSettingRow(settingsCard, this::toggleKeepScreen, false);
        keepName = r3[0]; keepHint = r3[1]; settingsKeepScreen = r3[2];

        page.addView(settingsCard);
        space(page, 18);

        /* ---------- подвал безопасности ---------- */
        securityTitle = caption("");
        page.addView(securityTitle);
        space(page, 6);
        securityBody = label("", 12, MUTED, false);
        securityBody.setLineSpacing(dp(2.5f), 1.0f);
        page.addView(securityBody);

        /* ---------- карточка сбоя (если был в текущей версии) ---------- */
        File crashFile = new File(getFilesDir(), "last-crash.txt");
        if (shouldShowCrashReport(crashFile)) {
            space(page, 18);
            errorCard = card(RED_BG, RED_BORDER);
            errorCard.setPadding(dp(16), dp(14), dp(16), dp(14));
            crashTitle = label("", 14, RED, true);
            errorCard.addView(crashTitle);
            space(errorCard, 5);
            crashBody = label("", 12, SOFT, false);
            errorCard.addView(crashBody);
            space(errorCard, 10);
            LinearLayout cBtns = row();
            crashCopy = chipButton("");
            crashCopy.setOnClickListener(v -> copyCrashReport());
            cBtns.addView(crashCopy, new LinearLayout.LayoutParams(0, dp(38), 1));
            spaceH(cBtns, 8);
            crashClear = chipButton("");
            crashClear.setOnClickListener(v -> {
                //noinspection ResultOfMethodCallIgnored
                crashFile.delete();
                if (errorCard != null) {
                    beginSmoothLayout();
                    errorCard.setVisibility(View.GONE);
                }
            });
            cBtns.addView(crashClear, new LinearLayout.LayoutParams(0, dp(38), 1));
            errorCard.addView(cBtns);
            page.addView(errorCard);
        }

        refreshView();
        runEntranceAnimation(top, statusCard, settingsCard);
    }

    @Override protected void onStart() {
        super.onStart();
        try {
            bindService(new Intent(this, ServerService.class), connection, BIND_AUTO_CREATE);
        } catch (Exception ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshView();
        syncTicker();
    }

    @Override protected void onPause() {
        resumed = false;
        stopLiveAnimations();
        super.onPause();
    }

    @Override protected void onStop() {
        resumed = false;
        uiHandler.removeCallbacksAndMessages(null);
        stopLiveAnimations();
        if (bound) {
            try { if (service != null) service.removeListener(serverListener); } catch (Exception ignored) {}
            try { unbindService(connection); } catch (Exception ignored) {}
            bound = false;
            service = null;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        resumed = false;
        uiHandler.removeCallbacksAndMessages(null);
        stopLiveAnimations();
        if (currentQrBitmap != null && !currentQrBitmap.isRecycled()) {
            qrImage.setImageBitmap(null);
            currentQrBitmap.recycle();
            currentQrBitmap = null;
        }
        super.onDestroy();
    }

    private boolean animationsEnabled() {
        try {
            return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0.01f;
        } catch (Throwable e) {
            return false;
        }
    }

    private void runEntranceAnimation(View... views) {
        if (!animationsEnabled()) return;
        try {
            float shift = dp(8);
            for (int i = 0; i < views.length; i++) {
                View v = views[i];
                if (v == null || v.getVisibility() != View.VISIBLE) continue;
                v.setAlpha(0f);
                v.setTranslationY(shift);
                v.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setStartDelay(i * 35L)
                        .setDuration(210L)
                        .setInterpolator(easeOut)
                        .start();
            }
        } catch (Throwable ignored) {
            for (View v : views) {
                if (v != null) { v.setAlpha(1f); v.setTranslationY(0f); }
            }
        }
    }

    private void beginSmoothLayout() {
        if (!resumed || pageRoot == null || !animationsEnabled()) return;
        try {
            AutoTransition t = new AutoTransition();
            t.setDuration(180L);
            t.setInterpolator(easeOut);
            TransitionManager.beginDelayedTransition(pageRoot, t);
        } catch (Throwable ignored) {}
    }

    private void attachPressScale(View view) {
        view.setOnTouchListener((v, ev) -> {
            if (!v.isEnabled() || !animationsEnabled()) return false;
            int a = ev.getActionMasked();
            try {
                if (a == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.975f).scaleY(0.975f).setDuration(85L).setInterpolator(easeOut).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(130L).setInterpolator(easeOut).start();
                }
            } catch (Throwable ignored) {}
            return false;
        });
    }

    private static void hapticTick(View v) {
        if (v == null) return;
        try { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignored) {}
    }

    private void syncTicker() {
        uiHandler.removeCallbacks(telemetryTickTask);
        if (resumed && isRunning()) {
            uiHandler.postDelayed(telemetryTickTask, 950L);
        } else if (statusRing != null) {
            try { statusRing.animate().cancel(); } catch (Throwable ignored) {}
            statusRing.setAlpha(0f);
            statusRing.setScaleX(1f);
            statusRing.setScaleY(1f);
        }
    }

    private void stopLiveAnimations() {
        uiHandler.removeCallbacks(telemetryTickTask);
        if (statusRing != null) {
            try { statusRing.animate().cancel(); } catch (Throwable ignored) {}
            statusRing.setAlpha(0f);
            statusRing.setScaleX(1f);
            statusRing.setScaleY(1f);
        }
        if (qrImage != null) {
            try { qrImage.animate().cancel(); } catch (Throwable ignored) {}
            qrImage.setAlpha(1f);
            qrImage.setScaleX(1f);
            qrImage.setScaleY(1f);
        }
    }

    private void pulseStatusRing(boolean busy) {
        if (statusRing == null || !resumed || !animationsEnabled()) return;
        try {
            statusRing.animate().cancel();
            statusRing.setScaleX(0.75f);
            statusRing.setScaleY(0.75f);
            statusRing.setAlpha(busy ? 0.85f : 0.62f);
            statusRing.animate()
                    .scaleX(busy ? 1.38f : 1.24f)
                    .scaleY(busy ? 1.38f : 1.24f)
                    .alpha(0.08f)
                    .setDuration(busy ? 560L : 780L)
                    .setInterpolator(easeOut)
                    .start();
        } catch (Throwable ignored) {}
    }

    private void setPending(int state) {
        pendingState = state;
        uiHandler.removeCallbacks(clearPendingTask);
        if (state != PENDING_NONE) {
            uiHandler.postDelayed(clearPendingTask, 4500L);
        }
        refreshView();
    }

    private String tr(String ru, String eng) { return en ? eng : ru; }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private Drawable ripple(int fill, int radius, int stroke, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), shape(fill, radius, stroke), shape(0xffffffff, radius, 0));
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout card(int fill, int stroke) { LinearLayout l = column(); l.setBackground(shape(fill, 18, stroke)); return l; }
    private LinearLayout tile() {
        LinearLayout l = column();
        l.setBackground(shape(PANEL2, 13, BORDER));
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        return l;
    }
    private TextView stepChip() {
        TextView t = label("", 11, SOFT, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(6), dp(7), dp(6), dp(7));
        t.setBackground(shape(PANEL2, 9, BORDER));
        return t;
    }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private TextView caption(String text) {
        TextView t = label(text, 10, MUTED, true);
        t.setLetterSpacing(.11f);
        return t;
    }
    private TextView button(String text, boolean primary) {
        TextView t = label(text, 14, primary ? DARK_INK : WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setBackground(ripple(primary ? MINT : PANEL3, 13, primary ? 0 : BORDER_STRONG, primary ? RIPPLE_DARK : RIPPLE_LIGHT));
        t.setClickable(true);
        t.setFocusable(true);
        attachPressScale(t);
        return t;
    }
    private TextView chipButton(String text) {
        TextView t = label(text, 12, WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setBackground(ripple(PANEL3, 10, BORDER_STRONG, RIPPLE_LIGHT));
        t.setClickable(true);
        t.setFocusable(true);
        attachPressScale(t);
        return t;
    }
    private TextView smallPill(String text) {
        TextView t = label(text, 12, MINT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(ripple(MINT_SOFT, 10, MINT_BORDER, RIPPLE_LIGHT));
        t.setClickable(true);
        t.setFocusable(true);
        attachPressScale(t);
        return t;
    }
    private void space(LinearLayout parent, float size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private void spaceH(LinearLayout parent, float size) { parent.addView(new View(this), new LinearLayout.LayoutParams(dp(size), 1)); }

    private TextView[] addSettingRow(LinearLayout parent, Runnable action, boolean withDivider) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(6), dp(11), dp(6), dp(11));
        r.setBackground(ripple(0x00000000, 10, 0, RIPPLE_LIGHT));
        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(v -> { hapticTick(v); action.run(); });

        LinearLayout texts = column();
        TextView name = label("", 14, WHITE, true);
        TextView hint = label("", 11, MUTED, false);
        texts.addView(name);
        space(texts, 2);
        texts.addView(hint);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        spaceH(r, 8);

        TextView value = label("", 12, MINT, true);
        value.setPadding(dp(10), dp(5), dp(10), dp(5));
        value.setBackground(shape(MINT_SOFT, 8, MINT_BORDER));
        r.addView(value);

        TextView chevron = label("  ›", 16, MUTED, false);
        r.addView(chevron);
        parent.addView(r);

        if (withDivider) {
            View divider = new View(this);
            divider.setBackgroundColor(BORDER);
            parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        return new TextView[]{ name, hint, value };
    }

    private boolean isRunning() { return service != null && service.isRunning(); }

    private boolean updateTelemetryStrip() {
        if (telemetryRow == null || service == null || !service.isRunning()) return false;
        int conns = service.activeConnections();
        int uploads = service.activeUploads();
        boolean recent = service.isClientActive();
        long upMs = service.uptimeMillis();

        boolean busy = uploads > 0 || conns > 0;
        if (uploads > 0) {
            telemetryDot.setBackground(shape(MINT, 100, 0));
            telemetryState.setTextColor(MINT);
            telemetryState.setText(tr("Загрузка файлов: " + uploads, "Uploading files: " + uploads));
        } else if (conns > 0) {
            telemetryDot.setBackground(shape(MINT, 100, 0));
            telemetryState.setTextColor(MINT);
            telemetryState.setText(tr("Передача данных · потоков: " + conns, "Active transfer · " + conns));
        } else if (recent) {
            telemetryDot.setBackground(shape(MINT, 100, 0));
            telemetryState.setTextColor(SOFT);
            telemetryState.setText(tr("Клиент подключён", "Client connected"));
        } else {
            telemetryDot.setBackground(shape(MUTED, 100, 0));
            telemetryState.setTextColor(MUTED);
            telemetryState.setText(tr("Готов к подключению", "Waiting for browser"));
        }
        telemetryUptime.setText(formatDuration(upMs));
        return busy;
    }

    private static String formatDuration(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private String formatBytes(long bytes) {
        String[] units = en
                ? new String[]{ "B", "KB", "MB", "GB", "TB" }
                : new String[]{ "Б", "КБ", "МБ", "ГБ", "ТБ" };
        double v = Math.max(0.0, (double) bytes);
        int i = 0;
        while (v >= 1024.0 && i < units.length - 1) { v /= 1024.0; i++; }
        if (i == 0) return Math.round(v) + " " + units[0];
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }

    private void refreshStorageCard() {
        if (storageCard == null) return;
        storageTitle.setText(tr("Память телефона", "Phone storage"));
        try {
            File dir = Environment.getExternalStorageDirectory();
            if (dir == null) { storageCard.setVisibility(View.GONE); return; }
            StatFs stat = new StatFs(dir.getPath());
            long total = stat.getTotalBytes();
            long free = Math.max(0L, Math.min(total, stat.getAvailableBytes()));
            if (total <= 0L) { storageCard.setVisibility(View.GONE); return; }
            long used = Math.max(0L, total - free);
            int pct = (int) Math.min(100L, Math.max(0L, Math.round((used * 100.0) / total)));
            float usedWeight = Math.min(1000f, Math.max(pct > 0 ? 18f : 0f, (float) ((used * 1000.0) / total)));

            int barColor = pct >= 91 ? RED : (pct >= 78 ? AMBER : MINT);
            int badgeBg = pct >= 91 ? RED_BG : (pct >= 78 ? AMBER_BG : MINT_SOFT);
            int badgeBd = pct >= 91 ? RED_BORDER : (pct >= 78 ? AMBER_BORDER : MINT_BORDER);

            storageFill.setBackground(shape(barColor, 100, 0));
            LinearLayout.LayoutParams fillLp = (LinearLayout.LayoutParams) storageFill.getLayoutParams();
            LinearLayout.LayoutParams restLp = (LinearLayout.LayoutParams) storageRest.getLayoutParams();
            fillLp.weight = usedWeight;
            restLp.weight = Math.max(0f, 1000f - usedWeight);
            storageFill.setLayoutParams(fillLp);
            storageRest.setLayoutParams(restLp);

            storagePctBadge.setText(pct + "%");
            storagePctBadge.setTextColor(barColor);
            storagePctBadge.setBackground(shape(badgeBg, 100, badgeBd));
            storageValue.setText(en
                    ? (formatBytes(free) + " free of " + formatBytes(total))
                    : ("Свободно " + formatBytes(free) + " из " + formatBytes(total)));
            storageCard.setVisibility(View.VISIBLE);
        } catch (Throwable e) {
            storageCard.setVisibility(View.GONE);
        }
    }

    private void applyCopyLabel(TextView btn, String normalText, boolean pill) {
        boolean flashed = btn == flashedCopyView && SystemClock.uptimeMillis() < flashedCopyUntil;
        if (flashed) {
            btn.setText(tr("✓ Скопировано", "✓ Copied"));
            btn.setTextColor(MINT);
            btn.setBackground(ripple(MINT_SOFT, 10, MINT, RIPPLE_LIGHT));
        } else {
            btn.setText(normalText);
            btn.setTextColor(pill ? MINT : WHITE);
            btn.setBackground(ripple(pill ? MINT_SOFT : PANEL3, 10, pill ? MINT_BORDER : BORDER_STRONG, RIPPLE_LIGHT));
        }
    }

    private void refreshView() {
        if (statusTitle == null) return;
        boolean running = isRunning();
        boolean allowed = hasAccess();
        String lastError = service != null ? service.lastError() : "";
        String svcKey = (running && service != null) ? service.accessKey() : "";

        // Сбрасываем промежуточные индикаторы, как только сервис подтвердил переход.
        if (pendingState == PENDING_STARTING && (running || !lastError.isEmpty())) {
            pendingState = PENDING_NONE;
            uiHandler.removeCallbacks(clearPendingTask);
        } else if (pendingState == PENDING_STOPPING && !running) {
            pendingState = PENDING_NONE;
            uiHandler.removeCallbacks(clearPendingTask);
        } else if (pendingState == PENDING_ROTATING && (!running || (!svcKey.isEmpty() && !svcKey.equals(pendingPrevKey)))) {
            pendingState = PENDING_NONE;
            uiHandler.removeCallbacks(clearPendingTask);
        }

        if (lastRunningState != null && lastRunningState != running) {
            beginSmoothLayout();
        }
        lastRunningState = running;

        boolean starting = pendingState == PENDING_STARTING;
        boolean stopping = pendingState == PENDING_STOPPING;
        boolean rotating = pendingState == PENDING_ROTATING;
        boolean busyTransition = starting || stopping || rotating;
        boolean hasErr = !running && !starting && !lastError.isEmpty();

        brandSub.setText(tr("WI-FI ПРОВОДНИК · v" + APP_VERSION, "WI-FI EXPLORER · v" + APP_VERSION));
        langButton.setText(en ? "EN" : "RU");
        if (starting) {
            liveBadge.setText(tr("◌ Запуск…", "◌ Starting…"));
            liveBadge.setTextColor(AMBER);
            liveBadge.setBackground(shape(AMBER_BG, 100, AMBER_BORDER));
        } else if (stopping) {
            liveBadge.setText(tr("◌ Стоп…", "◌ Stopping…"));
            liveBadge.setTextColor(AMBER);
            liveBadge.setBackground(shape(AMBER_BG, 100, AMBER_BORDER));
        } else {
            liveBadge.setText(running ? tr("● В сети", "● Online") : tr("○ Пауза", "○ Idle"));
            liveBadge.setTextColor(running ? MINT : MUTED);
            liveBadge.setBackground(shape(running ? MINT_SOFT : PANEL, 100, running ? MINT_BORDER : BORDER));
        }

        heroTitle.setText(tr("Файлы рядом. Без проводов.", "Files at hand. Wirelessly."));
        heroSub.setText(tr(
                "Откройте хранилище телефона в браузере компьютера или подключите как сетевой диск.",
                "Browse your phone storage in a desktop browser or mount it as a network drive."));

        statusSpinner.setVisibility(busyTransition ? View.VISIBLE : View.GONE);
        statusDot.setBackground(shape(
                (starting || stopping) ? AMBER : (running ? MINT : (hasErr ? RED : MUTED)), 100, 0));
        statusRing.setAlpha(running && !stopping ? 0.36f : 0f);

        if (starting) {
            statusTitle.setText(tr("Запускаем сервер…", "Starting server…"));
            statusTitle.setTextColor(AMBER);
        } else if (stopping) {
            statusTitle.setText(tr("Останавливаем доступ…", "Stopping access…"));
            statusTitle.setTextColor(AMBER);
        } else {
            statusTitle.setText(running ? tr("Доступ открыт", "Access is active") : tr("Доступ остановлен", "Access is stopped"));
            statusTitle.setTextColor(running ? MINT : WHITE);
        }

        int autoStopMin = prefInt(ServerService.PREF_AUTOSTOP, ServerService.DEFAULT_AUTOSTOP);
        String autoNote = autoStopMin <= 0
                ? tr("Автостоп выключен.", "Auto-stop disabled.")
                : tr("Автостоп через " + autoStopMin + " мин простоя.", "Auto-stops after " + autoStopMin + " min idle.");
        if (starting) {
            statusDescription.setText(tr("Поднимаем локальный HTTPS и готовим одноразовый код входа…",
                    "Preparing local HTTPS certificate and a fresh access code…"));
            statusDescription.setTextColor(SOFT);
        } else {
            statusDescription.setText(running
                    ? tr("Защищено HTTPS · работает в фоне. ", "HTTPS encrypted · runs in background. ") + autoNote
                    : (hasErr ? lastError : tr("Подключите телефон и компьютер к одной сети Wi-Fi и нажмите кнопку ниже.",
                            "Connect your phone and computer to the same Wi-Fi network and tap Start below.")));
            statusDescription.setTextColor(hasErr ? RED : MUTED);
        }

        telemetryRow.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) updateTelemetryStrip();

        boolean showSteps = !running && !starting && !hasErr;
        stepsRow.setVisibility(showSteps ? View.VISIBLE : View.GONE);
        if (showSteps) {
            stepOne.setText(tr("1 · Общий Wi-Fi", "1 · Same Wi-Fi"));
            stepTwo.setText(tr("2 · Запуск", "2 · Tap Start"));
            stepThree.setText(tr("3 · QR / браузер", "3 · Scan QR"));
        }

        if (starting) {
            mainButton.setText(tr("Запуск HTTPS…", "Starting HTTPS…"));
            mainButton.setTextColor(MINT);
            mainButton.setBackground(ripple(MINT_SOFT, 13, MINT_BORDER, RIPPLE_LIGHT));
            setControlEnabled(mainButton, false);
        } else if (stopping) {
            mainButton.setText(tr("Остановка…", "Stopping…"));
            mainButton.setTextColor(AMBER);
            mainButton.setBackground(ripple(AMBER_BG, 13, AMBER_BORDER, RIPPLE_LIGHT));
            setControlEnabled(mainButton, false);
        } else {
            mainButton.setText(running ? tr("Остановить доступ", "Stop access") : tr("Запустить доступ  →", "Start access  →"));
            mainButton.setTextColor(running ? RED : DARK_INK);
            mainButton.setBackground(ripple(running ? RED_BG : MINT, 13, running ? RED_BORDER : 0, running ? RIPPLE_LIGHT : RIPPLE_DARK));
            setControlEnabled(mainButton, true);
        }

        permissionCard.setVisibility(allowed ? View.GONE : View.VISIBLE);
        permissionGrantedRow.setVisibility(allowed ? View.VISIBLE : View.GONE);
        permissionTitle.setText(tr("Нужен доступ к файлам", "Storage access required"));
        permissionStatus.setText(tr(
                "Разрешите доступ к общему хранилищу, чтобы видеть и передавать файлы телефона.",
                "Grant all-files access so PocketBridge can list, preview, and transfer your files."));
        permissionButton.setText(tr("Открыть настройки доступа", "Grant storage access"));
        permissionOkText.setText(tr("✓  Доступ к файлам телефона разрешён", "✓  Phone storage access granted"));

        accessHeading.setText(tr("ПОДКЛЮЧЕНИЕ С КОМПЬЮТЕРА", "CONNECT FROM COMPUTER"));
        qrImage.setContentDescription(tr("QR-код для быстрого входа", "QR code for instant login"));
        qrCaption.setText(tr("Наведите камеру — вход без ввода кода", "Scan to open and sign in automatically"));
        applyCopyLabel(copyLink, tr("Ссылка с кодом", "Copy login link"), false);
        shareLink.setText(tr("Поделиться", "Share link"));

        addrCaption.setText(tr("АДРЕС В БРАУЗЕРЕ", "BROWSER ADDRESS"));
        applyCopyLabel(copyAddress, tr("Копировать", "Copy"), true);

        codeCaption.setText(tr("КОД ДОСТУПА", "ACCESS CODE"));
        applyCopyLabel(copyCode, tr("Скопировать код", "Copy code"), false);
        newKey.setText(rotating ? tr("Обновляем…", "Rotating…") : tr("Сменить код", "New code"));
        setControlEnabled(newKey, !rotating);

        davCaption.setText(tr("СЕТЕВОЙ ДИСК (WEBDAV)", "NETWORK DRIVE (WEBDAV)"));
        davHint.setText(tr("Логин любой · пароль — код доступа", "Any username · password is the access code"));
        applyCopyLabel(copyDav, tr("Копировать", "Copy"), true);

        clipCaption.setText(tr("ОБЩИЙ БУФЕР (ПК ↔ ТЕЛЕФОН)", "SHARED CLIPBOARD (PC ↔ PHONE)"));
        refreshSharedClipboardTile(running);

        settingsTitle.setText(tr("Настройки", "Settings"));
        settingsSub.setText(tr("Применяются сразу; активный сервер перезапустится.",
                "Applied immediately; a running server restarts automatically."));
        portName.setText(tr("Порт HTTPS", "HTTPS port"));
        portHint.setText(tr("По умолчанию " + ServerService.DEFAULT_PORT, "Default " + ServerService.DEFAULT_PORT));
        autoName.setText(tr("Автостоп", "Auto-stop"));
        autoHint.setText(tr("Выключать без активности", "Stop when idle"));
        keepName.setText(tr("Не гасить экран", "Keep screen awake"));
        keepHint.setText(tr("Пока открыто окно PocketBridge", "While PocketBridge is open"));

        securityTitle.setText(tr("БЕЗОПАСНОСТЬ", "SECURITY"));
        securityBody.setText(tr(
                "Трафик шифруется локальным сертификатом телефона (HTTPS): при первом входе браузер попросит подтвердить исключение — это нормально. Работайте только в доверенной сети Wi-Fi.",
                "Traffic is encrypted with a device-generated HTTPS certificate: accept the one-time browser warning on first connect. Use trusted Wi-Fi networks only."));

        if (errorCard != null) {
            crashTitle.setText(tr("Журнал последнего сбоя", "Last crash log"));
            crashBody.setText(tr("Скопируйте журнал для диагностики (может содержать пути к файлам).",
                    "Copy the log for troubleshooting (may include file paths)."));
            crashCopy.setText(tr("Скопировать", "Copy log"));
            crashClear.setText(tr("Скрыть", "Dismiss"));
        }

        connectionCard.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) {
            key = svcKey;
            port = service.port();
            List<String> ips = ipAddresses();
            boolean hasIp = !ips.isEmpty();
            firstAddress = hasIp ? "https://" + ips.get(0) + ":" + port : "";
            if (!hasIp) {
                addressText.setText(tr("Нет Wi-Fi адреса — проверьте сеть", "No Wi-Fi IP — check connection"));
                davText.setText("—");
            } else {
                List<String> urls = new ArrayList<>();
                for (String ip : ips) urls.add("https://" + ip + ":" + port);
                addressText.setText(String.join("\n", urls));
                davText.setText(davUrl());
            }
            codeText.setText(key);
            codeText.setAlpha(rotating ? 0.5f : 1f);
            setControlEnabled(copyAddress, hasIp);
            setControlEnabled(copyLink, hasIp);
            setControlEnabled(shareLink, hasIp);
            setControlEnabled(copyDav, hasIp);
            updateQrCode(hasIp ? linkWithKey() : "");
            if (rotating && qrSection.getVisibility() == View.VISIBLE) {
                qrImage.setAlpha(0.45f);
            }
            if (prefBool(ServerService.PREF_KEEP_SCREEN, true)) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } else {
            updateQrCode("");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        refreshStorageCard();
        refreshSettings();
        syncTicker();
    }

    private static void setControlEnabled(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.42f);
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
                if (resumed && animationsEnabled()) {
                    try {
                        qrImage.animate().cancel();
                        qrImage.setAlpha(0.25f);
                        qrImage.setScaleX(0.96f);
                        qrImage.setScaleY(0.96f);
                        qrImage.animate().alpha(1f).scaleX(1f).scaleY(1f)
                                .setDuration(200L).setInterpolator(easeOut).start();
                    } catch (Throwable ignored) {
                        qrImage.setAlpha(1f);
                        qrImage.setScaleX(1f);
                        qrImage.setScaleY(1f);
                    }
                } else {
                    qrImage.setAlpha(1f);
                }
            } else {
                qrSection.setVisibility(View.GONE);
            }
        } else {
            qrSection.setVisibility(View.VISIBLE);
            qrImage.setAlpha(1f);
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
                    pixels[rowOffset + x] = matrix.get(x, y) ? 0xff071015 : 0xffffffff;
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

    private String davUrl() {
        if (firstAddress == null || firstAddress.isEmpty()) return "";
        return firstAddress + "/dav/";
    }

    private static String extractSingleHttpUrl(String text) {
        if (text == null) return "";
        String s = text.trim();
        if (s.length() < 10 || s.length() > 4096) return "";
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return "";
        }
        String low = s.toLowerCase(Locale.US);
        if (!low.startsWith("https://") && !low.startsWith("http://")) return "";
        return s;
    }

    private void refreshSharedClipboardTile(boolean running) {
        if (clipPreview == null) return;
        String text = (running && service != null) ? service.clipboardText() : "";
        long updatedAt = (running && service != null) ? service.clipboardUpdatedAt() : 0L;
        String src = (running && service != null) ? service.clipboardSource() : "";
        boolean hasText = text != null && !text.isEmpty();

        if (hasText && updatedAt > 0L) {
            String hhmm = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(updatedAt));
            boolean fromWeb = "web".equals(src);
            clipMetaBadge.setText(fromWeb
                    ? tr("Из браузера · " + hhmm, "From browser · " + hhmm)
                    : tr("С телефона · " + hhmm, "From phone · " + hhmm));
            clipMetaBadge.setTextColor(fromWeb ? MINT : SOFT);
            clipMetaBadge.setBackground(shape(fromWeb ? MINT_SOFT : PANEL3, 100, fromWeb ? MINT_BORDER : BORDER_STRONG));
            clipMetaBadge.setVisibility(View.VISIBLE);
        } else {
            clipMetaBadge.setVisibility(View.GONE);
        }

        if (hasText) {
            clipPreview.setText(text.length() > 600 ? text.substring(0, 600) + "…" : text);
            clipPreview.setTextColor(WHITE);
        } else {
            clipPreview.setText(tr(
                    "Пересылайте ссылки, коды и заметки между браузером и телефоном — только в памяти сессии.",
                    "Send links, codes, and notes between browser and phone — kept in memory only."));
            clipPreview.setTextColor(MUTED);
        }

        applyCopyLabel(clipCopyBtn, tr("Копировать", "Copy"), false);
        setControlEnabled(clipCopyBtn, hasText);
        clipSendBtn.setText(hasText ? tr("Изменить", "Edit") : tr("Отправить", "Send"));

        if (hasText) {
            String url = extractSingleHttpUrl(text);
            boolean hasUrl = !url.isEmpty();
            clipOpenUrlBtn.setText(tr("Открыть ссылку ↗", "Open link ↗"));
            clipOpenUrlBtn.setVisibility(hasUrl ? View.VISIBLE : View.GONE);
            clipOpenSpacer.setVisibility(hasUrl ? View.VISIBLE : View.GONE);
            clipClearBtn.setText(tr("Очистить", "Clear"));
            clipExtraRow.setVisibility(View.VISIBLE);
        } else {
            clipExtraRow.setVisibility(View.GONE);
        }
    }

    private void openSharedClipboardUrl() {
        if (!isRunning() || service == null) return;
        String url = extractSingleHttpUrl(service.clipboardText());
        if (url.isEmpty()) return;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(view);
        } catch (Exception e) {
            Toast.makeText(this, tr("Не удалось открыть ссылку", "Could not open link"), Toast.LENGTH_SHORT).show();
        }
    }

    private void openSharedClipboardDialog() {
        if (isFinishing() || isDestroyed() || !isRunning() || service == null) return;
        LinearLayout box = column();
        box.setPadding(dp(22), dp(12), dp(22), dp(4));
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        input.setHint(tr("Ссылка, код или заметка для браузера…", "Link, code, or note for browser…"));
        input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(FileServer.CLIPBOARD_MAX_CHARS) });
        String cur = service.clipboardText();
        if (cur != null && !cur.isEmpty()) {
            input.setText(cur);
            input.setSelection(input.getText().length());
        }
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(tr("Общий буфер обмена", "Shared clipboard"))
                .setMessage(tr("Текст станет сразу доступен по кнопке «Буфер» в браузере и очистится при остановке доступа.",
                        "Text appears immediately under “Clipboard” in the browser and clears when access stops."))
                .setView(box)
                .setPositiveButton(tr("Отправить", "Send"), (d, which) -> {
                    if (service == null || !service.isRunning()) return;
                    String next = input.getText() == null ? "" : input.getText().toString();
                    service.setPhoneClipboard(next);
                    Toast.makeText(this, next.isEmpty()
                            ? tr("Общий буфер очищен", "Shared clipboard cleared")
                            : tr("Отправлено в общий буфер", "Sent to shared clipboard"), Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(tr("Вставить", "Paste"), null)
                .setNegativeButton(tr("Отмена", "Cancel"), null)
                .create();
        dialog.setOnShowListener(d -> {
            View pasteBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (pasteBtn != null) {
                pasteBtn.setOnClickListener(v -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData cd = cm != null ? cm.getPrimaryClip() : null;
                        if (cd != null && cd.getItemCount() > 0) {
                            CharSequence cs = cd.getItemAt(0).coerceToText(this);
                            if (cs != null && cs.length() > 0) {
                                String pasted = cs.length() > FileServer.CLIPBOARD_MAX_CHARS
                                        ? cs.subSequence(0, FileServer.CLIPBOARD_MAX_CHARS).toString()
                                        : cs.toString();
                                input.setText(pasted);
                                input.setSelection(input.getText().length());
                                hapticTick(v);
                                return;
                            }
                        }
                        Toast.makeText(this, tr("Буфер телефона пуст", "Phone clipboard is empty"), Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                });
            }
        });
        dialog.show();
    }

    private void shareLoginLink() {
        String link = linkWithKey();
        if (link.isEmpty()) return;
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "PocketBridge");
            send.putExtra(Intent.EXTRA_TEXT, link);
            startActivity(Intent.createChooser(send, tr("Отправить ссылку PocketBridge", "Share PocketBridge link")));
        } catch (Exception e) {
            copyValue(tr("Ссылка", "Link"), link, null);
        }
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
        if (!isRunning()) {
            Toast.makeText(this, tr("Сначала запустите доступ", "Start access first"), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            pendingPrevKey = key;
            setPending(PENDING_ROTATING);
            dispatchStartService(randomHexKey());
            Toast.makeText(this, tr("Новый код создан — войдите заново", "New code generated — sign in again"), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            setPending(PENDING_NONE);
            Toast.makeText(this, tr("Не удалось сменить код", "Failed to rotate code"), Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshSettings() {
        if (settingsPort == null) return;
        settingsPort.setText(String.valueOf(prefInt(ServerService.PREF_PORT, ServerService.DEFAULT_PORT)));
        int minutes = prefInt(ServerService.PREF_AUTOSTOP, ServerService.DEFAULT_AUTOSTOP);
        settingsAutoStop.setText(minutes <= 0 ? tr("Никогда", "Never") : minutes + tr(" мин", " min"));
        settingsKeepScreen.setText(prefBool(ServerService.PREF_KEEP_SCREEN, true) ? tr("Вкл", "On") : tr("Выкл", "Off"));
    }

    private void editPort() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout box = column();
        box.setPadding(dp(22), dp(12), dp(22), dp(4));
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(WHITE);
        input.setText(String.valueOf(prefInt(ServerService.PREF_PORT, ServerService.DEFAULT_PORT)));
        input.setSelection(input.getText().length());
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(tr("Порт HTTPS-сервера", "HTTPS server port"))
                .setMessage(tr("Укажите порт от 1024 до 65000. Если он занят, выберется следующий свободный.",
                        "Enter a port between 1024 and 65000. If busy, the next free port is used."))
                .setView(box)
                .setPositiveButton(tr("Сохранить", "Save"), (dialog, which) -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString().trim());
                        if (value < 1024 || value > 65000) throw new NumberFormatException();
                        prefs.edit().putInt(ServerService.PREF_PORT, value).apply();
                        refreshSettings();
                        restartIfRunning();
                    } catch (Exception e) {
                        Toast.makeText(this, tr("Некорректный порт", "Invalid port"), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(tr("Отмена", "Cancel"), null)
                .show();
    }

    private void editAutoStop() {
        if (isFinishing() || isDestroyed()) return;
        final int[] values = { 15, 30, 60, 0 };
        String[] labels = en
                ? new String[]{ "15 minutes", "30 minutes", "1 hour", "Never" }
                : new String[]{ "15 минут", "30 минут", "1 час", "Никогда" };
        int current = prefInt(ServerService.PREF_AUTOSTOP, ServerService.DEFAULT_AUTOSTOP);
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == current) checked = i;
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(tr("Автостоп без подключений", "Idle auto-stop"))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs.edit().putInt(ServerService.PREF_AUTOSTOP, values[which]).apply();
                    dialog.dismiss();
                    refreshView();
                    restartIfRunning();
                })
                .setNegativeButton(tr("Отмена", "Cancel"), null)
                .show();
    }

    private void toggleKeepScreen() {
        boolean value = !prefBool(ServerService.PREF_KEEP_SCREEN, true);
        prefs.edit().putBoolean(ServerService.PREF_KEEP_SCREEN, value).apply();
        refreshView();
    }

    private void restartIfRunning() {
        if (!isRunning()) return;
        final String current = key;
        setPending(PENDING_STARTING);
        try { startService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_STOP)); }
        catch (Exception ignored) {}
        uiHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            try { dispatchStartService(current); } catch (Exception ignored) { setPending(PENDING_NONE); }
        }, 400L);
    }

    private void copyValue(String labelText, String value, TextView trigger) {
        if (value == null || value.isEmpty()) return;
        try {
            ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clip != null) clip.setPrimaryClip(ClipData.newPlainText(labelText, value));
            hapticTick(trigger);
            if (trigger != null) {
                flashedCopyView = trigger;
                flashedCopyUntil = SystemClock.uptimeMillis() + 1400L;
                uiHandler.removeCallbacks(restoreCopyTask);
                uiHandler.postDelayed(restoreCopyTask, 1450L);
                refreshView();
            }
            Toast.makeText(this, en ? (labelText + " copied") : ("Скопировано: " + labelText), Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Api30.isStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        if (hasAccess()) {
            Toast.makeText(this, tr("Доступ уже разрешён", "Access already granted"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Api30.appAllFilesIntent(getPackageName()));
            } catch (Exception e) {
                try { startActivity(Api30.allFilesIntent()); }
                catch (Exception ex) {
                    Toast.makeText(this, tr("Откройте настройки доступа ко всем файлам", "Open All files access in Settings"), Toast.LENGTH_LONG).show();
                }
            }
        } else {
            requestPermissions(new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE }, 4);
        }
    }

    private void startServer() {
        if (!hasAccess()) {
            Toast.makeText(this, tr("Сначала разрешите доступ к файлам", "Grant storage access first"), Toast.LENGTH_LONG).show();
            requestAccess();
            return;
        }
        if (isRunning()) { refreshView(); return; }
        if (Build.VERSION.SDK_INT >= 33) {
            Api33.requestNotificationsIfNeeded(this);
        }
        key = randomHexKey();
        setPending(PENDING_STARTING);
        try {
            dispatchStartService(key);
        } catch (Exception e) {
            setPending(PENDING_NONE);
            Toast.makeText(this, tr("Не удалось запустить сервис", "Could not start service"), Toast.LENGTH_LONG).show();
        }
    }

    private void stopServer() {
        setPending(PENDING_STOPPING);
        try {
            startService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_STOP));
        } catch (Exception ignored) {
            if (service != null) service.stopFromUi();
        }
        Toast.makeText(this, tr("Доступ остановлен", "Access stopped"), Toast.LENGTH_SHORT).show();
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

    @SuppressWarnings("deprecation")
    private static void applySystemBars(Window window) {
        if (window == null) return;
        try {
            if (Build.VERSION.SDK_INT < 35) {
                window.setStatusBarColor(BG);
                window.setNavigationBarColor(BG);
            }
            if (Build.VERSION.SDK_INT >= 30) {
                Api30.clearLightSystemBars(window);
            }
        } catch (Throwable ignored) {
            // Оформление системных панелей не должно прерывать запуск ни на одной прошивке.
        }
    }

    private String prefString(String k, String def) {
        try { return prefs.getString(k, def); } catch (ClassCastException e) { return def; }
    }

    private int prefInt(String k, int def) {
        try { return prefs.getInt(k, def); } catch (ClassCastException e) { return def; }
    }

    private boolean prefBool(String k, boolean def) {
        try { return prefs.getBoolean(k, def); } catch (ClassCastException e) { return def; }
    }

    private static boolean shouldShowCrashReport(File crashFile) {
        if (crashFile == null || !crashFile.isFile() || crashFile.length() == 0) return false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(crashFile), StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            if (first != null && first.startsWith("PocketBridge ")
                    && !first.startsWith("PocketBridge " + APP_VERSION + " ")) {
                // Удаляем устаревший журнал сбоя от предыдущей версии после обновления.
                //noinspection ResultOfMethodCallIgnored
                crashFile.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @TargetApi(30)
    private static final class Api30 {
        static void clearLightSystemBars(Window window) {
            View decor = window.peekDecorView();
            if (decor == null) return;
            WindowInsetsController ic = decor.getWindowInsetsController();
            if (ic != null) {
                ic.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }

        static void applySafePadding(View page, WindowInsets insets, int padH, int padTop, int padBottom) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            page.setPadding(padH + bars.left, padTop + bars.top, padH + bars.right, padBottom + bars.bottom);
        }

        static boolean isStorageManager() {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable e) {
                return false;
            }
        }

        static Intent appAllFilesIntent(String pkg) {
            return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + pkg));
        }

        static Intent allFilesIntent() {
            return new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        }
    }

    @TargetApi(33)
    private static final class Api33 {
        static void requestNotificationsIfNeeded(Activity activity) {
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 5);
            }
        }
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
            if (clip != null) clip.setPrimaryClip(ClipData.newPlainText("Crash log", bytes.toString("UTF-8")));
            Toast.makeText(this, tr("Журнал скопирован", "Crash log copied"), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, tr("Не удалось прочитать журнал", "Could not read crash log"), Toast.LENGTH_LONG).show();
        }
    }
}
