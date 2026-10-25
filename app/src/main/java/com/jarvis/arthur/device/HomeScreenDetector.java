package com.jarvis.arthur.device;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.PowerManager;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility for detecting Android device lock status and home screen / launcher presence.
 * Ensures Jarvis stays inactive when locked and only activates on the home screen.
 */
public class HomeScreenDetector {
    private static final String TAG = "HomeScreenDetector";

    /**
     * Checks if the device is currently locked or screen is powered off.
     */
    public static boolean isDeviceLocked(Context context) {
        if (context == null) return false;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                return true; // Screen is off
            }

            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                return true; // Device keyguard is locked
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking device lock status: " + e.getMessage());
        }
        return false;
    }

    /**
     * Resolves all installed launcher / home screen application package names.
     */
    public static Set<String> getHomeScreenPackages(Context context) {
        Set<String> packages = new HashSet<>();

        if (context != null) {
            try {
                PackageManager pm = context.getPackageManager();
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);

            List<ResolveInfo> defaultHome = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (defaultHome != null) {
                for (ResolveInfo info : defaultHome) {
                    if (info.activityInfo != null && info.activityInfo.packageName != null) {
                        packages.add(info.activityInfo.packageName);
                    }
                }
            }

            List<ResolveInfo> allHome = pm.queryIntentActivities(homeIntent, 0);
            if (allHome != null) {
                for (ResolveInfo info : allHome) {
                    if (info.activityInfo != null && info.activityInfo.packageName != null) {
                        packages.add(info.activityInfo.packageName);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error resolving home screen packages: " + e.getMessage());
        }
    }

        // Common default fallback packages for system launchers
        packages.add("com.google.android.apps.nexuslauncher");
        packages.add("com.android.launcher3");
        packages.add("com.sec.android.app.launcher");
        packages.add("com.miui.home");
        packages.add("com.huawei.android.launcher");
        packages.add("com.oppo.launcher");
        packages.add("com.bbk.launcher2");
        packages.add("com.oneplus.launcher");
        packages.add("com.motorola.launcher3");

        return packages;
    }

    /**
     * Checks if a specified package belongs to the device's home screen launcher.
     */
    public static boolean isHomeScreenPackage(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        Set<String> homePkgs = getHomeScreenPackages(context);
        return homePkgs.contains(packageName.trim());
    }
}
