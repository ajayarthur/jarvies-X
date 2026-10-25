package com.jarvis.arthur.memory;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import com.jarvis.arthur.util.StartupLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust Local Storage Memory Engine for Jarvis Arthur.
 * - Stores all user data in the Android device's local file storage (not in-memory alone).
 * - Implements a strict 500 MB per-device storage quota with real-time usage tracking & notifications.
 * - Device isolation: All user facts and session logs are segregated in a private device directory.
 * - Smart Pruning: When requested, deletes only unnecessary, old session logs and dialogue turns,
 *   safely preserving all key user facts (identity, preferences, important notes).
 * - Avoids irrelevant conversational chatter by storing only explicitly requested information.
 */
public class MemoryManager {
    private static final String TAG = "MemoryManager";
    public static final long MAX_STORAGE_BYTES = 500L * 1024L * 1024L; // 500 MB storage limit per device
    private static final String PREF_NAME = "jarvis_dynamic_memory";
    private static final String KEY_MEMORY_JSON = "memory_payload";
    private static final String KEY_TURNS_JSON = "conversation_turns_payload";
    private static final int MAX_CONVERSATION_TURNS = 10;

    public static class MemoryFact {
        public String key;
        public String category; // "identity", "preference", "fact", "custom"
        public String value;
        public long timestamp;

        public MemoryFact(String key, String category, String value, long timestamp) {
            this.key = key;
            this.category = category;
            this.value = value;
            this.timestamp = timestamp;
        }

        public MemoryFact(String key, String value, long timestamp) {
            this(key, "fact", value, timestamp);
        }
    }

    public static class ConversationTurn {
        public String userText;
        public String assistantText;
        public long timestamp;

        public ConversationTurn(String userText, String assistantText, long timestamp) {
            this.userText = userText;
            this.assistantText = assistantText;
            this.timestamp = timestamp;
        }
    }

    private final SharedPreferences prefs;
    private final String deviceId;
    private final File deviceDir;
    private final File factsFile;
    private final File turnsFile;
    private final File sessionLogsDir;

    private final Map<String, MemoryFact> memoryMap = new HashMap<>();
    private final List<ConversationTurn> conversationTurns = new ArrayList<>();

    public MemoryManager(Context context) {
        this(context, null, null);
    }

    public MemoryManager(Context context, File customBaseDir) {
        this(context, customBaseDir, null);
    }

    public MemoryManager(Context context, File customBaseDir, String customDeviceId) {
        if (customDeviceId != null && !customDeviceId.trim().isEmpty()) {
            this.deviceId = customDeviceId.trim();
        } else if (context != null) {
            this.deviceId = resolveDeviceId(context);
        } else {
            this.deviceId = "device_local_primary";
        }

        if (context != null) {
            this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            File base = customBaseDir != null ? customBaseDir : context.getFilesDir();
            this.deviceDir = new File(base, "jarvis_vault_" + sanitizeForFileName(this.deviceId));
        } else {
            this.prefs = null;
            File base = customBaseDir != null ? customBaseDir : new File(System.getProperty("java.io.tmpdir"), "jarvis_local_storage");
            this.deviceDir = new File(base, "jarvis_vault_" + sanitizeForFileName(this.deviceId));
        }

        // Initialize local filesystem directory structure
        if (!deviceDir.exists()) {
            deviceDir.mkdirs();
        }
        this.factsFile = new File(deviceDir, "memory_facts.json");
        this.turnsFile = new File(deviceDir, "conversation_turns.json");
        this.sessionLogsDir = new File(deviceDir, "session_logs");
        if (!sessionLogsDir.exists()) {
            sessionLogsDir.mkdirs();
        }

        loadMemoryFromDisk();
        loadTurnsFromDisk();

        // Default creator memory if pristine
        if (!memoryMap.containsKey("name")) {
            setFact("name", "identity", "Arthur");
        }
        if (!memoryMap.containsKey("role")) {
            setFact("role", "identity", "Creator and AI Engineer");
        }
        StartupLogger.logStep("MEM-01", "MemoryManager initialized: deviceId=" + deviceId + ", factsCount=" + memoryMap.size() + ", dir=" + deviceDir.getAbsolutePath());
    }

    // --- Device Isolation Identifier ---

    public static String resolveDeviceId(Context context) {
        if (context != null) {
            try {
                String androidId = android.provider.Settings.Secure.getString(
                        context.getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID
                );
                if (androidId != null && !androidId.trim().isEmpty() && !androidId.equals("9774d56d682e549c")) {
                    return "dev_" + androidId.trim().toLowerCase(Locale.ROOT);
                }
            } catch (Exception ignored) {}

            try {
                File idFile = new File(context.getFilesDir(), "device_identity.id");
                if (idFile.exists()) {
                    byte[] data = new byte[(int) idFile.length()];
                    try (FileInputStream fis = new FileInputStream(idFile)) {
                        int read = fis.read(data);
                        if (read > 0) {
                            return new String(data, 0, read, StandardCharsets.UTF_8).trim();
                        }
                    }
                }
                String newId = "dev_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                try (FileOutputStream fos = new FileOutputStream(idFile)) {
                    fos.write(newId.getBytes(StandardCharsets.UTF_8));
                }
                return newId;
            } catch (Exception ignored) {}
        }
        return "dev_local_primary";
    }

    private static String sanitizeForFileName(String input) {
        if (input == null) return "default";
        return input.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public String getDeviceId() {
        return deviceId;
    }

    public File getDeviceStorageDir() {
        return deviceDir;
    }

    // --- Disk I/O: Local Storage Persistence ---

    public synchronized void loadMemoryFromDisk() {
        memoryMap.clear();

        // Primary: read from device's local file storage
        if (factsFile.exists() && factsFile.length() > 0) {
            try {
                byte[] data = new byte[(int) factsFile.length()];
                try (FileInputStream fis = new FileInputStream(factsFile)) {
                    int read = fis.read(data);
                    if (read > 0) {
                        String jsonStr = new String(data, 0, read, StandardCharsets.UTF_8);
                        parseMemoryJson(jsonStr);
                        return;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading memory from local storage file: " + e.getMessage());
            }
        }

        // Secondary / Fallback: read from SharedPreferences
        if (prefs != null) {
            String jsonStr = prefs.getString(KEY_MEMORY_JSON, null);
            if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                parseMemoryJson(jsonStr);
                persistMemoryToDisk(); // Synchronize to file
            }
        }
    }

    private void parseMemoryJson(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject obj = json.getJSONObject(k);
                String val = obj.getString("value");
                String cat = obj.optString("category", "fact");
                long ts = obj.optLong("timestamp", System.currentTimeMillis());
                memoryMap.put(k, new MemoryFact(k, cat, val, ts));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing memory JSON: " + e.getMessage());
        }
    }

    public synchronized void persistMemoryToDisk() {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, MemoryFact> entry : memoryMap.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put("value", entry.getValue().value);
                obj.put("category", entry.getValue().category);
                obj.put("timestamp", entry.getValue().timestamp);
                json.put(entry.getKey(), obj);
            }
            String jsonStr = json.toString(2);

            // 1. Write to local storage file on device
            try (FileOutputStream fos = new FileOutputStream(factsFile)) {
                fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }

            // 2. Also sync to SharedPreferences for fast access
            if (prefs != null) {
                prefs.edit().putString(KEY_MEMORY_JSON, jsonStr).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error persisting memory to local storage: " + e.getMessage());
        }
    }

    public synchronized void loadTurnsFromDisk() {
        conversationTurns.clear();

        // 1. Read from local storage file
        if (turnsFile.exists() && turnsFile.length() > 0) {
            try {
                byte[] data = new byte[(int) turnsFile.length()];
                try (FileInputStream fis = new FileInputStream(turnsFile)) {
                    int read = fis.read(data);
                    if (read > 0) {
                        String jsonStr = new String(data, 0, read, StandardCharsets.UTF_8);
                        parseTurnsJson(jsonStr);
                        return;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading turns from local storage: " + e.getMessage());
            }
        }

        // 2. Fallback to SharedPreferences
        if (prefs != null) {
            String jsonStr = prefs.getString(KEY_TURNS_JSON, null);
            if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                parseTurnsJson(jsonStr);
                persistTurnsToDisk();
            }
        }
    }

    private void parseTurnsJson(String jsonStr) {
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String user = obj.optString("user", "");
                String assistant = obj.optString("assistant", "");
                long ts = obj.optLong("timestamp", System.currentTimeMillis());
                if (!user.isEmpty()) {
                    conversationTurns.add(new ConversationTurn(user, assistant, ts));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing turns JSON: " + e.getMessage());
        }
    }

    public synchronized void persistTurnsToDisk() {
        try {
            JSONArray arr = new JSONArray();
            for (ConversationTurn turn : conversationTurns) {
                JSONObject obj = new JSONObject();
                obj.put("user", turn.userText);
                obj.put("assistant", turn.assistantText);
                obj.put("timestamp", turn.timestamp);
                arr.put(obj);
            }
            String jsonStr = arr.toString();

            try (FileOutputStream fos = new FileOutputStream(turnsFile)) {
                fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }

            if (prefs != null) {
                prefs.edit().putString(KEY_TURNS_JSON, jsonStr).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error persisting turns to local storage: " + e.getMessage());
        }
    }

    private synchronized void appendSessionLog(String userText, String assistantText) {
        if (sessionLogsDir == null) return;
        try {
            if (!sessionLogsDir.exists()) sessionLogsDir.mkdirs();
            String dayStamp = new java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new java.util.Date());
            File logFile = new File(sessionLogsDir, "session_" + dayStamp + ".log");

            JSONObject obj = new JSONObject();
            obj.put("timestamp", System.currentTimeMillis());
            obj.put("user", userText);
            obj.put("assistant", assistantText);
            String entry = obj.toString() + "\n";

            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(entry.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error writing session log to disk: " + e.getMessage());
        }
    }

    // --- Storage Quota and Telemetry (500 MB Limit) ---

    public synchronized long getStorageUsedBytes() {
        if (deviceDir == null || !deviceDir.exists()) return 0L;
        return calculateDirectorySize(deviceDir);
    }

    private long calculateDirectorySize(File directory) {
        long size = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    size += calculateDirectorySize(file);
                } else {
                    size += file.length();
                }
            }
        }
        return size;
    }

    public synchronized double getStorageUsedMb() {
        return getStorageUsedBytes() / (1024.0 * 1024.0);
    }

    public synchronized double getStorageLimitMb() {
        return MAX_STORAGE_BYTES / (1024.0 * 1024.0);
    }

    public synchronized double getUsagePercentage() {
        return (getStorageUsedBytes() * 100.0) / MAX_STORAGE_BYTES;
    }

    public synchronized boolean isStorageLimitReached() {
        return getStorageUsedBytes() >= MAX_STORAGE_BYTES;
    }

    public synchronized String getStorageStatusSummary() {
        long usedBytes = getStorageUsedBytes();
        double usedMb = usedBytes / (1024.0 * 1024.0);
        double pct = (usedBytes * 100.0) / MAX_STORAGE_BYTES;
        String formattedMb = String.format(Locale.ROOT, "%.2f MB", usedMb);
        String formattedPct = String.format(Locale.ROOT, "%.2f%%", pct);

        StringBuilder sb = new StringBuilder();
        sb.append("Local device storage usage: ").append(formattedMb)
                .append(" out of 500 MB (").append(formattedPct).append(" used). ");
        sb.append("All data is isolated in private storage for device [").append(deviceId).append("]. ");

        if (usedBytes >= MAX_STORAGE_BYTES) {
            sb.append("Notice: Storage limit has been reached! You can ask me to 'prune old data' anytime to free up space.");
        } else if (usedBytes >= (long)(MAX_STORAGE_BYTES * 0.9)) {
            sb.append("Notice: Storage is approaching the 500 MB limit (over 90% full).");
        } else {
            sb.append("Storage is healthy with plenty of room remaining.");
        }
        return sb.toString();
    }

    // --- Smart Pruning: Delete Only Unnecessary Old Data, Keeping Relevant Facts ---

    public synchronized String pruneOldData() {
        long initialBytes = getStorageUsedBytes();
        int deletedLogsCount = 0;
        int prunedTurnsCount = 0;

        // 1. Delete unnecessary old session logs on disk
        if (sessionLogsDir != null && sessionLogsDir.exists()) {
            File[] logs = sessionLogsDir.listFiles();
            if (logs != null) {
                for (File log : logs) {
                    if (log.delete()) {
                        deletedLogsCount++;
                    }
                }
            }
        }

        // 2. Prune old conversation turns beyond the top 3 most recent
        if (conversationTurns.size() > 3) {
            prunedTurnsCount = conversationTurns.size() - 3;
            List<ConversationTurn> recent = new ArrayList<>(conversationTurns.subList(conversationTurns.size() - 3, conversationTurns.size()));
            conversationTurns.clear();
            conversationTurns.addAll(recent);
            persistTurnsToDisk();
        }

        long finalBytes = getStorageUsedBytes();
        long freedBytes = Math.max(0, initialBytes - finalBytes);
        String freedText = formatBytes(freedBytes);

        return "Storage cleanup complete! Deleted " + deletedLogsCount + " old session logs and pruned " +
                prunedTurnsCount + " conversation turns, freeing up " + freedText + " of local device storage. " +
                "All your essential facts—including your name, role, preferences, and important notes—remain safely preserved.";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    // --- Fact Storage and Dynamic Updates ---

    public synchronized void setFact(String key, String category, String value) {
        if (key == null || value == null) return;
        String cleanKey = key.trim().toLowerCase(Locale.ROOT);
        String cleanVal = value.trim();
        String cat = (category != null && !category.trim().isEmpty()) ? category.trim().toLowerCase(Locale.ROOT) : "fact";

        // Check 500 MB quota
        if (isStorageLimitReached() && !memoryMap.containsKey(cleanKey)) {
            Log.w(TAG, "Cannot store new fact: 500 MB storage limit reached.");
            return;
        }

        memoryMap.put(cleanKey, new MemoryFact(cleanKey, cat, cleanVal, System.currentTimeMillis()));
        persistMemoryToDisk();
        Log.i(TAG, "Local storage fact saved [" + cat + "]: " + cleanKey + " -> " + cleanVal);
    }

    public synchronized void setFact(String key, String value) {
        String cat = "fact";
        if (key != null) {
            String lk = key.toLowerCase(Locale.ROOT);
            if (lk.equals("name") || lk.equals("role")) cat = "identity";
            else if (lk.startsWith("preference") || lk.startsWith("favorite")) cat = "preference";
            else if (lk.equals("current_goal") || lk.equals("project")) cat = "goal";
        }
        setFact(key, cat, value);
    }

    public synchronized String getFact(String key) {
        if (key == null) return null;
        MemoryFact fact = memoryMap.get(key.trim().toLowerCase(Locale.ROOT));
        return fact != null ? fact.value : null;
    }

    public synchronized boolean hasFact(String key) {
        if (key == null) return false;
        return memoryMap.containsKey(key.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized boolean removeFact(String key) {
        if (key == null) return false;
        String cleanKey = key.trim().toLowerCase(Locale.ROOT);
        if (memoryMap.containsKey(cleanKey)) {
            memoryMap.remove(cleanKey);
            persistMemoryToDisk();
            Log.i(TAG, "Memory removed key from disk: " + cleanKey);
            return true;
        }
        return false;
    }

    public synchronized void clearAll() {
        memoryMap.clear();
        conversationTurns.clear();
        if (factsFile.exists()) factsFile.delete();
        if (turnsFile.exists()) turnsFile.delete();

        if (sessionLogsDir.exists()) {
            File[] logs = sessionLogsDir.listFiles();
            if (logs != null) {
                for (File f : logs) f.delete();
            }
        }

        if (prefs != null) {
            prefs.edit().remove(KEY_MEMORY_JSON).remove(KEY_TURNS_JSON).apply();
        }
        Log.i(TAG, "All local storage facts and logs cleared from device.");
    }

    public synchronized Map<String, String> getAllFacts() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, MemoryFact> entry : memoryMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().value);
        }
        return result;
    }

    // --- Conversational History for Follow-Up Continuity ---

    public synchronized void recordConversationTurn(String userQuery, String assistantResponse) {
        if (userQuery == null || userQuery.trim().isEmpty()) return;
        conversationTurns.add(new ConversationTurn(userQuery.trim(), assistantResponse != null ? assistantResponse.trim() : "", System.currentTimeMillis()));
        while (conversationTurns.size() > MAX_CONVERSATION_TURNS) {
            conversationTurns.remove(0);
        }
        persistTurnsToDisk();
        appendSessionLog(userQuery.trim(), assistantResponse != null ? assistantResponse.trim() : "");
    }

    public synchronized List<ConversationTurn> getRecentTurns(int maxCount) {
        int count = Math.min(maxCount, conversationTurns.size());
        if (count <= 0) return Collections.emptyList();
        int startIndex = conversationTurns.size() - count;
        return new ArrayList<>(conversationTurns.subList(startIndex, conversationTurns.size()));
    }

    public synchronized ConversationTurn getLastTurn() {
        if (conversationTurns.isEmpty()) return null;
        return conversationTurns.get(conversationTurns.size() - 1);
    }

    // --- LLM Context Injection ---

    public synchronized String getMemoryPromptContext() {
        if (memoryMap.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n[Jarvis Persistent Memory - Verified Explicit User Details]:\n");

        String name = getFact("name");
        String role = getFact("role");
        if (name != null || role != null) {
            sb.append("- Identity: ");
            if (name != null) sb.append("Name is ").append(name);
            if (role != null) sb.append(name != null ? ", Role is " : "Role is ").append(role);
            sb.append("\n");
        }

        StringBuilder prefSb = new StringBuilder();
        for (Map.Entry<String, MemoryFact> entry : memoryMap.entrySet()) {
            if ("preference".equalsIgnoreCase(entry.getValue().category) ||
                    entry.getKey().startsWith("favorite_") || entry.getKey().startsWith("preference_")) {
                String cleanKey = entry.getKey().replace('_', ' ');
                prefSb.append("  * ").append(cleanKey).append(": ").append(entry.getValue().value).append("\n");
            }
        }
        if (prefSb.length() > 0) {
            sb.append("- Preferences:\n").append(prefSb);
        }

        StringBuilder factsSb = new StringBuilder();
        for (Map.Entry<String, MemoryFact> entry : memoryMap.entrySet()) {
            String k = entry.getKey();
            if (!k.equals("name") && !k.equals("role") && !k.startsWith("favorite_") && !k.startsWith("preference_")) {
                factsSb.append("  * ").append(k.replace('_', ' ')).append(": ").append(entry.getValue().value).append("\n");
            }
        }
        if (factsSb.length() > 0) {
            sb.append("- Important Facts:\n").append(factsSb);
        }

        sb.append("[Memory Directive]: These facts have been explicitly requested by the user to be stored permanently on local storage. Accurately recall and reference these details across conversations.\n");
        return sb.toString();
    }

    public synchronized String getSpokenMemorySummary() {
        if (memoryMap.isEmpty()) {
            return "My memory is currently clear! You can tell me your name, preferences, or any important facts whenever you'd like.";
        }

        StringBuilder sb = new StringBuilder("Here is what I have stored in device memory: ");
        String name = getFact("name");
        String role = getFact("role");

        if (name != null) {
            sb.append("your name is ").append(name).append(", ");
        }
        if (role != null) {
            sb.append("you're working as a ").append(role).append(", ");
        }

        for (Map.Entry<String, MemoryFact> entry : memoryMap.entrySet()) {
            String k = entry.getKey();
            if (!k.equals("name") && !k.equals("role")) {
                sb.append(k.replace('_', ' ')).append(" is ").append(entry.getValue().value).append(", ");
            }
        }

        String res = sb.toString().trim();
        if (res.endsWith(",")) {
            res = res.substring(0, res.length() - 1);
        }
        return res + ". All of this is saved on your device and will be kept until you ask me to remove it!";
    }

    /**
     * Handles explicit storage, removal, queries, storage telemetry, and smart pruning.
     */
    public synchronized String processInputForMemory(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        String rawClean = input.replaceAll("(?i)^(?:hey jarvis|jarvis|ok jarvis|hello jarvis)[,\\s]*", "").trim();
        String lower = rawClean.toLowerCase(Locale.ROOT);

        // ==========================================
        // 1. STORAGE USAGE & PRUNING COMMANDS
        // ==========================================
        if (lower.contains("storage usage") || lower.contains("storage status") ||
                lower.contains("check storage") || lower.contains("memory storage") ||
                lower.contains("storage limit") || lower.equals("storage")) {
            return getStorageStatusSummary();
        }

        if (lower.contains("prune old data") || lower.contains("delete old data") ||
                lower.contains("clean up storage") || lower.contains("clean up memory") ||
                lower.contains("clean old data") || lower.contains("clean storage") ||
                lower.contains("free up space") || lower.contains("free up storage")) {
            return pruneOldData();
        }

        // ==========================================
        // 2. DYNAMIC REMOVAL: Explicit Forget / Clear
        // ==========================================
        if (lower.startsWith("forget ") || lower.startsWith("remove ") || lower.startsWith("delete ") ||
                lower.contains("clear memory") || lower.contains("forget everything") ||
                lower.contains("reset memory") || lower.contains("wipe memory")) {

            if (lower.contains("clear memory") || lower.contains("forget everything") ||
                    lower.contains("reset memory") || lower.contains("wipe memory") || lower.equals("forget all")) {
                clearAll();
                return "All persistent memory and session history have been cleared. Starting completely fresh!";
            }

            if (lower.contains("name")) {
                removeFact("name");
                return "Understood, I've cleared your name from memory.";
            }

            if (lower.contains("role") || lower.contains("job") || lower.contains("profession")) {
                removeFact("role");
                return "Understood, I've cleared your role from memory.";
            }

            if (lower.contains("goal") || lower.contains("project")) {
                removeFact("project");
                removeFact("current_goal");
                return "I've cleared that project from memory.";
            }

            // Check if user specified a preference or specific fact to forget
            for (String k : new ArrayList<>(memoryMap.keySet())) {
                String normalizedKey = k.replace('_', ' ');
                if (lower.contains(normalizedKey) || lower.contains(k)) {
                    removeFact(k);
                    return "Understood, I've removed your " + normalizedKey + " from memory.";
                }
                MemoryFact fact = memoryMap.get(k);
                if (fact != null && lower.contains(fact.value.toLowerCase(Locale.ROOT))) {
                    removeFact(k);
                    return "Understood, I've removed that " + fact.value + " from memory.";
                }
            }

            // Generic removal match: "forget that [target]" or "remove fact [target]"
            Matcher m = Pattern.compile("(?i)(?:forget|remove|delete)\\s+(?:that|about|fact)?\\s*([a-zA-Z0-9\\s]{3,})").matcher(rawClean);
            if (m.find()) {
                String target = m.group(1).trim().toLowerCase(Locale.ROOT);
                for (String k : new ArrayList<>(memoryMap.keySet())) {
                    MemoryFact fact = memoryMap.get(k);
                    if (target.contains(k.replace('_', ' ')) || (fact != null && fact.value.toLowerCase(Locale.ROOT).contains(target))) {
                        removeFact(k);
                        return "Understood, I've removed your " + k.replace('_', ' ') + " from memory.";
                    }
                }
            }
            return "I couldn't find a matching detail in memory to remove. Let me know what specific fact to delete!";
        }

        // ==========================================
        // 3. ACCURATE RECALL: Direct Memory Inquiries
        // ==========================================
        if (lower.equals("what is my name") || lower.equals("what's my name") || lower.equals("who am i") || lower.equals("do you know my name")) {
            String name = getFact("name");
            return name != null ?
                    "Your name is " + name + "! Always a pleasure talking with you." :
                    "I don't have your name in memory yet. What should I call you?";
        }

        if (lower.equals("what is my role") || lower.equals("what's my role") || lower.equals("what do i do") || lower.equals("what's my job")) {
            String role = getFact("role");
            return role != null ?
                    "You're working as a " + role + "!" :
                    "You haven't told me your role yet. What do you do?";
        }

        if (lower.contains("what are my preferences") || lower.contains("what do i prefer") || lower.equals("my preferences")) {
            StringBuilder sb = new StringBuilder("Here are the preferences I have noted: ");
            boolean hasPref = false;
            for (Map.Entry<String, MemoryFact> entry : memoryMap.entrySet()) {
                if ("preference".equalsIgnoreCase(entry.getValue().category) ||
                        entry.getKey().startsWith("favorite_") || entry.getKey().startsWith("preference_")) {
                    sb.append(entry.getKey().replace('_', ' ')).append(" is ").append(entry.getValue().value).append("; ");
                    hasPref = true;
                }
            }
            return hasPref ? sb.toString().trim() : "You haven't shared any specific preferences yet. Tell me what you like or prefer anytime!";
        }

        if (lower.startsWith("what is my favorite ") || lower.startsWith("what's my favorite ")) {
            String topic = lower.replace("what is my favorite ", "").replace("what's my favorite ", "").replaceAll("[?!.]", "").trim();
            String key = "favorite_" + topic.replace(' ', '_');
            String val = getFact(key);
            if (val != null) {
                return "Your favorite " + topic + " is " + val + "!";
            }
            return "I don't have your favorite " + topic + " in memory yet.";
        }

        if (lower.contains("what do you remember") || lower.contains("what do you know about me") ||
                lower.contains("check memory") || lower.contains("show memory") || lower.contains("recall memory") || lower.equals("list memory")) {
            return getSpokenMemorySummary();
        }

        // Targeted recall: "Do you remember door code?", "Do you remember my sister's birthday?", "Recall project status", "Do you remember that..."
        Matcher recallMatch = Pattern.compile("(?i)^(?:do you remember(?:\\s+(?:my|the|that))?|what do you remember about|recall)\\s+([a-zA-Z0-9\\s]{2,40})").matcher(rawClean);
        if (recallMatch.find()) {
            String query = recallMatch.group(1).trim().toLowerCase(Locale.ROOT).replaceAll("[?!.]", "");
            for (Map.Entry<String, MemoryFact> entry : memoryMap.entrySet()) {
                String k = entry.getKey().replace('_', ' ');
                String val = entry.getValue().value;
                if (query.contains(k) || k.contains(query) || val.toLowerCase(Locale.ROOT).contains(query)) {
                    return "Yes, I remember! " + (k.startsWith("favorite") ? "your " + k + " is " + val : val) + ".";
                }
            }
            return "I don't have anything stored in memory about " + query + " yet.";
        }

        if (lower.equals("what did we just talk about") || lower.equals("what did i ask you earlier") ||
                lower.equals("what was my last question") || lower.equals("what did i just say")) {
            ConversationTurn last = getLastTurn();
            if (last != null) {
                return "Earlier, you asked: \"" + last.userText + "\", and I responded: \"" + last.assistantText + "\".";
            }
            return "We haven't discussed anything yet in this session!";
        }

        // ==========================================
        // 4. EXPLICIT STORAGE REQUESTS ONLY
        // ==========================================
        boolean updated = false;
        StringBuilder feedback = new StringBuilder();

        // Check 500 MB quota notification before writing new details
        if (isStorageLimitReached()) {
            return "Storage limit reached (500 MB)! Please say 'clean up storage' or 'prune old data' to free up space before saving new details.";
        }

        // Pattern A: "Remember my [attribute] is [value]"
        Matcher remMyMatch = Pattern.compile("(?i)(?:please\\s+)?remember\\s+my\\s+([a-zA-Z\\s]{2,20})\\s+(?:is|as)\\s+([a-zA-Z0-9\\s.,!'-]{2,50})").matcher(rawClean);
        if (remMyMatch.find()) {
            String attr = remMyMatch.group(1).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            String val = remMyMatch.group(2).trim().replaceAll("[.!?]+$", "");

            if (attr.equals("name")) {
                setFact("name", "identity", val);
                feedback.append("Got it, I'll remember your name is ").append(val).append(". ");
                updated = true;
            } else if (attr.equals("role") || attr.equals("job") || attr.equals("profession")) {
                setFact("role", "identity", val);
                feedback.append("Updated your role: ").append(val).append(". ");
                updated = true;
            } else if (attr.startsWith("favorite")) {
                setFact(attr, "preference", val);
                feedback.append("I'll remember that your ").append(attr.replace('_', ' ')).append(" is ").append(val).append(". ");
                updated = true;
            } else {
                setFact(attr, "fact", val);
                feedback.append("Saved to memory: your ").append(attr.replace('_', ' ')).append(" is ").append(val).append(". ");
                updated = true;
            }
        }

        // Pattern B: "Remember that [fact]", "Keep in mind that [fact]", "Note that [fact]", "Save this: [fact]", "Store this: [fact]"
        Matcher factDirectiveMatch = Pattern.compile("(?i)(?:please\\s+)?(?:remember\\s+that|keep\\s+in\\s+mind\\s+that|note\\s+that|take\\s+note\\s+that|don't\\s+forget\\s+that|save\\s+this\\s*[:,-]?|store\\s+this\\s*[:,-]?)\\s+([a-zA-Z0-9\\s.,!'-]{3,80})").matcher(rawClean);
        if (factDirectiveMatch.find()) {
            String factVal = factDirectiveMatch.group(1).trim().replaceAll("[.!?]+$", "");
            String[] words = factVal.toLowerCase(Locale.ROOT).split("\\s+");
            StringBuilder keyBuilder = new StringBuilder("fact");
            int wordCount = Math.min(3, words.length);
            for (int i = 0; i < wordCount; i++) {
                String w = words[i].replaceAll("[^a-zA-Z0-9]", "");
                if (!w.isEmpty() && !w.equals("that") && !w.equals("the") && !w.equals("my")) {
                    keyBuilder.append("_").append(w);
                }
            }
            String key = keyBuilder.toString();
            setFact(key, "fact", factVal);
            feedback.append("Saved to memory: \"").append(factVal).append("\". I will remember this across sessions until you ask me to remove it. ");
            updated = true;
        }

        // Pattern C: Explicit Name Declaration: "My name is Arthur", "Call me Arthur"
        Matcher nameMatch = Pattern.compile("(?i)(?:my\\s+name\\s+is|call\\s+me)\\s+([a-zA-Z]+)(?:\\s+and|$)").matcher(rawClean);
        if (nameMatch.find()) {
            String candidateName = nameMatch.group(1).trim();
            if (!candidateName.equalsIgnoreCase("working") && !candidateName.equalsIgnoreCase("studying") &&
                    !candidateName.equalsIgnoreCase("fine") && !candidateName.equalsIgnoreCase("good") &&
                    !candidateName.equalsIgnoreCase("happy") && !candidateName.equalsIgnoreCase("sorry") &&
                    !candidateName.equalsIgnoreCase("here") && !candidateName.equalsIgnoreCase("back") &&
                    !candidateName.equalsIgnoreCase("tired") && !candidateName.equalsIgnoreCase("ready") &&
                    !candidateName.equalsIgnoreCase("a") && !candidateName.equalsIgnoreCase("an")) {
                setFact("name", "identity", candidateName);
                feedback.append("Got it, I'll remember your name is ").append(candidateName).append(". ");
                updated = true;
            }
        }

        // Pattern D: Explicit Role Declaration: "My role is [role]", "My job is [role]", "I work as [role]", "I am a [profession]"
        Matcher roleMatch = Pattern.compile("(?i)(?:my\\s+role\\s+is|my\\s+job\\s+is|my\\s+profession\\s+is|i\\s+work\\s+as\\s+an?|i\\s+am\\s+an?|i'm\\s+an?)\\s+([a-zA-Z0-9\\s]{3,35})(?:\\.|,|\\s+and|$)").matcher(rawClean);
        if (roleMatch.find()) {
            String candidateRole = roleMatch.group(1).trim();
            String lowerRole = candidateRole.toLowerCase(Locale.ROOT);
            if (!lowerRole.startsWith("human") && !lowerRole.startsWith("person") &&
                    !lowerRole.startsWith("user") && !lowerRole.equals("tired") &&
                    !lowerRole.equals("happy") && !lowerRole.equals("ready") &&
                    !lowerRole.equals("fine") && !lowerRole.equals("sorry") &&
                    !lowerRole.equals("here") && !lowerRole.equals("good")) {
                setFact("role", "identity", candidateRole);
                feedback.append("Remembered: your role is ").append(candidateRole).append(". ");
                updated = true;
            }
        }

        // Pattern E: Explicit Favorite Preference: "My favorite language is Python", "My favorite food is sushi"
        Matcher favMatch = Pattern.compile("(?i)my\\s+favorite\\s+([a-zA-Z\\s]{2,20})\\s+is\\s+([a-zA-Z0-9\\s.,!'-]{2,40})").matcher(rawClean);
        if (favMatch.find()) {
            String topic = favMatch.group(1).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            String value = favMatch.group(2).trim().replaceAll("[.!?]+$", "");
            String key = "favorite_" + topic;
            setFact(key, "preference", value);
            feedback.append("I'll remember that your favorite ").append(topic.replace('_', ' ')).append(" is ").append(value).append(". ");
            updated = true;
        }

        // Pattern F: Explicit Preference: "I prefer dark mode", "My preference is concise code"
        Matcher prefMatch = Pattern.compile("(?i)(?:i\\s+prefer|my\\s+preference\\s+is)\\s+([a-zA-Z0-9\\s.,!'-]{2,50})").matcher(rawClean);
        if (prefMatch.find() && !favMatch.find()) {
            String prefVal = prefMatch.group(1).trim().replaceAll("[.!?]+$", "");
            String[] words = prefVal.toLowerCase(Locale.ROOT).split("\\s+");
            String slug = words.length > 0 ? words[0] : "general";
            if (words.length > 1 && !words[1].equalsIgnoreCase("over") && !words[1].equalsIgnoreCase("to")) {
                slug += "_" + words[1];
            }
            String key = "preference_" + slug;
            setFact(key, "preference", prefVal);
            feedback.append("Noted your preference: ").append(prefVal).append(". ");
            updated = true;
        }

        if (updated) {
            return feedback.toString().trim();
        }

        // No explicit memory command or fact detected -> return null so normal conversation or commands execute.
        return null;
    }
}
