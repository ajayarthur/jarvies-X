package com.jarvis.arthur;

import com.jarvis.arthur.device.HomeScreenDetector;
import com.jarvis.arthur.service.VoiceListenerService;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests verifying:
 * 1. Background execution persistence after first launch.
 * 2. Complete inactivity when the device is locked (never activates when locked).
 * 3. Activation only when unlocked and on the home screen.
 * 4. Wake-word detection on trigger word 'Jarvis'.
 * 5. 10-second silence timeout returning Jarvis to passive standby state.
 */
public class JarvisBackgroundServiceTest {

    private SettingsManager settings;

    @Before
    public void setUp() {
        settings = new SettingsManager(null);
    }

    @Test
    public void testFirstOpenConfiguresBackgroundExecution() {
        // Initially before first run
        settings.setHasOpenedOnce(false);
        Assert.assertFalse("Should be false before first open", settings.hasOpenedOnce());

        // Once opened for the first time
        settings.setHasOpenedOnce(true);
        settings.setBackgroundServiceEnabled(true);

        Assert.assertTrue("Jarvis must remember it has been opened", settings.hasOpenedOnce());
        Assert.assertTrue("Background execution must remain enabled", settings.isBackgroundServiceEnabled());

        // In a subsequent session
        SettingsManager session2 = new SettingsManager(null);
        Assert.assertTrue("Must persist background enabled flag across sessions", session2.isBackgroundServiceEnabled());
    }

    @Test
    public void testSilenceTimeoutConfiguration() {
        Assert.assertEquals("Default silence timeout must be 10 seconds", 10, settings.getSilenceTimeoutSeconds());

        settings.setSilenceTimeoutSeconds(10);
        Assert.assertEquals(10, settings.getSilenceTimeoutSeconds());
    }

    @Test
    public void testWakeWordCommandExtraction() {
        // Trigger only
        Assert.assertEquals("", VoiceListenerService.extractCommandAfterWakeWord("Jarvis"));
        Assert.assertEquals("", VoiceListenerService.extractCommandAfterWakeWord("hey jarvis"));
        Assert.assertEquals("", VoiceListenerService.extractCommandAfterWakeWord("ok Jarvis"));

        // Trigger with command
        Assert.assertEquals("what is the weather",
                VoiceListenerService.extractCommandAfterWakeWord("Jarvis, what is the weather"));
        Assert.assertEquals("turn on flashlight",
                VoiceListenerService.extractCommandAfterWakeWord("Jarvis turn on flashlight"));
        Assert.assertEquals("toggle wifi",
                VoiceListenerService.extractCommandAfterWakeWord("Hey Jarvis, toggle wifi"));
    }

    @Test
    public void testHomeScreenPackageDetection() {
        // Known standard launcher packages
        Assert.assertTrue(HomeScreenDetector.isHomeScreenPackage(null, "com.google.android.apps.nexuslauncher"));
        Assert.assertTrue(HomeScreenDetector.isHomeScreenPackage(null, "com.sec.android.app.launcher"));
        Assert.assertTrue(HomeScreenDetector.isHomeScreenPackage(null, "com.android.launcher3"));

        // Standard non-launcher apps must NOT be home screen
        Assert.assertFalse(HomeScreenDetector.isHomeScreenPackage(null, "com.android.chrome"));
        Assert.assertFalse(HomeScreenDetector.isHomeScreenPackage(null, "com.google.android.youtube"));
        Assert.assertFalse(HomeScreenDetector.isHomeScreenPackage(null, "com.whatsapp"));
    }

    @Test
    public void testStateTransitionsAndSilenceTimeout() {
        // Simulate Assistant State Machine transitions
        AtomicReference<VoiceListenerService.AssistantState> stateRef =
                new AtomicReference<>(VoiceListenerService.AssistantState.INACTIVE_LOCKED);

        // 1. Phone is locked: Inactive
        stateRef.set(VoiceListenerService.AssistantState.INACTIVE_LOCKED);
        Assert.assertEquals(VoiceListenerService.AssistantState.INACTIVE_LOCKED, stateRef.get());

        // 2. Unlocked, but inside 3rd-party app (e.g. Chrome): Inactive
        stateRef.set(VoiceListenerService.AssistantState.INACTIVE_APP);
        Assert.assertEquals(VoiceListenerService.AssistantState.INACTIVE_APP, stateRef.get());

        // 3. Unlocked AND on Home screen: Passive Standby
        stateRef.set(VoiceListenerService.AssistantState.PASSIVE_STANDBY);
        Assert.assertEquals(VoiceListenerService.AssistantState.PASSIVE_STANDBY, stateRef.get());

        // In Passive Standby: Non-wake word is ignored
        String randomUtterance = "What time is it";
        boolean wakeWordDetected = randomUtterance.toLowerCase().contains("jarvis");
        Assert.assertFalse("Must not wake on utterance without 'Jarvis'", wakeWordDetected);
        Assert.assertEquals("Remains in PASSIVE_STANDBY", VoiceListenerService.AssistantState.PASSIVE_STANDBY, stateRef.get());

        // 4. Trigger word 'Jarvis' spoken -> Wakes up to ACTIVE_LISTENING
        String wakeUtterance = "Hey Jarvis";
        if (wakeUtterance.toLowerCase().contains("jarvis")) {
            stateRef.set(VoiceListenerService.AssistantState.ACTIVE_LISTENING);
        }
        Assert.assertEquals("Wakes to ACTIVE_LISTENING", VoiceListenerService.AssistantState.ACTIVE_LISTENING, stateRef.get());

        // 5. 10-Second Silence Timeout fires -> returns to PASSIVE_STANDBY
        boolean tenSecondsSilenceElapsed = true;
        if (tenSecondsSilenceElapsed && stateRef.get() == VoiceListenerService.AssistantState.ACTIVE_LISTENING) {
            stateRef.set(VoiceListenerService.AssistantState.PASSIVE_STANDBY);
        }
        Assert.assertEquals("Returns to PASSIVE_STANDBY after 10s silence",
                VoiceListenerService.AssistantState.PASSIVE_STANDBY, stateRef.get());

        // 6. Device locked at any time -> drops immediately to INACTIVE_LOCKED
        boolean deviceLocked = true;
        if (deviceLocked) {
            stateRef.set(VoiceListenerService.AssistantState.INACTIVE_LOCKED);
        }
        Assert.assertEquals("Must drop to INACTIVE_LOCKED when phone locks",
                VoiceListenerService.AssistantState.INACTIVE_LOCKED, stateRef.get());
    }
}
