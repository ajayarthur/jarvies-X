package com.jarvis.arthur.voice;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import com.jarvis.arthur.SettingsManager;
import com.jarvis.arthur.util.StartupLogger;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Manages Text-to-Speech synthesis for Jarvis Arthur.
 * Configured specifically for a gentle, confident, professional adult male voice
 * with a natural American English accent, immediate streaming sentence playback,
 * and conversational deduplication.
 */
public class TtsManager implements TextToSpeech.OnInitListener {
    private static final String TAG = "TtsManager";

    public interface TtsCallback {
        void onTtsStarted(String utteranceId);
        void onTtsFinished(String utteranceId);
        void onTtsError(String utteranceId, String error);
    }

    private final Context context;
    private final SettingsManager settings;
    private final Handler mainHandler;
    private TextToSpeech tts;
    private boolean isInitialized = false;
    private volatile boolean isSpeaking = false;
    private volatile boolean isStreamingSession = false;
    private TtsCallback callback;
    private Voice currentVoice = null;

    // Utterance tracking & deduplication to ensure we never speak the same response twice
    private String lastSpokenUtterance = "";
    private long lastSpokenTimestamp = 0;
    private final Set<String> sessionSpokenChunks = new HashSet<>();

    public TtsManager(Context context, SettingsManager settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.tts = new TextToSpeech(this.context, this);
    }

    public void setCallback(TtsCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onInit(int status) {
        StartupLogger.logStep("TTS-01", "TextToSpeech onInit received status: " + (status == TextToSpeech.SUCCESS ? "SUCCESS" : "ERROR (" + status + ")"));
        if (status == TextToSpeech.SUCCESS) {
            configureMaleVoice();

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    isSpeaking = true;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onTtsStarted(utteranceId);
                    });
                }

                @Override
                public void onDone(String utteranceId) {
                    // Check after a brief delay if TTS is still speaking queued utterances
                    mainHandler.postDelayed(() -> {
                        if (tts != null && !tts.isSpeaking()) {
                            isSpeaking = false;
                            isStreamingSession = false;
                            if (callback != null) callback.onTtsFinished(utteranceId);
                        }
                    }, 80);
                }

                @Override
                public void onError(String utteranceId) {
                    mainHandler.postDelayed(() -> {
                        if (tts != null && !tts.isSpeaking()) {
                            isSpeaking = false;
                            isStreamingSession = false;
                            if (callback != null) callback.onTtsError(utteranceId, "TTS Error");
                        }
                    }, 80);
                }
            });

            isInitialized = true;
            Log.i(TAG, "TextToSpeech successfully initialized with gentle, confident adult American male profile.");
        } else {
            Log.e(TAG, "Failed to initialize TextToSpeech.");
            isInitialized = false;
        }
    }

    /**
     * Configures a gentle, confident, professional adult male voice
     * with a natural American English accent.
     */
    private void configureMaleVoice() {
        if (tts == null) return;

        // Requirement: Natural American English accent
        Locale preferredLocale = Locale.US;
        int result = tts.setLanguage(preferredLocale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            preferredLocale = Locale.getDefault();
            tts.setLanguage(preferredLocale);
        }

        try {
            Set<Voice> voices = tts.getVoices();
            if (voices != null && !voices.isEmpty()) {
                Voice bestVoice = null;
                int bestScore = -1;

                for (Voice v : voices) {
                    Locale loc = v.getLocale();
                    if (loc == null || !"en".equalsIgnoreCase(loc.getLanguage())) {
                        continue;
                    }

                    String name = v.getName().toLowerCase(Locale.ROOT);

                    // Strictly filter out female / woman voices
                    if (name.contains("female") || name.contains("-fem") || name.contains("#female") ||
                            name.contains("woman") || name.contains("_fem") || name.contains("-f-") ||
                            name.contains("f00") || name.contains("f01") || name.contains("f02") ||
                            name.contains("f03") || name.contains("f04")) {
                        continue;
                    }
                    if (v.getFeatures() != null && (v.getFeatures().contains("female") || v.getFeatures().contains("gender=female"))) {
                        continue;
                    }

                    int score = 0;

                    // American English accent
                    if ("US".equalsIgnoreCase(loc.getCountry())) {
                        score += 60;
                    } else if ("GB".equalsIgnoreCase(loc.getCountry())) {
                        score += 20;
                    }

                    // Male indicators
                    if (name.contains("male") || name.contains("#male") || name.contains("-male") || name.contains("man")) {
                        score += 50;
                    }
                    if (v.getFeatures() != null && (v.getFeatures().contains("male") || v.getFeatures().contains("gender=male"))) {
                        score += 50;
                    }

                    // High-fidelity natural adult male Google US TTS neural voice models
                    if (name.contains("sfg") || name.contains("tpd") || name.contains("iob") ||
                            name.contains("iol") || name.contains("iom") || name.contains("rjs")) {
                        score += 60;
                    }

                    // Prefer network-based high fidelity neural models over local robotic synthesizers
                    if (name.contains("network")) {
                        score += 30;
                    }

                    // Audio fidelity & quality
                    if (v.getQuality() == Voice.QUALITY_VERY_HIGH) {
                        score += 40;
                    } else if (v.getQuality() == Voice.QUALITY_HIGH) {
                        score += 25;
                    }

                    // Low latency for fast responses
                    if (v.getLatency() == Voice.LATENCY_VERY_LOW) {
                        score += 20;
                    } else if (v.getLatency() == Voice.LATENCY_LOW) {
                        score += 10;
                    }

                    if (score > bestScore) {
                        bestScore = score;
                        bestVoice = v;
                    }
                }

                if (bestVoice != null) {
                    currentVoice = bestVoice;
                    tts.setVoice(bestVoice);
                    Log.i(TAG, "Selected natural adult American male voice: " + bestVoice.getName() + " (Score=" + bestScore + ")");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Voice discovery notice: " + e.getMessage());
        }

        applyVoiceParameters();
    }

    private void applyVoiceParameters() {
        if (tts == null) return;
        // Gentle, confident, professional adult male tone & moderate conversational speed:
        // Pitch: 0.98f (warm, gentle, natural adult male frequency - not robotic or high pitched)
        // Rate: 1.02f (clean, moderate conversational pacing with natural pauses)
        tts.setPitch(0.98f);
        tts.setSpeechRate(1.02f);
        if (currentVoice != null) {
            try {
                tts.setVoice(currentVoice);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Speaks the first complete sentence of a streaming response immediately.
     * Uses QUEUE_FLUSH to begin audio output without waiting for the full response.
     */
    public synchronized void speakFirstChunk(String sentence) {
        if (!settings.isAudioFeedbackEnabled() || sentence == null) return;
        String clean = sanitizeForSpeech(sentence);
        if (clean.isEmpty()) return;

        // Deduplication: prevent speaking identical response consecutively
        long now = System.currentTimeMillis();
        if (clean.equalsIgnoreCase(lastSpokenUtterance) && (now - lastSpokenTimestamp < 6000)) {
            Log.d(TAG, "Skipping duplicate utterance: " + clean);
            return;
        }

        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not ready for first chunk.");
            return;
        }

        stop(); // abort any prior audio
        isStreamingSession = true;
        isSpeaking = true;
        sessionSpokenChunks.clear();
        sessionSpokenChunks.add(clean.toLowerCase(Locale.ROOT));
        lastSpokenUtterance = clean;
        lastSpokenTimestamp = now;

        applyVoiceParameters();

        String utteranceId = "jarvis_s1_" + UUID.randomUUID().toString();
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        Log.d(TAG, "Streaming TTS started with first chunk: " + clean);
    }

    /**
     * Queues subsequent sentences seamlessly while first chunk is already being spoken.
     * Uses QUEUE_ADD to chain sentences smoothly without gaps or duplicate speech.
     */
    public synchronized void speakNextChunk(String sentence) {
        if (!settings.isAudioFeedbackEnabled() || sentence == null) return;
        if (!isStreamingSession || tts == null || !isInitialized) return;

        String clean = sanitizeForSpeech(sentence);
        if (clean.isEmpty()) return;

        String norm = clean.toLowerCase(Locale.ROOT);
        if (sessionSpokenChunks.contains(norm)) {
            return; // Prevent repeating same sentence in this session
        }
        sessionSpokenChunks.add(norm);

        String utteranceId = "jarvis_sn_" + UUID.randomUUID().toString();
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

        tts.speak(clean, TextToSpeech.QUEUE_ADD, params, utteranceId);
        Log.d(TAG, "Streaming TTS queued subsequent chunk: " + clean);
    }

    /**
     * Completes the streaming feed. Utterances continue playing until finished.
     */
    public synchronized void endStreamingSession() {
        Log.d(TAG, "Streaming session input completed.");
    }

    /**
     * Standard non-streaming speak method (retained for backward compatibility and commands).
     */
    public synchronized void speak(String text) {
        if (!settings.isAudioFeedbackEnabled()) {
            if (callback != null) callback.onTtsFinished("disabled");
            return;
        }

        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not ready yet, queuing text.");
            if (callback != null) callback.onTtsFinished("not_initialized");
            return;
        }

        String spokenText = sanitizeForSpeech(text);
        if (spokenText.isEmpty()) {
            if (callback != null) callback.onTtsFinished("empty");
            return;
        }

        long now = System.currentTimeMillis();
        if (spokenText.equalsIgnoreCase(lastSpokenUtterance) && (now - lastSpokenTimestamp < 6000)) {
            Log.d(TAG, "Skipping duplicate utterance: " + spokenText);
            if (callback != null) callback.onTtsFinished("duplicate");
            return;
        }

        stop();
        isStreamingSession = false;
        lastSpokenUtterance = spokenText;
        lastSpokenTimestamp = now;

        applyVoiceParameters();

        String utteranceId = "jarvis_" + UUID.randomUUID().toString();
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

        tts.speak(spokenText, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
    }

    /**
     * Stops playback immediately (user barge-in / interruption).
     */
    public synchronized void stop() {
        isStreamingSession = false;
        if (tts != null && isSpeaking) {
            tts.stop();
        }
        isSpeaking = false;
    }

    public boolean isSpeaking() {
        return isSpeaking;
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        isInitialized = false;
        isSpeaking = false;
        isStreamingSession = false;
    }

    /**
     * Cleans up markdown, links, and code blocks, and polishes cadence
     * for a gentle, confident adult American male voice.
     */
    public static String sanitizeForSpeech(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("(?s)```.*?```", "code omitted.")
                .replaceAll("`.*?`", "")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
                .replaceAll("https?://\\S+", "link provided on screen")
                .replaceAll("[*#_~|\\[\\]]", "")
                .replaceAll("(?i)\\b(\\d+)\\s*°C\\b", "$1 degrees Celsius")
                .replaceAll("&", " and ")
                .replaceAll("%", " percent ")
                .replaceAll("\\+", " plus ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
