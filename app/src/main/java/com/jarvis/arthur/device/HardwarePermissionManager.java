package com.jarvis.arthur.device;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import android.view.View;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.jarvis.arthur.SettingsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages explicit user confirmations, Android system runtime permissions,
 * and persistent consent storage for full hardware control across sessions.
 * Ensures each permission is asked only once and remembered across sessions.
 */
public class HardwarePermissionManager {
    private static final String TAG = "HardwarePermManager";
    public static final int UNIFIED_PERMISSION_REQ_CODE = 2001;

    // Hardware Permission Keys
    public static final String PERM_DEVICE_ADMIN = "device_admin";
    public static final String PERM_WIFI = "wifi";
    public static final String PERM_BLUETOOTH = "bluetooth";
    public static final String PERM_MOBILE_DATA = "mobile_data";
    public static final String PERM_FLASHLIGHT = "flashlight";
    public static final String PERM_CAMERA = "camera";
    public static final String PERM_VOLUME = "volume";
    public static final String PERM_DND = "dnd";
    public static final String PERM_AUDIO = "audio";

    public static final List<String> ALL_HARDWARE_PERMISSIONS = Arrays.asList(
            PERM_DEVICE_ADMIN,
            PERM_AUDIO,
            PERM_CAMERA,
            PERM_FLASHLIGHT,
            PERM_WIFI,
            PERM_BLUETOOTH,
            PERM_VOLUME,
            PERM_DND,
            PERM_MOBILE_DATA
    );

    public interface ConsentCallback {
        void onGranted(String permissionKey);
        void onDenied(String permissionKey);
    }

    private final SettingsManager settings;

    public HardwarePermissionManager(SettingsManager settings) {
        this.settings = settings;
    }

    /**
     * Checks if explicit user consent was already granted and stored in persistent preferences.
     */
    public boolean hasConsent(String permissionKey) {
        if (settings == null) return false;
        if (settings.isDeviceAdminEnabled()) return true;
        if (settings.isUnifiedPermissionsConfirmed()) return true;
        if (settings.isAccessibilityPermissionGranted()) return true;
        return settings.isHardwarePermissionConfirmed(permissionKey);
    }

    public boolean hasUnifiedConsent() {
        return settings != null && (settings.isDeviceAdminEnabled() || settings.isUnifiedPermissionsConfirmed() || settings.isAccessibilityPermissionGranted());
    }

    public boolean hasAllHardwareConsents() {
        if (hasUnifiedConsent()) return true;
        for (String key : ALL_HARDWARE_PERMISSIONS) {
            if (!hasConsent(key)) return false;
        }
        return true;
    }

    public void grantAllHardwareConsents() {
        if (settings != null) {
            settings.setDeviceAdminEnabled(true);
            settings.setUnifiedPermissionsConfirmed(true);
            settings.setAccessibilityPermissionGranted(true);
            for (String key : ALL_HARDWARE_PERMISSIONS) {
                settings.setHardwarePermissionConfirmed(key, true);
            }
            Log.i(TAG, "All hardware permissions granted in unified flow and remembered forever.");
        }
    }

    /**
     * Stores user confirmation in persistent preferences so Jarvis never prompts again.
     */
    public void grantConsent(String permissionKey) {
        if (settings != null) {
            settings.setHardwarePermissionConfirmed(permissionKey, true);
            Log.i(TAG, "Explicit consent permanently stored for: " + permissionKey);
        }
    }

    /**
     * Resets consent for testing or user settings update.
     */
    public void revokeConsent(String permissionKey) {
        if (settings != null) {
            settings.setHardwarePermissionConfirmed(permissionKey, false);
        }
    }

    /**
     * Returns whether the underlying Android OS runtime permission / system policy is granted.
     */
    public boolean isSystemPermissionGranted(Context context, String permissionKey) {
        if (context == null) return false;

        switch (permissionKey) {
            case PERM_DEVICE_ADMIN:
                return com.jarvis.arthur.receiver.JarvisDeviceAdminReceiver.isDeviceAdminActive(context);

            case PERM_AUDIO:
                return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

            case PERM_CAMERA:
            case PERM_FLASHLIGHT:
                return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;

            case PERM_BLUETOOTH:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
                }
                return true; // Granted in manifest for Android 11 and below

            case PERM_WIFI:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    return ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
                }
                return true; // Granted in manifest

            case PERM_DND:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    return nm != null && nm.isNotificationPolicyAccessGranted();
                }
                return true;

            case PERM_VOLUME:
            case PERM_MOBILE_DATA:
                return true; // Handled via Android manifest permissions and system settings panels

            default:
                return false;
        }
    }

    /**
     * Requests user confirmation for a hardware permission.
     * If already granted & confirmed in a previous session, immediately executes callback.
     * Otherwise, displays explicit consent prompt and stores confirmation permanently upon approval.
     */
    public void requestConsent(Activity activity, String permissionKey, ConsentCallback callback) {
        if (activity == null) {
            if (callback != null) callback.onDenied(permissionKey);
            return;
        }

        boolean alreadyConfirmed = hasConsent(permissionKey);
        boolean systemGranted = isSystemPermissionGranted(activity, permissionKey);

        if (alreadyConfirmed && systemGranted) {
            Log.d(TAG, "Hardware permission already confirmed and active across sessions: " + permissionKey);
            if (callback != null) callback.onGranted(permissionKey);
            return;
        }

        // Prompt user for explicit hardware confirmation
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
                if (callback != null) callback.onDenied(permissionKey);
                return;
            }

            try {
                String title = "HARDWARE ACCESS // " + getDisplayName(permissionKey).toUpperCase();
                String message = getDescription(permissionKey) +
                        "\n\nOnce granted, Jarvis will remember this confirmation across sessions for seamless hands-free control.";

                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("ALLOW & REMEMBER", (d, which) -> {
                            grantConsent(permissionKey);
                            requestAndroidSystemPermission(activity, permissionKey);
                            if (callback != null) callback.onGranted(permissionKey);
                        })
                        .setNegativeButton("DENY", (d, which) -> {
                            if (callback != null) callback.onDenied(permissionKey);
                        })
                        .create();

                dialog.show();
            } catch (Throwable t) {
                Log.w(TAG, "Failed to display consent dialog: " + t.getMessage());
                if (callback != null) callback.onDenied(permissionKey);
            }
        });
    }

    private void requestAndroidSystemPermission(Activity activity, String permissionKey) {
        switch (permissionKey) {
            case PERM_AUDIO:
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, 101);
                break;

            case PERM_CAMERA:
            case PERM_FLASHLIGHT:
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, 102);
                break;

            case PERM_BLUETOOTH:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ActivityCompat.requestPermissions(activity,
                            new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, 103);
                }
                break;

            case PERM_WIFI:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(activity,
                            new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 104);
                }
                break;

            case PERM_DND:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null && !nm.isNotificationPolicyAccessGranted()) {
                        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                    }
                }
                break;

            default:
                break;
        }
    }

    public List<String> getUngrantedSystemPermissions(Context context) {
        List<String> list = new ArrayList<>();
        if (context == null) return list;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }
        return list;
    }

    /**
     * Single, unified permission request flow at the very first launch.
     * Displays a comprehensive dialog for all hardware capabilities,
     * stores them forever across sessions upon approval, and triggers batch system requests.
     */
    public void requestUnifiedPermissions(Activity activity, Runnable onComplete) {
        if (activity == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        List<String> ungrantedSystem = getUngrantedSystemPermissions(activity);
        boolean allConfirmed = hasAllHardwareConsents();

        if (allConfirmed && ungrantedSystem.isEmpty()) {
            Log.d(TAG, "All hardware permissions already authorized and active forever. Zero prompts required.");
            if (onComplete != null) onComplete.run();
            return;
        }

        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
                if (onComplete != null) onComplete.run();
                return;
            }

            try {
                View view = activity.getLayoutInflater().inflate(com.jarvis.arthur.R.layout.dialog_unified_permissions, null);
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setView(view)
                        .setCancelable(false)
                        .create();

                View btnAuth = view.findViewById(com.jarvis.arthur.R.id.btnAuthorizeAll);
                if (btnAuth != null) {
                    btnAuth.setOnClickListener(v -> {
                        grantAllHardwareConsents();
                        dialog.dismiss();

                        // 1. Android runtime permissions
                        List<String> neededSys = getUngrantedSystemPermissions(activity);
                        if (!neededSys.isEmpty()) {
                            ActivityCompat.requestPermissions(activity, neededSys.toArray(new String[0]), UNIFIED_PERMISSION_REQ_CODE);
                        }

                        // 2. Device Administrator activation
                        if (!com.jarvis.arthur.receiver.JarvisDeviceAdminReceiver.isDeviceAdminActive(activity)) {
                            try {
                                Intent adminIntent = com.jarvis.arthur.receiver.JarvisDeviceAdminReceiver.createAddDeviceAdminIntent(activity);
                                activity.startActivity(adminIntent);
                            } catch (Exception e) {
                                Log.w(TAG, "Could not launch Device Admin intent: " + e.getMessage());
                            }
                        }

                        // 3. Do-Not-Disturb notification policy permission
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
                            if (nm != null && !nm.isNotificationPolicyAccessGranted()) {
                                try {
                                    Intent dndIntent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                                    dndIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    activity.startActivity(dndIntent);
                                } catch (Exception ignored) {}
                            }
                        }

                        if (onComplete != null) onComplete.run();
                    });
                }

                View btnDismiss = view.findViewById(com.jarvis.arthur.R.id.btnDismissUnified);
                if (btnDismiss != null) {
                    btnDismiss.setOnClickListener(v -> {
                        dialog.dismiss();
                        if (onComplete != null) onComplete.run();
                    });
                }

                dialog.show();
            } catch (Throwable t) {
                Log.w(TAG, "Failed to display unified permissions dialog: " + t.getMessage());
                if (onComplete != null) onComplete.run();
            }
        });
    }

    /**
     * Preflight check at startup: executes the single unified permission flow once,
     * ensuring no further permission prompts appear unless revoked manually.
     */
    public void checkInitialHardwarePermissions(Activity activity, Runnable onComplete) {
        requestUnifiedPermissions(activity, onComplete);
    }

    public static String getDisplayName(String key) {
        switch (key) {
            case PERM_DEVICE_ADMIN: return "Device Administrator";
            case PERM_WIFI: return "Wi-Fi Control";
            case PERM_BLUETOOTH: return "Bluetooth Control";
            case PERM_MOBILE_DATA: return "Mobile Data Settings";
            case PERM_FLASHLIGHT: return "Flashlight Torch";
            case PERM_CAMERA: return "Camera Hardware";
            case PERM_VOLUME: return "Volume & Audio Subsystem";
            case PERM_DND: return "Do Not Disturb (DND)";
            case PERM_AUDIO: return "Microphone Voice Recognition";
            default: return "Hardware Access";
        }
    }

    public static String getDescription(String key) {
        switch (key) {
            case PERM_DEVICE_ADMIN:
                return "Grant Jarvis Device Administrator privileges to manage background device policies, screen lock security, and automated hardware execution.";
            case PERM_WIFI:
                return "Grant Jarvis permission to turn Wi-Fi on or off and access network connections upon your voice command.";
            case PERM_BLUETOOTH:
                return "Grant Jarvis permission to toggle Bluetooth on or off and connect with wireless peripherals upon your voice command.";
            case PERM_MOBILE_DATA:
                return "Grant Jarvis permission to access cellular data panels and manage mobile data connectivity upon your voice command.";
            case PERM_FLASHLIGHT:
                return "Grant Jarvis permission to switch the camera flash torch on and off upon your voice command.";
            case PERM_CAMERA:
                return "Grant Jarvis permission to launch the camera and capture photos upon your voice command.";
            case PERM_VOLUME:
                return "Grant Jarvis permission to adjust volume levels, mute audio, and configure audio modes upon your voice command.";
            case PERM_DND:
                return "Grant Jarvis permission to activate or deactivate Do Not Disturb silent mode upon your voice command.";
            case PERM_AUDIO:
                return "Grant Jarvis permission to listen to your voice and transcribe instructions.";
            default:
                return "Grant Jarvis permission to control this hardware feature upon your voice command.";
        }
    }
}
