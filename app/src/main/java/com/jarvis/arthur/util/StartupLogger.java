package com.jarvis.arthur.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostic logger for Jarvis startup sequence and crash diagnosis.
 * Records step-by-step breadcrumbs, catches exceptions, and persists crash logs.
 */
public class StartupLogger {
    public static final String TAG = "JarvisStartup";
    private static final String PREF_NAME = "jarvis_startup_diagnostics";
    private static final String KEY_LAST_CRASH = "last_crash_report";
    private static final String CRASH_FILE_NAME = "startup_crash.log";
    private static final int MAX_BREADCRUMBS = 100;

    private static final List<String> breadcrumbs = Collections.synchronizedList(new ArrayList<>());
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static void logStep(String stepNumber, String message) {
        String timestamp = TIME_FORMAT.format(new Date());
        String entry = String.format("[%s] [%s] %s", timestamp, stepNumber, message);
        breadcrumbs.add(entry);
        if (breadcrumbs.size() > MAX_BREADCRUMBS) {
            breadcrumbs.remove(0);
        }
        Log.i(TAG, entry);
    }

    public static void logWarning(String stepNumber, String message) {
        String timestamp = TIME_FORMAT.format(new Date());
        String entry = String.format("[%s] [WARN-%s] ⚠️ %s", timestamp, stepNumber, message);
        breadcrumbs.add(entry);
        if (breadcrumbs.size() > MAX_BREADCRUMBS) {
            breadcrumbs.remove(0);
        }
        Log.w(TAG, entry);
    }

    public static void logError(String stepNumber, String message, Throwable throwable) {
        String timestamp = TIME_FORMAT.format(new Date());
        String errorMsg = message + (throwable != null ? " (" + throwable.getClass().getSimpleName() + ": " + throwable.getMessage() + ")" : "");
        String entry = String.format("[%s] [ERROR-%s] ❌ %s", timestamp, stepNumber, errorMsg);
        breadcrumbs.add(entry);
        if (breadcrumbs.size() > MAX_BREADCRUMBS) {
            breadcrumbs.remove(0);
        }
        Log.e(TAG, entry, throwable);
    }

    public static List<String> getRecentLogs() {
        synchronized (breadcrumbs) {
            return new ArrayList<>(breadcrumbs);
        }
    }

    public static String getRecentLogsFormatted() {
        StringBuilder sb = new StringBuilder();
        synchronized (breadcrumbs) {
            for (String line : breadcrumbs) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    public static void recordCrash(Context context, Thread thread, Throwable throwable) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String stackTrace = sw.toString();

        StringBuilder crashReport = new StringBuilder();
        crashReport.append("========================================\n");
        crashReport.append("💥 JARVIS STARTUP FATAL CRASH REPORT\n");
        crashReport.append("Timestamp: ").append(timestamp).append("\n");
        crashReport.append("Thread: ").append(thread != null ? thread.getName() : "unknown").append("\n");
        crashReport.append("Exception: ").append(throwable.getClass().getName()).append(": ").append(throwable.getMessage()).append("\n");
        crashReport.append("========================================\n");
        crashReport.append("RECENT STARTUP BREADCRUMBS:\n");
        crashReport.append(getRecentLogsFormatted());
        crashReport.append("========================================\n");
        crashReport.append("STACK TRACE:\n");
        crashReport.append(stackTrace);
        crashReport.append("========================================\n");

        String fullReport = crashReport.toString();
        Log.e("JARVIS_STARTUP_CRASH", fullReport);

        // Save to SharedPreferences
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_LAST_CRASH, fullReport).apply();
            } catch (Exception e) {
                Log.e(TAG, "Failed to save crash to SharedPreferences: " + e.getMessage());
            }

            // Save to persistent file
            try {
                File crashFile = new File(context.getFilesDir(), CRASH_FILE_NAME);
                try (FileOutputStream fos = new FileOutputStream(crashFile, false)) {
                    fos.write(fullReport.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to write crash file: " + e.getMessage());
            }
        }
    }

    public static String getLastCrashReport(Context context) {
        if (context == null) return null;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String report = prefs.getString(KEY_LAST_CRASH, null);
            if (report != null && !report.isEmpty()) {
                return report;
            }
        } catch (Exception ignored) {}

        try {
            File crashFile = new File(context.getFilesDir(), CRASH_FILE_NAME);
            if (crashFile.exists() && crashFile.length() > 0) {
                java.io.FileInputStream fis = new java.io.FileInputStream(crashFile);
                byte[] data = new byte[(int) crashFile.length()];
                fis.read(data);
                fis.close();
                return new String(data, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}

        return null;
    }

    public static void clearCrashReport(Context context) {
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                prefs.edit().remove(KEY_LAST_CRASH).apply();
                File crashFile = new File(context.getFilesDir(), CRASH_FILE_NAME);
                if (crashFile.exists()) {
                    crashFile.delete();
                }
            } catch (Exception ignored) {}
        }
    }
}
