package com.jarvis.arthur.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;
import com.jarvis.arthur.util.StartupLogger;

import com.jarvis.arthur.MainActivity;
import com.jarvis.arthur.R;
import com.jarvis.arthur.SettingsManager;
import com.jarvis.arthur.device.DeviceController;
import com.jarvis.arthur.device.HardwarePermissionManager;
import com.jarvis.arthur.device.HomeScreenDetector;
import com.jarvis.arthur.intent.DynamicPhrasing;
import com.jarvis.arthur.intent.IntentRouter;
import com.jarvis.arthur.knowledge.KnowledgeSource;
import com.jarvis.arthur.memory.MemoryManager;
import com.jarvis.arthur.voice.TtsManager;
import com.jarvis.arthur.voice.VoiceManager;
import com.jarvis.arthur.voice.WakeWordDetector;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground Service running Jarvis continuously in the background after first launch.
 * - Stays completely inactive when the phone is locked or screen is off (0 mic / 0 CPU usage).
 * - Activates passive wake-word monitoring only when unlocked and on the home screen.
 * - Wakes up and responds when the trigger word "Jarvis" is spoken.
 * - Returns to passive state after 10 seconds of silence.
 */
public class VoiceListenerService extends Service {
    private static final String TAG = "VoiceListenerService";
    private static final String CHANNEL_ID = "jarvis_voice_channel";
    private static final int NOTIFICATION_ID = 4001;

    public enum AssistantState {
        INACTIVE_LOCKED,
        INACTIVE_APP,
        PASSIVE_STANDBY,
        ACTIVE_LISTENING,
        PROCESSING,
        SPEAKING,
        POST_TASK_COOLDOWN
    }

    public interface ServiceStateListener {
        void onStateChanged(AssistantState state, String statusMessage);
        void onTranscriptUpdate(String text, boolean isFinal);
        void onResponseSpoken(String spokenAnswer, String toolBadge);
        void onRmsLevel(float rms);
    }

    private static volatile VoiceListenerService instance;
    private static final List<ServiceStateListener> stateListeners = new CopyOnWriteArrayList<>();

    public static VoiceListenerService getInstance() {
        return instance;
    }

    public static void addListener(ServiceStateListener listener) {
        if (listener != null && !stateListeners.contains(listener)) {
            stateListeners.add(listener);
            if (instance != null) {
                listener.onStateChanged(instance.currentState, instance.currentStatusMessage);
            }
        }
    }

    public static void removeListener(ServiceStateListener listener) {
        if (listener != null) {
            stateListeners.remove(listener);
        }
    }

    public static void notifyForegroundPackageChanged(String packageName, boolean isHome) {
        if (instance != null) {
            instance.handleForegroundPackageChanged(packageName, isHome);
        }
    }

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public VoiceListenerService getService() {
            return VoiceListenerService.this;
        }
    }

    // Core Assistant Engines
    private SettingsManager settings;
    private MemoryManager memoryManager;
    private KnowledgeSource knowledgeSource;
    private HardwarePermissionManager permissionManager;
    private DeviceController deviceController;
    private IntentRouter intentRouter;
    private TtsManager ttsManager;
    private VoiceManager voiceManager;
    private WakeWordDetector wakeWordDetector;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AssistantState currentState = AssistantState.PASSIVE_STANDBY;
    private String currentStatusMessage = "Initializing...";
    private boolean isScreenLocked = false;
    private boolean isHomeScreen = true;
    private boolean isServiceRunning = false;

    // Post-Task Silence Cooldown Runnable
    private final Runnable cooldownCompletedRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isScreenLocked && isHomeScreen && currentState == AssistantState.POST_TASK_COOLDOWN) {
                Log.i(TAG, "Post-task silence cooldown elapsed. Reactivating passive standby listening for 'Jarvis'.");
                transitionToState(AssistantState.PASSIVE_STANDBY, "STANDBY // ON HOME SCREEN (SAY 'JARVIS')");
                startPassiveListening();
            }
        }
    };

    // 10-Second Silence Timeout Runnable
    private final Runnable silenceTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentState == AssistantState.ACTIVE_LISTENING) {
                Log.i(TAG, "10-second silence timeout reached. Returning to passive standby.");
                transitionToState(AssistantState.PASSIVE_STANDBY, "STANDBY // ON HOME SCREEN (SAY 'JARVIS')");
                if (settings != null && settings.isAudioFeedbackEnabled() && ttsManager != null) {
                    // Optional gentle audio acknowledgment of standby return
                }
            }
        }
    };

    // Broadcast receiver for Screen Lock and Home button events
    private final BroadcastReceiver screenLockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();

            switch (action) {
                case Intent.ACTION_SCREEN_OFF:
                    Log.i(TAG, "Screen turned OFF -> Entering INACTIVE_LOCKED");
                    onScreenLocked();
                    break;

                case Intent.ACTION_SCREEN_ON:
                    if (HomeScreenDetector.isDeviceLocked(context)) {
                        onScreenLocked();
                    } else {
                        checkAndApplyHomeState();
                    }
                    break;

                case Intent.ACTION_USER_PRESENT:
                    Log.i(TAG, "User unlocked device -> checking home screen");
                    onUserUnlocked();
                    break;

                case Intent.ACTION_CLOSE_SYSTEM_DIALOGS:
                    String reason = intent.getStringExtra("reason");
                    if ("homekey".equals(reason) || reason == null) {
                        Log.i(TAG, "Home key pressed -> navigating to home screen");
                        onHomeKeyPressed();
                    }
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        StartupLogger.logStep("VLS-01", "VoiceListenerService.onCreate invoked.");
        try {
            super.onCreate();
            instance = this;
            isServiceRunning = true;

            StartupLogger.logStep("VLS-02", "Creating notification channel...");
            createNotificationChannel();

            StartupLogger.logStep("VLS-03", "Initializing core engines in VoiceListenerService...");
            initCoreEngines();

            StartupLogger.logStep("VLS-04", "Registering screenLockReceiver...");
            registerScreenLockReceiver();

            // Check initial state
            if (HomeScreenDetector.isDeviceLocked(this)) {
                StartupLogger.logStep("VLS-05", "Device is currently locked -> entering onScreenLocked().");
                onScreenLocked();
            } else {
                isScreenLocked = false;
                isHomeScreen = true;
                StartupLogger.logStep("VLS-05", "Device is unlocked -> entering PASSIVE_STANDBY.");
                transitionToState(AssistantState.PASSIVE_STANDBY, "STANDBY // ON HOME SCREEN (SAY 'JARVIS')");
                startPassiveListening();
            }
            StartupLogger.logStep("VLS-06", "VoiceListenerService.onCreate completed successfully.");
        } catch (Throwable t) {
            StartupLogger.logError("VLS-06", "Fatal error in VoiceListenerService.onCreate", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        StartupLogger.logStep("VLS-07", "VoiceListenerService.onStartCommand called with startId=" + startId);
        try {
            Notification notification = buildNotification(currentStatusMessage);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int serviceType = 0;
                boolean micGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && micGranted) {
                    serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                StartupLogger.logStep("VLS-08", "Calling startForeground with serviceType=" + serviceType + " (micPermissionGranted=" + micGranted + ")");
                startForeground(NOTIFICATION_ID, notification, serviceType);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            StartupLogger.logStep("VLS-09", "startForeground succeeded in VoiceListenerService.");
        } catch (Throwable t) {
            StartupLogger.logError("VLS-09", "startForeground failed in VoiceListenerService: " + t.getMessage(), t);
        }

        if (settings != null) {
            settings.setHasOpenedOnce(true);
            settings.setBackgroundServiceEnabled(true);
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private boolean isReceiverRegistered = false;

    private void initCoreEngines() {
        try {
            settings = new SettingsManager(this);
            memoryManager = new MemoryManager(this);
            knowledgeSource = new KnowledgeSource(settings, memoryManager);
            permissionManager = new HardwarePermissionManager(settings);
            deviceController = new DeviceController(this, permissionManager);
            intentRouter = new IntentRouter(deviceController, knowledgeSource, memoryManager);
            ttsManager = new TtsManager(this, settings);
            voiceManager = new VoiceManager(this, settings);
            wakeWordDetector = WakeWordDetector.getInstance();
            long cooldownMs = (settings != null) ? (settings.getPostTaskCooldownSeconds() * 1000L) : WakeWordDetector.DEFAULT_COOLDOWN_MILLIS;
            wakeWordDetector.setCooldownDurationMillis(cooldownMs);
        } catch (Throwable t) {
            StartupLogger.logError("VLS-ENG", "Failed to initialize core engines in VoiceListenerService", t);
        }

        // TTS Callback
        if (ttsManager != null) {
            ttsManager.setCallback(new TtsManager.TtsCallback() {
                @Override
                public void onTtsStarted(String utteranceId) {
                    cancelSilenceTimeout();
                    if (voiceManager != null) voiceManager.onTtsStarted();
                    transitionToState(AssistantState.SPEAKING, "JARVIS SPEAKING...");
                }

                @Override
                public void onTtsFinished(String utteranceId) {
                    if (wakeWordDetector != null) {
                        wakeWordDetector.markTaskCompleted();
                    }
                    long cooldownMs = (settings != null) ? (settings.getPostTaskCooldownSeconds() * 1000L) : WakeWordDetector.DEFAULT_COOLDOWN_MILLIS;
                    if (voiceManager != null) {
                        voiceManager.onTaskCompleted(cooldownMs);
                    }
                    if (!isScreenLocked && isHomeScreen) {
                        transitionToState(AssistantState.POST_TASK_COOLDOWN, "TASK COMPLETED // WAITING FOR SILENCE...");
                        cancelSilenceTimeout();
                        handler.removeCallbacks(cooldownCompletedRunnable);
                        handler.postDelayed(cooldownCompletedRunnable, cooldownMs);
                    } else if (isScreenLocked) {
                        transitionToState(AssistantState.INACTIVE_LOCKED, "INACTIVE // DEVICE LOCKED");
                    } else {
                        transitionToState(AssistantState.INACTIVE_APP, "INACTIVE // IN APP");
                    }
                }

                @Override
                public void onTtsError(String utteranceId, String error) {
                    if (voiceManager != null) voiceManager.onTtsFinished();
                    if (!isScreenLocked && isHomeScreen) {
                        resetSilenceTimeout();
                    }
                }
            });
        }

        // Voice Manager Callback
        if (voiceManager != null) {
            voiceManager.setCallback(new VoiceManager.VoiceCallback() {
                @Override
                public void onStateChanged(VoiceManager.State state, String message) {}

                @Override
                public void onRmsLevel(float rmsdB) {
                    if (currentState == AssistantState.ACTIVE_LISTENING && rmsdB > 2.0f) {
                        resetSilenceTimeout();
                    }
                    for (ServiceStateListener l : stateListeners) {
                        try {
                            l.onRmsLevel(rmsdB);
                        } catch (Throwable ignored) {}
                    }
                }

                @Override
                public void onPartialSpeech(String text) {
                    if (currentState == AssistantState.ACTIVE_LISTENING) {
                        resetSilenceTimeout();
                    }
                    for (ServiceStateListener l : stateListeners) {
                        try {
                            l.onTranscriptUpdate(text, false);
                        } catch (Throwable ignored) {}
                    }
                }

                @Override
                public void onFinalSpeech(String text) {
                    handleSpeechRecognized(text);
                }

                @Override
                public void onError(int errorCode, String errorMessage) {
                    // Keep passive loop alive if unlocked
                    if (!isScreenLocked && isHomeScreen && currentState != AssistantState.SPEAKING) {
                        if (currentState == AssistantState.ACTIVE_LISTENING) {
                            // In active mode, silence timeout handles return to passive
                        }
                    }
                }
            });
        }
    }

    private void registerScreenLockReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenLockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenLockReceiver, filter);
            }
            isReceiverRegistered = true;
            StartupLogger.logStep("VLS-04", "screenLockReceiver successfully registered with Context.RECEIVER_NOT_EXPORTED.");
        } catch (Throwable t) {
            StartupLogger.logWarning("VLS-04", "registerReceiver failed with RECEIVER_NOT_EXPORTED: " + t.getMessage());
            try {
                registerReceiver(screenLockReceiver, filter);
                isReceiverRegistered = true;
                StartupLogger.logStep("VLS-04", "screenLockReceiver registered without flags.");
            } catch (Throwable t2) {
                StartupLogger.logError("VLS-04", "Failed to register screenLockReceiver: " + t2.getMessage(), t2);
            }
        }
    }

    /**
     * Called when screen is turned off or device is locked.
     * Guarantees that Jarvis NEVER activates when locked.
     */
    public void onScreenLocked() {
        isScreenLocked = true;
        cancelSilenceTimeout();
        if (voiceManager != null) {
            voiceManager.stopListening();
        }
        if (ttsManager != null && ttsManager.isSpeaking()) {
            ttsManager.stop();
        }
        transitionToState(AssistantState.INACTIVE_LOCKED, "INACTIVE // DEVICE LOCKED");
        Log.i(TAG, "Jarvis is completely inactive while device is locked (0% mic usage).");
    }

    /**
     * Called when the user unlocks the device with PIN/fingerprint/face.
     */
    public void onUserUnlocked() {
        isScreenLocked = false;
        checkAndApplyHomeState();
    }

    /**
     * Called when Home button is tapped or Home gesture is performed.
     */
    public void onHomeKeyPressed() {
        isHomeScreen = true;
        if (!isScreenLocked && !HomeScreenDetector.isDeviceLocked(this)) {
            if (currentState != AssistantState.ACTIVE_LISTENING && currentState != AssistantState.SPEAKING) {
                transitionToState(AssistantState.PASSIVE_STANDBY, "STANDBY // ON HOME SCREEN (SAY 'JARVIS')");
                startPassiveListening();
            }
        }
    }

    public void handleForegroundPackageChanged(String packageName, boolean isHome) {
        if (isScreenLocked || HomeScreenDetector.isDeviceLocked(this)) {
            return;
        }

        boolean isOurApp = getPackageName().equals(packageName);
        this.isHomeScreen = isHome || isOurApp;

        if (this.isHomeScreen) {
            if (currentState == AssistantState.INACTIVE_APP || currentState == AssistantState.INACTIVE_LOCKED) {
                transitionToState(AssistantState.PASSIVE_STANDBY, "STANDBY // ON HOME SCREEN (SAY 'JARVIS')");
                startPassiveListening();
            }
        } else {
            // User entered another app: stay inactive
            if (currentState != AssistantState.INACTIVE_APP) {
                cancelSilenceTimeout();
                if (voiceManager != null) {
                    voiceManager.stopListening();
                }
                transitionToState(AssistantState.INACTIVE_APP, "INACTIVE // IN APP (" + packageName + ")");
            }
        }
    }

    private void checkAndApplyHomeState() {
        if (isScreenLocked || HomeScreenDetector.isDeviceLocked(this)) {
            onScreenLocked();
            return;
        }

        if (isHomeScreen) {
            transitionToState(AssistantState.PASSIVE_STANDBY, "STANDBY // ON HOME SCREEN (SAY 'JARVIS')");
            startPassiveListening();
        } else {
            transitionToState(AssistantState.INACTIVE_APP, "INACTIVE // IN APP");
            if (voiceManager != null) {
                voiceManager.stopListening();
            }
        }
    }

    private void startPassiveListening() {
        if (voiceManager != null && !isScreenLocked && isHomeScreen) {
            voiceManager.startListening();
        }
    }

    /**
     * Central speech recognition routing.
     * Evaluates trigger word "Jarvis" when in passive standby,
     * and processes full conversational queries when in active listening mode.
     */
    private void handleSpeechRecognized(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return;
        String spoken = rawText.trim();
        Log.i(TAG, "handleSpeechRecognized in state [" + currentState + "]: " + spoken);

        if (wakeWordDetector == null) {
            wakeWordDetector = WakeWordDetector.getInstance();
        }

        WakeWordDetector.EvaluationResult eval = wakeWordDetector.evaluateSpeech(spoken);

        // 1. Post-Task Cooldown: ignore all speech until silence duration passes
        if (currentState == AssistantState.POST_TASK_COOLDOWN || eval.action == WakeWordDetector.EvaluationResult.Action.REJECTED_COOLDOWN) {
            Log.d(TAG, "Post-task cooldown active: waiting for silence. Ignored: " + spoken);
            return;
        }

        // 2. Strict Wake Word Gating: ignore background speech/conversations without "Jarvis"
        if (eval.action == WakeWordDetector.EvaluationResult.Action.REJECTED_NO_WAKE_WORD) {
            Log.d(TAG, "Background speech/conversation without 'Jarvis' ignored: " + spoken);
            // Remain completely quiet. Keep passive listening alive if on home screen.
            if (!isScreenLocked && isHomeScreen && currentState == AssistantState.PASSIVE_STANDBY) {
                startPassiveListening();
            }
            return;
        }

        for (ServiceStateListener l : stateListeners) {
            l.onTranscriptUpdate(spoken, true);
        }

        // 3. Wake Word Detected Alone (e.g. "Jarvis" or "Hey Jarvis")
        if (eval.action == WakeWordDetector.EvaluationResult.Action.ACCEPTED_WAKE_ONLY) {
            Log.i(TAG, "Trigger word 'Jarvis' detected alone! Waking up...");
            transitionToState(AssistantState.ACTIVE_LISTENING, "ACTIVE // LISTENING (10S TIMEOUT)");
            resetSilenceTimeout();

            String greeting = DynamicPhrasing.getDynamicGreeting();
            ttsManager.speakFirstChunk(greeting);
            for (ServiceStateListener l : stateListeners) {
                l.onResponseSpoken(greeting, "WAKE_TRIGGER");
            }
            return;
        }

        // 4. Wake Word + Command (e.g. "Jarvis, turn on wifi")
        if (eval.action == WakeWordDetector.EvaluationResult.Action.ACCEPTED_COMMAND) {
            Log.i(TAG, "Trigger word + command detected: [" + eval.command + "]. Processing...");
            transitionToState(AssistantState.ACTIVE_LISTENING, "ACTIVE // EXECUTING COMMAND");
            resetSilenceTimeout();
            processCommand(eval.command);
            return;
        }
    }

    /**
     * Executes intent routing and answers the user.
     */
    public void processCommand(String commandText) {
        if (commandText == null || commandText.trim().isEmpty()) return;

        transitionToState(AssistantState.PROCESSING, "THINKING...");
        cancelSilenceTimeout();

        if (intentRouter == null) {
            transitionToState(AssistantState.PASSIVE_STANDBY, "STANDBY // READY");
            return;
        }

        final StringBuilder responseAccumulator = new StringBuilder();

        intentRouter.routeStreaming(commandText, new IntentRouter.StreamingRouterCallback() {
            @Override
            public void onFirstSentence(String firstSentence, String toolBadge) {
                responseAccumulator.append(firstSentence);
                transitionToState(AssistantState.SPEAKING, "JARVIS SPEAKING...");
                if (ttsManager != null) {
                    ttsManager.speakFirstChunk(firstSentence);
                }
                for (ServiceStateListener l : stateListeners) {
                    l.onResponseSpoken(firstSentence, toolBadge);
                }
            }

            @Override
            public void onNextSentence(String nextSentence) {
                if (responseAccumulator.length() > 0) responseAccumulator.append(" ");
                responseAccumulator.append(nextSentence);
                if (ttsManager != null) {
                    ttsManager.speakNextChunk(nextSentence);
                }
                for (ServiceStateListener l : stateListeners) {
                    l.onResponseSpoken(responseAccumulator.toString(), "STREAM");
                }
            }

            @Override
            public void onComplete(IntentRouter.IntentResult fullResult) {
                if (fullResult != null && fullResult.spokenResponse != null) {
                    for (ServiceStateListener l : stateListeners) {
                        l.onResponseSpoken(fullResult.spokenResponse, fullResult.toolBadge);
                    }
                }
                if (ttsManager != null) {
                    ttsManager.endStreamingSession();
                }
                if (wakeWordDetector != null) {
                    wakeWordDetector.markTaskCompleted();
                }
            }
        });
    }

    private void resetSilenceTimeout() {
        handler.removeCallbacks(silenceTimeoutRunnable);
        int timeoutSec = settings != null ? settings.getSilenceTimeoutSeconds() : 10;
        handler.postDelayed(silenceTimeoutRunnable, timeoutSec * 1000L);
    }

    private void cancelSilenceTimeout() {
        handler.removeCallbacks(silenceTimeoutRunnable);
    }

    public static String extractCommandAfterWakeWord(String text) {
        String cmd = WakeWordDetector.getDefault().extractCommand(text);
        return cmd != null ? cmd : "";
    }

    private void transitionToState(AssistantState newState, String statusMessage) {
        this.currentState = newState;
        this.currentStatusMessage = statusMessage;
        Log.i(TAG, "State transition -> [" + newState + "]: " + statusMessage);

        updateNotification(statusMessage);

        for (ServiceStateListener l : stateListeners) {
            l.onStateChanged(newState, statusMessage);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Jarvis Arthur Voice Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background voice monitoring and home screen wake for Jarvis");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(text));
            }
        } catch (Exception ignored) {}
    }

    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("JARVIS 4.0 - Active")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    public AssistantState getCurrentState() {
        return currentState;
    }

    public boolean isScreenLocked() {
        return isScreenLocked;
    }

    public boolean isHomeScreen() {
        return isHomeScreen;
    }

    public void setHomeScreenForTesting(boolean isHome) {
        this.isHomeScreen = isHome;
    }

    public void setScreenLockedForTesting(boolean locked) {
        this.isScreenLocked = locked;
    }

    public void simulateWakeWordTrigger(String speech) {
        handleSpeechRecognized(speech);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        isServiceRunning = false;
        cancelSilenceTimeout();
        handler.removeCallbacks(cooldownCompletedRunnable);

        if (isReceiverRegistered) {
            try {
                unregisterReceiver(screenLockReceiver);
                isReceiverRegistered = false;
            } catch (Throwable ignored) {}
        }

        if (voiceManager != null) {
            try {
                voiceManager.destroy();
            } catch (Throwable ignored) {}
        }
        if (ttsManager != null) {
            try {
                ttsManager.shutdown();
            } catch (Throwable ignored) {}
        }
    }
}
