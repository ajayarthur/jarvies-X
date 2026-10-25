package com.jarvis.arthur;

import com.jarvis.arthur.device.DeviceController;
import com.jarvis.arthur.device.HardwarePermissionManager;
import com.jarvis.arthur.intent.DynamicPhrasing;
import com.jarvis.arthur.intent.IntentRouter;
import com.jarvis.arthur.knowledge.KnowledgeSource;
import com.jarvis.arthur.memory.MemoryManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests verifying:
 * 1. Single unified permission request flow at first launch.
 * 2. Secure and permanent persistence across sessions (remembered forever).
 * 3. Execution of each hardware action (Wi-Fi, Bluetooth, DND, volume, torch, camera, mobile data) only after approval.
 * 4. Confirmation that no further permission prompts appear unless revoked manually.
 */
public class HardwarePermissionTest {

    private SettingsManager settings;
    private HardwarePermissionManager permissionManager;
    private DeviceController deviceController;
    private MemoryManager memoryManager;
    private KnowledgeSource knowledgeSource;
    private IntentRouter intentRouter;

    @Before
    public void setUp() {
        settings = new SettingsManager(null);
        settings.resetAllHardwarePermissionConfirmations();

        permissionManager = new HardwarePermissionManager(settings);
        deviceController = new DeviceController(null, permissionManager);

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "jarvis_hw_test_" + System.currentTimeMillis());
        if (!tempDir.exists()) tempDir.mkdirs();
        memoryManager = new MemoryManager(null, tempDir);
        knowledgeSource = new KnowledgeSource(settings, memoryManager);
        intentRouter = new IntentRouter(deviceController, knowledgeSource, memoryManager);
    }

    @Test
    public void testPermissionKeysAndInitialState() {
        Assert.assertFalse("Unified consent must initially be false", permissionManager.hasUnifiedConsent());
        Assert.assertFalse("All consents must initially be false", permissionManager.hasAllHardwareConsents());

        for (String permKey : HardwarePermissionManager.ALL_HARDWARE_PERMISSIONS) {
            Assert.assertFalse("Permission " + permKey + " must not have consent initially",
                    permissionManager.hasConsent(permKey));
            Assert.assertNotNull("Display name for " + permKey + " must not be null",
                    HardwarePermissionManager.getDisplayName(permKey));
            Assert.assertNotNull("Description for " + permKey + " must not be null",
                    HardwarePermissionManager.getDescription(permKey));
        }
    }

    @Test
    public void testSingleUnifiedPermissionFlowAndPermanentPersistence() {
        // Step 1: User completes the single unified flow at first launch
        permissionManager.grantAllHardwareConsents();

        Assert.assertTrue("Unified consent flag must be set", permissionManager.hasUnifiedConsent());
        Assert.assertTrue("All hardware consents flag must be set", permissionManager.hasAllHardwareConsents());

        // Verify every hardware feature is authorized
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_WIFI));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_BLUETOOTH));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_DND));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_MOBILE_DATA));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_CAMERA));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_FLASHLIGHT));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_VOLUME));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_AUDIO));

        // Step 2: Simulate subsequent app sessions (app closed and reopened forever)
        HardwarePermissionManager session2 = new HardwarePermissionManager(settings);
        Assert.assertTrue("Session 2 must remember unified consent forever", session2.hasUnifiedConsent());
        Assert.assertTrue("Session 2 must remember all hardware consents forever", session2.hasAllHardwareConsents());

        // Step 3: Verify that NO further permission prompts appear
        AtomicBoolean onCompleteCalled = new AtomicBoolean(false);
        session2.requestUnifiedPermissions(null, () -> onCompleteCalled.set(true));
        Assert.assertTrue("requestUnifiedPermissions must immediately proceed without prompts when already authorized",
                onCompleteCalled.get());
    }

    @Test
    public void testIndividualGrantConsentAndPersistenceAcrossSessions() {
        // Grant permissions individually
        permissionManager.grantConsent(HardwarePermissionManager.PERM_WIFI);
        permissionManager.grantConsent(HardwarePermissionManager.PERM_BLUETOOTH);
        permissionManager.grantConsent(HardwarePermissionManager.PERM_DND);
        permissionManager.grantConsent(HardwarePermissionManager.PERM_MOBILE_DATA);
        permissionManager.grantConsent(HardwarePermissionManager.PERM_CAMERA);
        permissionManager.grantConsent(HardwarePermissionManager.PERM_FLASHLIGHT);
        permissionManager.grantConsent(HardwarePermissionManager.PERM_VOLUME);

        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_WIFI));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_BLUETOOTH));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_DND));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_MOBILE_DATA));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_CAMERA));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_FLASHLIGHT));
        Assert.assertTrue(permissionManager.hasConsent(HardwarePermissionManager.PERM_VOLUME));

        // In a new session
        HardwarePermissionManager session2Manager = new HardwarePermissionManager(settings);
        Assert.assertTrue(session2Manager.hasConsent(HardwarePermissionManager.PERM_WIFI));
        Assert.assertTrue(session2Manager.hasConsent(HardwarePermissionManager.PERM_BLUETOOTH));
        Assert.assertTrue(session2Manager.hasConsent(HardwarePermissionManager.PERM_DND));
        Assert.assertTrue(session2Manager.hasConsent(HardwarePermissionManager.PERM_MOBILE_DATA));
        Assert.assertTrue(session2Manager.hasConsent(HardwarePermissionManager.PERM_CAMERA));
        Assert.assertTrue(session2Manager.hasConsent(HardwarePermissionManager.PERM_FLASHLIGHT));
        Assert.assertTrue(session2Manager.hasConsent(HardwarePermissionManager.PERM_VOLUME));
    }

    @Test
    public void testNoFurtherPromptsUnlessManuallyRevoked() {
        // Authorize all permissions
        permissionManager.grantAllHardwareConsents();
        Assert.assertTrue(permissionManager.hasUnifiedConsent());

        // Manual revocation / reset in Settings
        settings.resetAllHardwarePermissionConfirmations();
        Assert.assertFalse("Unified consent must be cleared on manual reset", permissionManager.hasUnifiedConsent());
        Assert.assertFalse("Individual permissions must be cleared on manual reset",
                permissionManager.hasConsent(HardwarePermissionManager.PERM_WIFI));
        Assert.assertFalse("Individual permissions must be cleared on manual reset",
                permissionManager.hasConsent(HardwarePermissionManager.PERM_BLUETOOTH));

        // After manual revocation, actions require approval again
        DeviceController.CommandResult wifiRes = deviceController.setWifi(true);
        Assert.assertFalse(wifiRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", wifiRes.detail);
    }

    @Test
    public void testDeviceControllerGatingWithoutConsent() {
        // When consent is not granted, hardware actions must fail with NEEDS_PERMISSION detail
        DeviceController.CommandResult wifiRes = deviceController.setWifi(true);
        Assert.assertFalse(wifiRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", wifiRes.detail);
        Assert.assertTrue(wifiRes.spokenMessage.contains("consent"));

        DeviceController.CommandResult btRes = deviceController.setBluetooth(true);
        Assert.assertFalse(btRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", btRes.detail);

        DeviceController.CommandResult dndRes = deviceController.setDoNotDisturb(true);
        Assert.assertFalse(dndRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", dndRes.detail);

        DeviceController.CommandResult dataRes = deviceController.openMobileDataSettings();
        Assert.assertFalse(dataRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", dataRes.detail);

        DeviceController.CommandResult camRes = deviceController.openCamera();
        Assert.assertFalse(camRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", camRes.detail);

        DeviceController.CommandResult flashRes = deviceController.setFlashlight(true);
        Assert.assertFalse(flashRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", flashRes.detail);

        DeviceController.CommandResult volRes = deviceController.adjustVolume("UP", 50);
        Assert.assertFalse(volRes.success);
        Assert.assertEquals("NEEDS_PERMISSION", volRes.detail);
    }

    @Test
    public void testDeviceControllerGatingWithUnifiedConsentGranted() {
        // When unified consent is granted, all hardware actions proceed without NEEDS_PERMISSION
        permissionManager.grantAllHardwareConsents();

        DeviceController.CommandResult wifiRes = deviceController.setWifi(true);
        Assert.assertNotEquals("NEEDS_PERMISSION", wifiRes.detail);

        DeviceController.CommandResult btRes = deviceController.setBluetooth(true);
        Assert.assertNotEquals("NEEDS_PERMISSION", btRes.detail);

        DeviceController.CommandResult dndRes = deviceController.setDoNotDisturb(true);
        Assert.assertNotEquals("NEEDS_PERMISSION", dndRes.detail);

        DeviceController.CommandResult dataRes = deviceController.openMobileDataSettings();
        Assert.assertNotEquals("NEEDS_PERMISSION", dataRes.detail);

        DeviceController.CommandResult camRes = deviceController.openCamera();
        Assert.assertNotEquals("NEEDS_PERMISSION", camRes.detail);

        DeviceController.CommandResult flashRes = deviceController.setFlashlight(true);
        Assert.assertNotEquals("NEEDS_PERMISSION", flashRes.detail);

        DeviceController.CommandResult volRes = deviceController.adjustVolume("UP", 50);
        Assert.assertNotEquals("NEEDS_PERMISSION", volRes.detail);
    }

    @Test
    public void testIntentRoutingForHardwareCommands() throws InterruptedException {
        // Test Wi-Fi command routing
        assertHardwareRouting("Turn on Wi-Fi", "HARDWARE_WIFI");
        assertHardwareRouting("Disable wifi", "HARDWARE_WIFI");

        // Test Bluetooth command routing
        assertHardwareRouting("Turn on Bluetooth", "HARDWARE_BLUETOOTH");
        assertHardwareRouting("Deactivate bluetooth", "HARDWARE_BLUETOOTH");

        // Test Do Not Disturb routing
        assertHardwareRouting("Turn on Do Not Disturb", "HARDWARE_DND");
        assertHardwareRouting("Disable DND mode", "HARDWARE_DND");

        // Test Mobile Data routing
        assertHardwareRouting("Open mobile data settings", "HARDWARE_DATA");

        // Test Camera routing
        assertHardwareRouting("Open camera", "HARDWARE_CAMERA");
        assertHardwareRouting("Take a photo", "HARDWARE_CAMERA");

        // Test Flashlight routing
        assertHardwareRouting("Turn on flashlight", "HARDWARE_TORCH");

        // Test Volume routing
        assertHardwareRouting("Volume up", "AUDIO_CONTROL");
        assertHardwareRouting("Mute volume", "AUDIO_CONTROL");
    }

    private void assertHardwareRouting(String query, String expectedBadge) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<IntentRouter.IntentResult> resultRef = new AtomicReference<>();

        intentRouter.route(query, new IntentRouter.RouterCallback() {
            @Override
            public void onResult(IntentRouter.IntentResult result) {
                resultRef.set(result);
                latch.countDown();
            }
        });

        Assert.assertTrue("Timed out waiting for routing of: " + query, latch.await(3, TimeUnit.SECONDS));
        IntentRouter.IntentResult res = resultRef.get();
        Assert.assertNotNull(res);
        Assert.assertTrue("Hardware action must be recognized as a command: " + query, res.isCommand);
        Assert.assertEquals("Expected tool badge " + expectedBadge + " for query: " + query,
                expectedBadge, res.toolBadge);
    }

    @Test
    public void testDynamicPhrasingHardwareResponses() {
        String wifiOn = DynamicPhrasing.getWifiResponse(true);
        String wifiOff = DynamicPhrasing.getWifiResponse(false);
        Assert.assertNotNull(wifiOn);
        Assert.assertNotNull(wifiOff);
        Assert.assertFalse(wifiOn.isEmpty());
        Assert.assertFalse(wifiOff.isEmpty());

        String btOn = DynamicPhrasing.getBluetoothResponse(true);
        String btOff = DynamicPhrasing.getBluetoothResponse(false);
        Assert.assertNotNull(btOn);
        Assert.assertNotNull(btOff);

        String dndOn = DynamicPhrasing.getDndResponse(true);
        String dndOff = DynamicPhrasing.getDndResponse(false);
        Assert.assertNotNull(dndOn);
        Assert.assertNotNull(dndOff);

        String dataRes = DynamicPhrasing.getMobileDataResponse();
        Assert.assertNotNull(dataRes);
    }
}
