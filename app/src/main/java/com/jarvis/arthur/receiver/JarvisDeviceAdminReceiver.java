package com.jarvis.arthur.receiver;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.jarvis.arthur.R;
import com.jarvis.arthur.SettingsManager;
import com.jarvis.arthur.device.HardwarePermissionManager;
import com.jarvis.arthur.util.StartupLogger;

/**
 * Device Administration Receiver for Jarvis Arthur.
 * Provides system-level device administration privileges:
 * - Direct background lock screen security (lockNow)
 * - Device policy and hardware subsystem management
 * - Automated execution of hardware commands without repeated permission prompts
 */
public class JarvisDeviceAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "JarvisDeviceAdmin";

    public static ComponentName getComponentName(@NonNull Context context) {
        return new ComponentName(context.getApplicationContext(), JarvisDeviceAdminReceiver.class);
    }

    public static boolean isDeviceAdminActive(@NonNull Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isAdminActive(getComponentName(context));
        } catch (Throwable t) {
            Log.w(TAG, "Error checking device admin status: " + t.getMessage());
            return false;
        }
    }

    public static Intent createAddDeviceAdminIntent(@NonNull Context context) {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(context));
        try {
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(R.string.device_admin_description));
        } catch (Throwable ignored) {
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Elevate Jarvis to Device Administrator for background hardware control and device protection.");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    public static boolean lockNow(@NonNull Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isAdminActive(getComponentName(context))) {
                dpm.lockNow();
                Log.i(TAG, "Device successfully locked via DevicePolicyManager.lockNow()");
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to lock device: " + t.getMessage(), t);
        }
        return false;
    }

    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        super.onEnabled(context, intent);
        StartupLogger.logStep("ADM-01", "Jarvis Device Administrator successfully enabled by user.");
        Log.i(TAG, "Device Administrator privileges granted.");

        try {
            SettingsManager settings = new SettingsManager(context);
            settings.setDeviceAdminEnabled(true);
            settings.setUnifiedPermissionsConfirmed(true);

            HardwarePermissionManager permManager = new HardwarePermissionManager(settings);
            permManager.grantAllHardwareConsents();

            Toast.makeText(context, "Jarvis Device Admin Activated // Hardware controls automated", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            StartupLogger.logError("ADM-01", "Error handling onEnabled", t);
        }
    }

    @Override
    public void onDisabled(@NonNull Context context, @NonNull Intent intent) {
        super.onDisabled(context, intent);
        StartupLogger.logWarning("ADM-02", "Jarvis Device Administrator was disabled.");
        Log.w(TAG, "Device Administrator privileges revoked.");

        try {
            SettingsManager settings = new SettingsManager(context);
            settings.setDeviceAdminEnabled(false);
            Toast.makeText(context, "Jarvis Device Administrator Deactivated", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "Error handling onDisabled: " + t.getMessage());
        }
    }

    @Override
    public CharSequence onDisableRequested(@NonNull Context context, @NonNull Intent intent) {
        StartupLogger.logWarning("ADM-03", "User requested to disable Jarvis Device Administrator.");
        return "Deactivating Device Administrator will disable background lock screen security and automated hardware management.";
    }

    @Override
    public void onPasswordFailed(@NonNull Context context, @NonNull Intent intent) {
        super.onPasswordFailed(context, intent);
        Log.d(TAG, "Device lock attempt failed.");
    }

    @Override
    public void onPasswordSucceeded(@NonNull Context context, @NonNull Intent intent) {
        super.onPasswordSucceeded(context, intent);
        Log.d(TAG, "Device unlocked successfully.");
    }
}
