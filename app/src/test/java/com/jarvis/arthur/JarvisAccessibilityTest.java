package com.jarvis.arthur;

import com.jarvis.arthur.device.DeviceController;
import com.jarvis.arthur.device.HardwarePermissionManager;
import com.jarvis.arthur.service.JarvisAccessibilityService;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Comprehensive unit test suite verifying:
 * 1. Accessibility Service background execution and one-time permission grant management.
 * 2. Strict gating of system settings (Bluetooth, DND, Silent Mode, Volume) before permission grant.
 * 3. Permanent memory of the permission grant forever across sessions.
 * 4. Immediate execution of all system settings commands without further prompts after initial approval.
 * 5. Full support for Bluetooth on/off/toggle, Silent mode on/off/toggle, and Do-Not-Disturb on/off/toggle.
 * 6. User-triggered voice wake commands (e.g. saying 'Jarvis, turn on bluetooth').
 * 7. Gating re-enabled if permissions are manually revoked.
 */
public class JarvisAccessibilityTest {

    private SettingsManager settings;
    private HardwarePermissionManager permissionManager;
    private DeviceController deviceController;
    private JarvisAccessibilityService accessibilityService;

    @Before
    public void setUp() {
        settings = new SettingsManager(null);
        settings.resetAllHardwarePermissionConfirmations();

        permissionManager = new HardwarePermissionManager(settings);
        deviceController = new DeviceController(null, permissionManager);
        accessibilityService = new JarvisAccessibilityService(deviceController, settings);
    }

    @Test
    public void testInitialStateRequiresOneTimePermission() {
        Assert.assertFalse("Accessibility permission must initially be ungranted",
                settings.isAccessibilityPermissionGranted());
        Assert.assertFalse("Unified permissions must initially be ungranted",
                settings.isUnifiedPermissionsConfirmed());
        Assert.assertFalse("One-time permission must initially be false",
                JarvisAccessibilityService.hasOneTimePermission(null));

        // Commands must be rejected with NEEDS_PERMISSION before initial approval
        JarvisAccessibilityService.SystemCommandResult btRes =
                accessibilityService.executeSystemCommand("turn on bluetooth");
        Assert.assertFalse("Bluetooth must fail without permission", btRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", btRes.detail);
        Assert.assertTrue(btRes.spokenMessage.contains("permission"));

        JarvisAccessibilityService.SystemCommandResult dndRes =
                accessibilityService.executeSystemCommand("turn on do not disturb");
        Assert.assertFalse("DND must fail without permission", dndRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", dndRes.detail);

        JarvisAccessibilityService.SystemCommandResult silentRes =
                accessibilityService.executeSystemCommand("activate silent mode");
        Assert.assertFalse("Silent mode must fail without permission", silentRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", silentRes.detail);

        JarvisAccessibilityService.SystemCommandResult volRes =
                accessibilityService.executeSystemCommand("volume up");
        Assert.assertFalse("Volume must fail without permission", volRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", volRes.detail);
    }

    @Test
    public void testOneTimePermissionGrantAndPermanentPersistence() {
        // Step 1: User grants one-time approval
        JarvisAccessibilityService.grantOneTimePermission(null);

        Assert.assertTrue("One-time permission must be granted",
                JarvisAccessibilityService.hasOneTimePermission(null));
        Assert.assertTrue("Accessibility permission flag must be stored in settings",
                settings.isAccessibilityPermissionGranted());
        Assert.assertTrue("Unified permissions flag must be set",
                settings.isUnifiedPermissionsConfirmed());

        // Step 2: Simulate app restart / new session
        SettingsManager session2Settings = new SettingsManager(null);
        Assert.assertTrue("Permission must be remembered forever across sessions",
                session2Settings.isAccessibilityPermissionGranted());
        Assert.assertTrue("Unified permissions must be remembered forever across sessions",
                session2Settings.isUnifiedPermissionsConfirmed());

        HardwarePermissionManager session2Hw = new HardwarePermissionManager(session2Settings);
        Assert.assertTrue("Hardware consents must be recognized across sessions",
                session2Hw.hasConsent(HardwarePermissionManager.PERM_BLUETOOTH));
        Assert.assertTrue("DND consent must be recognized across sessions",
                session2Hw.hasConsent(HardwarePermissionManager.PERM_DND));
        Assert.assertTrue("Volume consent must be recognized across sessions",
                session2Hw.hasConsent(HardwarePermissionManager.PERM_VOLUME));
    }

    @Test
    public void testBluetoothActionsAfterPermissionGrant() {
        // Authorize once
        JarvisAccessibilityService.grantOneTimePermission(null);

        // 1. Bluetooth ON
        JarvisAccessibilityService.SystemCommandResult onRes =
                accessibilityService.executeSystemCommand("turn on bluetooth");
        Assert.assertNotEquals("NEEDS_PERMISSION", onRes.detail);
        Assert.assertEquals("BLUETOOTH", onRes.action);

        // 2. Bluetooth OFF
        JarvisAccessibilityService.SystemCommandResult offRes =
                accessibilityService.executeSystemCommand("turn off bluetooth");
        Assert.assertNotEquals("NEEDS_PERMISSION", offRes.detail);
        Assert.assertEquals("BLUETOOTH", offRes.action);

        // 3. Bluetooth TOGGLE
        JarvisAccessibilityService.SystemCommandResult toggleRes =
                accessibilityService.executeSystemCommand("toggle bluetooth");
        Assert.assertNotEquals("NEEDS_PERMISSION", toggleRes.detail);
        Assert.assertEquals("BLUETOOTH", toggleRes.action);
    }

    @Test
    public void testDoNotDisturbActionsAfterPermissionGrant() {
        // Authorize once
        JarvisAccessibilityService.grantOneTimePermission(null);

        // 1. DND ON
        JarvisAccessibilityService.SystemCommandResult onRes =
                accessibilityService.executeSystemCommand("turn on do not disturb");
        Assert.assertNotEquals("NEEDS_PERMISSION", onRes.detail);
        Assert.assertEquals("DND", onRes.action);

        // 2. DND OFF
        JarvisAccessibilityService.SystemCommandResult offRes =
                accessibilityService.executeSystemCommand("turn off do not disturb");
        Assert.assertNotEquals("NEEDS_PERMISSION", offRes.detail);
        Assert.assertEquals("DND", offRes.action);

        // 3. DND TOGGLE
        JarvisAccessibilityService.SystemCommandResult toggleRes =
                accessibilityService.executeSystemCommand("toggle dnd");
        Assert.assertNotEquals("NEEDS_PERMISSION", toggleRes.detail);
        Assert.assertEquals("DND", toggleRes.action);
    }

    @Test
    public void testSilentModeActionsAfterPermissionGrant() {
        // Authorize once
        JarvisAccessibilityService.grantOneTimePermission(null);

        // 1. Silent mode ON
        JarvisAccessibilityService.SystemCommandResult onRes =
                accessibilityService.executeSystemCommand("activate silent mode");
        Assert.assertNotEquals("NEEDS_PERMISSION", onRes.detail);
        Assert.assertEquals("SILENT_MODE", onRes.action);

        // 2. Silent mode OFF
        JarvisAccessibilityService.SystemCommandResult offRes =
                accessibilityService.executeSystemCommand("turn off silent mode");
        Assert.assertNotEquals("NEEDS_PERMISSION", offRes.detail);
        Assert.assertEquals("SILENT_MODE", offRes.action);

        // 3. Silent mode TOGGLE
        JarvisAccessibilityService.SystemCommandResult toggleRes =
                accessibilityService.executeSystemCommand("toggle silent mode");
        Assert.assertNotEquals("NEEDS_PERMISSION", toggleRes.detail);
        Assert.assertEquals("SILENT_MODE", toggleRes.action);
    }

    @Test
    public void testVolumeActionsAfterPermissionGrant() {
        // Authorize once
        JarvisAccessibilityService.grantOneTimePermission(null);

        // 1. Volume UP
        JarvisAccessibilityService.SystemCommandResult upRes =
                accessibilityService.executeSystemCommand("volume up");
        Assert.assertNotEquals("NEEDS_PERMISSION", upRes.detail);
        Assert.assertEquals("VOLUME", upRes.action);

        // 2. Volume DOWN
        JarvisAccessibilityService.SystemCommandResult downRes =
                accessibilityService.executeSystemCommand("volume down");
        Assert.assertNotEquals("NEEDS_PERMISSION", downRes.detail);
        Assert.assertEquals("VOLUME", downRes.action);

        // 3. MUTE
        JarvisAccessibilityService.SystemCommandResult muteRes =
                accessibilityService.executeSystemCommand("mute audio");
        Assert.assertNotEquals("NEEDS_PERMISSION", muteRes.detail);
        Assert.assertEquals("VOLUME", muteRes.action);

        // 4. Target percentage
        JarvisAccessibilityService.SystemCommandResult pctRes =
                accessibilityService.executeSystemCommand("set volume to 75%");
        Assert.assertNotEquals("NEEDS_PERMISSION", pctRes.detail);
        Assert.assertEquals("VOLUME", pctRes.action);
    }

    @Test
    public void testVoiceWakeTriggerCommands() {
        // Authorize once
        JarvisAccessibilityService.grantOneTimePermission(null);

        // 1. Trigger word 'Jarvis' only
        JarvisAccessibilityService.SystemCommandResult wakeOnly =
                accessibilityService.onWakeWordDetected("Jarvis");
        Assert.assertTrue(wakeOnly.success);
        Assert.assertEquals("WAKE_WORD", wakeOnly.action);
        Assert.assertEquals("STANDBY", wakeOnly.detail);

        // 2. Trigger word + Bluetooth command
        JarvisAccessibilityService.SystemCommandResult wakeBt =
                accessibilityService.onWakeWordDetected("Jarvis, turn on bluetooth");
        Assert.assertEquals("BLUETOOTH", wakeBt.action);
        Assert.assertNotEquals("NEEDS_PERMISSION", wakeBt.detail);

        // 3. Trigger word + Silent mode command
        JarvisAccessibilityService.SystemCommandResult wakeSilent =
                accessibilityService.onWakeWordDetected("Jarvis, silent mode on");
        Assert.assertEquals("SILENT_MODE", wakeSilent.action);
        Assert.assertNotEquals("NEEDS_PERMISSION", wakeSilent.detail);

        // 4. Trigger word + Do Not Disturb command
        JarvisAccessibilityService.SystemCommandResult wakeDnd =
                accessibilityService.onWakeWordDetected("Hey Jarvis, turn off do not disturb");
        Assert.assertEquals("DND", wakeDnd.action);
        Assert.assertNotEquals("NEEDS_PERMISSION", wakeDnd.detail);

        // 5. Trigger word + Volume command
        JarvisAccessibilityService.SystemCommandResult wakeVol =
                accessibilityService.onWakeWordDetected("Jarvis, volume up");
        Assert.assertEquals("VOLUME", wakeVol.action);
        Assert.assertNotEquals("NEEDS_PERMISSION", wakeVol.detail);
    }

    @Test
    public void testManualRevocationReEnablesGating() {
        // Authorize once
        JarvisAccessibilityService.grantOneTimePermission(null);
        Assert.assertTrue(JarvisAccessibilityService.hasOneTimePermission(null));

        // Revoke
        JarvisAccessibilityService.revokeOneTimePermission(null);
        Assert.assertFalse(JarvisAccessibilityService.hasOneTimePermission(null));
        Assert.assertFalse(settings.isAccessibilityPermissionGranted());

        // Subsequent commands must immediately be blocked again
        JarvisAccessibilityService.SystemCommandResult res =
                accessibilityService.executeSystemCommand("turn on bluetooth");
        Assert.assertFalse(res.success);
        Assert.assertEquals("NEEDS_PERMISSION", res.detail);
    }

    @Test
    public void testGlobalAccessibilityActions() {
        // Authorize once
        JarvisAccessibilityService.grantOneTimePermission(null);

        JarvisAccessibilityService.SystemCommandResult homeRes =
                accessibilityService.executeSystemCommand("go home");
        Assert.assertEquals("GLOBAL_ACTION", homeRes.action);
        Assert.assertEquals("HOME", homeRes.detail);

        JarvisAccessibilityService.SystemCommandResult backRes =
                accessibilityService.executeSystemCommand("go back");
        Assert.assertEquals("GLOBAL_ACTION", backRes.action);
        Assert.assertEquals("BACK", backRes.detail);

        JarvisAccessibilityService.SystemCommandResult qsRes =
                accessibilityService.executeSystemCommand("open quick settings");
        Assert.assertEquals("GLOBAL_ACTION", qsRes.action);
        Assert.assertEquals("QUICK_SETTINGS", qsRes.detail);

        JarvisAccessibilityService.SystemCommandResult notifRes =
                accessibilityService.executeSystemCommand("show notifications");
        Assert.assertEquals("GLOBAL_ACTION", notifRes.action);
        Assert.assertEquals("NOTIFICATIONS", notifRes.detail);
    }

    @Test
    public void testStaticAccessibilityHelpers() {
        Assert.assertFalse(JarvisAccessibilityService.isAccessibilityServiceEnabled(null));
        Assert.assertFalse(JarvisAccessibilityService.isServiceActive());
        Assert.assertNull(JarvisAccessibilityService.getInstance());

        // Should not throw NPE on null context
        JarvisAccessibilityService.openAccessibilitySettings(null);
    }

    @Test
    public void testIntentRoutingSilentModeAndDnd() throws InterruptedException {
        java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"), "jarvis_acc_test_" + System.currentTimeMillis());
        if (!tempDir.exists()) tempDir.mkdirs();
        com.jarvis.arthur.memory.MemoryManager mem = new com.jarvis.arthur.memory.MemoryManager(null, tempDir);
        com.jarvis.arthur.knowledge.KnowledgeSource ks = new com.jarvis.arthur.knowledge.KnowledgeSource(settings, mem);
        com.jarvis.arthur.intent.IntentRouter router = new com.jarvis.arthur.intent.IntentRouter(deviceController, ks, mem);

        java.util.concurrent.CountDownLatch latch1 = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<com.jarvis.arthur.intent.IntentRouter.IntentResult> res1 = new java.util.concurrent.atomic.AtomicReference<>();
        router.route("activate silent mode", r -> {
            res1.set(r);
            latch1.countDown();
        });
        Assert.assertTrue(latch1.await(3, java.util.concurrent.TimeUnit.SECONDS));
        Assert.assertNotNull(res1.get());
        Assert.assertEquals("HARDWARE_SILENT", res1.get().toolBadge);

        java.util.concurrent.CountDownLatch latch2 = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<com.jarvis.arthur.intent.IntentRouter.IntentResult> res2 = new java.util.concurrent.atomic.AtomicReference<>();
        router.route("toggle bluetooth", r -> {
            res2.set(r);
            latch2.countDown();
        });
        Assert.assertTrue(latch2.await(3, java.util.concurrent.TimeUnit.SECONDS));
        Assert.assertNotNull(res2.get());
        Assert.assertEquals("HARDWARE_BLUETOOTH", res2.get().toolBadge);

        java.util.concurrent.CountDownLatch latch3 = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<com.jarvis.arthur.intent.IntentRouter.IntentResult> res3 = new java.util.concurrent.atomic.AtomicReference<>();
        router.route("toggle dnd", r -> {
            res3.set(r);
            latch3.countDown();
        });
        Assert.assertTrue(latch3.await(3, java.util.concurrent.TimeUnit.SECONDS));
        Assert.assertNotNull(res3.get());
        Assert.assertEquals("HARDWARE_DND", res3.get().toolBadge);
    }
}
