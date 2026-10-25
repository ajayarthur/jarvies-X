package com.jarvis.arthur;

import com.jarvis.arthur.device.DeviceController;
import com.jarvis.arthur.device.HardwarePermissionManager;
import com.jarvis.arthur.intent.IntentRouter;
import com.jarvis.arthur.knowledge.KnowledgeSource;
import com.jarvis.arthur.memory.MemoryManager;
import com.jarvis.arthur.receiver.JarvisDeviceAdminReceiver;
import com.jarvis.arthur.service.JarvisAccessibilityService;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Comprehensive Unit Test Suite verifying:
 * 1. Device Administrator receiver configuration and lifecycle.
 * 2. Elevation of Jarvis permissions via Device Admin with transparent explanation.
 * 3. Permanent multi-session persistence of Device Admin and all hardware consents.
 * 4. Background automated hardware controls (Bluetooth, DND, Volume, Wi-Fi, Screen Lock) triggered by voice.
 * 5. Complete absence of repeated permission prompts once Device Admin is authorized.
 */
public class JarvisDeviceAdminTest {

    private SettingsManager settings;
    private HardwarePermissionManager permissionManager;
    private DeviceController deviceController;
    private MemoryManager memoryManager;
    private KnowledgeSource knowledgeSource;
    private IntentRouter intentRouter;
    private JarvisAccessibilityService accessibilityService;

    @Before
    public void setUp() {
        settings = new SettingsManager(null);
        settings.resetAllHardwarePermissionConfirmations();

        permissionManager = new HardwarePermissionManager(settings);
        deviceController = new DeviceController(null, permissionManager);

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "jarvis_admin_test_" + System.currentTimeMillis());
        if (!tempDir.exists()) tempDir.mkdirs();
        memoryManager = new MemoryManager(null, tempDir);
        knowledgeSource = new KnowledgeSource(settings, memoryManager);
        intentRouter = new IntentRouter(deviceController, knowledgeSource, memoryManager);
        accessibilityService = new JarvisAccessibilityService(deviceController, settings);
    }

    @Test
    public void testDeviceAdminKeyAndTransparentExplanations() {
        Assert.assertNotNull(HardwarePermissionManager.PERM_DEVICE_ADMIN);
        Assert.assertEquals("device_admin", HardwarePermissionManager.PERM_DEVICE_ADMIN);

        String displayName = HardwarePermissionManager.getDisplayName(HardwarePermissionManager.PERM_DEVICE_ADMIN);
        Assert.assertNotNull(displayName);
        Assert.assertTrue(displayName.contains("Device Administrator"));

        String description = HardwarePermissionManager.getDescription(HardwarePermissionManager.PERM_DEVICE_ADMIN);
        Assert.assertNotNull(description);
        Assert.assertTrue(description.contains("privileges"));
        Assert.assertTrue(description.contains("security"));
        Assert.assertTrue(description.contains("hardware"));
    }

    @Test
    public void testInitialStateRequiresAuthorization() {
        Assert.assertFalse("Device Admin must initially be disabled", settings.isDeviceAdminEnabled());
        Assert.assertFalse("Unified permissions must initially be ungranted", settings.isUnifiedPermissionsConfirmed());
        Assert.assertFalse("Hardware consent must be false", permissionManager.hasConsent(HardwarePermissionManager.PERM_DEVICE_ADMIN));
    }

    @Test
    public void testDeviceAdminActivationAndMultiSessionPersistence() {
        // Step 1: User grants Device Administrator privileges
        settings.setDeviceAdminEnabled(true);
        permissionManager.grantAllHardwareConsents();

        Assert.assertTrue("Device admin must be enabled in settings", settings.isDeviceAdminEnabled());
        Assert.assertTrue("Unified permissions flag must be set", settings.isUnifiedPermissionsConfirmed());
        Assert.assertTrue("Device admin consent must be active",
                permissionManager.hasConsent(HardwarePermissionManager.PERM_DEVICE_ADMIN));

        // When Device Admin is granted, ALL hardware subsystems are automatically authorized
        Assert.assertTrue("Wi-Fi must be authorized", permissionManager.hasConsent(HardwarePermissionManager.PERM_WIFI));
        Assert.assertTrue("Bluetooth must be authorized", permissionManager.hasConsent(HardwarePermissionManager.PERM_BLUETOOTH));
        Assert.assertTrue("DND must be authorized", permissionManager.hasConsent(HardwarePermissionManager.PERM_DND));
        Assert.assertTrue("Volume must be authorized", permissionManager.hasConsent(HardwarePermissionManager.PERM_VOLUME));
        Assert.assertTrue("Flashlight must be authorized", permissionManager.hasConsent(HardwarePermissionManager.PERM_FLASHLIGHT));
        Assert.assertTrue("Camera must be authorized", permissionManager.hasConsent(HardwarePermissionManager.PERM_CAMERA));
        Assert.assertTrue("Mobile data must be authorized", permissionManager.hasConsent(HardwarePermissionManager.PERM_MOBILE_DATA));

        // Step 2: Simulate app restart / new sessions (remembered forever across sessions)
        SettingsManager session2Settings = new SettingsManager(null);
        Assert.assertTrue("Session 2 must remember Device Admin enabled", session2Settings.isDeviceAdminEnabled());
        Assert.assertTrue("Session 2 must remember Unified Permissions", session2Settings.isUnifiedPermissionsConfirmed());

        HardwarePermissionManager session2Hw = new HardwarePermissionManager(session2Settings);
        Assert.assertTrue("Session 2 must confirm all consents", session2Hw.hasAllHardwareConsents());
        Assert.assertTrue("Session 2 Wi-Fi consent remembered", session2Hw.hasConsent(HardwarePermissionManager.PERM_WIFI));
        Assert.assertTrue("Session 2 Bluetooth consent remembered", session2Hw.hasConsent(HardwarePermissionManager.PERM_BLUETOOTH));
        Assert.assertTrue("Session 2 DND consent remembered", session2Hw.hasConsent(HardwarePermissionManager.PERM_DND));
        Assert.assertTrue("Session 2 Volume consent remembered", session2Hw.hasConsent(HardwarePermissionManager.PERM_VOLUME));
    }

    @Test
    public void testVoiceCommandsRecognitionForDeviceLockAndAdmin() {
        Assert.assertTrue("Lock device command recognized", IntentRouter.isCommand("lock device"));
        Assert.assertTrue("Lock phone command recognized", IntentRouter.isCommand("lock phone"));
        Assert.assertTrue("Lock screen command recognized", IntentRouter.isCommand("lock screen"));
        Assert.assertTrue("Lock the phone command recognized", IntentRouter.isCommand("lock the phone"));
        Assert.assertTrue("Device admin command recognized", IntentRouter.isCommand("device admin"));
    }

    @Test
    public void testIntentRoutingForLockDevice() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> resultRef = new AtomicReference<>();

        intentRouter.route("Jarvis, lock device", result -> {
            resultRef.set(result);
            latch.countDown();
        });

        Assert.assertTrue("Intent routing timed out", latch.await(4, TimeUnit.SECONDS));
        IntentRouter.IntentResult res = resultRef.get();
        Assert.assertNotNull(res);
        Assert.assertTrue(res.isCommand);
        Assert.assertEquals("HARDWARE_ADMIN", res.toolBadge);
        Assert.assertNotNull(res.spokenResponse);
    }

    @Test
    public void testAutomatedHardwareControlsUnderDeviceAdmin() throws InterruptedException {
        // Elevate with Device Admin
        settings.setDeviceAdminEnabled(true);
        permissionManager.grantAllHardwareConsents();

        // 1. Bluetooth voice command
        CountDownLatch btLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> btRef = new AtomicReference<>();
        intentRouter.route("turn on bluetooth", result -> {
            btRef.set(result);
            btLatch.countDown();
        });
        Assert.assertTrue(btLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue(btRef.get().isCommand);
        Assert.assertEquals("HARDWARE_BLUETOOTH", btRef.get().toolBadge);
        Assert.assertFalse("Must NOT require permission prompt", btRef.get().spokenResponse.contains("Explicit consent is required"));

        // 2. Wi-Fi voice command
        CountDownLatch wifiLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> wifiRef = new AtomicReference<>();
        intentRouter.route("turn on wifi", result -> {
            wifiRef.set(result);
            wifiLatch.countDown();
        });
        Assert.assertTrue(wifiLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue(wifiRef.get().isCommand);
        Assert.assertEquals("HARDWARE_WIFI", wifiRef.get().toolBadge);
        Assert.assertFalse("Must NOT require permission prompt", wifiRef.get().spokenResponse.contains("Explicit consent is required"));

        // 3. Do Not Disturb voice command
        CountDownLatch dndLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> dndRef = new AtomicReference<>();
        intentRouter.route("turn on do not disturb", result -> {
            dndRef.set(result);
            dndLatch.countDown();
        });
        Assert.assertTrue(dndLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue(dndRef.get().isCommand);
        Assert.assertEquals("HARDWARE_DND", dndRef.get().toolBadge);
        Assert.assertFalse("Must NOT require permission prompt", dndRef.get().spokenResponse.contains("Explicit consent is required"));

        // 4. Volume voice command
        CountDownLatch volLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> volRef = new AtomicReference<>();
        intentRouter.route("set volume to 80 percent", result -> {
            volRef.set(result);
            volLatch.countDown();
        });
        Assert.assertTrue(volLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue(volRef.get().isCommand);
        Assert.assertEquals("AUDIO_CONTROL", volRef.get().toolBadge);
        Assert.assertFalse("Must NOT require permission prompt", volRef.get().spokenResponse.contains("requires your consent"));
    }

    @Test
    public void testAccessibilityServiceWithDeviceAdmin() {
        // Activate Device Admin
        settings.setDeviceAdminEnabled(true);
        permissionManager.grantAllHardwareConsents();

        // System commands must now succeed without prompt
        JarvisAccessibilityService.SystemCommandResult lockRes =
                accessibilityService.executeSystemCommand("lock device");
        Assert.assertEquals("LOCK_DEVICE", lockRes.action);

        JarvisAccessibilityService.SystemCommandResult wifiRes =
                accessibilityService.executeSystemCommand("turn on wifi");
        Assert.assertEquals("WIFI", wifiRes.action);

        JarvisAccessibilityService.SystemCommandResult btRes =
                accessibilityService.executeSystemCommand("turn on bluetooth");
        Assert.assertEquals("BLUETOOTH", btRes.action);

        JarvisAccessibilityService.SystemCommandResult dndRes =
                accessibilityService.executeSystemCommand("turn on do not disturb");
        Assert.assertEquals("DND", dndRes.action);

        JarvisAccessibilityService.SystemCommandResult volRes =
                accessibilityService.executeSystemCommand("volume up");
        Assert.assertEquals("VOLUME", volRes.action);
    }

    @Test
    public void testDeviceAdminRevocation() {
        settings.setDeviceAdminEnabled(true);
        Assert.assertTrue(settings.isDeviceAdminEnabled());

        settings.resetAllHardwarePermissionConfirmations();
        Assert.assertFalse("Must be disabled after reset", settings.isDeviceAdminEnabled());
        Assert.assertFalse("Unified permissions must be false", settings.isUnifiedPermissionsConfirmed());
    }

    // --- Programmatic Hardware Control Mock Controller ---
    static class ProgrammaticMockDeviceController extends DeviceController {
        boolean wifiState = false;
        boolean bluetoothState = false;
        boolean dndState = false;
        int volumeLevel = 50;
        boolean volumeMuted = false;
        boolean settingsPanelOpened = false;
        String lastSettingsPanel = null;

        ProgrammaticMockDeviceController(HardwarePermissionManager pm) {
            super(null, pm);
        }

        @Override
        public CommandResult setWifi(boolean enable) {
            this.wifiState = enable;
            String spoken = enable ?
                    "Wi-Fi radio is now turned on and confirmed active, sir." :
                    "Wi-Fi radio is now turned off and confirmed inactive, sir.";
            return new CommandResult(true, spoken, "WIFI", enable ? "CONFIRMED_ON" : "CONFIRMED_OFF");
        }

        @Override
        public CommandResult toggleWifi() {
            return setWifi(!wifiState);
        }

        @Override
        public CommandResult setBluetooth(boolean enable) {
            this.bluetoothState = enable;
            String spoken = enable ?
                    "Bluetooth radio is now turned on and confirmed active, sir." :
                    "Bluetooth radio is now turned off and confirmed inactive, sir.";
            return new CommandResult(true, spoken, "BLUETOOTH", enable ? "CONFIRMED_ON" : "CONFIRMED_OFF");
        }

        @Override
        public CommandResult toggleBluetooth() {
            return setBluetooth(!bluetoothState);
        }

        @Override
        public CommandResult setDoNotDisturb(boolean enable) {
            this.dndState = enable;
            String spoken = enable ?
                    "Do Not Disturb is now turned on and confirmed active, sir. Calls and alerts are silenced." :
                    "Do Not Disturb is now turned off and confirmed inactive, sir. Standard audio alerts restored.";
            return new CommandResult(true, spoken, "DND", enable ? "CONFIRMED_ON" : "CONFIRMED_OFF");
        }

        @Override
        public CommandResult toggleDoNotDisturb() {
            return setDoNotDisturb(!dndState);
        }

        @Override
        public CommandResult setVolumeState(boolean enable) {
            this.volumeMuted = !enable;
            if (!enable) {
                String spoken = "Volume is now turned off and confirmed muted, sir.";
                return new CommandResult(true, spoken, "VOLUME", "CONFIRMED_OFF (0%)");
            } else {
                if (volumeLevel == 0) volumeLevel = 65;
                String spoken = "Volume is now turned on and confirmed at " + volumeLevel + " percent, sir.";
                return new CommandResult(true, spoken, "VOLUME", "CONFIRMED_ON (" + volumeLevel + "%)");
            }
        }

        @Override
        public CommandResult adjustVolume(String action, int targetPercent) {
            if ("off".equalsIgnoreCase(action) || "mute".equalsIgnoreCase(action)) {
                return setVolumeState(false);
            } else if ("on".equalsIgnoreCase(action) || "unmute".equalsIgnoreCase(action)) {
                return setVolumeState(true);
            } else if ("up".equalsIgnoreCase(action)) {
                volumeLevel = Math.min(100, volumeLevel + 10);
                volumeMuted = false;
                return new CommandResult(true, "Volume increased and confirmed at " + volumeLevel + " percent, sir.", "VOLUME", volumeLevel + "%");
            } else if ("down".equalsIgnoreCase(action)) {
                volumeLevel = Math.max(0, volumeLevel - 10);
                return new CommandResult(true, "Volume lowered and confirmed at " + volumeLevel + " percent, sir.", "VOLUME", volumeLevel + "%");
            } else if (targetPercent >= 0) {
                volumeLevel = Math.max(0, Math.min(100, targetPercent));
                volumeMuted = (volumeLevel == 0);
                return new CommandResult(true, "Volume set and confirmed at " + volumeLevel + " percent, sir.", "VOLUME", volumeLevel + "%");
            }
            return new CommandResult(false, "Invalid volume command.", "VOLUME", null);
        }

        @Override
        public CommandResult openSettingsPanel(String panel) {
            this.settingsPanelOpened = true;
            this.lastSettingsPanel = panel;
            return new CommandResult(true, "Settings panel opened for " + panel, "SETTINGS", panel);
        }
    }

    @Test
    public void testProgrammaticHardwareControl_BluetoothBothOnAndOff() throws InterruptedException {
        // Authorize via Device Admin
        settings.setDeviceAdminEnabled(true);
        permissionManager.grantAllHardwareConsents();

        ProgrammaticMockDeviceController mockController = new ProgrammaticMockDeviceController(permissionManager);
        IntentRouter router = new IntentRouter(mockController, knowledgeSource, memoryManager);
        JarvisAccessibilityService accService = new JarvisAccessibilityService(mockController, settings);

        // 1. Turn ON Bluetooth via voice
        CountDownLatch onLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> onRef = new AtomicReference<>();
        router.route("turn on bluetooth", result -> {
            onRef.set(result);
            onLatch.countDown();
        });
        Assert.assertTrue(onLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue("Command recognized", onRef.get().isCommand);
        Assert.assertEquals("HARDWARE_BLUETOOTH", onRef.get().toolBadge);
        Assert.assertTrue("Bluetooth hardware must be ON", mockController.bluetoothState);
        Assert.assertTrue("Response confirms active", onRef.get().spokenResponse.contains("confirmed active"));
        Assert.assertFalse("Must NOT open manual settings panel", mockController.settingsPanelOpened);

        // 2. Turn OFF Bluetooth via voice
        CountDownLatch offLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> offRef = new AtomicReference<>();
        router.route("turn off bluetooth", result -> {
            offRef.set(result);
            offLatch.countDown();
        });
        Assert.assertTrue(offLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue("Command recognized", offRef.get().isCommand);
        Assert.assertEquals("HARDWARE_BLUETOOTH", offRef.get().toolBadge);
        Assert.assertFalse("Bluetooth hardware must be OFF", mockController.bluetoothState);
        Assert.assertTrue("Response confirms inactive", offRef.get().spokenResponse.contains("confirmed inactive"));
        Assert.assertFalse("Must NOT open manual settings panel", mockController.settingsPanelOpened);

        // 3. Accessibility Service ON and OFF execution
        JarvisAccessibilityService.SystemCommandResult accOn = accService.executeSystemCommand("turn on bluetooth");
        Assert.assertTrue(accOn.success);
        Assert.assertTrue("Bluetooth turned ON via accessibility", mockController.bluetoothState);
        Assert.assertFalse("Zero settings panels opened", mockController.settingsPanelOpened);

        JarvisAccessibilityService.SystemCommandResult accOff = accService.executeSystemCommand("turn off bluetooth");
        Assert.assertTrue(accOff.success);
        Assert.assertFalse("Bluetooth turned OFF via accessibility", mockController.bluetoothState);
        Assert.assertFalse("Zero settings panels opened", mockController.settingsPanelOpened);
    }

    @Test
    public void testProgrammaticHardwareControl_WifiBothOnAndOff() throws InterruptedException {
        // Authorize via Device Admin
        settings.setDeviceAdminEnabled(true);
        permissionManager.grantAllHardwareConsents();

        ProgrammaticMockDeviceController mockController = new ProgrammaticMockDeviceController(permissionManager);
        IntentRouter router = new IntentRouter(mockController, knowledgeSource, memoryManager);
        JarvisAccessibilityService accService = new JarvisAccessibilityService(mockController, settings);

        // 1. Turn ON Wi-Fi via voice
        CountDownLatch onLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> onRef = new AtomicReference<>();
        router.route("turn on wifi", result -> {
            onRef.set(result);
            onLatch.countDown();
        });
        Assert.assertTrue(onLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue("Command recognized", onRef.get().isCommand);
        Assert.assertEquals("HARDWARE_WIFI", onRef.get().toolBadge);
        Assert.assertTrue("Wi-Fi hardware must be ON", mockController.wifiState);
        Assert.assertTrue("Response confirms active", onRef.get().spokenResponse.contains("confirmed active"));
        Assert.assertFalse("Must NOT open manual settings panel", mockController.settingsPanelOpened);

        // 2. Turn OFF Wi-Fi via voice
        CountDownLatch offLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> offRef = new AtomicReference<>();
        router.route("turn off wifi", result -> {
            offRef.set(result);
            offLatch.countDown();
        });
        Assert.assertTrue(offLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue("Command recognized", offRef.get().isCommand);
        Assert.assertEquals("HARDWARE_WIFI", offRef.get().toolBadge);
        Assert.assertFalse("Wi-Fi hardware must be OFF", mockController.wifiState);
        Assert.assertTrue("Response confirms inactive", offRef.get().spokenResponse.contains("confirmed inactive"));
        Assert.assertFalse("Must NOT open manual settings panel", mockController.settingsPanelOpened);

        // 3. Accessibility Service ON and OFF execution
        JarvisAccessibilityService.SystemCommandResult accOn = accService.executeSystemCommand("turn on wifi");
        Assert.assertTrue(accOn.success);
        Assert.assertTrue("Wi-Fi turned ON via accessibility", mockController.wifiState);
        Assert.assertFalse("Zero settings panels opened", mockController.settingsPanelOpened);

        JarvisAccessibilityService.SystemCommandResult accOff = accService.executeSystemCommand("turn off wifi");
        Assert.assertTrue(accOff.success);
        Assert.assertFalse("Wi-Fi turned OFF via accessibility", mockController.wifiState);
        Assert.assertFalse("Zero settings panels opened", mockController.settingsPanelOpened);
    }

    @Test
    public void testProgrammaticHardwareControl_DndBothOnAndOff() throws InterruptedException {
        // Authorize via Device Admin
        settings.setDeviceAdminEnabled(true);
        permissionManager.grantAllHardwareConsents();

        ProgrammaticMockDeviceController mockController = new ProgrammaticMockDeviceController(permissionManager);
        IntentRouter router = new IntentRouter(mockController, knowledgeSource, memoryManager);
        JarvisAccessibilityService accService = new JarvisAccessibilityService(mockController, settings);

        // 1. Turn ON Do Not Disturb via voice
        CountDownLatch onLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> onRef = new AtomicReference<>();
        router.route("turn on do not disturb", result -> {
            onRef.set(result);
            onLatch.countDown();
        });
        Assert.assertTrue(onLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue("Command recognized", onRef.get().isCommand);
        Assert.assertEquals("HARDWARE_DND", onRef.get().toolBadge);
        Assert.assertTrue("DND hardware mode must be ON", mockController.dndState);
        Assert.assertTrue("Response confirms active", onRef.get().spokenResponse.contains("confirmed active"));
        Assert.assertFalse("Must NOT open manual settings panel", mockController.settingsPanelOpened);

        // 2. Turn OFF Do Not Disturb via voice
        CountDownLatch offLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> offRef = new AtomicReference<>();
        router.route("turn off do not disturb", result -> {
            offRef.set(result);
            offLatch.countDown();
        });
        Assert.assertTrue(offLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue("Command recognized", offRef.get().isCommand);
        Assert.assertEquals("HARDWARE_DND", offRef.get().toolBadge);
        Assert.assertFalse("DND hardware mode must be OFF", mockController.dndState);
        Assert.assertTrue("Response confirms inactive", offRef.get().spokenResponse.contains("confirmed inactive"));
        Assert.assertFalse("Must NOT open manual settings panel", mockController.settingsPanelOpened);

        // 3. Accessibility Service ON and OFF execution
        JarvisAccessibilityService.SystemCommandResult accOn = accService.executeSystemCommand("turn on dnd");
        Assert.assertTrue(accOn.success);
        Assert.assertTrue("DND turned ON via accessibility", mockController.dndState);

        JarvisAccessibilityService.SystemCommandResult accOff = accService.executeSystemCommand("turn off dnd");
        Assert.assertTrue(accOff.success);
        Assert.assertFalse("DND turned OFF via accessibility", mockController.dndState);
    }

    @Test
    public void testProgrammaticHardwareControl_VolumeBothOnAndOff() throws InterruptedException {
        // Authorize via Device Admin
        settings.setDeviceAdminEnabled(true);
        permissionManager.grantAllHardwareConsents();

        ProgrammaticMockDeviceController mockController = new ProgrammaticMockDeviceController(permissionManager);
        IntentRouter router = new IntentRouter(mockController, knowledgeSource, memoryManager);
        JarvisAccessibilityService accService = new JarvisAccessibilityService(mockController, settings);

        // 1. Turn OFF Volume (Mute to 0%) via voice
        CountDownLatch offLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> offRef = new AtomicReference<>();
        router.route("turn off volume", result -> {
            offRef.set(result);
            offLatch.countDown();
        });
        Assert.assertTrue(offLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue("Command recognized", offRef.get().isCommand);
        Assert.assertEquals("AUDIO_CONTROL", offRef.get().toolBadge);
        Assert.assertTrue("Volume must be muted (OFF)", mockController.volumeMuted);
        Assert.assertTrue("Response confirms muted", offRef.get().spokenResponse.contains("confirmed muted"));
        Assert.assertFalse("Must NOT open manual settings panel", mockController.settingsPanelOpened);

        // 2. Turn ON Volume (Unmute/Restore) via voice
        CountDownLatch onLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> onRef = new AtomicReference<>();
        router.route("turn on volume", result -> {
            onRef.set(result);
            onLatch.countDown();
        });
        Assert.assertTrue(onLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue("Command recognized", onRef.get().isCommand);
        Assert.assertEquals("AUDIO_CONTROL", onRef.get().toolBadge);
        Assert.assertFalse("Volume must be unmuted (ON)", mockController.volumeMuted);
        Assert.assertTrue("Response confirms turned on", onRef.get().spokenResponse.contains("confirmed at"));
        Assert.assertFalse("Must NOT open manual settings panel", mockController.settingsPanelOpened);

        // 3. Mute and Unmute alternate phrases
        CountDownLatch muteLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> muteRef = new AtomicReference<>();
        router.route("mute volume", result -> {
            muteRef.set(result);
            muteLatch.countDown();
        });
        Assert.assertTrue(muteLatch.await(3, TimeUnit.SECONDS));
        Assert.assertTrue("Volume muted", mockController.volumeMuted);

        CountDownLatch unmuteLatch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> unmuteRef = new AtomicReference<>();
        router.route("unmute volume", result -> {
            unmuteRef.set(result);
            unmuteLatch.countDown();
        });
        Assert.assertTrue(unmuteLatch.await(3, TimeUnit.SECONDS));
        Assert.assertFalse("Volume unmuted", mockController.volumeMuted);

        // 4. Accessibility Service Volume OFF and ON
        JarvisAccessibilityService.SystemCommandResult accOff = accService.executeSystemCommand("turn off volume");
        Assert.assertTrue(accOff.success);
        Assert.assertTrue("Volume turned off via accessibility", mockController.volumeMuted);

        JarvisAccessibilityService.SystemCommandResult accOn = accService.executeSystemCommand("turn on volume");
        Assert.assertTrue(accOn.success);
        Assert.assertFalse("Volume turned on via accessibility", mockController.volumeMuted);
    }
}
