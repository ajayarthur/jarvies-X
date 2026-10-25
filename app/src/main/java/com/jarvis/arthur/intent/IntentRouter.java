package com.jarvis.arthur.intent;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jarvis.arthur.device.DeviceController;
import com.jarvis.arthur.knowledge.KnowledgeSource;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes intent from spoken text.
 * Dispatches commands to DeviceController and routes all conversational / question inputs
 * dynamically to flexible language models or streaming knowledge sources.
 */
public class IntentRouter {
    private static final String TAG = "IntentRouter";

    public static class IntentResult {
        public final boolean success;
        public final boolean isCommand;
        public final String spokenResponse;
        public final String toolBadge;
        public final String sourceUrl;
        public final String query;

        public IntentResult(boolean success, boolean isCommand, String spokenResponse,
                            String toolBadge, String sourceUrl, String query) {
            this.success = success;
            this.isCommand = isCommand;
            this.spokenResponse = spokenResponse;
            this.toolBadge = toolBadge;
            this.sourceUrl = sourceUrl;
            this.query = query;
        }
    }

    public interface RouterCallback {
        void onResult(IntentResult result);
    }

    public interface StreamingRouterCallback {
        void onFirstSentence(String firstSentence, String toolBadge);
        void onNextSentence(String nextSentence);
        void onComplete(IntentResult fullResult);
    }

    private final DeviceController deviceController;
    private final KnowledgeSource knowledgeSource;
    private final com.jarvis.arthur.memory.MemoryManager memoryManager;
    private final Handler mainHandler;

    public IntentRouter(DeviceController deviceController, KnowledgeSource knowledgeSource,
                        com.jarvis.arthur.memory.MemoryManager memoryManager) {
        this.deviceController = deviceController;
        this.knowledgeSource = knowledgeSource;
        this.memoryManager = memoryManager;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public IntentRouter(DeviceController deviceController, KnowledgeSource knowledgeSource) {
        this(deviceController, knowledgeSource, null);
    }

    private void postCallback(Runnable runnable) {
        if (runnable == null) return;
        boolean posted = false;
        if (mainHandler != null) {
            try {
                posted = mainHandler.post(runnable);
            } catch (Exception ignored) {}
        }
        if (!posted) {
            runnable.run();
        }
    }

    /**
     * Standard non-streaming dispatch (retained for backward compatibility and unit tests).
     */
    public void route(String rawSpokenText, RouterCallback callback) {
        routeStreaming(rawSpokenText, new StreamingRouterCallback() {
            @Override
            public void onFirstSentence(String firstSentence, String toolBadge) {}

            @Override
            public void onNextSentence(String nextSentence) {}

            @Override
            public void onComplete(IntentResult fullResult) {
                if (callback != null) {
                    callback.onResult(fullResult);
                }
            }
        });
    }

    /**
     * Real-time streaming dispatch method.
     * Starts voice output on the first completed sentence.
     */
    public void routeStreaming(String rawSpokenText, StreamingRouterCallback callback) {
        if (rawSpokenText == null || rawSpokenText.trim().isEmpty()) {
            IntentResult emptyResult = new IntentResult(false, false,
                    "I didn't catch that, sir. Please repeat your instruction.", "IDLE", null, "");
            notifyStreamingCallback(callback, emptyResult);
            return;
        }

        String clean = cleanSpeech(rawSpokenText);
        String lower = clean.toLowerCase(Locale.ROOT);

        Log.d(TAG, "Routing processed speech: " + clean);

        // 1. Dynamic Memory processing
        if (memoryManager != null && isPureMemoryQuery(lower)) {
            String memFeedback = memoryManager.processInputForMemory(clean);
            if (memFeedback != null) {
                IntentResult result = new IntentResult(true, false,
                        memFeedback, "DYNAMIC_MEMORY", null, clean);
                notifyStreamingCallback(callback, result);
                return;
            }
        }

        // 2. Device Commands
        if (isCommand(lower)) {
            executeCommand(clean, lower, res -> notifyStreamingCallback(callback, res));
            return;
        }

        // 3. Conversational / Questions -> Real-time streaming
        executeQuestionStreaming(clean, callback);
    }

    private void executeQuestionStreaming(String query, StreamingRouterCallback callback) {
        knowledgeSource.queryKnowledgeStreaming(query, new KnowledgeSource.StreamingKnowledgeCallback() {
            @Override
            public void onFirstSentence(String firstSentence, String sourceBadge) {
                postCallback(() -> {
                    if (callback != null) {
                        callback.onFirstSentence(firstSentence, sourceBadge);
                    }
                });
            }

            @Override
            public void onNextSentence(String nextSentence) {
                postCallback(() -> {
                    if (callback != null) {
                        callback.onNextSentence(nextSentence);
                    }
                });
            }

            @Override
            public void onComplete(String fullResponse, String sourceBadge, String sourceUrl) {
                IntentResult result = new IntentResult(true, false,
                        fullResponse, sourceBadge, sourceUrl, query);
                recordAndFinish(callback, result);
            }

            @Override
            public void onFailure(String errorMessage) {
                String dynamicFallback = "Sir, I encountered an issue retrieving that information online. " +
                        "My autonomous cores remain ready for your next request.";
                IntentResult result = new IntentResult(false, false,
                        dynamicFallback, "OFFLINE_CORE", null, query);
                postCallback(() -> {
                    if (callback != null) {
                        callback.onFirstSentence(dynamicFallback, "OFFLINE_CORE");
                    }
                });
                recordAndFinish(callback, result);
            }
        });
    }

    private void notifyStreamingCallback(StreamingRouterCallback callback, IntentResult result) {
        postCallback(() -> {
            if (callback != null) {
                callback.onFirstSentence(result.spokenResponse, result.toolBadge);
            }
        });
        recordAndFinish(callback, result);
    }

    private void recordAndFinish(StreamingRouterCallback callback, IntentResult result) {
        if (memoryManager != null && result != null &&
                result.query != null && !result.query.trim().isEmpty() &&
                result.spokenResponse != null && !result.spokenResponse.trim().isEmpty()) {
            memoryManager.recordConversationTurn(result.query, result.spokenResponse);
        }
        postCallback(() -> {
            if (callback != null) {
                callback.onComplete(result);
            }
        });
    }

    private static boolean isPureMemoryQuery(String lower) {
        return lower.startsWith("forget ") ||
                lower.startsWith("remove ") ||
                lower.startsWith("delete ") ||
                lower.contains("clear memory") ||
                lower.contains("forget me") ||
                lower.contains("reset memory") ||
                lower.contains("wipe memory") ||
                lower.contains("storage usage") ||
                lower.contains("storage status") ||
                lower.contains("check storage") ||
                lower.contains("storage limit") ||
                lower.contains("memory storage") ||
                lower.contains("prune old data") ||
                lower.contains("delete old data") ||
                lower.contains("clean up storage") ||
                lower.contains("clean up memory") ||
                lower.contains("clean old data") ||
                lower.contains("clean storage") ||
                lower.contains("free up space") ||
                lower.contains("free up storage") ||
                lower.equals("what is my name") ||
                lower.equals("what's my name") ||
                lower.equals("who am i") ||
                lower.equals("what is my role") ||
                lower.equals("what's my role") ||
                lower.equals("what do i do") ||
                lower.equals("what's my job") ||
                lower.contains("what do you remember") ||
                lower.contains("what do you know about me") ||
                lower.contains("what are my preferences") ||
                lower.contains("what do i prefer") ||
                lower.startsWith("what is my favorite ") ||
                lower.startsWith("what's my favorite ") ||
                lower.startsWith("do you remember ") ||
                lower.startsWith("recall ") ||
                lower.equals("show memory") ||
                lower.equals("check memory") ||
                lower.equals("list memory") ||
                lower.equals("what did we just talk about") ||
                lower.equals("what did i ask you earlier") ||
                lower.equals("what was my last question") ||
                lower.equals("what did i just say") ||
                lower.startsWith("remember ") ||
                lower.startsWith("keep in mind that ") ||
                lower.startsWith("note that ") ||
                lower.startsWith("take note ") ||
                lower.startsWith("don't forget that ") ||
                lower.startsWith("save this") ||
                lower.startsWith("store this") ||
                lower.startsWith("my favorite ") ||
                lower.startsWith("i prefer ") ||
                lower.startsWith("my preference is ") ||
                lower.startsWith("my name is ") ||
                lower.startsWith("my role is ") ||
                lower.startsWith("my job is ") ||
                lower.startsWith("i work as ") ||
                lower.startsWith("call me ");
    }

    private void executeCommand(String originalText, String lower, RouterCallback callback) {
        // 0. Device Admin & Screen Lock
        if (lower.contains("lock device") || lower.contains("lock phone") || lower.contains("lock screen") ||
                lower.contains("lock the phone") || lower.contains("lock the device")) {
            DeviceController.CommandResult res = deviceController.lockDevice();
            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "HARDWARE_ADMIN", null, originalText));
            return;
        }

        if (lower.contains("device admin") || lower.contains("device administrator")) {
            DeviceController.CommandResult res = deviceController.requestDeviceAdmin();
            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "DEVICE_ADMIN", null, originalText));
            return;
        }

        // A. Flashlight / Torch
        if (lower.contains("flashlight") || lower.contains("torch")) {
            boolean turnOn = lower.contains("on") || lower.contains("enable") || lower.contains("start");
            boolean turnOff = lower.contains("off") || lower.contains("disable") || lower.contains("stop");

            DeviceController.CommandResult res;
            if (turnOn) {
                res = deviceController.setFlashlight(true);
            } else if (turnOff) {
                res = deviceController.setFlashlight(false);
            } else {
                res = deviceController.toggleFlashlight();
            }

            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "HARDWARE_TORCH", null, originalText));
            return;
        }

        // B. Volume
        if (lower.contains("volume") || lower.contains("mute") || lower.contains("unmute")) {
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

            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "AUDIO_CONTROL", null, originalText));
            return;
        }

        // C. Wi-Fi Control
        if (lower.contains("wifi") || lower.contains("wi-fi")) {
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

            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "HARDWARE_WIFI", null, originalText));
            return;
        }

        // D. Bluetooth Control
        if (lower.contains("bluetooth")) {
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

            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "HARDWARE_BLUETOOTH", null, originalText));
            return;
        }

        // E. Silent Mode / Do Not Disturb (DND)
        if (lower.contains("silent mode") || lower.contains("silence mode")) {
            boolean turnOff = lower.contains("off") || lower.contains("disable") || lower.contains("deactivate") || lower.contains("normal");
            DeviceController.CommandResult res;
            if (turnOff) {
                res = deviceController.setSilentMode(false);
            } else if (lower.contains("toggle")) {
                res = deviceController.toggleSilentMode();
            } else {
                res = deviceController.setSilentMode(true);
            }

            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "HARDWARE_SILENT", null, originalText));
            return;
        }

        if (lower.contains("do not disturb") || lower.contains("dnd")) {
            boolean turnOff = lower.contains("off") || lower.contains("disable") || lower.contains("deactivate") || lower.contains("normal");

            DeviceController.CommandResult res;
            if (turnOff) {
                res = deviceController.setDoNotDisturb(false);
            } else if (lower.contains("toggle")) {
                res = deviceController.toggleDoNotDisturb();
            } else {
                res = deviceController.setDoNotDisturb(true);
            }

            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "HARDWARE_DND", null, originalText));
            return;
        }

        // F. Mobile Data
        if (lower.contains("mobile data") || lower.contains("cellular data")) {
            DeviceController.CommandResult res = deviceController.openMobileDataSettings();
            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "HARDWARE_DATA", null, originalText));
            return;
        }

        // G. Camera Launch
        if (lower.contains("take a photo") || lower.contains("take photo") || lower.contains("take picture") || lower.contains("open camera")) {
            DeviceController.CommandResult res = deviceController.openCamera();
            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "HARDWARE_CAMERA", null, originalText));
            return;
        }

        // H. Battery
        if (lower.contains("battery") || lower.contains("charge") || lower.contains("power level")) {
            DeviceController.CommandResult res = deviceController.getBatteryStatus();
            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "POWER_TELEMETRY", null, originalText));
            return;
        }

        // I. Settings panels
        if (lower.contains("wifi setting") || lower.contains("wi-fi setting")) {
            DeviceController.CommandResult res = deviceController.openSettingsPanel("wifi");
            notifyCommandCallback(callback, new IntentResult(res.success, true, res.spokenMessage, "SETTINGS", null, originalText));
            return;
        }
        if (lower.contains("bluetooth setting")) {
            DeviceController.CommandResult res = deviceController.openSettingsPanel("bluetooth");
            notifyCommandCallback(callback, new IntentResult(res.success, true, res.spokenMessage, "SETTINGS", null, originalText));
            return;
        }
        if (lower.contains("display setting") || lower.contains("brightness")) {
            DeviceController.CommandResult res = deviceController.openSettingsPanel("display");
            notifyCommandCallback(callback, new IntentResult(res.success, true, res.spokenMessage, "SETTINGS", null, originalText));
            return;
        }

        // J. Timer
        if (lower.contains("timer") || lower.contains("alarm")) {
            int seconds = parseTimerSeconds(lower);
            if (seconds > 0) {
                DeviceController.CommandResult res = deviceController.setTimer(seconds, "Jarvis Timer");
                notifyCommandCallback(callback, new IntentResult(res.success, true, res.spokenMessage, "CHRONO_TIMER", null, originalText));
                return;
            }
        }

        // K. Open / Launch App
        String appName = extractAppName(originalText);
        if (!appName.isEmpty()) {
            DeviceController.CommandResult res = deviceController.launchApp(appName);
            notifyCommandCallback(callback, new IntentResult(res.success, true,
                    res.spokenMessage, "APP_LAUNCH", null, originalText));
            return;
        }

        // Fallback: If command pattern didn't yield an action, route dynamically to KnowledgeSource
        knowledgeSource.queryKnowledge(originalText, new KnowledgeSource.KnowledgeCallback() {
            @Override
            public void onSuccess(String spokenAnswer, String sourceBadge, String sourceUrl) {
                notifyCommandCallback(callback, new IntentResult(true, false,
                        spokenAnswer, sourceBadge, sourceUrl, originalText));
            }

            @Override
            public void onFailure(String errorMessage) {
                String dynamicFallback = "Sir, I encountered an issue retrieving that information online. " +
                        "My autonomous cores remain ready for your next request.";
                notifyCommandCallback(callback, new IntentResult(false, false,
                        dynamicFallback, "OFFLINE_CORE", null, originalText));
            }
        });
    }

    private void notifyCommandCallback(RouterCallback callback, IntentResult result) {
        if (memoryManager != null && result != null &&
                result.query != null && !result.query.trim().isEmpty() &&
                result.spokenResponse != null && !result.spokenResponse.trim().isEmpty()) {
            memoryManager.recordConversationTurn(result.query, result.spokenResponse);
        }
        postCallback(() -> {
            if (callback != null) {
                callback.onResult(result);
            }
        });
    }

    /**
     * Determines whether spoken input is a device command.
     */
    public static boolean isCommand(String text) {
        return text.startsWith("open ") ||
                text.startsWith("launch ") ||
                text.startsWith("start ") ||
                text.contains("flashlight") ||
                text.contains("torch") ||
                text.contains("volume") ||
                text.contains("mute") ||
                text.contains("battery") ||
                text.contains("wifi") ||
                text.contains("wi-fi") ||
                text.contains("bluetooth") ||
                text.contains("mobile data") ||
                text.contains("cellular data") ||
                text.contains("do not disturb") ||
                text.contains("dnd") ||
                text.contains("silent mode") ||
                text.contains("take a photo") ||
                text.contains("take photo") ||
                text.contains("take picture") ||
                text.contains("open camera") ||
                text.contains("lock device") ||
                text.contains("lock phone") ||
                text.contains("lock screen") ||
                text.contains("lock the phone") ||
                text.contains("lock the device") ||
                text.contains("device admin") ||
                text.contains("device administrator") ||
                text.contains("set a timer") ||
                text.contains("set timer") ||
                text.contains("set alarm") ||
                text.contains("wifi settings") ||
                text.contains("bluetooth settings");
    }

    private static String extractAppName(String query) {
        Pattern pattern = Pattern.compile("(?i)^(?:open|launch|start|run|play)\\s+([a-zA-Z0-9\\s]+)");
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            String app = matcher.group(1).trim();
            if (!app.equalsIgnoreCase("flashlight") && !app.equalsIgnoreCase("torch")) {
                return app;
            }
        }
        return "";
    }

    private static int parseTimerSeconds(String query) {
        Matcher minuteMatcher = Pattern.compile("(\\d+)\\s*(?:min|minute)").matcher(query);
        if (minuteMatcher.find()) {
            return Integer.parseInt(minuteMatcher.group(1)) * 60;
        }

        Matcher secondMatcher = Pattern.compile("(\\d+)\\s*(?:sec|second)").matcher(query);
        if (secondMatcher.find()) {
            return Integer.parseInt(secondMatcher.group(1));
        }

        return 300; // default 5 minutes
    }

    private static String cleanSpeech(String raw) {
        return raw.replaceAll("(?i)^(?:hey jarvis|jarvis|ok jarvis|hello jarvis)[,\\s]*", "").trim();
    }
}
