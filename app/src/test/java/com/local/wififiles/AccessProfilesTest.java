package com.local.wififiles;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = MainActivityLaunchTest.AllFilesEnvironment.class)
public class AccessProfilesTest {
    @Test public void profileSwitchClosesOldListenerAndUnavailableProfileKeepsPreviousScope() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File storage = Environment.getExternalStorageDirectory();
        File photo = Files.createDirectories(new File(storage, "Photos").toPath()).toFile();
        File downloads = Files.createDirectories(new File(storage, "Downloads").toPath()).toFile();
        File laterMissing = Files.createDirectories(new File(storage, "MissingSoon").toPath()).toFile();
        AccessProfiles.configure(app, "Photos", true, null);
        AccessProfiles.Profile photos = AccessProfiles.saveCurrent(app, "Photos — read only");
        AccessProfiles.configure(app, "MissingSoon", true, null);
        AccessProfiles.saveCurrent(app, "Unavailable");
        assertTrue(laterMissing.delete());
        AccessProfiles.configure(app, "Downloads", false, null);
        AccessProfiles.Profile write = AccessProfiles.saveCurrent(app, "Downloads — read/write");
        assertEquals(3, AccessProfiles.list(app).size());
        assertEquals(write.id, AccessProfiles.active(app).id);

        String original = "0123456789abcdef";
        ServiceController<ServerService> sc = Robolectric.buildService(ServerService.class, start(app, original)).create().startCommand(0, 1);
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        ServerService service = sc.get();
        MainActivity activity = ac.get();
        try {
            assertTrue(service.lastError(), service.isRunning());
            assertFalse(service.isReadOnly());
            assertEquals(downloads.getCanonicalFile(), BridgeOptions.sharedFolder(app));
            selectProfile(activity, 1);  // the selected profile no longer exists
            assertEquals(write.id, AccessProfiles.active(app).id);
            assertEquals(downloads.getCanonicalFile(), BridgeOptions.sharedFolder(app));
            assertEquals(original, service.accessKey());
            assertTrue(service.isRunning());

            selectProfile(activity, 0);
            assertFalse("Old listener closes before the new settings go live", service.isRunning());
            assertEquals(photos.id, AccessProfiles.active(app).id);
            assertEquals(photo.getCanonicalFile(), BridgeOptions.sharedFolder(app));
            assertTrue(BridgeOptions.readOnly(app));
            String next = (String) field(activity, "key");
            assertNotEquals(original, next);
            assertTrue(next.matches("[0-9a-f]{16}"));
            service.onStartCommand(start(app, next), 0, 2);
            assertTrue(service.lastError(), service.isRunning());
            assertTrue(service.isReadOnly());
            assertEquals(next, service.accessKey());
            AccessProfiles.delete(app, photos);
            assertNull(AccessProfiles.active(app));
            assertEquals(photo.getCanonicalFile(), BridgeOptions.sharedFolder(app));
            assertTrue(BridgeOptions.readOnly(app));
        } finally {
            ac.pause().stop().destroy();
            service.stopFromUi();
            sc.destroy();
        }
    }

    private static Intent start(Context app, String key) {
        return new Intent(app, ServerService.class).setAction(ServerService.ACTION_START).putExtra(ServerService.EXTRA_KEY, key);
    }

    private static void selectProfile(MainActivity activity, int position) throws Exception {
        TextView label = (TextView) field(activity, "profileName");
        ((View) label.getParent().getParent()).performClick();
        AlertDialog selector = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(selector.getListView());
        selector.getListView().performItemClick(selector.getListView().getAdapter().getView(position, null, selector.getListView()), position, position);
        ShadowLooper.idleMainLooper();
        AlertDialog action = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(action.getButton(DialogInterface.BUTTON_POSITIVE));
        action.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
    }

    private static Object field(MainActivity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }
}
