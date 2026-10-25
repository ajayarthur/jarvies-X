package com.jarvis.arthur;

import com.jarvis.arthur.intent.DynamicPhrasing;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests confirming that Jarvis produces varied, dynamic responses
 * instead of single fixed template fallbacks across different query types.
 */
public class JarvisDynamicResponseTest {

    @Test
    public void testDynamicGreetingsAreVaried() {
        Set<String> uniqueResponses = new HashSet<>();
        // Sample greeting generation 30 times
        for (int i = 0; i < 30; i++) {
            uniqueResponses.add(DynamicPhrasing.getDynamicGreeting());
        }

        System.out.println("--- GREETING TEST RESULTS ---");
        for (String r : uniqueResponses) {
            System.out.println("Variation: " + r);
        }

        // Must produce more than 1 variation (proves non-static template)
        Assert.assertTrue("Greetings should have multiple variations, found: " + uniqueResponses.size(),
                uniqueResponses.size() > 1);
    }

    @Test
    public void testDynamicPersonaResponsesAreVaried() {
        Set<String> creatorReplies = new HashSet<>();
        Set<String> identityReplies = new HashSet<>();
        Set<String> gratitudeReplies = new HashSet<>();

        for (int i = 0; i < 30; i++) {
            creatorReplies.add(DynamicPhrasing.getDynamicPersonaResponse("who created you"));
            identityReplies.add(DynamicPhrasing.getDynamicPersonaResponse("who are you"));
            gratitudeReplies.add(DynamicPhrasing.getDynamicPersonaResponse("thank you jarvis"));
        }

        System.out.println("--- PERSONA TEST RESULTS ---");
        System.out.println("Creator reply variations: " + creatorReplies.size());
        for (String r : creatorReplies) {
            System.out.println("  - " + r);
        }

        System.out.println("Identity reply variations: " + identityReplies.size());
        for (String r : identityReplies) {
            System.out.println("  - " + r);
        }

        Assert.assertTrue("Creator inquiries must have varied answers", creatorReplies.size() > 1);
        Assert.assertTrue("Identity inquiries must have varied answers", identityReplies.size() > 1);
        Assert.assertTrue("Gratitude replies must have varied answers", gratitudeReplies.size() > 1);
    }

    @Test
    public void testDynamicAppLaunchConfirmationsAreVaried() {
        Set<String> launchReplies = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            launchReplies.add(DynamicPhrasing.getAppLaunchResponse("YouTube"));
        }

        System.out.println("--- APP LAUNCH VARIATIONS ---");
        for (String r : launchReplies) {
            System.out.println("  - " + r);
        }

        Assert.assertTrue("App launch confirmations must have multiple variations", launchReplies.size() > 1);
    }

    @Test
    public void testDynamicHardwareConfirmationsAreVaried() {
        Set<String> flashOnReplies = new HashSet<>();
        Set<String> flashOffReplies = new HashSet<>();
        Set<String> volumeReplies = new HashSet<>();
        Set<String> batteryReplies = new HashSet<>();

        for (int i = 0; i < 30; i++) {
            flashOnReplies.add(DynamicPhrasing.getFlashlightResponse(true));
            flashOffReplies.add(DynamicPhrasing.getFlashlightResponse(false));
            volumeReplies.add(DynamicPhrasing.getVolumeResponse("70%"));
            batteryReplies.add(DynamicPhrasing.getBatteryResponse("85% and charging"));
        }

        System.out.println("--- HARDWARE VARIATIONS ---");
        System.out.println("Flashlight ON variations: " + flashOnReplies.size());
        System.out.println("Volume variations: " + volumeReplies.size());
        System.out.println("Battery variations: " + batteryReplies.size());

        Assert.assertTrue("Flashlight ON replies must have multiple variations", flashOnReplies.size() > 1);
        Assert.assertTrue("Flashlight OFF replies must have multiple variations", flashOffReplies.size() > 1);
        Assert.assertTrue("Volume replies must have multiple variations", volumeReplies.size() > 1);
        Assert.assertTrue("Battery replies must have multiple variations", batteryReplies.size() > 1);
    }

    @Test
    public void testDynamicMathPhrasingsAreVaried() {
        Set<String> mathReplies = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            mathReplies.add(DynamicPhrasing.getMathResponse("3,915"));
        }

        System.out.println("--- MATH VARIATIONS ---");
        for (String r : mathReplies) {
            System.out.println("  - " + r);
        }

        Assert.assertTrue("Math phrasing must have multiple variations", mathReplies.size() > 1);
    }

    @Test
    public void testDynamicKnowledgeExtractFormattingIsVaried() {
        Set<String> formattedExtracts = new HashSet<>();
        String rawExtract = "Quantum computing is a multidisciplinary field comprising aspects of computer science, physics, and mathematics.";
        for (int i = 0; i < 30; i++) {
            formattedExtracts.add(DynamicPhrasing.formatKnowledgeExtract("Quantum Computing", rawExtract));
        }

        System.out.println("--- KNOWLEDGE EXTRACT VARIATIONS ---");
        for (String r : formattedExtracts) {
            System.out.println("  - " + r);
        }

        Assert.assertTrue("Knowledge extract introductions must vary", formattedExtracts.size() > 1);
    }

    @Test
    public void testDynamicWeatherReportsAreVaried() {
        Set<String> weatherReports = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            weatherReports.add(DynamicPhrasing.formatWeatherResponse("London", "Light Rain", "14", "13", "82", "19"));
        }

        System.out.println("--- WEATHER VARIATIONS ---");
        for (String r : weatherReports) {
            System.out.println("  - " + r);
        }

        Assert.assertTrue("Weather reports must vary in phrasing structure", weatherReports.size() > 1);
    }

    @Test
    public void testTtsSanitizerProducesNaturalConversationalSpeech() {
        String rawGeminiOutput = "Here is the summary:\n* First point: Quantum states.\n* Second: Superposition at https://en.wikipedia.org/wiki/Quantum.\n```code```";
        String clean = com.jarvis.arthur.voice.TtsManager.sanitizeForSpeech(rawGeminiOutput);
        System.out.println("--- TTS SANITIZER RESULT ---");
        System.out.println("Before: " + rawGeminiOutput);
        System.out.println("After:  " + clean);

        Assert.assertFalse("Cleaned speech should not contain asterisks", clean.contains("*"));
        Assert.assertFalse("Cleaned speech should not contain raw URLs", clean.contains("https://"));
        Assert.assertFalse("Cleaned speech should not contain code block markers", clean.contains("```"));
    }
}
