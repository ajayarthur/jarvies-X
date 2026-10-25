package com.jarvis.arthur;

import com.jarvis.arthur.device.DeviceController;
import com.jarvis.arthur.device.HardwarePermissionManager;
import com.jarvis.arthur.service.JarvisAccessibilityService;
import com.jarvis.arthur.voice.WakeWordDetector;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit Test Suite for Wake Word Detection and Post-Task Silence Cooldown.
 * Verifies:
 * 1. Strict wake word detection: only responds to 'Jarvis', 'Hey Jarvis', 'OK Jarvis', etc.
 * 2. Suppression of random background speech, ambient conversations, and non-wake utterances.
 * 3. Accurate command extraction from leading, trailing, and embedded wake words.
 * 4. Post-task silence cooldown: enforces a mandatory quiet window after task completion before reactivating.
 * 5. Jarvis remains completely quiet in between commands when background chatter is present.
 * 6. Accessibility Service integration: rejects commands lacking the wake word or arriving during cooldown.
 */
public class WakeWordDetectorTest {

    private WakeWordDetector detector;
    private SettingsManager settings;
    private HardwarePermissionManager permissionManager;
    private DeviceController deviceController;
    private JarvisAccessibilityService accessibilityService;

    @Before
    public void setUp() {
        detector = new WakeWordDetector(2000L); // 2 second cooldown for tests
        detector.resetCooldown();

        settings = new SettingsManager(null);
        settings.resetAllHardwarePermissionConfirmations();
        settings.setWakeWordRequired(true);
        settings.setPostTaskCooldownSeconds(2);

        permissionManager = new HardwarePermissionManager(settings);
        deviceController = new DeviceController(null, permissionManager);
        accessibilityService = new JarvisAccessibilityService(deviceController, settings);
    }

    @Test
    public void testStrictWakeWordDetectionPositiveCases() {
        String[] validWakePhrases = new String[]{
                "Jarvis",
                "jarvis",
                "JARVIS",
                "Hey Jarvis",
                "hey jarvis",
                "OK Jarvis",
                "Okay Jarvis",
                "Hello Jarvis",
                "Hi Jarvis",
                "Yo Jarvis",
                "Jarvis, turn on wifi",
                "Hey Jarvis, turn off bluetooth",
                "OK Jarvis, volume up",
                "Turn off do not disturb, Jarvis",
                "Can you check the battery, Jarvis?"
        };

        for (String phrase : validWakePhrases) {
            Assert.assertTrue("Should recognize wake word in: '" + phrase + "'",
                    detector.hasWakeWord(phrase));
        }
    }

    @Test
    public void testStrictWakeWordDetectionRejectsBackgroundSpeech() {
        String[] backgroundSpeech = new String[]{
                "Hello everyone in the conference room",
                "Can someone pass the water bottle please?",
                "The weather is very warm today in Paris",
                "Turn on the living room television",
                "Hey Google, what is the traffic like?",
                "Alexa, play some jazz music",
                "Did you see that football match yesterday?",
                "I am discussing the quarterly financial reports",
                "Pass the salt",
                "What time does the train arrive?",
                "Good morning Bob",
                "Jarvises are cool", // Not isolated word
                "",
                "   "
        };

        for (String speech : backgroundSpeech) {
            Assert.assertFalse("Should REJECT background speech: '" + speech + "'",
                    detector.hasWakeWord(speech));

            WakeWordDetector.EvaluationResult eval = detector.evaluateSpeech(speech);
            Assert.assertEquals("Should reject without wake word for: '" + speech + "'",
                    WakeWordDetector.EvaluationResult.Action.REJECTED_NO_WAKE_WORD, eval.action);
            Assert.assertTrue("Should remain quiet for: '" + speech + "'",
                    eval.shouldRemainQuiet());
            Assert.assertFalse("Should NOT be accepted for: '" + speech + "'",
                    eval.isAccepted());
        }

        // Null speech test
        Assert.assertFalse("Null speech must be rejected", detector.hasWakeWord(null));
        Assert.assertTrue("Null speech should remain quiet", detector.evaluateSpeech(null).shouldRemainQuiet());
    }

    @Test
    public void testCommandExtraction() {
        // 1. Wake word alone (should extract empty string to prompt "Yes, sir?")
        Assert.assertEquals("", detector.extractCommand("Jarvis"));
        Assert.assertEquals("", detector.extractCommand("Hey Jarvis"));
        Assert.assertEquals("", detector.extractCommand("OK Jarvis,"));
        Assert.assertEquals("", detector.extractCommand("Hello Jarvis!"));

        // 2. Leading wake word with commands
        Assert.assertEquals("turn on wifi", detector.extractCommand("Jarvis, turn on wifi"));
        Assert.assertEquals("turn off bluetooth", detector.extractCommand("Hey Jarvis, turn off bluetooth"));
        Assert.assertEquals("set volume to 80%", detector.extractCommand("OK Jarvis, set volume to 80%"));
        Assert.assertEquals("what is the capital of France?", detector.extractCommand("Hello Jarvis, what is the capital of France?"));

        // 3. Trailing wake word with commands
        Assert.assertEquals("turn off wifi", detector.extractCommand("turn off wifi, Jarvis"));
        Assert.assertEquals("mute the audio", detector.extractCommand("mute the audio, hey Jarvis"));

        // 4. Non-wake word input must return null
        Assert.assertNull(detector.extractCommand("Pass me the water bottle"));
        Assert.assertNull(detector.extractCommand("What is the time?"));
    }

    @Test
    public void testPostTaskSilenceCooldownBehavior() throws InterruptedException {
        detector.setCooldownDurationMillis(500L); // 500ms for fast test execution
        detector.resetCooldown();

        Assert.assertFalse("Initially should not be in cooldown", detector.isInCooldown());

        // Step 1: User issues a command and Jarvis completes the task
        WakeWordDetector.EvaluationResult firstCmd = detector.evaluateSpeech("Jarvis, turn on wifi");
        Assert.assertEquals(WakeWordDetector.EvaluationResult.Action.ACCEPTED_COMMAND, firstCmd.action);
        Assert.assertEquals("turn on wifi", firstCmd.command);

        // Mark task completed (TTS finished speaking)
        detector.markTaskCompleted();
        Assert.assertTrue("Must be in cooldown immediately after task completion", detector.isInCooldown());
        Assert.assertTrue("Remaining cooldown must be > 0", detector.getRemainingCooldownMillis() > 0);

        // Step 2: During silence cooldown window, any new utterance is rejected to ensure quiet silence
        WakeWordDetector.EvaluationResult duringCooldown = detector.evaluateSpeech("Jarvis, turn off wifi");
        Assert.assertEquals("Speech during cooldown must be rejected",
                WakeWordDetector.EvaluationResult.Action.REJECTED_COOLDOWN, duringCooldown.action);
        Assert.assertTrue("Must remain quiet during cooldown", duringCooldown.shouldRemainQuiet());
        Assert.assertFalse("Must not accept command during cooldown", duringCooldown.isAccepted());

        // Step 3: Wait for silence cooldown duration to elapse
        Thread.sleep(550L);

        Assert.assertFalse("Cooldown must have expired after silence period", detector.isInCooldown());
        Assert.assertEquals(0L, detector.getRemainingCooldownMillis());

        // Step 4: After silence cooldown elapses, Jarvis is reactivated and accepts wake word again
        WakeWordDetector.EvaluationResult afterCooldown = detector.evaluateSpeech("Jarvis, turn off wifi");
        Assert.assertEquals("Must accept command after cooldown has elapsed",
                WakeWordDetector.EvaluationResult.Action.ACCEPTED_COMMAND, afterCooldown.action);
        Assert.assertEquals("turn off wifi", afterCooldown.command);
    }

    @Test
    public void testRemainsQuietInBetweenCommands() {
        // Set up detector in ready state
        detector.resetCooldown();

        // Simulate continuous ambient conversation in a room with background noise
        String[] ambientConversation = new String[]{
                "Did you remember to buy milk at the grocery store?",
                "Yes, I picked it up on the way home.",
                "Let's watch a movie tonight.",
                "Sounds great, what do you want to watch?",
                "Maybe an action movie.",
                "Alexa turn on the fan",
                "Ok Google what's the weather"
        };

        for (String sentence : ambientConversation) {
            WakeWordDetector.EvaluationResult eval = detector.evaluateSpeech(sentence);
            Assert.assertTrue("Jarvis must remain completely quiet for ambient chatter: '" + sentence + "'",
                    eval.shouldRemainQuiet());
            Assert.assertFalse("Must not trigger for: '" + sentence + "'", eval.isAccepted());
        }

        // Now user intentionally addresses Jarvis
        WakeWordDetector.EvaluationResult validWake = detector.evaluateSpeech("Hey Jarvis, turn on flashlight");
        Assert.assertTrue("Must activate when wake word is spoken", validWake.isAccepted());
        Assert.assertEquals(WakeWordDetector.EvaluationResult.Action.ACCEPTED_COMMAND, validWake.action);
        Assert.assertEquals("turn on flashlight", validWake.command);

        // Task completes
        detector.markTaskCompleted();

        // More ambient background chatter follows immediately
        WakeWordDetector.EvaluationResult postTaskChatter = detector.evaluateSpeech("The flashlight is bright!");
        Assert.assertTrue("Jarvis must remain quiet for post-task chatter", postTaskChatter.shouldRemainQuiet());
    }

    @Test
    public void testAccessibilityServiceWakeWordIntegration() {
        // Grant permissions
        settings.setDeviceAdminEnabled(true);
        permissionManager.grantAllHardwareConsents();
        WakeWordDetector.getDefault().resetCooldown();

        // 1. Random speech without wake word must be IGNORED and remain quiet
        JarvisAccessibilityService.SystemCommandResult randomSpeechRes =
                accessibilityService.onWakeWordDetected("turn on bluetooth");
        Assert.assertFalse("Must reject command without wake word", randomSpeechRes.success);
        Assert.assertEquals("IGNORED", randomSpeechRes.action);
        Assert.assertEquals("NO_WAKE_WORD", randomSpeechRes.detail);
        Assert.assertNull("Spoken message must be null to remain quiet", randomSpeechRes.spokenMessage);

        JarvisAccessibilityService.SystemCommandResult convRes =
                accessibilityService.onWakeWordDetected("Hey Bob, how are you doing today?");
        Assert.assertFalse("Must reject conversation", convRes.success);
        Assert.assertEquals("IGNORED", convRes.action);

        // 2. Wake word alone prompts standby
        JarvisAccessibilityService.SystemCommandResult wakeAloneRes =
                accessibilityService.onWakeWordDetected("Jarvis");
        Assert.assertTrue("Must recognize wake word alone", wakeAloneRes.success);
        Assert.assertEquals("WAKE_WORD", wakeAloneRes.action);
        Assert.assertEquals("STANDBY", wakeAloneRes.detail);

        // 3. Wake word + command executes properly
        WakeWordDetector.getDefault().resetCooldown();
        JarvisAccessibilityService.SystemCommandResult wakeCmdRes =
                accessibilityService.onWakeWordDetected("Jarvis, turn on bluetooth");
        Assert.assertEquals("BLUETOOTH", wakeCmdRes.action);

        // 4. Immediate subsequent speech during post-task cooldown is ignored
        WakeWordDetector.getDefault().markTaskCompleted();
        JarvisAccessibilityService.SystemCommandResult cooldownRes =
                accessibilityService.onWakeWordDetected("Jarvis, turn off bluetooth");
        Assert.assertFalse("Must be ignored during post-task cooldown", cooldownRes.success);
        Assert.assertEquals("IGNORED", cooldownRes.action);
        Assert.assertEquals("COOLDOWN", cooldownRes.detail);
        Assert.assertNull(cooldownRes.spokenMessage);

        // Reset singleton cooldown so other tests are not impacted
        WakeWordDetector.getDefault().resetCooldown();
    }
}
