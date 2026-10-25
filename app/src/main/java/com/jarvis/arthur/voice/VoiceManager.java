package com.jarvis.arthur.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import com.jarvis.arthur.SettingsManager;
import com.jarvis.arthur.util.StartupLogger;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Continuous Voice Input Manager using Android's native SpeechRecognizer.
 * Step 1 implementation: Handles continuous listening, RMS audio levels,
 * partial transcripts, silence detection, and auto-restart loops.
 */
public class VoiceManager {
    private static final String TAG = "VoiceManager";

    public enum State {
        IDLE,
        LISTENING,
        PROCESSING,
        SPEAKING,
        ERROR
    }

    public interface VoiceCallback {
        void onStateChanged(State state, String message);
        void onRmsLevel(float rmsdB);
        void onPartialSpeech(String text);
        void onFinalSpeech(String text);
        void onError(int errorCode, String errorMessage);
    }

    private final Context context;
    private final SettingsManager settings;
    private final Handler mainHandler;
    private VoiceCallback callback;

    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;

    private boolean isContinuousModeActive = true;
    private boolean isSpeaking = false;
    private boolean isManuallyPaused = false;
    private State currentState = State.IDLE;
    private int consecutiveErrorCount = 0;
    private final WakeWordDetector wakeWordDetector;

    public VoiceManager(Context context, SettingsManager settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.isContinuousModeActive = settings.isContinuousListening();
        long cooldownMs = (settings != null) ? (settings.getPostTaskCooldownSeconds() * 1000L) : WakeWordDetector.DEFAULT_COOLDOWN_MILLIS;
        this.wakeWordDetector = new WakeWordDetector(cooldownMs);

        initRecognizerIntent();
    }

    public WakeWordDetector getWakeWordDetector() {
        return wakeWordDetector;
    }

    public void setCallback(VoiceCallback callback) {
        this.callback = callback;
    }

    private void initRecognizerIntent() {
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        
        // Optimize silence length for immediate snappy responses
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600L);
    }

    private void createRecognizer() {
        destroyRecognizer();
        boolean available = SpeechRecognizer.isRecognitionAvailable(context);
        StartupLogger.logStep("VOX-01", "Checking SpeechRecognizer availability: " + available);
        if (available) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
                if (speechRecognizer != null) {
                    speechRecognizer.setRecognitionListener(new InnerRecognitionListener());
                    StartupLogger.logStep("VOX-02", "SpeechRecognizer created successfully.");
                    Log.d(TAG, "SpeechRecognizer created successfully.");
                } else {
                    StartupLogger.logWarning("VOX-02", "createSpeechRecognizer returned null.");
                    updateState(State.ERROR, "Speech recognition unavailable.");
                    if (callback != null) {
                        callback.onError(-1, "Speech recognition engine returned null.");
                    }
                }
            } catch (Throwable t) {
                StartupLogger.logError("VOX-02", "Failed to create SpeechRecognizer", t);
                updateState(State.ERROR, "Speech recognition error.");
            }
        } else {
            StartupLogger.logWarning("VOX-01", "SpeechRecognizer is not available on this device.");
            Log.e(TAG, "SpeechRecognizer is not available on this device.");
            updateState(State.ERROR, "Speech recognition not available on device.");
            if (callback != null) {
                callback.onError(-1, "Speech recognition engine not available.");
            }
        }
    }

    private void destroyRecognizer() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception e) {
                Log.w(TAG, "Error destroying recognizer: " + e.getMessage());
            }
            speechRecognizer = null;
        }
    }

    /**
     * Start continuous listening session.
     */
    public void startListening() {
        mainHandler.post(() -> {
            isManuallyPaused = false;
            startListeningInternal();
        });
    }

    private void startListeningInternal() {
        if (isSpeaking) {
            Log.d(TAG, "Cannot start listening: Jarvis is currently speaking.");
            return;
        }

        if (isManuallyPaused) {
            Log.d(TAG, "Listening is manually paused.");
            return;
        }

        try {
            if (speechRecognizer == null) {
                createRecognizer();
            }

            if (speechRecognizer != null) {
                speechRecognizer.startListening(recognizerIntent);
                updateState(State.LISTENING, "Listening...");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start listening: " + e.getMessage());
            scheduleRestart(800);
        }
    }

    /**
     * Stop listening manually.
     */
    public void stopListening() {
        mainHandler.post(() -> {
            isManuallyPaused = true;
            if (speechRecognizer != null) {
                try {
                    speechRecognizer.stopListening();
                } catch (Exception ignored) {}
            }
            updateState(State.IDLE, "Standby");
        });
    }

    /**
     * Toggle continuous listening mode on/off.
     */
    public void setContinuousMode(boolean enabled) {
        this.isContinuousModeActive = enabled;
        settings.setContinuousListening(enabled);
        if (enabled) {
            isManuallyPaused = false;
            startListening();
        } else {
            stopListening();
        }
    }

    public boolean isContinuousMode() {
        return isContinuousModeActive;
    }

    /**
     * Notify VoiceManager that TTS has started speaking.
     * SpeechRecognizer is paused so Jarvis does not hear its own voice.
     */
    public void onTtsStarted() {
        mainHandler.post(() -> {
            isSpeaking = true;
            updateState(State.SPEAKING, "Jarvis is speaking...");
            if (speechRecognizer != null) {
                try {
                    speechRecognizer.cancel();
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Notify VoiceManager that TTS has finished speaking / task has completed.
     * Enforces silence cooldown before resuming continuous listening!
     */
    public void onTtsFinished() {
        long cooldownMs = (settings != null) ? (settings.getPostTaskCooldownSeconds() * 1000L) : WakeWordDetector.DEFAULT_COOLDOWN_MILLIS;
        onTaskCompleted(cooldownMs);
    }

    public void onTaskCompleted(long cooldownMillis) {
        mainHandler.post(() -> {
            isSpeaking = false;
            consecutiveErrorCount = 0;
            wakeWordDetector.markTaskCompleted();
            if (isContinuousModeActive && !isManuallyPaused) {
                updateState(State.IDLE, "Cooldown // Waiting for silence");
                scheduleRestart(cooldownMillis);
            } else {
                updateState(State.IDLE, "Standby // Say 'Jarvis'");
            }
        });
    }

    private void scheduleRestart(long delayMillis) {
        mainHandler.removeCallbacks(restartRunnable);
        if (isContinuousModeActive && !isManuallyPaused && !isSpeaking) {
            mainHandler.postDelayed(restartRunnable, delayMillis);
        }
    }

    private final Runnable restartRunnable = new Runnable() {
        @Override
        public void run() {
            if (isContinuousModeActive && !isManuallyPaused && !isSpeaking) {
                Log.d(TAG, "Auto-restarting SpeechRecognizer for continuous listening loop.");
                startListeningInternal();
            }
        }
    };

    private void updateState(State state, String message) {
        this.currentState = state;
        if (callback != null) {
            callback.onStateChanged(state, message);
        }
    }

    public State getCurrentState() {
        return currentState;
    }

    public void destroy() {
        mainHandler.removeCallbacks(restartRunnable);
        isManuallyPaused = true;
        destroyRecognizer();
    }

    // RecognitionListener implementation
    private class InnerRecognitionListener implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {
            consecutiveErrorCount = 0;
            updateState(State.LISTENING, "Listening... speak now");
        }

        @Override
        public void onBeginningOfSpeech() {
            Log.d(TAG, "User speech started.");
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            if (callback != null && currentState == State.LISTENING) {
                callback.onRmsLevel(rmsdB);
            }
        }

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            Log.d(TAG, "User speech ended, waiting for recognition results.");
            updateState(State.PROCESSING, "Analyzing speech...");
        }

        @Override
        public void onError(int error) {
            String errorMsg = getErrorText(error);
            Log.w(TAG, "SpeechRecognizer onError: " + error + " (" + errorMsg + ")");

            consecutiveErrorCount++;

            // Normal silence / timeout when nobody spoke
            boolean isNormalTimeout = (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT);

            if (isNormalTimeout) {
                consecutiveErrorCount = 0;
                if (isContinuousModeActive && !isManuallyPaused && !isSpeaking) {
                    scheduleRestart(300);
                    return;
                }
            }

            // Client or busy error -> recreate instance
            if (error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                createRecognizer();
                scheduleRestart(500);
                return;
            }

            if (callback != null) {
                callback.onError(error, errorMsg);
            }

            // If continuous, back off slightly and restart
            if (isContinuousModeActive && !isManuallyPaused && !isSpeaking) {
                long backoff = Math.min(consecutiveErrorCount * 400L, 2500L);
                scheduleRestart(backoff);
            } else {
                updateState(State.IDLE, "Standby");
            }
        }

        @Override
        public void onResults(Bundle results) {
            consecutiveErrorCount = 0;
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                String spokenText = matches.get(0).trim();
                Log.i(TAG, "Recognized Speech: " + spokenText);

                // Wake-word and post-task cooldown checking
                WakeWordDetector.EvaluationResult eval = wakeWordDetector.evaluateSpeech(spokenText);
                if (settings.isWakeWordRequired() && eval.shouldRemainQuiet()) {
                    Log.d(TAG, "Speech rejected (" + eval.action + "): remaining quiet. Spoken: " + spokenText);
                    updateState(State.IDLE, "Standby // Say 'Jarvis'");
                    if (isContinuousModeActive && !isManuallyPaused && !isSpeaking) {
                        scheduleRestart(250);
                    }
                    return;
                }

                String textToProcess = (eval.action == WakeWordDetector.EvaluationResult.Action.ACCEPTED_COMMAND && eval.command != null && !eval.command.isEmpty()) ?
                        eval.command : spokenText;

                updateState(State.PROCESSING, "Recognized: " + spokenText);
                if (callback != null) {
                    callback.onFinalSpeech(textToProcess);
                }
            } else {
                if (isContinuousModeActive && !isManuallyPaused && !isSpeaking) {
                    scheduleRestart(250);
                }
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (partial != null && !partial.isEmpty()) {
                String text = partial.get(0).trim();
                if (callback != null) {
                    callback.onPartialSpeech(text);
                }
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {}
    }

    private static String getErrorText(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "No speech match detected";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Speech recognizer busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech input";
            default:
                return "Recognition error (" + errorCode + ")";
        }
    }
}
