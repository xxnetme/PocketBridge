package com.local.wififiles;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

final class BridgeOptions {
    static final String ROOT = "sharedRoot";
    static final String READ_ONLY = "readOnly";
    static final String DISCOVERY = "mdnsDiscovery";

    private BridgeOptions() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
    }

    /** null означает повреждённую настройку, а не разрешение открыть всё хранилище. */
    static String root(Context context) {
        try { return prefs(context).getString(ROOT, ""); }
        catch (ClassCastException e) { return null; }
    }

    static File sharedFolder(Context context) throws IOException {
        return resolveSharedFolder(root(context));
    }

    static File resolveSharedFolder(String selection) throws IOException {
        return StorageRoot.resolve(Environment.getExternalStorageDirectory(), selection);
    }

    static boolean readOnly(Context context) {
        try { return prefs(context).getBoolean(READ_ONLY, false); }
        catch (ClassCastException e) { return true; }
    }

    static boolean discovery(Context context) {
        try { return prefs(context).getBoolean(DISCOVERY, false); }
        catch (ClassCastException e) { return false; }
    }

    static boolean hasStorageAccess(Context context) {
        if (Build.VERSION.SDK_INT >= 30) {
            try { return Environment.isExternalStorageManager(); }
            catch (RuntimeException unavailable) { return false; }
        }
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean english(Context context) {
        String saved;
        try { saved = prefs(context).getString("ui_lang", ""); }
        catch (ClassCastException ignored) { saved = ""; }
        if ("en".equals(saved) || "ru".equals(saved)) return "en".equals(saved);
        String language = Locale.getDefault().getLanguage();
        return !("ru".equals(language) || "uk".equals(language) || "be".equals(language) || "kk".equals(language));
    }
}
