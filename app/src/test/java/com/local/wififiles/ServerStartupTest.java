package com.local.wififiles;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = ServerStartupTest.StartupEnvironment.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ServerStartupTest {
    private Context app;
    private ServiceController<ServerService> controller;

    @Before public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        StartupEnvironment.resetTracking();
        File external = StartupEnvironment.testDirectory();
        Files.createDirectories(new File(external, "First").toPath());
        Files.createDirectories(new File(external, "Second").toPath());
        BridgeOptions.prefs(app).edit().putString("ui_lang", "en")
                .putString(BridgeOptions.ROOT, "First").putBoolean(BridgeOptions.READ_ONLY, false).commit();
        Intent intent = new Intent(app, ServerService.class).setAction(ServerService.ACTION_START)
                .putExtra(ServerService.EXTRA_KEY, "0123456789abcdef");
        controller = Robolectric.buildService(ServerService.class, intent).create();
    }

    @After public void tearDown() {
        if (controller != null) controller.destroy();
        StartupEnvironment.resetTracking();
    }

    @Test public void validatesSharedStorageOffTheMainThread() throws Exception {
        controller.startCommand(0, 1);
        MainActivityLaunchTest.awaitStarted(controller.get());
        assertEquals("Shared storage filesystem checks must not run on the Android main thread",
                0, StartupEnvironment.mainReads.get());
        assertTrue("Validate shared storage both before and after TLS preparation",
                StartupEnvironment.workerReads.get() >= 2);
    }

    @Test public void doesNotPublishAnAlreadyPreparedStartAfterStop() throws Exception {
        ServerService service = prepareWhileMainLooperIsPaused();
        service.stopFromUi();
        ShadowLooper.idleMainLooper();
        assertFalse(service.isStarting());
        assertFalse(service.isRunning());
        assertEquals("A discarded result must not start a listener", 0, service.port());
        assertNull(ServerService.current());
    }

    @Test public void lateSettingsChangeNeverPublishesThePreviousScope() throws Exception {
        ServerService service = prepareWhileMainLooperIsPaused();
        BridgeOptions.prefs(app).edit().putString(BridgeOptions.ROOT, "Second")
                .putBoolean(BridgeOptions.READ_ONLY, true).commit();
        AtomicInteger unsafeNotifications = new AtomicInteger();
        service.addListener(() -> {
            if (service.isRunning() && !service.isReadOnly()) unsafeNotifications.incrementAndGet();
        });
        MainActivityLaunchTest.awaitStarted(service);
        assertEquals(0, unsafeNotifications.get());
        assertTrue(service.isReadOnly());
        File actualRoot = (File) field(field(service, "server"), "root");
        assertEquals(new File(StartupEnvironment.testDirectory(), "Second").getCanonicalFile(), actualRoot);
    }

    @Test public void permissionRevokedAfterPreparationPreventsActivation() throws Exception {
        ServerService service = prepareWhileMainLooperIsPaused();
        StartupEnvironment.permissionGranted = false;
        ShadowLooper.idleMainLooper();
        assertFalse(service.isStarting());
        assertFalse(service.isRunning());
        assertEquals(0, service.port());
        assertEquals("Grant storage access first", service.lastError());
        assertNull(ServerService.active());
    }

    private ServerService prepareWhileMainLooperIsPaused() throws Exception {
        controller.startCommand(0, 1);
        ServerService service = controller.get();
        assertTrue(service.isStarting());
        Future<?> worker = (Future<?>) field(service, "preparation");
        worker.get(15, TimeUnit.SECONDS);
        assertTrue(service.isStarting());
        assertFalse(service.isRunning());
        assertEquals("The worker must not publish or activate a prepared server", 0, service.port());
        return service;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Implements(value = Environment.class, minSdk = 30)
    public static class StartupEnvironment extends ShadowEnvironment {
        static final AtomicInteger mainReads = new AtomicInteger();
        static final AtomicInteger workerReads = new AtomicInteger();
        static volatile boolean permissionGranted = true;

        static File testDirectory() { return ShadowEnvironment.getExternalStorageDirectory(); }

        static void resetTracking() {
            mainReads.set(0);
            workerReads.set(0);
            permissionGranted = true;
        }

        @Implementation(minSdk = 30)
        protected static boolean isExternalStorageManager() { return permissionGranted; }

        @Implementation
        protected static File getExternalStorageDirectory() {
            (Looper.myLooper() == Looper.getMainLooper() ? mainReads : workerReads).incrementAndGet();
            return testDirectory();
        }
    }
}
