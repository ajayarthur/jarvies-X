package com.jarvis.arthur.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.jarvis.arthur.SettingsManager;
import com.jarvis.arthur.device.DeviceController;
import com.jarvis.arthur.device.HardwarePermissionManager;
import com.jarvis.arthur.device.HomeScreenDetector;
import com.jarvis.arthur.util.StartupLogger;
import com.jarvis.arthur.voice.WakeWordDetector;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accessibility Service in Jarvis running persistently in the background.
 * - Monitors foreground window states and home screen transitions.
 * - Monitors user-triggered voice wake commands (e.g. saying 'Jarvis').
 * - Interacts directly with Android system settings:
 *     1. Bluetooth (on, off, toggle)
 *     2. Do Not Disturb (on, off, toggle)
 *     3. Silent Mode (activate, deactivate, toggle)
 *     4. Volume (increase, decrease, mute, set level)
 * - Requires a single, clear, one-time user permission grant.
 * - After initial approval, remembers permissions permanently and executes commands without further prompts.
 */
public class JarvisAccessibilityService extends AccessibilityService {
    private static final String TAG = "JarvisAccessibility";

    public static class SystemCommandResult {
        public final boolean success;
        public final String action;
        public final String detail;
        public final String spokenMessage;

        public SystemCommandResult(boolean success, String action, String detail, String spokenMessage) {
            this.success = success;
            this.action = action;
            this.detail = detail;
            this.spokenMessage = spokenMessage;
        }

        @Override
        public String toString() {
            return "SystemCommandResult{" +
                    "success=" + success +
                    ", action='" + action + '\'' +
                    ", detail='" + detail + '\'' +
                    ", spokenMessage='" + spokenMessage + '\'' +
                    '}';
        }
    }

    private static volatile JarvisAccessibilityService instance;

    private DeviceController deviceController;
    private SettingsManager settingsManager;
    private HardwarePermissionManager permissionManager;

    public JarvisAccessibilityService() {
        // Required default constructor for Android Service
    }

    /**
     * Testing constructor allowing injection of mock or context-free components.
     */
    public JarvisAccessibilityService(DeviceController deviceController, SettingsManager settingsManager) {
        this.deviceController = deviceController;
        this.settingsManager = settingsManager;
        if (settingsManager != null) {
            this.permissionManager = new HardwarePermissionManager(settingsManager);
        }
    }

    public static JarvisAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isServiceActive() {
        return instance != null;
    }

    /**
     * Checks if the user has enabled the Jarvis Accessibility Service in Android System Settings.
     */
    public static boolean isAccessibilityServiceEnabled(Context context) {
        if (context == null) return false;
        String serviceId = context.getPackageName() + "/" + JarvisAccessibilityService.class.getName();
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException ignored) {}

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                return settingValue.contains(serviceId) ||
                        settingValue.contains(JarvisAccessibilityService.class.getSimpleName());
            }
        }
        return false;
    }

    /**
     * Launches the system accessibility settings screen for one-time user authorization.
     */
    public static void openAccessibilitySettings(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open accessibility settings: " + e.getMessage());
        }
    }

    /**
     * Checks whether the user has provided the one-time permission grant for system settings.
     */
    public static boolean hasOneTimePermission(Context context) {
        SettingsManager sm = new SettingsManager(context);
        return sm.isAccessibilityPermissionGranted() || sm.isUnifiedPermissionsConfirmed();
    }

    /**
     * Grants the one-time permission and remembers it permanently in persistent storage.
     */
    public static void grantOneTimePermission(Context context) {
        SettingsManager sm = new SettingsManager(context);
        sm.setAccessibilityPermissionGranted(true);
        sm.setUnifiedPermissionsConfirmed(true);
        HardwarePermissionManager hpm = new HardwarePermissionManager(sm);
        hpm.grantAllHardwareConsents();
        Log.i(TAG, "One-time system settings permission granted and remembered permanently.");
    }

    /**
     * Revokes permissions (for testing or manual user reset).
     */
    public static void revokeOneTimePermission(Context context) {
        SettingsManager sm = new SettingsManager(context);
        sm.resetAllHardwarePermissionConfirmations();
        Log.i(TAG, "System settings permissions revoked.");
    }

    @Override
    public void onCreate() {
        StartupLogger.logStep("ACC-01", "JarvisAccessibilityService.onCreate invoked.");
        super.onCreate();
        instance = this;
        initControllers(this);
    }

    @Override
    protected void onServiceConnected() {
        StartupLogger.logStep("ACC-02", "JarvisAccessibilityService.onServiceConnected invoked.");
        super.onServiceConnected();
        instance = this;
        initControllers(this);

        // Configure accessibility service flags dynamically
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC | AccessibilityServiceInfo.FEEDBACK_SPOKEN;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        // Record persistent permission approval
        if (settingsManager != null) {
            settingsManager.setAccessibilityPermissionGranted(true);
        }

        Log.i(TAG, "Jarvis Accessibility Service connected & active in background. Permissions remembered.");

        // Start VoiceListenerService if enabled
        try {
            if (settingsManager != null && settingsManager.isBackgroundServiceEnabled()) {
                Intent serviceIntent = new Intent(this, VoiceListenerService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not start VoiceListenerService from Accessibility: " + e.getMessage());
        }
    }

    private void initControllers(Context ctx) {
        if (settingsManager == null) {
            settingsManager = new SettingsManager(ctx);
        }
        if (permissionManager == null) {
            permissionManager = new HardwarePermissionManager(settingsManager);
        }
        if (deviceController == null) {
            deviceController = new DeviceController(ctx, permissionManager);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgName = event.getPackageName();
            if (pkgName != null) {
                String pkg = pkgName.toString();
                boolean isHome = HomeScreenDetector.isHomeScreenPackage(this, pkg);
                Log.d(TAG, "Window changed: " + pkg + " (isHome=" + isHome + ")");
                VoiceListenerService.notifyForegroundPackageChanged(pkg, isHome);
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted.");
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
        Log.i(TAG, "Jarvis Accessibility Service destroyed.");
    }

    /**
     * Called when a voice command containing the trigger word 'Jarvis' is received.
     * Extracts the specific system command and executes it.
     */
    public SystemCommandResult onWakeWordDetected(String rawUtterance) {
        if (rawUtterance == null || rawUtterance.trim().isEmpty()) {
            return new SystemCommandResult(false, "WAKE_WORD", "EMPTY_INPUT", "Yes sir, I am listening.");
        }

        WakeWordDetector detector = WakeWordDetector.getDefault();
        WakeWordDetector.EvaluationResult eval = detector.evaluateSpeech(rawUtterance);

        if (eval.action == WakeWordDetector.EvaluationResult.Action.REJECTED_COOLDOWN) {
            return new SystemCommandResult(false, "IGNORED", "COOLDOWN", null);
        }
        if (eval.action == WakeWordDetector.EvaluationResult.Action.REJECTED_NO_WAKE_WORD) {
            return new SystemCommandResult(false, "IGNORED", "NO_WAKE_WORD", null);
        }
        if (eval.action == WakeWordDetector.EvaluationResult.Action.ACCEPTED_WAKE_ONLY) {
            return new SystemCommandResult(true, "WAKE_WORD", "STANDBY", "Online and listening, sir.");
        }

        return executeSystemCommand(eval.command);
    }

    /**
     * Executes system setting interactions (Bluetooth, Do Not Disturb, Silent Mode, Volume).
     * Gated strictly behind the one-time user permission grant.
     * After initial approval, executes all commands immediately without further prompts.
     */
    public SystemCommandResult executeSystemCommand(String commandText) {
        if (commandText == null || commandText.trim().isEmpty()) {
            return new SystemCommandResult(false, "UNKNOWN", "EMPTY_COMMAND", "Please specify a system command, sir.");
        }

        // Clean wake word if present in input
        String clean = VoiceListenerService.extractCommandAfterWakeWord(commandText);
        if (clean.isEmpty()) clean = commandText.trim();
        String lower = clean.toLowerCase(Locale.ROOT);

        // Ensure controllers are initialized
        if (settingsManager == null) {
            settingsManager = new SettingsManager(this);
        }
        if (permissionManager == null) {
            permissionManager = new HardwarePermissionManager(settingsManager);
        }
        if (deviceController == null) {
            deviceController = new DeviceController(this, permissionManager);
        }

        // Single One-Time Permission Verification
        boolean hasPermission = settingsManager.isDeviceAdminEnabled() ||
                com.jarvis.arthur.receiver.JarvisDeviceAdminReceiver.isDeviceAdminActive(this) ||
                settingsManager.isAccessibilityPermissionGranted() ||
                settingsManager.isUnifiedPermissionsConfirmed() ||
                permissionManager.hasAllHardwareConsents();

        // 0. LOCK DEVICE COMMANDS
        if (lower.contains("lock device") || lower.contains("lock phone") || lower.contains("lock screen") ||
                lower.contains("lock the phone") || lower.contains("lock the device")) {
            DeviceController.CommandResult res = deviceController.lockDevice();
            return new SystemCommandResult(res.success, "LOCK_DEVICE", res.detail, res.spokenMessage);
        }

        // 1. WI-FI COMMANDS
        if (lower.contains("wifi") || lower.contains("wi-fi")) {
            if (!hasPermission && !permissionManager.hasConsent(HardwarePermissionManager.PERM_WIFI)) {
                return new SystemCommandResult(false, "WIFI", "NEEDS_PERMISSION",
                        "System settings control requires a one-time permission grant, sir. Please authorize Jarvis to manage Wi-Fi.");
            }

            boolean turnOn = lower.contains("on") || lower.contains("enable") || lower.contains("activate") || lower.contains("start");
            boolean turnOff = lower.contains("off") || lower.contains("disable") || lower.contains("deactivate") || lower.contains("stop");

            DeviceController.CommandResult res;
            if (turnOn) {
                res = deviceController.setWifi(true);
            } else if (turnOff) {
                res = deviceController.setWifi(false);
            } else {
                res = deviceController.toggleWifi();
            }

            return new SystemCommandResult(res.success, "WIFI", res.detail, res.spokenMessage);
        }

        // 2. BLUETOOTH COMMANDS
        if (lower.contains("bluetooth")) {
            if (!hasPermission && !permissionManager.hasConsent(HardwarePermissionManager.PERM_BLUETOOTH)) {
                return new SystemCommandResult(false, "BLUETOOTH", "NEEDS_PERMISSION",
                        "System settings control requires a one-time permission grant, sir. Please authorize Jarvis to manage Bluetooth.");
            }

            boolean turnOn = lower.contains("on") || lower.contains("enable") || lower.contains("activate") || lower.contains("start");
            boolean turnOff = lower.contains("off") || lower.contains("disable") || lower.contains("deactivate") || lower.contains("stop");

            DeviceController.CommandResult res;
            if (turnOn) {
                res = deviceController.setBluetooth(true);
            } else if (turnOff) {
                res = deviceController.setBluetooth(false);
            } else {
                res = deviceController.toggleBluetooth();
            }

            return new SystemCommandResult(res.success, "BLUETOOTH", res.detail, res.spokenMessage);
        }

        // 2. SILENT MODE COMMANDS
        if (lower.contains("silent mode") || lower.contains("silence mode")) {
            if (!hasPermission &&
                    !permissionManager.hasConsent(HardwarePermissionManager.PERM_DND) &&
                    !permissionManager.hasConsent(HardwarePermissionManager.PERM_VOLUME)) {
                return new SystemCommandResult(false, "SILENT_MODE", "NEEDS_PERMISSION",
                        "System settings control requires a one-time permission grant, sir. Please authorize Jarvis to manage sound settings.");
            }

            boolean turnOff = lower.contains("off") || lower.contains("disable") || lower.contains("deactivate") || lower.contains("normal");
            DeviceController.CommandResult res;
            if (turnOff) {
                res = deviceController.setSilentMode(false);
            } else if (lower.contains("toggle")) {
                res = deviceController.toggleSilentMode();
            } else {
                res = deviceController.setSilentMode(true);
            }

            return new SystemCommandResult(res.success, "SILENT_MODE", res.detail, res.spokenMessage);
        }

        // 3. DO NOT DISTURB (DND) COMMANDS
        if (lower.contains("do not disturb") || lower.contains("dnd")) {
            if (!hasPermission && !permissionManager.hasConsent(HardwarePermissionManager.PERM_DND)) {
                return new SystemCommandResult(false, "DND", "NEEDS_PERMISSION",
                        "System settings control requires a one-time permission grant, sir. Please authorize Jarvis to manage Do Not Disturb.");
            }

            boolean turnOff = lower.contains("off") || lower.contains("disable") || lower.contains("deactivate") || lower.contains("normal");
            DeviceController.CommandResult res;
            if (turnOff) {
                res = deviceController.setDoNotDisturb(false);
            } else if (lower.contains("toggle")) {
                res = deviceController.toggleDoNotDisturb();
            } else {
                res = deviceController.setDoNotDisturb(true);
            }

            return new SystemCommandResult(res.success, "DND", res.detail, res.spokenMessage);
        }

        // 4. VOLUME COMMANDS
        if (lower.contains("volume") || lower.contains("mute") || lower.contains("unmute")) {
            if (!hasPermission && !permissionManager.hasConsent(HardwarePermissionManager.PERM_VOLUME)) {
                return new SystemCommandResult(false, "VOLUME", "NEEDS_PERMISSION",
                        "System settings control requires a one-time permission grant, sir. Please authorize Jarvis to manage volume.");
            }

            DeviceController.CommandResult res;
            if (lower.contains("unmute") || lower.contains("turn on") || lower.contains("enable volume")) {
                res = deviceController.adjustVolume("on", -1);
            } else if (lower.contains("mute") || lower.contains("turn off") || lower.contains("shut off") || lower.contains("volume off")) {
                res = deviceController.adjustVolume("off", 0);
            } else if (lower.contains("up") || lower.contains("increase") || lower.contains("raise") || lower.contains("louder")) {
                res = deviceController.adjustVolume("up", -1);
            } else if (lower.contains("down") || lower.contains("decrease") || lower.contains("lower") || lower.contains("quieter")) {
                res = deviceController.adjustVolume("down", -1);
            } else {
                Matcher m = Pattern.compile("(\\d+)\\s*%?").matcher(lower);
                if (m.find()) {
                    int pct = Integer.parseInt(m.group(1));
                    res = deviceController.adjustVolume("set", pct);
                } else if (lower.contains("off")) {
                    res = deviceController.adjustVolume("off", 0);
                } else if (lower.contains("on")) {
                    res = deviceController.adjustVolume("on", -1);
                } else {
                    res = deviceController.adjustVolume("up", -1);
                }
            }

            return new SystemCommandResult(res.success, "VOLUME", res.detail, res.spokenMessage);
        }

        // 5. GLOBAL ACCESSIBILITY ACTIONS (Home, Back, Quick Settings, Notifications)
        if (lower.equals("home") || lower.contains("go home") || lower.contains("open home")) {
            boolean success = performGlobalAction(GLOBAL_ACTION_HOME);
            return new SystemCommandResult(success, "GLOBAL_ACTION", "HOME", "Navigating to home screen, sir.");
        }

        if (lower.equals("back") || lower.contains("go back")) {
            boolean success = performGlobalAction(GLOBAL_ACTION_BACK);
            return new SystemCommandResult(success, "GLOBAL_ACTION", "BACK", "Going back, sir.");
        }

        if (lower.contains("quick settings")) {
            boolean success = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            return new SystemCommandResult(success, "GLOBAL_ACTION", "QUICK_SETTINGS", "Opening quick settings, sir.");
        }

        if (lower.contains("notifications") || lower.contains("notification shade")) {
            boolean success = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            return new SystemCommandResult(success, "GLOBAL_ACTION", "NOTIFICATIONS", "Opening notification panel, sir.");
        }

        return new SystemCommandResult(false, "UNKNOWN", "UNRECOGNIZED_COMMAND",
                "I could not identify that system setting instruction, sir.");
    }
}
