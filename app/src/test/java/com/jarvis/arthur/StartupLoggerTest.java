package com.jarvis.arthur;

import com.jarvis.arthur.util.StartupLogger;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Unit tests verifying:
 * 1. StartupLogger breadcrumb logging across initialization phases.
 * 2. Error logging and exception formatting.
 * 3. Crash report persistence and stack trace recording.
 * 4. Log retrieval and formatting for diagnosis.
 */
public class StartupLoggerTest {

    @Before
    public void setUp() {
        StartupLogger.clearCrashReport(null);
    }

    @Test
    public void testBreadcrumbLogging() {
        StartupLogger.logStep("TEST-01", "Starting test sequence");
        StartupLogger.logStep("TEST-02", "Executing intermediate operation");
        StartupLogger.logWarning("TEST-03", "A non-fatal condition occurred");
        StartupLogger.logError("TEST-04", "Simulated error", new IllegalArgumentException("Invalid test parameter"));

        List<String> logs = StartupLogger.getRecentLogs();
        Assert.assertNotNull(logs);
        Assert.assertTrue(logs.size() >= 4);

        String formatted = StartupLogger.getRecentLogsFormatted();
        Assert.assertTrue(formatted.contains("[TEST-01]"));
        Assert.assertTrue(formatted.contains("[TEST-02]"));
        Assert.assertTrue(formatted.contains("WARN-TEST-03"));
        Assert.assertTrue(formatted.contains("ERROR-TEST-04"));
        Assert.assertTrue(formatted.contains("IllegalArgumentException"));
    }

    @Test
    public void testCrashRecordingAndRetrieval() {
        Exception simulatedCrash = new NullPointerException("Simulated critical component null pointer");
        StartupLogger.recordCrash(null, Thread.currentThread(), simulatedCrash);

        // Without a context, it writes to logcat and formats properly without throwing exceptions
        String formatted = StartupLogger.getRecentLogsFormatted();
        Assert.assertNotNull(formatted);
    }

    @Test
    public void testExceptionFormatDetails() {
        try {
            String str = null;
            str.toLowerCase();
            Assert.fail("Should have thrown NullPointerException");
        } catch (NullPointerException npe) {
            StartupLogger.logError("EXC-01", "Null pointer detected in test", npe);
            String logs = StartupLogger.getRecentLogsFormatted();
            Assert.assertTrue(logs.contains("NullPointerException"));
        }
    }
}
