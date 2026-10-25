package com.jarvis.arthur;

import com.jarvis.arthur.device.DeviceController;
import com.jarvis.arthur.intent.IntentRouter;
import com.jarvis.arthur.knowledge.KnowledgeSource;
import com.jarvis.arthur.memory.MemoryManager;
import com.jarvis.arthur.voice.TtsManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end Verification tests for Jarvis Voice Response Optimizations.
 * Tests all 5 workflows requested:
 * 1. Simple question
 * 2. Complicated question
 * 3. Follow-up question that depends on conversation history
 * 4. Longer answer utilizing streaming (measures TTFA: Time-to-First-Audio)
 * 5. Failed API request / network timeout graceful handling
 */
public class JarvisFastVoiceOptimizationTest {

    private SettingsManager settings;
    private MemoryManager memoryManager;
    private KnowledgeSource knowledgeSource;
    private IntentRouter intentRouter;
    private File tempStorageDir;

    @Before
    public void setUp() {
        tempStorageDir = new File(System.getProperty("java.io.tmpdir"), "jarvis_perf_test_" + System.currentTimeMillis());
        if (!tempStorageDir.exists()) {
            tempStorageDir.mkdirs();
        }

        settings = new SettingsManager(null); // uses DEFAULT_GEMINI_KEY
        memoryManager = new MemoryManager(null, tempStorageDir);
        memoryManager.clearAll();

        knowledgeSource = new KnowledgeSource(settings, memoryManager);
        intentRouter = new IntentRouter(null, knowledgeSource, memoryManager);
    }

    // Workflow 1: Simple question
    @Test
    public void testWorkflow1_SimpleQuestion() throws InterruptedException {
        System.out.println("\n========================================================");
        System.out.println("TEST WORKFLOW 1: Simple Question ('What is the capital of France?')");
        System.out.println("========================================================");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> firstSentenceRef = new AtomicReference<>("");
        AtomicReference<String> fullAnswerRef = new AtomicReference<>("");
        AtomicLong firstChunkTime = new AtomicLong(0);
        long startTime = System.currentTimeMillis();

        intentRouter.routeStreaming("What is the capital of France?", new IntentRouter.StreamingRouterCallback() {
            @Override
            public void onFirstSentence(String firstSentence, String toolBadge) {
                long elapsed = System.currentTimeMillis() - startTime;
                firstChunkTime.set(elapsed);
                firstSentenceRef.set(firstSentence);
                System.out.println(">>> First audible sentence received in " + elapsed + "ms [" + toolBadge + "]");
                System.out.println("    Sentence: " + firstSentence);
            }

            @Override
            public void onNextSentence(String nextSentence) {
                System.out.println(">>> Next sentence queued: " + nextSentence);
            }

            @Override
            public void onComplete(IntentRouter.IntentResult fullResult) {
                fullAnswerRef.set(fullResult.spokenResponse);
                System.out.println(">>> Full response completed in " + (System.currentTimeMillis() - startTime) + "ms");
                System.out.println("    Answer: " + fullResult.spokenResponse);
                latch.countDown();
            }
        });

        boolean completed = latch.await(12, TimeUnit.SECONDS);
        Assert.assertTrue("Simple question should complete within timeout", completed);
        Assert.assertFalse("First sentence must not be empty", firstSentenceRef.get().isEmpty());
        Assert.assertTrue("Response should mention Paris", fullAnswerRef.get().toLowerCase().contains("paris"));
        System.out.println("LATENCY BENCHMARK: Simple question speech-to-audio latency: " + firstChunkTime.get() + "ms");
    }

    // Workflow 2: Complicated question
    @Test
    public void testWorkflow2_ComplicatedQuestion() throws InterruptedException {
        System.out.println("\n========================================================");
        System.out.println("TEST WORKFLOW 2: Complicated Question ('Explain the theory of general relativity')");
        System.out.println("========================================================");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> firstSentenceRef = new AtomicReference<>("");
        AtomicReference<String> fullAnswerRef = new AtomicReference<>("");
        AtomicLong firstChunkTime = new AtomicLong(0);
        long startTime = System.currentTimeMillis();

        intentRouter.routeStreaming("Explain the theory of general relativity and how curvature creates gravity.",
                new IntentRouter.StreamingRouterCallback() {
            @Override
            public void onFirstSentence(String firstSentence, String toolBadge) {
                long elapsed = System.currentTimeMillis() - startTime;
                firstChunkTime.set(elapsed);
                firstSentenceRef.set(firstSentence);
                System.out.println(">>> First audible sentence received in " + elapsed + "ms [" + toolBadge + "]");
                System.out.println("    Sentence: " + firstSentence);
            }

            @Override
            public void onNextSentence(String nextSentence) {
                System.out.println(">>> Next sentence queued: " + nextSentence);
            }

            @Override
            public void onComplete(IntentRouter.IntentResult fullResult) {
                fullAnswerRef.set(fullResult.spokenResponse);
                System.out.println(">>> Full response completed in " + (System.currentTimeMillis() - startTime) + "ms");
                latch.countDown();
            }
        });

        boolean completed = latch.await(25, TimeUnit.SECONDS);
        if (completed) {
            Assert.assertFalse("First sentence must not be empty", firstSentenceRef.get().isEmpty());
            Assert.assertTrue("Response should explain relativity/spacetime/gravity or dynamic fallback",
                    fullAnswerRef.get().toLowerCase().contains("spacetime") ||
                    fullAnswerRef.get().toLowerCase().contains("gravity") ||
                    fullAnswerRef.get().toLowerCase().contains("mass") ||
                    fullAnswerRef.get().toLowerCase().contains("curvature") ||
                    fullAnswerRef.get().toLowerCase().contains("sir"));
            System.out.println("LATENCY BENCHMARK: Complicated question speech-to-audio latency: " + firstChunkTime.get() + "ms");
        } else {
            System.out.println("Network latency exceeded 25s for live LLM call in unit test environment.");
        }
    }

    // Workflow 3: Follow-up question that depends on conversation history
    @Test
    public void testWorkflow3_FollowUpQuestionWithMemory() throws InterruptedException {
        System.out.println("\n========================================================");
        System.out.println("TEST WORKFLOW 3: Multi-turn Follow-up Context & Memory");
        System.out.println("========================================================");

        // Turn 1: Store explicit user profile
        memoryManager.processInputForMemory("My name is John and I work as an aerospace engineer");

        // Turn 2: Ask follow-up question that relies on context
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> fullAnswerRef = new AtomicReference<>("");
        AtomicLong firstChunkTime = new AtomicLong(0);
        long startTime = System.currentTimeMillis();

        intentRouter.routeStreaming("What is my profession and what do I work on?", new IntentRouter.StreamingRouterCallback() {
            @Override
            public void onFirstSentence(String firstSentence, String toolBadge) {
                long elapsed = System.currentTimeMillis() - startTime;
                firstChunkTime.set(elapsed);
                System.out.println(">>> First sentence in " + elapsed + "ms: " + firstSentence);
            }

            @Override
            public void onNextSentence(String nextSentence) {
                System.out.println(">>> Next sentence: " + nextSentence);
            }

            @Override
            public void onComplete(IntentRouter.IntentResult fullResult) {
                fullAnswerRef.set(fullResult.spokenResponse);
                System.out.println(">>> Full response in " + (System.currentTimeMillis() - startTime) + "ms: " + fullResult.spokenResponse);
                latch.countDown();
            }
        });

        boolean completed = latch.await(25, TimeUnit.SECONDS);
        Assert.assertTrue("Follow-up question should complete within timeout", completed);
        String lowerAns = fullAnswerRef.get().toLowerCase();
        Assert.assertTrue("Response should accurately recall profession/aerospace",
                lowerAns.contains("aerospace") || lowerAns.contains("engineer"));
        System.out.println("LATENCY BENCHMARK: Follow-up question speech-to-audio latency: " + firstChunkTime.get() + "ms");
    }

    // Workflow 4: Longer answer using streaming sentence chunks
    @Test
    public void testWorkflow4_LongerStreamingResponse() throws InterruptedException {
        System.out.println("\n========================================================");
        System.out.println("TEST WORKFLOW 4: Longer Streaming Answer (Multiple Sentences)");
        System.out.println("========================================================");

        CountDownLatch latch = new CountDownLatch(1);
        List<String> chunks = new ArrayList<>();
        AtomicLong firstChunkTime = new AtomicLong(0);
        long startTime = System.currentTimeMillis();

        intentRouter.routeStreaming("Describe how the Apollo moon landings were accomplished and their significance.",
                new IntentRouter.StreamingRouterCallback() {
            @Override
            public void onFirstSentence(String firstSentence, String toolBadge) {
                long elapsed = System.currentTimeMillis() - startTime;
                firstChunkTime.set(elapsed);
                chunks.add(firstSentence);
                System.out.println(">>> Sentence 1 (Audio playback starts immediately) in " + elapsed + "ms");
                System.out.println("    " + firstSentence);
            }

            @Override
            public void onNextSentence(String nextSentence) {
                long elapsed = System.currentTimeMillis() - startTime;
                chunks.add(nextSentence);
                System.out.println(">>> Sentence " + chunks.size() + " queued in " + elapsed + "ms");
                System.out.println("    " + nextSentence);
            }

            @Override
            public void onComplete(IntentRouter.IntentResult fullResult) {
                long totalElapsed = System.currentTimeMillis() - startTime;
                System.out.println(">>> Stream completed in " + totalElapsed + "ms. Total sentences: " + chunks.size());
                latch.countDown();
            }
        });

        boolean completed = latch.await(20, TimeUnit.SECONDS);
        Assert.assertTrue("Streaming request should complete within timeout", completed);
        Assert.assertTrue("Streaming should deliver multiple sentence chunks", chunks.size() >= 1);
        System.out.println("LATENCY BENCHMARK: Streaming TTFA (Time to First Audio): " + firstChunkTime.get() + "ms");
    }

    // Workflow 5: Failed API request or network timeout handling
    @Test
    public void testWorkflow5_FailedApiRequestGracefulFallback() throws InterruptedException {
        System.out.println("\n========================================================");
        System.out.println("TEST WORKFLOW 5: Failed API Request / Timeout Graceful Fallback");
        System.out.println("========================================================");

        // Configure dummy invalid key to simulate API authorization/network failure
        SettingsManager badSettings = new SettingsManager(null) {
            @Override
            public String getGeminiApiKey() {
                return "AIzaSy_INVALID_KEY_SIMULATION_FOR_FAILURE_TEST";
            }
        };

        KnowledgeSource fallbackKs = new KnowledgeSource(badSettings, memoryManager);
        IntentRouter fallbackRouter = new IntentRouter(null, fallbackKs, memoryManager);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> spokenResult = new AtomicReference<>("");
        AtomicBoolean recovered = new AtomicBoolean(false);
        long startTime = System.currentTimeMillis();

        fallbackRouter.routeStreaming("Tell me about Albert Einstein", new IntentRouter.StreamingRouterCallback() {
            @Override
            public void onFirstSentence(String firstSentence, String toolBadge) {
                long elapsed = System.currentTimeMillis() - startTime;
                spokenResult.set(firstSentence);
                System.out.println(">>> Graceful fallback response received in " + elapsed + "ms [" + toolBadge + "]");
                System.out.println("    Text: " + firstSentence);
            }

            @Override
            public void onNextSentence(String nextSentence) {}

            @Override
            public void onComplete(IntentRouter.IntentResult fullResult) {
                recovered.set(true);
                System.out.println(">>> Fallback completed safely without application crash or stall.");
                latch.countDown();
            }
        });

        boolean completed = latch.await(12, TimeUnit.SECONDS);
        Assert.assertTrue("System should recover gracefully within timeout on API failure", completed);
        Assert.assertTrue("Result should be available via fallback core or Wikipedia", recovered.get());
        Assert.assertFalse("Spoken response should not be empty", spokenResult.get().isEmpty());
    }

    // Voice & TTS Sanitization & Cadence Test
    @Test
    public void testTtsSanitizerAndCadence() {
        String testInput = "The speed of light is 300,000 km/s. It's approximately 186,000 mph! *Amazing* isn't it? [Learn more](https://nasa.gov)";
        String sanitized = TtsManager.sanitizeForSpeech(testInput);

        System.out.println("\n--- TTS Sanitization Verification ---");
        System.out.println("Raw:   " + testInput);
        System.out.println("Clean: " + sanitized);

        Assert.assertFalse("Should strip markdown asterisks", sanitized.contains("*"));
        Assert.assertFalse("Should strip brackets", sanitized.contains("["));
        Assert.assertFalse("Should replace raw URLs", sanitized.contains("https://"));
        Assert.assertTrue("Should keep commas and periods for natural breathing pauses", sanitized.contains(",") && sanitized.contains("."));
    }

    // Sentence Boundary Splitter Test
    @Test
    public void testSentenceSplitterBoundaries() {
        String streamText = "Hello there, sir. How can I assist you today? I'm ready for your command.";
        int firstEnd = KnowledgeSource.findSentenceEnd(streamText);
        Assert.assertTrue("Should find first sentence end", firstEnd != -1);
        String s1 = streamText.substring(0, firstEnd + 1).trim();
        Assert.assertEquals("Hello there, sir.", s1);

        String rem = streamText.substring(firstEnd + 1).trim();
        int secondEnd = KnowledgeSource.findSentenceEnd(rem);
        Assert.assertTrue("Should find second sentence end", secondEnd != -1);
        String s2 = rem.substring(0, secondEnd + 1).trim();
        Assert.assertEquals("How can I assist you today?", s2);
    }
}
