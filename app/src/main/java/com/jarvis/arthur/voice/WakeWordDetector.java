package com.jarvis.arthur.voice;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dedicated Wake Word Detection and Post-Task Silence Cooldown Engine for Jarvis.
 *
 * Requirements:
 * 1. Strict Wake Word Gating:
 *    Ensures Jarvis strictly activates and responds ONLY to speech containing the designated wake word
 *    (e.g., 'Jarvis', 'Hey Jarvis', 'OK Jarvis', 'Hello Jarvis').
 *    Completely suppresses and ignores random background speech, ambient conversations, or non-wake utterances.
 *
 * 2. Post-Task Silence Cooldown:
 *    After Jarvis completes a task or finishes speaking, enforces a mandatory silence/cooldown period
 *    (default 3 seconds) before reactivating. Rejects speech during this cooldown window to prevent
 *    acoustic echo, self-triggering, or conversational overlap.
 *
 * 3. Command Extraction:
 *    Extracts the user's intent after or before the wake word, stripping wake tokens,
 *    leading/trailing punctuation, and formatting cleanly for intent routing.
 */
public class WakeWordDetector {

    public static final String DEFAULT_WAKE_WORD = "jarvis";
    public static final long DEFAULT_COOLDOWN_MILLIS = 3000L; // 3 seconds of required silence

    // Regex matching "jarvis" as an isolated word or preceded by conversational prefixes (hey, ok, okay, hello, hi, yo, dear)
    private static final Pattern WAKE_WORD_PATTERN = Pattern.compile(
            "\\b(?:hey|ok|okay|hi|hello|yo|dear|mr|mister)?\\s*jarvis\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static volatile WakeWordDetector instance;

    public static WakeWordDetector getInstance() {
        if (instance == null) {
            synchronized (WakeWordDetector.class) {
                if (instance == null) {
                    instance = new WakeWordDetector();
                }
            }
        }
        return instance;
    }

    public static WakeWordDetector getDefault() {
        return getInstance();
    }

    /**
     * Evaluation result representing the decision on a recognized speech utterance.
     */
    public static class EvaluationResult {
        public enum Action {
            ACCEPTED_COMMAND,      // Contains wake word + command to execute
            ACCEPTED_WAKE_ONLY,    // Contains wake word alone (e.g. "Jarvis" -> prompts "Yes, sir?")
            REJECTED_COOLDOWN,     // Rejected: Still in post-task silence cooldown
            REJECTED_NO_WAKE_WORD  // Rejected: Background speech without wake word -> remain completely quiet
        }

        public final Action action;
        public final String command;
        public final String rawSpeech;
        public final long remainingCooldownMillis;

        public EvaluationResult(Action action, String command, String rawSpeech, long remainingCooldownMillis) {
            this.action = action;
            this.command = command;
            this.rawSpeech = rawSpeech;
            this.remainingCooldownMillis = remainingCooldownMillis;
        }

        public boolean isAccepted() {
            return action == Action.ACCEPTED_COMMAND || action == Action.ACCEPTED_WAKE_ONLY;
        }

        public boolean shouldRemainQuiet() {
            return action == Action.REJECTED_COOLDOWN || action == Action.REJECTED_NO_WAKE_WORD;
        }

        @Override
        public String toString() {
            return "EvaluationResult{" +
                    "action=" + action +
                    ", command='" + command + '\'' +
                    ", remainingCooldownMillis=" + remainingCooldownMillis +
                    '}';
        }
    }

    private volatile long taskCompletedTimestamp = 0L;
    private volatile long cooldownDurationMillis = DEFAULT_COOLDOWN_MILLIS;

    public WakeWordDetector() {
        this(DEFAULT_COOLDOWN_MILLIS);
    }

    public WakeWordDetector(long cooldownDurationMillis) {
        this.cooldownDurationMillis = cooldownDurationMillis;
        this.taskCompletedTimestamp = 0L;
    }

    /**
     * Checks if the utterance contains the wake word 'Jarvis' as a distinct token.
     */
    public boolean hasWakeWord(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        return WAKE_WORD_PATTERN.matcher(text).find();
    }

    /**
     * Extracts the specific command following or surrounding the wake word.
     * Returns empty string "" if only the wake word was spoken.
     * Returns null if no wake word was present in the text.
     */
    public String extractCommand(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;

        Matcher m = WAKE_WORD_PATTERN.matcher(trimmed);
        if (!m.find()) {
            return null; // Wake word not present
        }

        // Strip matched wake word pattern
        String withoutWake = trimmed.substring(0, m.start()) + " " + trimmed.substring(m.end());

        // Check if remaining text is solely punctuation/whitespace (wake word only)
        if (withoutWake.matches("^[\\s,.:;!?\\-]+$") || withoutWake.trim().isEmpty()) {
            return "";
        }

        // Clean leading and trailing delimiters (commas, colons, hyphens), preserving sentence punctuation like '?'
        withoutWake = withoutWake.replaceAll("^[\\s,.:;\\-]+", "").replaceAll("[\\s,.:;\\-]+$", "").trim();
        withoutWake = withoutWake.replaceAll("\\s+", " ");

        return withoutWake;
    }

    /**
     * Evaluates speech against post-task cooldown silence and strict wake word presence.
     */
    public EvaluationResult evaluateSpeech(String rawSpeech) {
        if (rawSpeech == null || rawSpeech.trim().isEmpty()) {
            return new EvaluationResult(EvaluationResult.Action.REJECTED_NO_WAKE_WORD, null, rawSpeech, 0L);
        }

        // 1. Post-Task Silence Cooldown Verification
        if (isInCooldown()) {
            return new EvaluationResult(
                    EvaluationResult.Action.REJECTED_COOLDOWN,
                    null,
                    rawSpeech,
                    getRemainingCooldownMillis()
            );
        }

        // 2. Strict Wake Word Gating: ignore background speech/conversation without "Jarvis"
        if (!hasWakeWord(rawSpeech)) {
            return new EvaluationResult(
                    EvaluationResult.Action.REJECTED_NO_WAKE_WORD,
                    null,
                    rawSpeech,
                    0L
            );
        }

        // 3. Command Extraction
        String cmd = extractCommand(rawSpeech);
        if (cmd == null || cmd.isEmpty()) {
            return new EvaluationResult(
                    EvaluationResult.Action.ACCEPTED_WAKE_ONLY,
                    "",
                    rawSpeech,
                    0L
            );
        } else {
            return new EvaluationResult(
                    EvaluationResult.Action.ACCEPTED_COMMAND,
                    cmd,
                    rawSpeech,
                    0L
            );
        }
    }

    /**
     * Marks that a task or spoken response has finished, starting the mandatory silence cooldown.
     */
    public void markTaskCompleted() {
        this.taskCompletedTimestamp = System.currentTimeMillis();
    }

    /**
     * Indicates whether the assistant is currently in the post-task silence cooldown.
     */
    public boolean isInCooldown() {
        if (cooldownDurationMillis <= 0 || taskCompletedTimestamp <= 0) return false;
        long elapsed = System.currentTimeMillis() - taskCompletedTimestamp;
        return elapsed < cooldownDurationMillis;
    }

    /**
     * Returns the remaining cooldown milliseconds, or 0 if silence window has elapsed.
     */
    public long getRemainingCooldownMillis() {
        if (cooldownDurationMillis <= 0 || taskCompletedTimestamp <= 0) return 0L;
        long elapsed = System.currentTimeMillis() - taskCompletedTimestamp;
        long remaining = cooldownDurationMillis - elapsed;
        return Math.max(0L, remaining);
    }

    /**
     * Manually resets/cancels the cooldown window.
     */
    public void resetCooldown() {
        this.taskCompletedTimestamp = 0L;
    }

    public long getCooldownDurationMillis() {
        return cooldownDurationMillis;
    }

    public void setCooldownDurationMillis(long millis) {
        this.cooldownDurationMillis = Math.max(0L, millis);
    }
}
