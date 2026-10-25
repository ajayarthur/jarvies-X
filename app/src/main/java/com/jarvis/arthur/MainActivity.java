package com.jarvis.arthur;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.Context;
import android.widget.ScrollView;

import com.jarvis.arthur.util.StartupLogger;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.jarvis.arthur.device.DeviceController;
import com.jarvis.arthur.device.HardwarePermissionManager;
import com.jarvis.arthur.intent.IntentRouter;
import com.jarvis.arthur.knowledge.KnowledgeSource;
import com.jarvis.arthur.service.VoiceListenerService;
import com.jarvis.arthur.ui.ArcReactorView;
import com.jarvis.arthur.ui.AudioWaveformView;
import com.jarvis.arthur.voice.TtsManager;
import com.jarvis.arthur.voice.VoiceManager;
import com.jarvis.arthur.voice.WakeWordDetector;

import java.util.ArrayList;
import java.util.List;

/**
 * Main Activity for Jarvis Arthur Assistant.
 * Coordinates Continuous Voice Listening, Intent Routing, Knowledge Retrieval, and TTS.
 */
public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQ_CODE = 101;

    private SettingsManager settings;
    private HardwarePermissionManager hardwarePermissionManager;
    private TtsManager ttsManager;
    private DeviceController deviceController;
    private KnowledgeSource knowledgeSource;
    private IntentRouter intentRouter;
    private VoiceManager voiceManager;

    // UI Views
    private ArcReactorView arcReactorView;
    private AudioWaveformView waveformView;
    private TextView tvStatusText;
    private View statusIndicatorDot;
    private TextView tvTranscript;
    private TextView tvResponse;
    private TextView tvToolBadge;
    private SwitchMaterial switchContinuous;
    private EditText etCommandInput;
    private ImageButton btnSend;
    private ImageButton btnSettings;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        StartupLogger.logStep("ACT-01", "MainActivity.onCreate called. savedInstanceState=" + (savedInstanceState != null));
        try {
            super.onCreate(savedInstanceState);
            StartupLogger.logStep("ACT-02", "super.onCreate finished.");
        } catch (Throwable t) {
            StartupLogger.logError("ACT-02", "Fatal error during super.onCreate", t);
            throw t;
        }

        try {
            StartupLogger.logStep("ACT-03", "Inflating layout R.layout.activity_main...");
            setContentView(R.layout.activity_main);
            StartupLogger.logStep("ACT-04", "setContentView completed successfully.");
        } catch (Throwable t) {
            StartupLogger.logError("ACT-04", "Fatal error during setContentView", t);
            throw t;
        }

        try {
            StartupLogger.logStep("ACT-05", "Binding UI view components in initViews()...");
            initViews();
            StartupLogger.logStep("ACT-06", "initViews completed successfully.");
        } catch (Throwable t) {
            StartupLogger.logError("ACT-06", "Fatal error during initViews", t);
            throw t;
        }

        try {
            StartupLogger.logStep("ACT-07", "Initializing core assistant engines in initCoreEngines()...");
            initCoreEngines();
            StartupLogger.logStep("ACT-08", "initCoreEngines completed successfully.");
        } catch (Throwable t) {
            StartupLogger.logError("ACT-08", "Fatal error during initCoreEngines", t);
            throw t;
        }

        try {
            StartupLogger.logStep("ACT-09", "Checking and requesting permissions...");
            checkAndRequestPermissions();
            StartupLogger.logStep("ACT-10", "checkAndRequestPermissions dispatched.");
        } catch (Throwable t) {
            StartupLogger.logError("ACT-10", "Fatal error during checkAndRequestPermissions", t);
            throw t;
        }

        StartupLogger.logStep("ACT-11", "MainActivity.onCreate finished successfully.");

        // Check if there was a fatal crash recorded in the previous run
        String lastCrash = StartupLogger.getLastCrashReport(this);
        if (lastCrash != null && !lastCrash.isEmpty()) {
            mainHandler.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    new AlertDialog.Builder(this)
                            .setTitle("PREVIOUS CRASH DETECTED")
                            .setMessage("Jarvis detected an error or crash from your last session. Would you like to view the diagnostics?")
                            .setPositiveButton("View Diagnostics", (d, w) -> showStartupLogsDialog())
                            .setNegativeButton("Dismiss", null)
                            .show();
                }
            }, 800);
        }
    }

    private void initViews() {
        arcReactorView = findViewById(R.id.arcReactorView);
        waveformView = findViewById(R.id.waveformView);
        tvStatusText = findViewById(R.id.tvStatusText);
        statusIndicatorDot = findViewById(R.id.statusIndicatorDot);
        tvTranscript = findViewById(R.id.tvTranscript);
        tvResponse = findViewById(R.id.tvResponse);
        tvToolBadge = findViewById(R.id.tvToolBadge);
        switchContinuous = findViewById(R.id.switchContinuous);
        etCommandInput = findViewById(R.id.etCommandInput);
        btnSend = findViewById(R.id.btnSend);
        btnSettings = findViewById(R.id.btnSettings);

        // Tap Arc Reactor, waveform, or response text to toggle voice or interrupt speaking (barge-in)
        if (arcReactorView != null) {
            arcReactorView.setOnClickListener(v -> handleVoiceInteractionTap());
        }
        if (waveformView != null) {
            waveformView.setOnClickListener(v -> {
                if (ttsManager != null && ttsManager.isSpeaking()) {
                    handleVoiceInteractionTap();
                }
            });
        }
        if (tvResponse != null) {
            tvResponse.setOnClickListener(v -> {
                if (ttsManager != null && ttsManager.isSpeaking()) {
                    handleVoiceInteractionTap();
                }
            });
        }

        // Continuous listening toggle
        if (switchContinuous != null) {
            switchContinuous.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (voiceManager != null) {
                    voiceManager.setContinuousMode(isChecked);
                }
                if (settings != null) {
                    settings.setContinuousListening(isChecked);
                }
                if (isChecked) {
                    Toast.makeText(this, "Continuous Listening Enabled", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Continuous Listening Paused", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Send manual text query
        if (btnSend != null) {
            btnSend.setOnClickListener(v -> handleManualCommand());
        }
        if (etCommandInput != null) {
            etCommandInput.setOnEditorActionListener((v, actionId, event) -> {
                handleManualCommand();
                return true;
            });
        }

        // Quick action chips - safely wire click listeners
        setupChip(R.id.chipWifi, "Toggle Wi-Fi");
        setupChip(R.id.chipBluetooth, "Toggle Bluetooth");
        setupChip(R.id.chipDnd, "Toggle Do Not Disturb");
        setupChip(R.id.chipCamera, "Open Camera");
        setupChip(R.id.chipWeather, "What is the current weather?");
        setupChip(R.id.chipFlashlight, "Turn on flashlight");
        setupChip(R.id.chipYoutube, "Open YouTube");
        setupChip(R.id.chipBattery, "What is my battery level?");
        setupChip(R.id.chipMath, "Calculate 45 * 87");
        setupChip(R.id.chipQuantum, "Tell me about Quantum Computing");
        setupChip(R.id.chipTime, "What time is it?");
        setupChip(R.id.chipMemory, "What do you remember about me?");

        // Settings Dialog
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }

        // Long press on title opens Startup Diagnostics
        View tvTitle = findViewById(R.id.tvAppTitle);
        if (tvTitle != null) {
            tvTitle.setOnLongClickListener(v -> {
                showStartupLogsDialog();
                return true;
            });
        }
    }

    private void setupChip(int resId, String command) {
        View chip = findViewById(resId);
        if (chip != null) {
            chip.setOnClickListener(v -> processSpokenInput(command));
        }
    }

    private void handleVoiceInteractionTap() {
        VoiceListenerService service = VoiceListenerService.getInstance();
        boolean isSpeaking = (ttsManager != null && ttsManager.isSpeaking()) ||
                (service != null && service.getCurrentState() == VoiceListenerService.AssistantState.SPEAKING);

        if (isSpeaking) {
            if (ttsManager != null) ttsManager.stop();
            if (voiceManager != null) voiceManager.onTtsFinished();
            Toast.makeText(this, "Assistant interrupted. Listening...", Toast.LENGTH_SHORT).show();
        } else if (voiceManager != null && voiceManager.getCurrentState() == VoiceManager.State.LISTENING) {
            voiceManager.stopListening();
        } else if (voiceManager != null) {
            voiceManager.startListening();
        }
    }

    private void initCoreEngines() {
        StartupLogger.logStep("ENG-01", "Initializing SettingsManager...");
        try {
            settings = new SettingsManager(this);
            StartupLogger.logStep("ENG-02", "SettingsManager ready. Provider=" + settings.getLlmProvider());
        } catch (Throwable t) {
            StartupLogger.logError("ENG-02", "SettingsManager initialization failed", t);
        }

        StartupLogger.logStep("ENG-03", "Initializing HardwarePermissionManager...");
        try {
            hardwarePermissionManager = new HardwarePermissionManager(settings);
            StartupLogger.logStep("ENG-04", "HardwarePermissionManager ready.");
        } catch (Throwable t) {
            StartupLogger.logError("ENG-04", "HardwarePermissionManager initialization failed", t);
        }

        StartupLogger.logStep("ENG-05", "Initializing MemoryManager (500 MB vault)...");
        com.jarvis.arthur.memory.MemoryManager memoryManager = null;
        try {
            memoryManager = new com.jarvis.arthur.memory.MemoryManager(this);
            StartupLogger.logStep("ENG-06", String.format(java.util.Locale.US, "MemoryManager ready. StorageUsage=%.2f MB / %.0f MB (%.1f%%)",
                    memoryManager.getStorageUsedMb(), memoryManager.getStorageLimitMb(), memoryManager.getUsagePercentage()));
        } catch (Throwable t) {
            StartupLogger.logError("ENG-06", "MemoryManager initialization failed", t);
        }

        if (switchContinuous != null && settings != null) {
            switchContinuous.setChecked(settings.isContinuousListening());
        }

        StartupLogger.logStep("ENG-07", "Initializing TtsManager...");
        try {
            ttsManager = new TtsManager(this, settings);
            StartupLogger.logStep("ENG-08", "TtsManager instantiated.");
        } catch (Throwable t) {
            StartupLogger.logError("ENG-08", "TtsManager initialization failed", t);
        }

        StartupLogger.logStep("ENG-09", "Initializing DeviceController...");
        try {
            deviceController = new DeviceController(this, hardwarePermissionManager);
            StartupLogger.logStep("ENG-10", "DeviceController ready.");
        } catch (Throwable t) {
            StartupLogger.logError("ENG-10", "DeviceController initialization failed", t);
        }

        StartupLogger.logStep("ENG-11", "Initializing KnowledgeSource & IntentRouter...");
        try {
            knowledgeSource = new KnowledgeSource(settings, memoryManager);
            intentRouter = new IntentRouter(deviceController, knowledgeSource, memoryManager);
            StartupLogger.logStep("ENG-12", "KnowledgeSource & IntentRouter ready.");
        } catch (Throwable t) {
            StartupLogger.logError("ENG-12", "KnowledgeSource / IntentRouter initialization failed", t);
        }

        StartupLogger.logStep("ENG-13", "Initializing VoiceManager...");
        try {
            voiceManager = new VoiceManager(this, settings);
            StartupLogger.logStep("ENG-14", "VoiceManager instantiated.");
        } catch (Throwable t) {
            StartupLogger.logError("ENG-14", "VoiceManager initialization failed", t);
        }

        // Link TTS callbacks with Voice Manager for speech coordination
        if (ttsManager != null) {
            ttsManager.setCallback(new TtsManager.TtsCallback() {
                @Override
                public void onTtsStarted(String utteranceId) {
                    if (voiceManager != null) voiceManager.onTtsStarted();
                    updateUiState(VoiceManager.State.SPEAKING, "SPEAKING // TAP TO INTERRUPT");
                }

                @Override
                public void onTtsFinished(String utteranceId) {
                    if (voiceManager != null) voiceManager.onTtsFinished();
                    if (voiceManager == null || !voiceManager.isContinuousMode()) {
                        updateUiState(VoiceManager.State.IDLE, "STANDBY // READY");
                    }
                }

                @Override
                public void onTtsError(String utteranceId, String error) {
                    if (voiceManager != null) voiceManager.onTtsFinished();
                }
            });
        }

        // Link Voice callbacks with UI and Intent Router
        if (voiceManager != null) {
            voiceManager.setCallback(new VoiceManager.VoiceCallback() {
                @Override
                public void onStateChanged(VoiceManager.State state, String message) {
                    updateUiState(state, message != null ? message.toUpperCase() : "READY");
                }

                @Override
                public void onRmsLevel(float rmsdB) {
                    if (arcReactorView != null) arcReactorView.setRmsLevel(rmsdB);
                    if (waveformView != null) waveformView.setRms(rmsdB);
                }

                @Override
                public void onPartialSpeech(String text) {
                    if (tvTranscript != null) tvTranscript.setText(text);
                }

                @Override
                public void onFinalSpeech(String text) {
                    processSpokenInput(text);
                }

                @Override
                public void onError(int errorCode, String errorMessage) {
                    // Keep UI informed
                    if (errorCode != 7 && errorCode != 6) { // not silence timeouts
                        if (tvStatusText != null) {
                            tvStatusText.setText("STATUS: " + (errorMessage != null ? errorMessage.toUpperCase() : "ERROR"));
                        }
                    }
                }
            });
        }
    }

    private void processSpokenInput(String speechText) {
        if (speechText == null || speechText.trim().isEmpty()) return;
        if (tvTranscript != null) tvTranscript.setText(speechText);
        updateUiState(VoiceManager.State.PROCESSING, "PROCESSING INTENT...");

        VoiceListenerService service = VoiceListenerService.getInstance();
        if (service != null) {
            service.processCommand(speechText);
            return;
        }

        if (intentRouter == null) {
            updateUiState(VoiceManager.State.ERROR, "ENGINE INITIALIZING...");
            return;
        }

        final StringBuilder streamedTextAccumulator = new StringBuilder();

        intentRouter.routeStreaming(speechText, new IntentRouter.StreamingRouterCallback() {
            @Override
            public void onFirstSentence(String firstSentence, String toolBadge) {
                streamedTextAccumulator.append(firstSentence);
                if (tvResponse != null) tvResponse.setText(firstSentence);
                if (tvToolBadge != null) tvToolBadge.setText("[" + toolBadge + "]");

                // Step 2: Immediate audio response on the first usable sentence
                if (ttsManager != null) {
                    ttsManager.speakFirstChunk(firstSentence);
                }
            }

            @Override
            public void onNextSentence(String nextSentence) {
                if (streamedTextAccumulator.length() > 0) {
                    streamedTextAccumulator.append(" ");
                }
                streamedTextAccumulator.append(nextSentence);
                if (tvResponse != null) tvResponse.setText(streamedTextAccumulator.toString());

                // Seamlessly queue subsequent sentences into the TTS playback stream
                if (ttsManager != null) {
                    ttsManager.speakNextChunk(nextSentence);
                }
            }

            @Override
            public void onComplete(IntentRouter.IntentResult fullResult) {
                if (fullResult != null && fullResult.spokenResponse != null) {
                    if (tvResponse != null) tvResponse.setText(fullResult.spokenResponse);
                    if (tvToolBadge != null) tvToolBadge.setText("[" + fullResult.toolBadge + "]");
                    checkPromptIfHardwareConsentNeeded(fullResult);
                }
                if (ttsManager != null) {
                    ttsManager.endStreamingSession();
                }
                WakeWordDetector.getDefault().markTaskCompleted();
            }
        });
    }

    private void handleManualCommand() {
        if (etCommandInput == null) return;
        String text = etCommandInput.getText().toString().trim();
        if (!text.isEmpty()) {
            etCommandInput.setText("");
            processSpokenInput(text);
        }
    }

    private void updateUiState(VoiceManager.State state, String text) {
        if (tvStatusText != null && text != null) {
            tvStatusText.setText(text);
        }
        if (arcReactorView != null) {
            arcReactorView.setState(state);
        }
        if (waveformView != null) {
            waveformView.setState(state);
        }

        if (statusIndicatorDot != null) {
            try {
                switch (state) {
                    case LISTENING:
                        statusIndicatorDot.setBackgroundResource(R.drawable.bg_dot_emerald);
                        break;
                    case PROCESSING:
                        statusIndicatorDot.setBackgroundColor(getColor(R.color.gold_accent));
                        break;
                    case SPEAKING:
                        statusIndicatorDot.setBackgroundColor(getColor(R.color.cyan_primary));
                        break;
                    case ERROR:
                        statusIndicatorDot.setBackgroundColor(getColor(R.color.ruby_error));
                        break;
                    case IDLE:
                    default:
                        statusIndicatorDot.setBackgroundColor(getColor(R.color.blue_accent));
                        break;
                }
            } catch (Throwable ignored) {}
        }
    }

    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        EditText etApiKey = dialogView.findViewById(R.id.etApiKey);
        EditText etGroqApiKey = dialogView.findViewById(R.id.etGroqApiKey);
        SwitchMaterial switchWakeWord = dialogView.findViewById(R.id.switchWakeWord);
        SwitchMaterial switchAudioFeedback = dialogView.findViewById(R.id.switchAudioFeedback);
        TextView tvHwStatus = dialogView.findViewById(R.id.tvHardwarePermStatus);
        Button btnResetPerms = dialogView.findViewById(R.id.btnResetHardwarePermissions);
        Button btnSave = dialogView.findViewById(R.id.btnSaveSettings);
        Button btnCancel = dialogView.findViewById(R.id.btnCancelSettings);

        etApiKey.setText(settings.getGeminiApiKey());
        etGroqApiKey.setText(settings.getGroqApiKey());
        switchWakeWord.setChecked(settings.isWakeWordRequired());
        switchAudioFeedback.setChecked(settings.isAudioFeedbackEnabled());

        if (tvHwStatus != null) {
            StringBuilder sb = new StringBuilder();
            boolean unified = settings.isUnifiedPermissionsConfirmed();
            sb.append("Unified Setup: ")
              .append(unified ? "AUTHORIZED FOREVER" : "PENDING SETUP")
              .append("\n\n");
            for (String key : HardwarePermissionManager.ALL_HARDWARE_PERMISSIONS) {
                boolean confirmed = settings.isHardwarePermissionConfirmed(key);
                sb.append(HardwarePermissionManager.getDisplayName(key))
                  .append(": ")
                  .append(confirmed ? "GRANTED" : "PENDING")
                  .append("  •  ");
            }
            tvHwStatus.setText(sb.toString().trim());
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        if (btnResetPerms != null) {
            btnResetPerms.setOnClickListener(v -> {
                settings.resetAllHardwarePermissionConfirmations();
                Toast.makeText(this, "Hardware consents reset. Jarvis will prompt again upon next hardware command.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        }

        Button btnOpenAdmin = dialogView.findViewById(R.id.btnOpenDeviceAdminPermissions);
        if (btnOpenAdmin != null) {
            btnOpenAdmin.setOnClickListener(v -> {
                dialog.dismiss();
                if (hardwarePermissionManager != null) {
                    hardwarePermissionManager.requestUnifiedPermissions(MainActivity.this, null);
                }
            });
        }

        Button btnViewStartup = dialogView.findViewById(R.id.btnViewStartupLogs);
        if (btnViewStartup != null) {
            btnViewStartup.setOnClickListener(v -> showStartupLogsDialog());
        }

        btnSave.setOnClickListener(v -> {
            settings.setGeminiApiKey(etApiKey.getText().toString().trim());
            settings.setGroqApiKey(etGroqApiKey.getText().toString().trim());
            settings.setWakeWordRequired(switchWakeWord.isChecked());
            settings.setAudioFeedbackEnabled(switchAudioFeedback.isChecked());
            dialog.dismiss();
            Toast.makeText(this, "Settings updated successfully", Toast.LENGTH_SHORT).show();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void checkPromptIfHardwareConsentNeeded(IntentRouter.IntentResult result) {
        if (result == null || result.success || hardwarePermissionManager == null) return;
        String badge = result.toolBadge;
        if (badge == null) return;

        String permKey = null;
        switch (badge) {
            case "HARDWARE_ADMIN":
            case "DEVICE_ADMIN":
            case "LOCK_DEVICE":
                permKey = HardwarePermissionManager.PERM_DEVICE_ADMIN;
                break;
            case "HARDWARE_WIFI": permKey = HardwarePermissionManager.PERM_WIFI; break;
            case "HARDWARE_BLUETOOTH": permKey = HardwarePermissionManager.PERM_BLUETOOTH; break;
            case "HARDWARE_DND": permKey = HardwarePermissionManager.PERM_DND; break;
            case "HARDWARE_DATA": permKey = HardwarePermissionManager.PERM_MOBILE_DATA; break;
            case "HARDWARE_CAMERA": permKey = HardwarePermissionManager.PERM_CAMERA; break;
            case "HARDWARE_TORCH": permKey = HardwarePermissionManager.PERM_FLASHLIGHT; break;
            case "AUDIO_CONTROL": permKey = HardwarePermissionManager.PERM_VOLUME; break;
        }

        if (permKey != null && !hardwarePermissionManager.hasConsent(permKey)) {
            final String finalKey = permKey;
            hardwarePermissionManager.requestConsent(this, permKey, new HardwarePermissionManager.ConsentCallback() {
                @Override
                public void onGranted(String permissionKey) {
                    Toast.makeText(MainActivity.this, "Consent saved. Retrying command...", Toast.LENGTH_SHORT).show();
                    if (result.query != null && !result.query.isEmpty()) {
                        processSpokenInput(result.query);
                    }
                }

                @Override
                public void onDenied(String permissionKey) {
                    Toast.makeText(MainActivity.this, "Permission denied for " + HardwarePermissionManager.getDisplayName(finalKey), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void checkAndRequestPermissions() {
        if (hardwarePermissionManager != null) {
            hardwarePermissionManager.checkInitialHardwarePermissions(this, this::startServicesAndListening);
        } else {
            startServicesAndListening();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE || requestCode == HardwarePermissionManager.UNIFIED_PERMISSION_REQ_CODE) {
            boolean audioGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.RECORD_AUDIO.equals(permissions[i]) &&
                        grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    audioGranted = false;
                }
            }

            if (audioGranted) {
                startServicesAndListening();
            } else {
                Toast.makeText(this, "Microphone permission required for voice input.", Toast.LENGTH_LONG).show();
                updateUiState(VoiceManager.State.ERROR, "MICROPHONE PERMISSION DENIED");
            }
        }
    }

    private void startServicesAndListening() {
        StartupLogger.logStep("SRV-01", "startServicesAndListening invoked.");
        if (settings != null) {
            settings.setHasOpenedOnce(true);
            settings.setBackgroundServiceEnabled(true);
        }

        StartupLogger.logStep("SRV-02", "Launching VoiceListenerService...");
        try {
            Intent serviceIntent = new Intent(this, VoiceListenerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            StartupLogger.logStep("SRV-03", "VoiceListenerService start intent successfully dispatched.");
        } catch (Throwable e) {
            StartupLogger.logError("SRV-03", "Failed to start VoiceListenerService", e);
        }

        // Greet and initiate continuous listening
        mainHandler.postDelayed(() -> {
            StartupLogger.logStep("SRV-04", "Delayed continuous listening check triggered.");
            if (settings != null && settings.isContinuousListening() && voiceManager != null) {
                try {
                    voiceManager.startListening();
                    StartupLogger.logStep("SRV-05", "voiceManager.startListening initiated.");
                } catch (Throwable t) {
                    StartupLogger.logError("SRV-05", "Failed to start voiceManager listening", t);
                }
            }
        }, 600);
    }

    private void showStartupLogsDialog() {
        List<String> logs = StartupLogger.getRecentLogs();
        String lastCrash = StartupLogger.getLastCrashReport(this);

        StringBuilder sb = new StringBuilder();
        if (lastCrash != null && !lastCrash.isEmpty()) {
            sb.append("⚠️ DETECTED CRASH REPORT:\n")
              .append(lastCrash)
              .append("\n----------------------------------------\n\n");
        }

        sb.append("STARTUP BREADCRUMBS & TELEMETRY:\n");
        for (String logLine : logs) {
            sb.append(logLine).append("\n");
        }

        ScrollView scrollView = new ScrollView(this);
        TextView textView = new TextView(this);
        textView.setText(sb.toString());
        textView.setTextSize(11f);
        textView.setPadding(32, 24, 32, 24);
        textView.setTextIsSelectable(true);
        textView.setTypeface(android.graphics.Typeface.MONOSPACE);
        textView.setTextColor(getColor(R.color.text_body));
        scrollView.addView(textView);

        if (!isFinishing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed())) {
            try {
                new AlertDialog.Builder(this)
                        .setTitle("JARVIS STARTUP DIAGNOSTICS")
                        .setView(scrollView)
                        .setPositiveButton("Close", null)
                        .setNeutralButton("Copy Logs", (d, w) -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis Startup Logs", sb.toString()));
                                Toast.makeText(this, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Clear Crash Log", (d, w) -> {
                            StartupLogger.clearCrashReport(this);
                            Toast.makeText(this, "Crash report cleared", Toast.LENGTH_SHORT).show();
                        })
                        .show();
            } catch (Throwable t) {
                StartupLogger.logError("UI", "Failed to display startup logs dialog", t);
            }
        }
    }

    private final VoiceListenerService.ServiceStateListener serviceListener = new VoiceListenerService.ServiceStateListener() {
        @Override
        public void onStateChanged(VoiceListenerService.AssistantState state, String statusMessage) {
            mainHandler.post(() -> {
                if (tvStatusText != null && statusMessage != null) {
                    tvStatusText.setText(statusMessage);
                }
                switch (state) {
                    case ACTIVE_LISTENING:
                        updateUiState(VoiceManager.State.LISTENING, statusMessage != null ? statusMessage : "LISTENING...");
                        break;
                    case PROCESSING:
                        updateUiState(VoiceManager.State.PROCESSING, statusMessage != null ? statusMessage : "PROCESSING...");
                        break;
                    case SPEAKING:
                        updateUiState(VoiceManager.State.SPEAKING, statusMessage != null ? statusMessage : "SPEAKING...");
                        break;
                    case PASSIVE_STANDBY:
                    case INACTIVE_LOCKED:
                    case INACTIVE_APP:
                    default:
                        updateUiState(VoiceManager.State.IDLE, statusMessage != null ? statusMessage : "STANDBY");
                        break;
                }
            });
        }

        @Override
        public void onTranscriptUpdate(String text, boolean isFinal) {
            mainHandler.post(() -> {
                if (tvTranscript != null) tvTranscript.setText(text);
            });
        }

        @Override
        public void onResponseSpoken(String spokenAnswer, String toolBadge) {
            mainHandler.post(() -> {
                if (tvResponse != null) tvResponse.setText(spokenAnswer);
                if (tvToolBadge != null && toolBadge != null) {
                    tvToolBadge.setText("[" + toolBadge + "]");
                }
            });
        }

        @Override
        public void onRmsLevel(float rms) {
            mainHandler.post(() -> {
                if (arcReactorView != null) arcReactorView.setRmsLevel(rms);
                if (waveformView != null) waveformView.setRms(rms);
            });
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        VoiceListenerService.addListener(serviceListener);
        VoiceListenerService.notifyForegroundPackageChanged(getPackageName(), true);

        if (settings != null && settings.isContinuousListening() && voiceManager != null &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                voiceManager.startListening();
            } catch (Throwable t) {
                StartupLogger.logError("RESUME", "Failed to resume voice listening in onResume", t);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        VoiceListenerService.removeListener(serviceListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        VoiceListenerService.removeListener(serviceListener);
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
