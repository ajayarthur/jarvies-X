package com.jarvis.arthur;

import com.jarvis.arthur.memory.MemoryManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * Robust Unit tests verifying local device storage, 500 MB quota tracking,
 * device isolation, smart pruning, and explicit memory retention for Jarvis Arthur.
 */
public class JarvisMemoryTest {

    private File tempStorageDir;
    private MemoryManager memoryManager;

    @Before
    public void setUp() {
        tempStorageDir = new File(System.getProperty("java.io.tmpdir"), "jarvis_unit_test_" + System.currentTimeMillis());
        if (!tempStorageDir.exists()) {
            tempStorageDir.mkdirs();
        }
        memoryManager = new MemoryManager(null, tempStorageDir);
        memoryManager.clearAll();
    }

    @Test
    public void testSetAndGetBasicMemory() {
        memoryManager.setFact("name", "Arthur");
        memoryManager.setFact("role", "Lead AI Engineer");

        Assert.assertEquals("Arthur", memoryManager.getFact("name"));
        Assert.assertEquals("Lead AI Engineer", memoryManager.getFact("role"));
        Assert.assertTrue(memoryManager.hasFact("name"));
        Assert.assertTrue(memoryManager.hasFact("role"));
    }

    @Test
    public void testLocalStoragePersistenceAcrossInstances() {
        // Save facts in first instance
        memoryManager.processInputForMemory("My name is Arthur");
        memoryManager.processInputForMemory("Remember my role is Systems Architect");
        memoryManager.processInputForMemory("My favorite language is Python");
        memoryManager.processInputForMemory("Remember that door code is 9021");

        // Create a completely new instance pointing to the exact same local device storage directory
        MemoryManager secondInstance = new MemoryManager(null, tempStorageDir);

        Assert.assertEquals("Arthur", secondInstance.getFact("name"));
        Assert.assertEquals("Systems Architect", secondInstance.getFact("role"));
        Assert.assertEquals("Python", secondInstance.getFact("favorite_language"));

        // Verify the door code fact persists in local storage
        boolean factPersisted = false;
        for (String val : secondInstance.getAllFacts().values()) {
            if (val.contains("door code is 9021")) {
                factPersisted = true;
                break;
            }
        }
        Assert.assertTrue("Door code must persist on local disk across instances", factPersisted);
    }

    @Test
    public void testDeviceIsolation() {
        // Verify storage directory is device-isolated
        File storageDir = memoryManager.getDeviceStorageDir();
        Assert.assertNotNull(storageDir);
        Assert.assertTrue("Storage dir name must include vault namespace", storageDir.getName().startsWith("jarvis_vault_"));

        // Confirm isolation between two different device directories
        MemoryManager secondDeviceManager = new MemoryManager(null, tempStorageDir, "dev_secondary_phone");

        memoryManager.setFact("secret_key", "device_one_secret");
        Assert.assertNull("Second device must not access first device's isolated storage", secondDeviceManager.getFact("secret_key"));
        secondDeviceManager.setFact("secret_key", "device_two_secret");
        Assert.assertEquals("device_one_secret", memoryManager.getFact("secret_key"));
        Assert.assertEquals("device_two_secret", secondDeviceManager.getFact("secret_key"));
    }

    @Test
    public void testStorageUsageTrackingAnd500MbLimit() {
        // 500 MB limit
        Assert.assertEquals(500.0, memoryManager.getStorageLimitMb(), 0.001);
        Assert.assertEquals(500L * 1024L * 1024L, MemoryManager.MAX_STORAGE_BYTES);

        // Usage tracking
        long usedBytes = memoryManager.getStorageUsedBytes();
        Assert.assertTrue("Used bytes must be >= 0", usedBytes >= 0);

        double usedMb = memoryManager.getStorageUsedMb();
        Assert.assertTrue("Used MB must be >= 0", usedMb >= 0.0);

        double pct = memoryManager.getUsagePercentage();
        Assert.assertTrue("Usage pct must be >= 0 and <= 100", pct >= 0.0 && pct <= 100.0);

        // Status summary output
        String summary = memoryManager.processInputForMemory("check storage");
        Assert.assertNotNull(summary);
        Assert.assertTrue(summary.contains("500 MB"));
        Assert.assertTrue(summary.contains("isolated in private storage"));
    }

    @Test
    public void testSmartPruningPreservesKeyFactsWhileDeletingOldData() {
        // 1. Store critical key details (Identity, Preferences, Important facts)
        memoryManager.processInputForMemory("My name is Arthur");
        memoryManager.processInputForMemory("My role is Chief Architect");
        memoryManager.processInputForMemory("My favorite language is Rust");
        memoryManager.processInputForMemory("Remember that sister's birthday is October 25th");

        // 2. Generate multiple conversation turns and session logs
        for (int i = 1; i <= 6; i++) {
            memoryManager.recordConversationTurn("Question turn " + i, "Answer response " + i);
        }

        // Verify turns exist
        Assert.assertTrue(memoryManager.getRecentTurns(10).size() > 3);

        // 3. User commands pruning: "Prune old data"
        String pruneFeedback = memoryManager.processInputForMemory("prune old data");
        Assert.assertNotNull(pruneFeedback);
        Assert.assertTrue(pruneFeedback.toLowerCase().contains("cleanup complete"));

        // 4. VERIFY: Critical user details are 100% PRESERVED
        Assert.assertEquals("Arthur", memoryManager.getFact("name"));
        Assert.assertEquals("Chief Architect", memoryManager.getFact("role"));
        Assert.assertEquals("Rust", memoryManager.getFact("favorite_language"));

        boolean factPreserved = false;
        for (String val : memoryManager.getAllFacts().values()) {
            if (val.contains("sister's birthday is October 25th")) {
                factPreserved = true;
                break;
            }
        }
        Assert.assertTrue("Sister's birthday fact must be preserved during pruning", factPreserved);

        // 5. VERIFY: Old turns were pruned to top 3
        Assert.assertEquals(3, memoryManager.getRecentTurns(10).size());
    }

    @Test
    public void testExplicitNameAndRoleStorage() {
        String feedback = memoryManager.processInputForMemory("My name is Arthur and I am a software engineer");
        Assert.assertNotNull("Feedback should confirm memory extraction", feedback);

        Assert.assertEquals("Arthur", memoryManager.getFact("name"));
        Assert.assertEquals("software engineer", memoryManager.getFact("role"));

        String updateFeedback = memoryManager.processInputForMemory("Remember my role is Chief AI Architect");
        Assert.assertNotNull(updateFeedback);
        Assert.assertEquals("Chief AI Architect", memoryManager.getFact("role"));
    }

    @Test
    public void testExplicitPreferencesAndFavorites() {
        String favFeedback = memoryManager.processInputForMemory("My favorite language is Python");
        Assert.assertNotNull(favFeedback);
        Assert.assertTrue(favFeedback.contains("Python"));
        Assert.assertEquals("Python", memoryManager.getFact("favorite_language"));

        String prefFeedback = memoryManager.processInputForMemory("I prefer dark mode");
        Assert.assertNotNull(prefFeedback);
        Assert.assertTrue(prefFeedback.contains("dark mode"));

        String allPrefs = memoryManager.processInputForMemory("what are my preferences");
        Assert.assertNotNull(allPrefs);
        Assert.assertTrue(allPrefs.contains("Python"));
        Assert.assertTrue(allPrefs.contains("dark mode"));

        String favQuery = memoryManager.processInputForMemory("what is my favorite language");
        Assert.assertNotNull(favQuery);
        Assert.assertTrue(favQuery.contains("Python"));
    }

    @Test
    public void testTargetedRecallForCustomTopics() {
        memoryManager.processInputForMemory("Remember that door code is 4492");
        memoryManager.processInputForMemory("My favorite framework is Next.js");

        String recallCode = memoryManager.processInputForMemory("Do you remember door code?");
        Assert.assertNotNull(recallCode);
        Assert.assertTrue(recallCode.contains("4492"));

        String recallFramework = memoryManager.processInputForMemory("Do you remember my favorite framework?");
        Assert.assertNotNull(recallFramework);
        Assert.assertTrue(recallFramework.contains("Next.js"));

        String missingRecall = memoryManager.processInputForMemory("Do you remember my gym locker?");
        Assert.assertNotNull(missingRecall);
        Assert.assertTrue(missingRecall.toLowerCase().contains("don't have anything stored"));
    }

    @Test
    public void testDynamicUpdatingOverwritesStaleData() {
        memoryManager.processInputForMemory("My role is junior developer");
        Assert.assertEquals("junior developer", memoryManager.getFact("role"));

        memoryManager.processInputForMemory("Remember my role is Lead System Architect");
        Assert.assertEquals("Lead System Architect", memoryManager.getFact("role"));
        Assert.assertNotEquals("junior developer", memoryManager.getFact("role"));

        memoryManager.processInputForMemory("My favorite language is Java");
        Assert.assertEquals("Java", memoryManager.getFact("favorite_language"));

        memoryManager.processInputForMemory("Remember my favorite language is Rust");
        Assert.assertEquals("Rust", memoryManager.getFact("favorite_language"));
        Assert.assertNotEquals("Java", memoryManager.getFact("favorite_language"));
    }

    @Test
    public void testDynamicRemovalAndExplicitForgetting() {
        memoryManager.processInputForMemory("My favorite language is Python");
        memoryManager.processInputForMemory("Remember that flight is at 6 PM");

        memoryManager.processInputForMemory("What is the weather today?");
        memoryManager.processInputForMemory("Calculate 50 * 20");

        Assert.assertEquals("Python", memoryManager.getFact("favorite_language"));

        String forgetFav = memoryManager.processInputForMemory("Forget my favorite language");
        Assert.assertNotNull(forgetFav);
        Assert.assertTrue(forgetFav.toLowerCase().contains("removed"));
        Assert.assertNull(memoryManager.getFact("favorite_language"));

        boolean flightFound = false;
        for (String val : memoryManager.getAllFacts().values()) {
            if (val.contains("flight is at 6 PM")) {
                flightFound = true;
                break;
            }
        }
        Assert.assertTrue("Flight fact must still remain in memory", flightFound);

        String forgetFlight = memoryManager.processInputForMemory("Forget that flight is at 6 PM");
        Assert.assertNotNull(forgetFlight);
        Assert.assertTrue(forgetFlight.toLowerCase().contains("removed"));
    }

    @Test
    public void testClearAllMemory() {
        memoryManager.setFact("name", "Arthur");
        memoryManager.setFact("role", "Researcher");
        memoryManager.recordConversationTurn("Hello", "Hi Arthur");

        String feedback = memoryManager.processInputForMemory("Clear memory");
        Assert.assertNotNull(feedback);
        Assert.assertTrue(feedback.toLowerCase().contains("cleared"));

        Assert.assertNull(memoryManager.getFact("name"));
        Assert.assertNull(memoryManager.getFact("role"));
        Assert.assertTrue(memoryManager.getRecentTurns(5).isEmpty());
    }

    @Test
    public void testStrictAvoidanceOfIrrelevantConversationalData() {
        int initialSize = memoryManager.getAllFacts().size();

        Assert.assertNull(memoryManager.processInputForMemory("I am tired today"));
        Assert.assertNull(memoryManager.processInputForMemory("I am eating lunch right now"));
        Assert.assertNull(memoryManager.processInputForMemory("I am feeling great"));
        Assert.assertNull(memoryManager.processInputForMemory("What is 45 * 87?"));
        Assert.assertNull(memoryManager.processInputForMemory("What's the weather in London today?"));
        Assert.assertNull(memoryManager.processInputForMemory("Turn on the flashlight"));
        Assert.assertNull(memoryManager.processInputForMemory("Open YouTube"));

        Assert.assertEquals("Zero new facts should be added from irrelevant conversation",
                initialSize, memoryManager.getAllFacts().size());
    }

    @Test
    public void testPromptContextInjection() {
        memoryManager.setFact("name", "Arthur");
        memoryManager.setFact("role", "Autonomous Systems Creator");
        memoryManager.processInputForMemory("My favorite language is Python");
        memoryManager.processInputForMemory("Remember that project Apollo launches Friday");

        String context = memoryManager.getMemoryPromptContext();
        Assert.assertTrue("Context must contain user name", context.contains("Arthur"));
        Assert.assertTrue("Context must contain role", context.contains("Autonomous Systems Creator"));
        Assert.assertTrue("Context must contain favorite language", context.contains("Python"));
        Assert.assertTrue("Context must contain Apollo fact", context.contains("Apollo launches Friday"));
        Assert.assertTrue("Context must contain memory directive", context.contains("Memory Directive"));
    }
}
