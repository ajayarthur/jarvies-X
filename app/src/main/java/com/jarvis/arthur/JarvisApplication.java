package com.jarvis.arthur;

import android.app.Application;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.jarvis.arthur.util.StartupLogger;

/**
 * Custom Application class for Jarvis Arthur.
 * Sets up global uncaught exception handlers and startup telemetry
 * to capture and log any crash occurring during startup or runtime.
 */
public class JarvisApplication extends Application {
    private static final String TAG = "JarvisApplication";
    private static JarvisApplication instance;

    public static JarvisApplication getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // 1. Install Global Uncaught Exception Handler
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StartupLogger.recordCrash(this, thread, throwable);
            } catch (Exception e) {
                Log.e(TAG, "Failed to record startup crash: " + e.getMessage(), e);
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });

        // 2. Log System Telemetry Breadcrumb
        StartupLogger.logStep("APP-01", "JarvisApplication process initialized. PID=" + Process.myPid());
        StartupLogger.logStep("APP-02", String.format(
                "Device Hardware: %s %s (API %d, Android %s, ABI %s)",
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
                Build.VERSION.RELEASE, (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) ? Build.SUPPORTED_ABIS[0] : "unknown"
        ));

        // 3. Check for previous crash on last run
        String lastCrash = StartupLogger.getLastCrashReport(this);
        if (lastCrash != null) {
            StartupLogger.logWarning("APP-03", "A previous crash report was detected from last execution.");
            Log.e(TAG, "PREVIOUS SESSION CRASH:\n" + lastCrash);
        } else {
            StartupLogger.logStep("APP-03", "No previous crash reports detected. Clean session.");
        }
    }
}
