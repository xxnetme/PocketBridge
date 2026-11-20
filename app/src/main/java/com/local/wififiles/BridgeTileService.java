package com.local.wififiles;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Системная плитка не хранит код доступа и обновляется по состоянию сервера. */
public final class BridgeTileService extends TileService {
    static void refresh(Context context) {
        try { requestListeningState(context, new ComponentName(context, BridgeTileService.class)); }
        catch (RuntimeException ignored) { /* tile may not have been added */ }
    }

    @Override public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::toggle);
        else toggle();
    }

    private void toggle() {
        ServerService active = ServerService.current();
        if (active != null) {
            active.stopFromUi();
            updateTile();
            return;
        }
        if (BridgeOptions.hasStorageAccess(this)) {
            try {
                startForegroundService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_TILE_START));
                return;
            } catch (RuntimeException ignored) {
                // Some Android builds do not allow a foreground-service start from a tile.
            }
        }
        openApp();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean active = ServerService.active() != null;
        boolean en = BridgeOptions.english(this);
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(active ? (en ? "Access on" : "Доступ открыт") : (en ? "Access off" : "Доступ закрыт"));
        }
        tile.updateTile();
    }

    @SuppressWarnings("deprecation")
    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) Api34.open(this, intent);
        else startActivityAndCollapse(intent);
    }

    @TargetApi(34)
    private static final class Api34 {
        static void open(BridgeTileService service, Intent intent) {
            service.startActivityAndCollapse(PendingIntent.getActivity(service, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
    }
}
