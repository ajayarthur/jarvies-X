package com.jarvis.arthur;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings and configuration storage for Jarvis Arthur.
 * Provides safe defaults and secure preferences storage.
 */
public class SettingsManager {
    private static final String PREF_NAME = "jarvis_arthur_prefs";
    private static final String KEY_GEMINI_API_KEY = "gemini_api_key";
    private static final String KEY_GROQ_API_KEY = "groq_api_key";
    private static final String KEY_LLM_PROVIDER = "llm_provider";
    private static final String KEY_CONTINUOUS_LISTENING = "continuous_listening";
    private static final String KEY_WAKE_WORD_REQUIRED = "wake_word_required";
    private static final String KEY_AUDIO_FEEDBACK = "audio_feedback";
    private static final String KEY_SPEECH_RATE = "speech_rate";
    private static final String KEY_SPEECH_PITCH = "speech_pitch";
    private static final String KEY_BACKGROUND_SERVICE_ENABLED = "background_service_enabled";
    private static final String KEY_SILENCE_TIMEOUT_SECONDS = "silence_timeout_seconds";
    private static final String KEY_POST_TASK_COOLDOWN_SECONDS = "post_task_cooldown_seconds";
    private static final String KEY_HAS_OPENED_ONCE = "has_opened_once";

    public static final int DEFAULT_POST_TASK_COOLDOWN_SECONDS = 3;

    // Default Gemini API key placeholder (configure via Settings dialog in-app)
    public static final String DEFAULT_GEMINI_KEY = "";

    private final SharedPreferences prefs;
    private static final java.util.Map<String, Object> inMemoryFallback = new java.util.concurrent.ConcurrentHashMap<>();

    public SettingsManager(Context context) {
        this.prefs = (context != null) ? context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) : null;
    }

    public String getGeminiApiKey() {
        if (prefs == null) return DEFAULT_GEMINI_KEY;
        String key = prefs.getString(KEY_GEMINI_API_KEY, DEFAULT_GEMINI_KEY);
        if (key == null || key.trim().isEmpty()) {
            return DEFAULT_GEMINI_KEY;
        }
        return key;
    }

    public void setGeminiApiKey(String key) {
        if (prefs != null) {
            prefs.edit().putString(KEY_GEMINI_API_KEY, key != null ? key.trim() : "").apply();
        }
    }

    public String getGroqApiKey() {
        if (prefs == null) return "";
        return prefs.getString(KEY_GROQ_API_KEY, "");
    }

    public void setGroqApiKey(String key) {
        if (prefs != null) {
            prefs.edit().putString(KEY_GROQ_API_KEY, key != null ? key.trim() : "").apply();
        }
    }

    public String getLlmProvider() {
        if (prefs == null) return "auto";
        return prefs.getString(KEY_LLM_PROVIDER, "auto"); // "auto", "gemini", "groq", "wikipedia"
    }

    public void setLlmProvider(String provider) {
        if (prefs != null) {
            prefs.edit().putString(KEY_LLM_PROVIDER, provider).apply();
        }
    }

    public boolean isContinuousListening() {
        if (prefs == null) return true;
        return prefs.getBoolean(KEY_CONTINUOUS_LISTENING, true);
    }

    public void setContinuousListening(boolean enabled) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_CONTINUOUS_LISTENING, enabled).apply();
        }
    }

    public boolean isWakeWordRequired() {
        if (prefs == null) return true;
        return prefs.getBoolean(KEY_WAKE_WORD_REQUIRED, true); // Strict wake-word gating enabled by default
    }

    public void setWakeWordRequired(boolean required) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_WAKE_WORD_REQUIRED, required).apply();
        }
    }

    public int getPostTaskCooldownSeconds() {
        if (prefs == null) return DEFAULT_POST_TASK_COOLDOWN_SECONDS;
        return prefs.getInt(KEY_POST_TASK_COOLDOWN_SECONDS, DEFAULT_POST_TASK_COOLDOWN_SECONDS);
    }

    public void setPostTaskCooldownSeconds(int seconds) {
        if (prefs != null) {
            prefs.edit().putInt(KEY_POST_TASK_COOLDOWN_SECONDS, Math.max(1, seconds)).apply();
        }
    }

    public boolean isAudioFeedbackEnabled() {
        if (prefs == null) return true;
        return prefs.getBoolean(KEY_AUDIO_FEEDBACK, true);
    }

    public void setAudioFeedbackEnabled(boolean enabled) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_AUDIO_FEEDBACK, enabled).apply();
        }
    }

    public float getSpeechRate() {
        if (prefs == null) return 1.02f; // Clean, natural conversational pacing
        return prefs.getFloat(KEY_SPEECH_RATE, 1.02f);
    }

    public void setSpeechRate(float rate) {
        if (prefs != null) {
            prefs.edit().putFloat(KEY_SPEECH_RATE, rate).apply();
        }
    }

    public float getSpeechPitch() {
        if (prefs == null) return 0.98f; // Gentle, confident adult male tone
        return prefs.getFloat(KEY_SPEECH_PITCH, 0.98f);
    }

    public void setSpeechPitch(float pitch) {
        if (prefs != null) {
            prefs.edit().putFloat(KEY_SPEECH_PITCH, pitch).apply();
        }
    }

    public boolean isBackgroundServiceEnabled() {
        if (prefs == null) {
            Object v = inMemoryFallback.get(KEY_BACKGROUND_SERVICE_ENABLED);
            return v instanceof Boolean ? (Boolean) v : true;
        }
        return prefs.getBoolean(KEY_BACKGROUND_SERVICE_ENABLED, true);
    }

    public void setBackgroundServiceEnabled(boolean enabled) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_BACKGROUND_SERVICE_ENABLED, enabled).apply();
        } else {
            inMemoryFallback.put(KEY_BACKGROUND_SERVICE_ENABLED, enabled);
        }
    }

    public boolean hasOpenedOnce() {
        if (prefs == null) {
            Object v = inMemoryFallback.get(KEY_HAS_OPENED_ONCE);
            return v instanceof Boolean ? (Boolean) v : false;
        }
        return prefs.getBoolean(KEY_HAS_OPENED_ONCE, false);
    }

    public void setHasOpenedOnce(boolean opened) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_HAS_OPENED_ONCE, opened).apply();
        } else {
            inMemoryFallback.put(KEY_HAS_OPENED_ONCE, opened);
        }
    }

    public int getSilenceTimeoutSeconds() {
        if (prefs == null) return 10;
        return prefs.getInt(KEY_SILENCE_TIMEOUT_SECONDS, 10);
    }

    public void setSilenceTimeoutSeconds(int seconds) {
        if (prefs != null) {
            prefs.edit().putInt(KEY_SILENCE_TIMEOUT_SECONDS, seconds).apply();
        }
    }

    // Hardware Permission Confirmation Storage
    private static final String PREF_HW_PERM_PREFIX = "hw_perm_confirmed_";
    private static final String KEY_UNIFIED_PERMISSIONS_CONFIRMED = "unified_permissions_confirmed";
    private static final String KEY_ACCESSIBILITY_PERMISSION_GRANTED = "accessibility_permission_granted";
    private static final String KEY_DEVICE_ADMIN_ENABLED = "device_admin_enabled";

    public boolean isDeviceAdminEnabled() {
        if (prefs == null) {
            Object v = inMemoryFallback.get(KEY_DEVICE_ADMIN_ENABLED);
            return v instanceof Boolean ? (Boolean) v : false;
        }
        return prefs.getBoolean(KEY_DEVICE_ADMIN_ENABLED, false);
    }

    public void setDeviceAdminEnabled(boolean enabled) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_DEVICE_ADMIN_ENABLED, enabled).apply();
        } else {
            inMemoryFallback.put(KEY_DEVICE_ADMIN_ENABLED, enabled);
        }
        if (enabled) {
            setUnifiedPermissionsConfirmed(true);
        }
    }

    public boolean isUnifiedPermissionsConfirmed() {
        if (prefs == null) {
            Object v = inMemoryFallback.get(KEY_UNIFIED_PERMISSIONS_CONFIRMED);
            if (v instanceof Boolean && (Boolean) v) return true;
            Object admin = inMemoryFallback.get(KEY_DEVICE_ADMIN_ENABLED);
            return admin instanceof Boolean ? (Boolean) admin : false;
        }
        return prefs.getBoolean(KEY_UNIFIED_PERMISSIONS_CONFIRMED, false) ||
               prefs.getBoolean(KEY_DEVICE_ADMIN_ENABLED, false);
    }

    public void setUnifiedPermissionsConfirmed(boolean confirmed) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_UNIFIED_PERMISSIONS_CONFIRMED, confirmed).apply();
        } else {
            inMemoryFallback.put(KEY_UNIFIED_PERMISSIONS_CONFIRMED, confirmed);
        }
    }

    public boolean isAccessibilityPermissionGranted() {
        if (prefs == null) {
            Object v = inMemoryFallback.get(KEY_ACCESSIBILITY_PERMISSION_GRANTED);
            return v instanceof Boolean ? (Boolean) v : false;
        }
        return prefs.getBoolean(KEY_ACCESSIBILITY_PERMISSION_GRANTED, false);
    }

    public void setAccessibilityPermissionGranted(boolean granted) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_ACCESSIBILITY_PERMISSION_GRANTED, granted).apply();
        } else {
            inMemoryFallback.put(KEY_ACCESSIBILITY_PERMISSION_GRANTED, granted);
        }
    }

    public boolean isHardwarePermissionConfirmed(String key) {
        if (key == null) return false;
        String fullKey = PREF_HW_PERM_PREFIX + key.toLowerCase(java.util.Locale.ROOT).trim();
        if (prefs != null) {
            if (prefs.contains(fullKey)) {
                return prefs.getBoolean(fullKey, false);
            }
            return prefs.getBoolean(KEY_DEVICE_ADMIN_ENABLED, false) ||
                   prefs.getBoolean(KEY_UNIFIED_PERMISSIONS_CONFIRMED, false) ||
                   prefs.getBoolean(KEY_ACCESSIBILITY_PERMISSION_GRANTED, false);
        }
        if (inMemoryFallback.containsKey(fullKey)) {
            Object val = inMemoryFallback.get(fullKey);
            return val instanceof Boolean && (Boolean) val;
        }
        Object admin = inMemoryFallback.get(KEY_DEVICE_ADMIN_ENABLED);
        if (admin instanceof Boolean && (Boolean) admin) return true;
        Object unified = inMemoryFallback.get(KEY_UNIFIED_PERMISSIONS_CONFIRMED);
        if (unified instanceof Boolean && (Boolean) unified) return true;
        Object access = inMemoryFallback.get(KEY_ACCESSIBILITY_PERMISSION_GRANTED);
        return access instanceof Boolean && (Boolean) access;
    }

    public void setHardwarePermissionConfirmed(String key, boolean confirmed) {
        if (key == null) return;
        String fullKey = PREF_HW_PERM_PREFIX + key.toLowerCase(java.util.Locale.ROOT).trim();
        if (prefs != null) {
            prefs.edit().putBoolean(fullKey, confirmed).apply();
        } else {
            inMemoryFallback.put(fullKey, confirmed);
        }
    }

    public void resetAllHardwarePermissionConfirmations() {
        setUnifiedPermissionsConfirmed(false);
        setAccessibilityPermissionGranted(false);
        setDeviceAdminEnabled(false);
        if (prefs != null) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove(KEY_UNIFIED_PERMISSIONS_CONFIRMED);
            editor.remove(KEY_ACCESSIBILITY_PERMISSION_GRANTED);
            editor.remove(KEY_DEVICE_ADMIN_ENABLED);
            for (String k : prefs.getAll().keySet()) {
                if (k.startsWith(PREF_HW_PERM_PREFIX)) {
                    editor.remove(k);
                }
            }
            editor.apply();
        } else {
            inMemoryFallback.remove(KEY_UNIFIED_PERMISSIONS_CONFIRMED);
            inMemoryFallback.remove(KEY_ACCESSIBILITY_PERMISSION_GRANTED);
            inMemoryFallback.remove(KEY_DEVICE_ADMIN_ENABLED);
            inMemoryFallback.keySet().removeIf(k -> k.startsWith(PREF_HW_PERM_PREFIX));
        }
    }
}
