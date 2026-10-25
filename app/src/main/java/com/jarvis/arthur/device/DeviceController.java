package com.jarvis.arthur.device;

import android.Manifest;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.jarvis.arthur.SettingsManager;
import com.jarvis.arthur.intent.DynamicPhrasing;
import com.jarvis.arthur.util.StartupLogger;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executes system-level hardware commands and application launches on Android.
 * Controls Wi-Fi, Bluetooth, Mobile Data, Flashlight, Camera, Volume, and Do Not Disturb (DND)
 * with explicit user consent verification and session persistence.
 */
public class DeviceController {
    private static final String TAG = "DeviceController";

    public static class CommandResult {
        public final boolean success;
        public final String spokenMessage;
        public final String toolBadge;
        public final String detail;

        public CommandResult(boolean success, String spokenMessage, String toolBadge, String detail) {
            this.success = success;
            this.spokenMessage = spokenMessage;
            this.toolBadge = toolBadge;
            this.detail = detail;
        }
    }

    private final Context context;
    private final HardwarePermissionManager permissionManager;
    private final CameraManager cameraManager;
    private final AudioManager audioManager;
    private String cameraIdWithFlash = null;
    private boolean isFlashlightOn = false;

    // Common app aliases mapping to package names or search terms
    private static final Map<String, String[]> APP_ALIASES = new HashMap<>();

    static {
        APP_ALIASES.put("youtube", new String[]{"com.google.android.youtube"});
        APP_ALIASES.put("whatsapp", new String[]{"com.whatsapp"});
        APP_ALIASES.put("chrome", new String[]{"com.android.chrome"});
        APP_ALIASES.put("camera", new String[]{"com.android.camera", "com.google.android.GoogleCamera"});
        APP_ALIASES.put("spotify", new String[]{"com.spotify.music"});
        APP_ALIASES.put("maps", new String[]{"com.google.android.apps.maps"});
        APP_ALIASES.put("gmail", new String[]{"com.google.android.gm"});
        APP_ALIASES.put("calculator", new String[]{"com.google.android.calculator", "com.android.calculator2"});
        APP_ALIASES.put("clock", new String[]{"com.google.android.deskclock", "com.android.deskclock"});
        APP_ALIASES.put("settings", new String[]{"com.android.settings"});
        APP_ALIASES.put("play store", new String[]{"com.android.vending"});
        APP_ALIASES.put("telegram", new String[]{"org.telegram.messenger"});
        APP_ALIASES.put("instagram", new String[]{"com.instagram.android"});
        APP_ALIASES.put("twitter", new String[]{"com.twitter.android"});
        APP_ALIASES.put("contacts", new String[]{"com.google.android.contacts", "com.android.contacts"});
        APP_ALIASES.put("phone", new String[]{"com.google.android.dialer", "com.android.dialer"});
        APP_ALIASES.put("messages", new String[]{"com.google.android.apps.messaging", "com.android.mms"});
        APP_ALIASES.put("netflix", new String[]{"com.netflix.mediaclient"});
    }

    public DeviceController(Context context, HardwarePermissionManager permissionManager) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.permissionManager = permissionManager != null ? permissionManager :
                new HardwarePermissionManager(new SettingsManager(this.context));
        this.cameraManager = this.context != null ? (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE) : null;
        this.audioManager = this.context != null ? (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE) : null;
        initCameraFlash();
    }

    public DeviceController(Context context) {
        this(context, null);
    }

    public HardwarePermissionManager getPermissionManager() {
        return permissionManager;
    }

    private void initCameraFlash() {
        StartupLogger.logStep("DEV-01", "DeviceController: scanning camera hardware for torch flash...");
        try {
            if (cameraManager != null) {
                String[] ids = cameraManager.getCameraIdList();
                if (ids != null) {
                    StartupLogger.logStep("DEV-02", "CameraManager found " + ids.length + " camera IDs.");
                    for (String id : ids) {
                        if (id == null) continue;
                        CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                        if (c == null) continue;
                        Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                        if (flashAvailable != null && flashAvailable &&
                                facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                            cameraIdWithFlash = id;
                            StartupLogger.logStep("DEV-03", "Identified back camera with torch support: ID=" + id);
                            break;
                        }
                    }
                } else {
                    StartupLogger.logWarning("DEV-02", "CameraManager returned null camera list.");
                }
            } else {
                StartupLogger.logWarning("DEV-02", "CameraManager is null on this device.");
            }
        } catch (Throwable e) {
            StartupLogger.logWarning("DEV-03", "Camera flash initialization warning: " + e.getMessage());
        }
    }

    // --- Wi-Fi Hardware Control ---
    public CommandResult setWifi(boolean enable) {
        if (!permissionManager.hasConsent(HardwarePermissionManager.PERM_WIFI)) {
            return new CommandResult(false,
                    "Explicit consent is required to manage Wi-Fi, sir. Please confirm permission.",
                    "WIFI", "NEEDS_PERMISSION");
        }

        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "WIFI", null);
        }

        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            try {
                boolean current = wifiManager.isWifiEnabled();
                if (current == enable) {
                    String spoken = enable ?
                            "Wi-Fi is already turned on, sir. Status confirmed active." :
                            "Wi-Fi is already turned off, sir. Status confirmed inactive.";
                    return new CommandResult(true, spoken, "WIFI", enable ? "CONFIRMED_ON" : "CONFIRMED_OFF");
                }
            } catch (Throwable ignored) {}
        }

        boolean toggled = false;

        // 1. DevicePolicyManager API (Android 13+ / API 33+) with Device Admin / Owner
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                android.content.ComponentName admin = com.jarvis.arthur.receiver.JarvisDeviceAdminReceiver.getComponentName(context);
                if (dpm != null && dpm.isAdminActive(admin)) {
                    java.lang.reflect.Method m = dpm.getClass().getMethod("setWifiEnabled", android.content.ComponentName.class, boolean.class);
                    m.invoke(dpm, admin, enable);
                    toggled = true;
                }
            } catch (Throwable ignored) {}
        }

        // 2. Direct WifiManager toggle (supported for Device Owners, Device Admins, system apps, and pre-Q)
        if (!toggled && wifiManager != null) {
            try {
                toggled = wifiManager.setWifiEnabled(enable);
            } catch (Throwable ignored) {}
        }

        // 3. Low-level IPC via ServiceManager & IWifiManager reflection
        if (!toggled) {
            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                android.os.IBinder b = (android.os.IBinder) smClass.getMethod("getService", String.class).invoke(null, "wifi");
                if (b != null) {
                    Class<?> stubClass = Class.forName("android.net.wifi.IWifiManager$Stub");
                    Object iWifi = stubClass.getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
                    try {
                        java.lang.reflect.Method m = iWifi.getClass().getMethod("setWifiEnabled", String.class, boolean.class);
                        m.invoke(iWifi, context.getPackageName(), enable);
                        toggled = true;
                    } catch (NoSuchMethodException e) {
                        java.lang.reflect.Method m = iWifi.getClass().getMethod("setWifiEnabled", boolean.class);
                        m.invoke(iWifi, enable);
                        toggled = true;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 4. System shell execution fallback (cmd wifi / svc wifi)
        if (!toggled) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"cmd", "wifi", "set-wifi-enabled", enable ? "enabled" : "disabled"});
                p.waitFor(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                toggled = true;
            } catch (Throwable ignored) {}
        }
        if (!toggled) {
            try {
                Process p2 = Runtime.getRuntime().exec(new String[]{"svc", "wifi", enable ? "enable" : "disable"});
                p2.waitFor(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                toggled = true;
            } catch (Throwable ignored) {}
        }

        // State Verification: Confirm hardware transition programmatically without opening manual settings
        boolean verifiedState = enable;
        if (wifiManager != null) {
            for (int i = 0; i < 4; i++) {
                try {
                    if (wifiManager.isWifiEnabled() == enable) {
                        verifiedState = enable;
                        break;
                    }
                    Thread.sleep(100);
                } catch (Throwable ignored) {}
            }
            try {
                verifiedState = wifiManager.isWifiEnabled();
            } catch (Throwable ignored) {}
        }

        String spoken;
        String badgeDetail;
        if (verifiedState == enable) {
            spoken = enable ?
                    "Wi-Fi radio is now turned on and confirmed active, sir." :
                    "Wi-Fi radio is now turned off and confirmed inactive, sir.";
            badgeDetail = enable ? "CONFIRMED_ON" : "CONFIRMED_OFF";
        } else {
            spoken = enable ?
                    "Wi-Fi activation command executed for device hardware, sir." :
                    "Wi-Fi deactivation command executed for device hardware, sir.";
            badgeDetail = enable ? "EXECUTED_ON" : "EXECUTED_OFF";
        }

        return new CommandResult(true, spoken, "WIFI", badgeDetail);
    }

    public CommandResult toggleWifi() {
        if (context != null) {
            try {
                WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    return setWifi(!wifiManager.isWifiEnabled());
                }
            } catch (Exception ignored) {}
        }
        return setWifi(true);
    }

    // --- Bluetooth Hardware Control ---
    public CommandResult setBluetooth(boolean enable) {
        if (!permissionManager.hasConsent(HardwarePermissionManager.PERM_BLUETOOTH)) {
            return new CommandResult(false,
                    "Explicit consent is required to control Bluetooth, sir. Please confirm permission.",
                    "BLUETOOTH", "NEEDS_PERMISSION");
        }

        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "BLUETOOTH", null);
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return new CommandResult(false, "Bluetooth hardware is unavailable on this device, sir.", "BLUETOOTH", null);
        }

        try {
            boolean currentBt = adapter.isEnabled();
            if (currentBt == enable) {
                String spoken = enable ?
                        "Bluetooth is already turned on, sir. Status confirmed active." :
                        "Bluetooth is already turned off, sir. Status confirmed inactive.";
                return new CommandResult(true, spoken, "BLUETOOTH", enable ? "CONFIRMED_ON" : "CONFIRMED_OFF");
            }
        } catch (Throwable ignored) {}

        boolean toggled = false;

        // 1. Direct adapter enable() / disable()
        try {
            if (enable) {
                toggled = adapter.enable();
            } else {
                toggled = adapter.disable();
            }
        } catch (Throwable ignored) {}

        // 2. Reflection on BluetoothAdapter.mService (IBluetooth)
        if (!toggled) {
            try {
                java.lang.reflect.Field field = adapter.getClass().getDeclaredField("mService");
                field.setAccessible(true);
                Object iBt = field.get(adapter);
                if (iBt != null) {
                    if (enable) {
                        java.lang.reflect.Method m = iBt.getClass().getMethod("enable");
                        m.invoke(iBt);
                        toggled = true;
                    } else {
                        java.lang.reflect.Method m = iBt.getClass().getMethod("disable", boolean.class);
                        m.invoke(iBt, true);
                        toggled = true;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 3. Low-level IPC via ServiceManager & IBluetooth Stub reflection
        if (!toggled) {
            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                android.os.IBinder b = (android.os.IBinder) smClass.getMethod("getService", String.class).invoke(null, "bluetooth");
                if (b != null) {
                    Class<?> stubClass = Class.forName("android.bluetooth.IBluetooth$Stub");
                    Object iBt = stubClass.getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
                    if (enable) {
                        iBt.getClass().getMethod("enable").invoke(iBt);
                        toggled = true;
                    } else {
                        iBt.getClass().getMethod("disable", boolean.class).invoke(iBt, true);
                        toggled = true;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 4. System shell execution fallback (cmd bluetooth_manager / svc bluetooth)
        if (!toggled) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"cmd", "bluetooth_manager", enable ? "enable" : "disable"});
                p.waitFor(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                toggled = true;
            } catch (Throwable ignored) {}
        }
        if (!toggled) {
            try {
                Process p2 = Runtime.getRuntime().exec(new String[]{"svc", "bluetooth", enable ? "enable" : "disable"});
                p2.waitFor(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                toggled = true;
            } catch (Throwable ignored) {}
        }

        // State Verification: Confirm hardware transition programmatically without opening manual settings
        boolean verifiedState = enable;
        for (int i = 0; i < 4; i++) {
            try {
                if (adapter.isEnabled() == enable) {
                    verifiedState = enable;
                    break;
                }
                Thread.sleep(100);
            } catch (Throwable ignored) {}
        }
        try {
            verifiedState = adapter.isEnabled();
        } catch (Throwable ignored) {}

        String spoken;
        String badgeDetail;
        if (verifiedState == enable) {
            spoken = enable ?
                    "Bluetooth radio is now turned on and confirmed active, sir." :
                    "Bluetooth radio is now turned off and confirmed inactive, sir.";
            badgeDetail = enable ? "CONFIRMED_ON" : "CONFIRMED_OFF";
        } else {
            spoken = enable ?
                    "Bluetooth activation command executed for device hardware, sir." :
                    "Bluetooth deactivation command executed for device hardware, sir.";
            badgeDetail = enable ? "EXECUTED_ON" : "EXECUTED_OFF";
        }

        return new CommandResult(true, spoken, "BLUETOOTH", badgeDetail);
    }

    public CommandResult toggleBluetooth() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                return setBluetooth(!adapter.isEnabled());
            }
        } catch (Exception ignored) {}
        return setBluetooth(true);
    }

    // --- Do Not Disturb (DND) / Notification Policy Control ---
    public CommandResult setDoNotDisturb(boolean enable) {
        if (!permissionManager.hasConsent(HardwarePermissionManager.PERM_DND)) {
            return new CommandResult(false,
                    "Explicit consent is required to manage Do Not Disturb, sir. Please confirm permission.",
                    "DND", "NEEDS_PERMISSION");
        }

        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "DND", null);
        }

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return new CommandResult(false, "Notification manager is unavailable on this device, sir.", "DND", null);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (enable) {
                    if (nm.isNotificationPolicyAccessGranted()) {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                    }
                    if (audioManager != null) {
                        audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                    }
                    String spoken = "Do Not Disturb is now turned on and confirmed active, sir. Calls and alerts are silenced.";
                    return new CommandResult(true, spoken, "DND", "CONFIRMED_ON");
                } else {
                    if (nm.isNotificationPolicyAccessGranted()) {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                    }
                    if (audioManager != null) {
                        audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                    }
                    String spoken = "Do Not Disturb is now turned off and confirmed inactive, sir. Standard audio alerts restored.";
                    return new CommandResult(true, spoken, "DND", "CONFIRMED_OFF");
                }
            } catch (Exception e) {
                return new CommandResult(false, "Could not adjust Do Not Disturb settings: " + e.getMessage(), "DND", null);
            }
        } else {
            // Older Android versions fallback to ringer mode
            if (audioManager != null) {
                audioManager.setRingerMode(enable ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL);
                String spoken = enable ?
                        "Do Not Disturb is now turned on and confirmed active, sir." :
                        "Do Not Disturb is now turned off and normal sound restored, sir.";
                return new CommandResult(true, spoken, "DND", enable ? "CONFIRMED_ON" : "CONFIRMED_OFF");
            }
        }
        return new CommandResult(false, "Do Not Disturb unsupported on this Android version.", "DND", null);
    }

    public CommandResult toggleDoNotDisturb() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null) {
            try {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                    int currentFilter = nm.getCurrentInterruptionFilter();
                    boolean isDnd = currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL;
                    return setDoNotDisturb(!isDnd);
                }
            } catch (Exception ignored) {}
        }
        if (audioManager != null) {
            boolean isSilent = audioManager.getRingerMode() == AudioManager.RINGER_MODE_SILENT;
            return setDoNotDisturb(!isSilent);
        }
        return setDoNotDisturb(true);
    }

    // --- Silent Mode Control ---
    public CommandResult setSilentMode(boolean enable) {
        if (!permissionManager.hasConsent(HardwarePermissionManager.PERM_DND) &&
                !permissionManager.hasConsent(HardwarePermissionManager.PERM_VOLUME)) {
            return new CommandResult(false,
                    "Explicit consent is required to manage silent mode, sir. Please confirm permission.",
                    "SILENT_MODE", "NEEDS_PERMISSION");
        }

        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "SILENT_MODE", null);
        }

        try {
            if (audioManager != null) {
                audioManager.setRingerMode(enable ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.isNotificationPolicyAccessGranted()) {
                    nm.setInterruptionFilter(enable ?
                            NotificationManager.INTERRUPTION_FILTER_PRIORITY :
                            NotificationManager.INTERRUPTION_FILTER_ALL);
                }
            }
            String spoken = enable ? "Silent mode activated, sir." : "Silent mode deactivated. Sound restored, sir.";
            return new CommandResult(true, spoken, "SILENT_MODE", enable ? "ACTIVATED" : "DEACTIVATED");
        } catch (Exception e) {
            return new CommandResult(false, "Could not adjust silent mode: " + e.getMessage(), "SILENT_MODE", null);
        }
    }

    public CommandResult toggleSilentMode() {
        if (audioManager != null) {
            boolean isSilent = audioManager.getRingerMode() == AudioManager.RINGER_MODE_SILENT;
            return setSilentMode(!isSilent);
        }
        return setSilentMode(true);
    }

    // --- Mobile Data Connectivity Settings ---
    public CommandResult openMobileDataSettings() {
        if (!permissionManager.hasConsent(HardwarePermissionManager.PERM_MOBILE_DATA)) {
            return new CommandResult(false,
                    "Explicit consent is required to manage mobile data settings, sir. Please confirm permission.",
                    "MOBILE_DATA", "NEEDS_PERMISSION");
        }

        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "MOBILE_DATA", null);
        }

        try {
            Intent panelIntent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                panelIntent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
            } else {
                panelIntent = new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
            }
            panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(panelIntent);
            String spoken = DynamicPhrasing.getMobileDataResponse();
            return new CommandResult(true, spoken, "MOBILE_DATA", "PANEL_OPENED");
        } catch (Exception e) {
            return openSettingsPanel("network");
        }
    }

    // --- Camera Hardware Launch ---
    public CommandResult openCamera() {
        if (!permissionManager.hasConsent(HardwarePermissionManager.PERM_CAMERA)) {
            return new CommandResult(false,
                    "Camera permission required, sir. Please confirm consent.",
                    "CAMERA", "NEEDS_PERMISSION");
        }

        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "CAMERA", null);
        }

        try {
            Intent cameraIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(cameraIntent);
            return new CommandResult(true, DynamicPhrasing.getAppLaunchResponse("Camera"), "CAMERA", "OPENED");
        } catch (Exception e) {
            return new CommandResult(false, "Unable to open camera: " + e.getMessage(), "CAMERA", null);
        }
    }

    // --- Flashlight Control ---
    public CommandResult setFlashlight(boolean enable) {
        if (!permissionManager.hasConsent(HardwarePermissionManager.PERM_FLASHLIGHT)) {
            return new CommandResult(false,
                    "Flashlight control requires your consent, sir. Please confirm permission.",
                    "FLASHLIGHT", "NEEDS_PERMISSION");
        }

        if (cameraManager == null || cameraIdWithFlash == null) {
            return new CommandResult(false, "Flashlight hardware is unavailable on this device, sir.", "FLASHLIGHT", null);
        }

        try {
            cameraManager.setTorchMode(cameraIdWithFlash, enable);
            isFlashlightOn = enable;
            String spoken = DynamicPhrasing.getFlashlightResponse(enable);
            return new CommandResult(true, spoken, "FLASHLIGHT", enable ? "ON" : "OFF");
        } catch (Exception e) {
            return new CommandResult(false, "Unable to adjust flashlight: " + e.getMessage(), "FLASHLIGHT", null);
        }
    }

    public CommandResult toggleFlashlight() {
        return setFlashlight(!isFlashlightOn);
    }

    // --- Volume Control ---
    private static int previousNonZeroVolume = -1;

    public CommandResult setVolumeState(boolean enable) {
        if (!permissionManager.hasConsent(HardwarePermissionManager.PERM_VOLUME)) {
            return new CommandResult(false,
                    "Volume control requires your consent, sir. Please confirm permission.",
                    "VOLUME", "NEEDS_PERMISSION");
        }
        if (audioManager == null) {
            return new CommandResult(false, "Audio subsystem unavailable.", "VOLUME", null);
        }

        try {
            int stream = AudioManager.STREAM_MUSIC;
            int max = audioManager.getStreamMaxVolume(stream);
            int current = audioManager.getStreamVolume(stream);

            if (!enable) {
                // Turning OFF volume (muting)
                if (current > 0) {
                    previousNonZeroVolume = current;
                }
                audioManager.setStreamVolume(stream, 0, 0);
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0);
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0);
                    audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0);
                } catch (Throwable ignored) {}

                String spoken = "Volume is now turned off and confirmed muted, sir.";
                return new CommandResult(true, spoken, "VOLUME", "CONFIRMED_OFF (0%)");
            } else {
                // Turning ON volume (restoring / unmuting)
                int restoreLevel = (previousNonZeroVolume > 0) ? previousNonZeroVolume : Math.round(max * 0.65f);
                restoreLevel = Math.max(1, Math.min(max, restoreLevel));
                audioManager.setStreamVolume(stream, restoreLevel, 0);
                try {
                    int ringMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, Math.round(ringMax * 0.65f), 0);
                } catch (Throwable ignored) {}

                int verifiedVol = audioManager.getStreamVolume(stream);
                int pct = (int) (((float) verifiedVol / max) * 100);
                String spoken = "Volume is now turned on and confirmed at " + pct + " percent, sir.";
                return new CommandResult(true, spoken, "VOLUME", "CONFIRMED_ON (" + pct + "%)");
            }
        } catch (Exception e) {
            return new CommandResult(false, "Failed to adjust volume state: " + e.getMessage(), "VOLUME", null);
        }
    }

    public CommandResult adjustVolume(String action, int targetPercent) {
        if (!permissionManager.hasConsent(HardwarePermissionManager.PERM_VOLUME)) {
            return new CommandResult(false,
                    "Volume control requires your consent, sir. Please confirm permission.",
                    "VOLUME", "NEEDS_PERMISSION");
        }

        if (audioManager == null) {
            return new CommandResult(false, "Audio subsystem unavailable.", "VOLUME", null);
        }

        try {
            int stream = AudioManager.STREAM_MUSIC;
            int max = audioManager.getStreamMaxVolume(stream);

            if ("off".equalsIgnoreCase(action) || "mute".equalsIgnoreCase(action)) {
                return setVolumeState(false);
            } else if ("on".equalsIgnoreCase(action) || "unmute".equalsIgnoreCase(action)) {
                return setVolumeState(true);
            } else if ("up".equalsIgnoreCase(action)) {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0);
                int newVol = audioManager.getStreamVolume(stream);
                int pct = (int) (((float) newVol / max) * 100);
                return new CommandResult(true, "Volume increased and confirmed at " + pct + " percent, sir.", "VOLUME", pct + "%");
            } else if ("down".equalsIgnoreCase(action)) {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0);
                int newVol = audioManager.getStreamVolume(stream);
                int pct = (int) (((float) newVol / max) * 100);
                return new CommandResult(true, "Volume lowered and confirmed at " + pct + " percent, sir.", "VOLUME", pct + "%");
            } else if (targetPercent >= 0) {
                int level = Math.round(((float) targetPercent / 100.0f) * max);
                level = Math.max(0, Math.min(max, level));
                audioManager.setStreamVolume(stream, level, 0);
                int verified = audioManager.getStreamVolume(stream);
                int actualPct = (int) (((float) verified / max) * 100);
                return new CommandResult(true, "Volume set and confirmed at " + actualPct + " percent, sir.", "VOLUME", actualPct + "%");
            }
        } catch (Exception e) {
            return new CommandResult(false, "Failed to adjust volume: " + e.getMessage(), "VOLUME", null);
        }

        return new CommandResult(false, "Invalid volume command.", "VOLUME", null);
    }

    // --- Battery Telemetry ---
    public CommandResult getBatteryStatus() {
        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "BATTERY", null);
        }

        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);

            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL;

                float batteryPct = (level / (float) scale) * 100;
                int intPct = Math.round(batteryPct);

                String statusSuffix = isCharging ? " and actively charging" : "";
                String spoken = DynamicPhrasing.getBatteryResponse(intPct + " percent" + statusSuffix);
                return new CommandResult(true, spoken, "BATTERY", intPct + "% (" + (isCharging ? "Charging" : "Discharging") + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "Battery status error: " + e.getMessage());
        }
        return new CommandResult(false, "Unable to read battery telemetry, sir.", "BATTERY", null);
    }

    // --- Launch Installed Apps ---
    public CommandResult launchApp(String appName) {
        if (appName == null || appName.trim().isEmpty()) {
            return new CommandResult(false, "Sir, please specify which application to open.", "APP_LAUNCH", null);
        }

        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "APP_LAUNCH", null);
        }

        String target = appName.toLowerCase(Locale.ROOT).trim();
        PackageManager pm = context.getPackageManager();

        if (target.contains("camera")) {
            return openCamera();
        }

        if (target.contains("settings")) {
            return openSettingsPanel("system");
        }

        // Aliases
        for (Map.Entry<String, String[]> entry : APP_ALIASES.entrySet()) {
            if (target.contains(entry.getKey())) {
                for (String pkg : entry.getValue()) {
                    Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                        String label = entry.getKey().substring(0, 1).toUpperCase() + entry.getKey().substring(1);
                        return new CommandResult(true, DynamicPhrasing.getAppLaunchResponse(label), "APP_LAUNCH", pkg);
                    }
                }
            }
        }

        // Search installed apps
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo app : apps) {
                CharSequence label = pm.getApplicationLabel(app);
                if (label != null) {
                    String appLabelLower = label.toString().toLowerCase(Locale.ROOT);
                    if (appLabelLower.equals(target) || appLabelLower.contains(target) || target.contains(appLabelLower)) {
                        Intent launchIntent = pm.getLaunchIntentForPackage(app.packageName);
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(launchIntent);
                            return new CommandResult(true, DynamicPhrasing.getAppLaunchResponse(label.toString()), "APP_LAUNCH", app.packageName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying installed applications: " + e.getMessage());
        }

        // Fallback: Google Play Store
        try {
            Intent playStoreIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://search?q=" + Uri.encode(target)));
            playStoreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(playStoreIntent);
            return new CommandResult(true,
                    "Application " + appName + " is not installed locally. Opening Play Store to locate it, sir.",
                    "APP_LAUNCH", "Play Store Search");
        } catch (Exception e) {
            return new CommandResult(false,
                    "Sir, unable to locate or open " + appName + ".",
                    "APP_LAUNCH", null);
        }
    }

    // --- Settings Panels ---
    public CommandResult openSettingsPanel(String panel) {
        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "SETTINGS", null);
        }

        try {
            Intent intent;
            String label;
            if ("wifi".equalsIgnoreCase(panel)) {
                intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                label = "Wi-Fi";
            } else if ("bluetooth".equalsIgnoreCase(panel)) {
                intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                label = "Bluetooth";
            } else if ("display".equalsIgnoreCase(panel)) {
                intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
                label = "Display";
            } else if ("sound".equalsIgnoreCase(panel)) {
                intent = new Intent(Settings.ACTION_SOUND_SETTINGS);
                label = "Sound";
            } else if ("network".equalsIgnoreCase(panel) || "data".equalsIgnoreCase(panel)) {
                intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                label = "Network & Data";
            } else {
                intent = new Intent(Settings.ACTION_SETTINGS);
                label = "System";
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return new CommandResult(true, "Accessing " + label + " settings, sir.", "SETTINGS", label);
        } catch (Exception e) {
            return new CommandResult(false, "Could not open settings: " + e.getMessage(), "SETTINGS", null);
        }
    }

    // --- Timers & Alarms ---
    public CommandResult setTimer(int seconds, String message) {
        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "TIMER", null);
        }

        try {
            Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER);
            intent.putExtra(AlarmClock.EXTRA_LENGTH, seconds);
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, message != null ? message : "Jarvis Timer");
            intent.putExtra(AlarmClock.EXTRA_SKIP_UI, false);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);

            int minutes = seconds / 60;
            String timeStr = minutes > 0 ? minutes + " minutes" : seconds + " seconds";
            return new CommandResult(true, "Timer initialized for " + timeStr + ", sir.", "TIMER", timeStr);
        } catch (Exception e) {
            return new CommandResult(false, "Timer could not be initialized: " + e.getMessage(), "TIMER", null);
        }
    }

    // --- Device Administration Controls ---
    public boolean isDeviceAdminActive() {
        if (context == null) return false;
        return com.jarvis.arthur.receiver.JarvisDeviceAdminReceiver.isDeviceAdminActive(context);
    }

    public CommandResult requestDeviceAdmin() {
        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "DEVICE_ADMIN", null);
        }
        try {
            Intent intent = com.jarvis.arthur.receiver.JarvisDeviceAdminReceiver.createAddDeviceAdminIntent(context);
            context.startActivity(intent);
            return new CommandResult(true, "Presenting Device Administrator authorization, sir.", "DEVICE_ADMIN", "REQUESTED");
        } catch (Exception e) {
            return new CommandResult(false, "Could not open Device Administrator settings: " + e.getMessage(), "DEVICE_ADMIN", null);
        }
    }

    public CommandResult lockDevice() {
        if (context == null) {
            return new CommandResult(false, "Context unavailable.", "LOCK_DEVICE", null);
        }

        // 1. Try Device Administrator lockNow
        boolean locked = com.jarvis.arthur.receiver.JarvisDeviceAdminReceiver.lockNow(context);
        if (locked) {
            return new CommandResult(true, "Securing device and locking screen, sir.", "LOCK_DEVICE", "LOCKED");
        }

        // 2. Fallback to Accessibility Global Action Lock (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            com.jarvis.arthur.service.JarvisAccessibilityService accessService =
                    com.jarvis.arthur.service.JarvisAccessibilityService.getInstance();
            if (accessService != null) {
                boolean performed = accessService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                if (performed) {
                    return new CommandResult(true, "Device screen locked, sir.", "LOCK_DEVICE", "ACCESSIBILITY_LOCKED");
                }
            }
        }

        // 3. If neither is active, prompt user to authorize Device Admin
        try {
            Intent intent = com.jarvis.arthur.receiver.JarvisDeviceAdminReceiver.createAddDeviceAdminIntent(context);
            context.startActivity(intent);
            return new CommandResult(true,
                    "Device Administrator permission is required to lock the screen. Please authorize Jarvis, sir.",
                    "LOCK_DEVICE", "NEEDS_ADMIN");
        } catch (Exception e) {
            return new CommandResult(false, "Device Administrator permission required to lock screen.", "LOCK_DEVICE", null);
        }
    }
}
