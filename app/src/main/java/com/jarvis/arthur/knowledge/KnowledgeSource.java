package com.jarvis.arthur.knowledge;

import android.os.Build;
import android.util.Log;

import com.jarvis.arthur.SettingsManager;
import com.jarvis.arthur.intent.DynamicPhrasing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Dynamic Knowledge Engine for Jarvis Arthur.
 * Dispatches queries to ultra-fast Gemini streaming endpoints or external knowledge sources.
 * Emits streaming sentence chunks so speech output starts immediately on the first complete sentence.
 */
public class KnowledgeSource {
    private static final String TAG = "KnowledgeSource";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    // Ultra-fast candidate Gemini models available on API key (gemini-3.1-flash-lite is fastest TTFT ~1.2s)
    private static final String[] GEMINI_MODELS = {
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-flash-latest"
    };

    public interface KnowledgeCallback {
        void onSuccess(String spokenAnswer, String sourceBadge, String sourceUrl);
        void onFailure(String errorMessage);
    }

    public interface StreamingKnowledgeCallback {
        void onFirstSentence(String firstSentence, String sourceBadge);
        void onNextSentence(String nextSentence);
        void onComplete(String fullResponse, String sourceBadge, String sourceUrl);
        void onFailure(String errorMessage);
    }

    private final SettingsManager settings;
    private final com.jarvis.arthur.memory.MemoryManager memoryManager;
    private final OkHttpClient httpClient;

    public KnowledgeSource(SettingsManager settings, com.jarvis.arthur.memory.MemoryManager memoryManager) {
        this.settings = settings;
        this.memoryManager = memoryManager;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    public KnowledgeSource(SettingsManager settings) {
        this(settings, null);
    }

    /**
     * Standard non-streaming query (retained for backward compatibility and unit tests).
     */
    public void queryKnowledge(String rawQuestion, KnowledgeCallback callback) {
        queryKnowledgeStreaming(rawQuestion, new StreamingKnowledgeCallback() {
            @Override
            public void onFirstSentence(String firstSentence, String sourceBadge) {}

            @Override
            public void onNextSentence(String nextSentence) {}

            @Override
            public void onComplete(String fullResponse, String sourceBadge, String sourceUrl) {
                callback.onSuccess(fullResponse, sourceBadge, sourceUrl);
            }

            @Override
            public void onFailure(String errorMessage) {
                callback.onFailure(errorMessage);
            }
        });
    }

    /**
     * Real-time streaming knowledge dispatch.
     * Starts TTS on the very first complete sentence while remaining tokens stream in.
     */
    public void queryKnowledgeStreaming(String rawQuestion, StreamingKnowledgeCallback callback) {
        if (rawQuestion == null || rawQuestion.trim().isEmpty()) {
            callback.onFailure("Empty query.");
            return;
        }

        final String clean = rawQuestion.trim();
        final String lower = clean.toLowerCase(Locale.ROOT);

        // 1. Instant local computations (Math, Time, Date, Weather, Device Info)
        if (isMathQuery(clean)) {
            handleMathQuery(clean, new KnowledgeCallback() {
                @Override
                public void onSuccess(String spokenAnswer, String sourceBadge, String sourceUrl) {
                    callback.onFirstSentence(spokenAnswer, sourceBadge);
                    callback.onComplete(spokenAnswer, sourceBadge, sourceUrl);
                }

                @Override
                public void onFailure(String errorMessage) {
                    callback.onFailure(errorMessage);
                }
            });
            return;
        }

        if (isTimeOrDateQuery(clean)) {
            handleTimeDateQuery(clean, new KnowledgeCallback() {
                @Override
                public void onSuccess(String spokenAnswer, String sourceBadge, String sourceUrl) {
                    callback.onFirstSentence(spokenAnswer, sourceBadge);
                    callback.onComplete(spokenAnswer, sourceBadge, sourceUrl);
                }

                @Override
                public void onFailure(String errorMessage) {
                    callback.onFailure(errorMessage);
                }
            });
            return;
        }

        if (isWeatherQuery(clean)) {
            handleWeatherQuery(clean, new KnowledgeCallback() {
                @Override
                public void onSuccess(String spokenAnswer, String sourceBadge, String sourceUrl) {
                    callback.onFirstSentence(spokenAnswer, sourceBadge);
                    callback.onComplete(spokenAnswer, sourceBadge, sourceUrl);
                }

                @Override
                public void onFailure(String errorMessage) {
                    callback.onFailure(errorMessage);
                }
            });
            return;
        }

        if (isDeviceInfoQuery(clean)) {
            handleDeviceInfoQuery(new KnowledgeCallback() {
                @Override
                public void onSuccess(String spokenAnswer, String sourceBadge, String sourceUrl) {
                    callback.onFirstSentence(spokenAnswer, sourceBadge);
                    callback.onComplete(spokenAnswer, sourceBadge, sourceUrl);
                }

                @Override
                public void onFailure(String errorMessage) {
                    callback.onFailure(errorMessage);
                }
            });
            return;
        }

        // 2. Check for configured Language Model (Gemini or Groq)
        String geminiKey = settings.getGeminiApiKey();
        String groqKey = settings.getGroqApiKey();
        String provider = settings.getLlmProvider();

        boolean hasGemini = geminiKey != null && !geminiKey.trim().isEmpty() && !geminiKey.contains("XXXX");
        boolean hasGroq = groqKey != null && !groqKey.trim().isEmpty() && !groqKey.contains("XXXX");

        if ("groq".equalsIgnoreCase(provider) && hasGroq) {
            queryGroq(clean, groqKey, callback);
            return;
        }

        if (hasGemini) {
            streamGemini(clean, geminiKey, 0, callback);
            return;
        }

        if (hasGroq) {
            queryGroq(clean, groqKey, callback);
            return;
        }

        // 3. Fallback to offline persona or Wikipedia
        fallbackStreaming(clean, lower, callback);
    }

    private void fallbackStreaming(String clean, String lower, StreamingKnowledgeCallback callback) {
        if (isConversationalOrPersona(lower)) {
            String dynamicReply = DynamicPhrasing.getDynamicPersonaResponse(lower);
            callback.onFirstSentence(dynamicReply, "JARVIS_CORE");
            callback.onComplete(dynamicReply, "JARVIS_CORE", null);
            return;
        }

        queryWikipedia(clean, new KnowledgeCallback() {
            @Override
            public void onSuccess(String spokenAnswer, String sourceBadge, String sourceUrl) {
                callback.onFirstSentence(spokenAnswer, sourceBadge);
                callback.onComplete(spokenAnswer, sourceBadge, sourceUrl);
            }

            @Override
            public void onFailure(String errorMessage) {
                callback.onFailure(errorMessage);
            }
        });
    }

    // --- High-Speed Google Gemini SSE Streaming ---
    private void streamGemini(String prompt, String apiKey, int modelIndex, StreamingKnowledgeCallback callback) {
        if (modelIndex >= GEMINI_MODELS.length) {
            Log.w(TAG, "All candidate Gemini models exhausted. Checking Groq / Wikipedia fallbacks.");
            String groqKey = settings.getGroqApiKey();
            if (groqKey != null && !groqKey.trim().isEmpty() && !groqKey.contains("XXXX")) {
                queryGroq(prompt, groqKey, callback);
            } else {
                fallbackStreaming(prompt, prompt.toLowerCase(Locale.ROOT), callback);
            }
            return;
        }

        String modelName = GEMINI_MODELS[modelIndex];
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName +
                    ":streamGenerateContent?alt=sse&key=" + apiKey.trim();

            String memoryContext = memoryManager != null ? memoryManager.getMemoryPromptContext() : "";
            JSONObject systemInstruction = new JSONObject();
            JSONArray sysParts = new JSONArray();
            JSONObject sysPart = new JSONObject();
            sysPart.put("text", "You are Jarvis: a gentle, confident, professional adult male personal AI companion. " +
                    "Speak with a natural, clear, conversational American English cadence. " +
                    "Use natural contractions (I'm, here's, let's, you've got) and articulate phrasing. " +
                    "For simple questions, give a concise, direct answer in 1 to 2 sentences. " +
                    "For complicated questions, explain clearly in 2 to 3 sentences maximum. " +
                    "Never use markdown symbols, asterisks, bullet points, numbered lists, or URLs. " +
                    memoryContext);
            sysParts.put(sysPart);
            systemInstruction.put("parts", sysParts);

            JSONArray contents = new JSONArray();

            // Inject recent conversation turns (last 3-4 turns) for context without excessive tokens
            if (memoryManager != null) {
                java.util.List<com.jarvis.arthur.memory.MemoryManager.ConversationTurn> turns = memoryManager.getRecentTurns(4);
                for (com.jarvis.arthur.memory.MemoryManager.ConversationTurn turn : turns) {
                    if (turn.userText != null && !turn.userText.trim().isEmpty()) {
                        JSONObject uContent = new JSONObject();
                        uContent.put("role", "user");
                        JSONArray uParts = new JSONArray();
                        JSONObject uP = new JSONObject();
                        uP.put("text", turn.userText);
                        uParts.put(uP);
                        uContent.put("parts", uParts);
                        contents.put(uContent);

                        if (turn.assistantText != null && !turn.assistantText.trim().isEmpty()) {
                            JSONObject mContent = new JSONObject();
                            mContent.put("role", "model");
                            JSONArray mParts = new JSONArray();
                            JSONObject mP = new JSONObject();
                            mP.put("text", turn.assistantText);
                            mParts.put(mP);
                            mContent.put("parts", mParts);
                            contents.put(mContent);
                        }
                    }
                }
            }

            // Current prompt
            JSONObject currentContent = new JSONObject();
            currentContent.put("role", "user");
            JSONArray currentParts = new JSONArray();
            JSONObject currentPart = new JSONObject();
            currentPart.put("text", prompt);
            currentParts.put(currentPart);
            currentContent.put("parts", currentParts);
            contents.put(currentContent);

            JSONObject genConfig = new JSONObject();
            genConfig.put("temperature", 0.65);
            genConfig.put("maxOutputTokens", 180);

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("system_instruction", systemInstruction);
            jsonBody.put("contents", contents);
            jsonBody.put("generationConfig", genConfig);

            RequestBody body = RequestBody.create(jsonBody.toString(), JSON_MEDIA);
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Accept", "text/event-stream")
                    .header("User-Agent", "JarvisArthurAndroid/4.0")
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.w(TAG, "Gemini stream error on " + modelName + ": " + e.getMessage());
                    streamGemini(prompt, apiKey, modelIndex + 1, callback);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (response) {
                        if (!response.isSuccessful() || response.body() == null) {
                            Log.w(TAG, "Gemini non-200 code (" + response.code() + ") on " + modelName);
                            streamGemini(prompt, apiKey, modelIndex + 1, callback);
                            return;
                        }

                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
                        String line;
                        StringBuilder fullResponse = new StringBuilder();
                        StringBuilder currentSentenceBuffer = new StringBuilder();
                        boolean[] firstSentenceDispatched = new boolean[]{false};

                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data: ")) continue;
                            String jsonStr = line.substring(6).trim();
                            if (jsonStr.isEmpty()) continue;

                            try {
                                JSONObject root = new JSONObject(jsonStr);
                                JSONArray candidates = root.optJSONArray("candidates");
                                if (candidates != null && candidates.length() > 0) {
                                    JSONObject firstCandidate = candidates.getJSONObject(0);
                                    JSONObject candContent = firstCandidate.optJSONObject("content");
                                    if (candContent != null) {
                                        JSONArray candParts = candContent.optJSONArray("parts");
                                        if (candParts != null && candParts.length() > 0) {
                                            String chunk = candParts.getJSONObject(0).optString("text", "");
                                            if (!chunk.isEmpty()) {
                                                fullResponse.append(chunk);
                                                currentSentenceBuffer.append(chunk);

                                                while (true) {
                                                    String bufStr = currentSentenceBuffer.toString();
                                                    int endIdx = findSentenceEnd(bufStr);
                                                    if (endIdx != -1) {
                                                        String sentence = bufStr.substring(0, endIdx + 1).trim();
                                                        String remainder = bufStr.substring(endIdx + 1).replaceFirst("^\\s+", "");
                                                        currentSentenceBuffer.setLength(0);
                                                        currentSentenceBuffer.append(remainder);

                                                        if (!sentence.isEmpty()) {
                                                            if (!firstSentenceDispatched[0]) {
                                                                firstSentenceDispatched[0] = true;
                                                                callback.onFirstSentence(sentence, "GEMINI_AI (" + modelName + ")");
                                                            } else {
                                                                callback.onNextSentence(sentence);
                                                            }
                                                        }
                                                    } else {
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Exception parseEx) {
                                Log.w(TAG, "Chunk parse notice: " + parseEx.getMessage());
                            }
                        }

                        // Flush leftover text in buffer
                        String leftover = currentSentenceBuffer.toString().trim();
                        if (!leftover.isEmpty()) {
                            if (!firstSentenceDispatched[0]) {
                                firstSentenceDispatched[0] = true;
                                callback.onFirstSentence(leftover, "GEMINI_AI (" + modelName + ")");
                            } else {
                                callback.onNextSentence(leftover);
                            }
                        }

                        String completeText = fullResponse.toString().trim();
                        if (completeText.isEmpty()) {
                            streamGemini(prompt, apiKey, modelIndex + 1, callback);
                            return;
                        }

                        callback.onComplete(completeText, "GEMINI_AI (" + modelName + ")", "https://ai.google.dev");
                    } catch (Exception streamEx) {
                        Log.w(TAG, "Stream reading failed: " + streamEx.getMessage());
                        streamGemini(prompt, apiKey, modelIndex + 1, callback);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Stream initiation error: " + e.getMessage());
            streamGemini(prompt, apiKey, modelIndex + 1, callback);
        }
    }

    /**
     * Determines complete sentence boundaries for streaming TTS synthesis.
     */
    public static int findSentenceEnd(String text) {
        if (text == null || text.length() < 3) return -1;
        for (int i = 0; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                char next = text.charAt(i + 1);
                if (Character.isWhitespace(next)) {
                    // Filter out common abbreviations
                    if (c == '.' && i >= 2) {
                        String sub = text.substring(Math.max(0, i - 4), i + 1).toLowerCase(Locale.ROOT);
                        if (sub.endsWith("mr.") || sub.endsWith("dr.") || sub.endsWith("vs.") ||
                                sub.endsWith("ms.") || sub.endsWith("prof.") ||
                                sub.endsWith("e.g.") || sub.endsWith("i.e.")) {
                            continue;
                        }
                    }
                    // Filter out decimals: 3.14
                    if (c == '.' && i > 0 && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(next)) {
                        continue;
                    }
                    return i;
                }
            } else if (c == '\n') {
                if (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) {
                    return i;
                }
            }
        }
        return -1;
    }

    // --- Groq LLM API (High-speed LLaMA 3.3 / Mixtral fallback) ---
    private void queryGroq(String prompt, String apiKey, StreamingKnowledgeCallback callback) {
        try {
            String url = "https://api.groq.com/openai/v1/chat/completions";

            String memContext = memoryManager != null ? memoryManager.getMemoryPromptContext() : "";
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", "You are Jarvis: a gentle, confident, professional adult male personal AI companion. " +
                    "Speak with a natural, clear, conversational American English cadence. " +
                    "Use natural contractions and articulate phrasing. " +
                    "Keep answers concise (1 to 3 sentences maximum). " +
                    "Never use markdown symbols, asterisks, bullet points, or raw links." + memContext);

            JSONArray messages = new JSONArray();
            messages.put(sysMsg);

            if (memoryManager != null) {
                java.util.List<com.jarvis.arthur.memory.MemoryManager.ConversationTurn> turns = memoryManager.getRecentTurns(4);
                for (com.jarvis.arthur.memory.MemoryManager.ConversationTurn turn : turns) {
                    if (turn.userText != null && !turn.userText.trim().isEmpty()) {
                        JSONObject uMsg = new JSONObject();
                        uMsg.put("role", "user");
                        uMsg.put("content", turn.userText);
                        messages.put(uMsg);

                        if (turn.assistantText != null && !turn.assistantText.trim().isEmpty()) {
                            JSONObject aMsg = new JSONObject();
                            aMsg.put("role", "assistant");
                            aMsg.put("content", turn.assistantText);
                            messages.put(aMsg);
                        }
                    }
                }
            }

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.put(userMsg);

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", "llama-3.3-70b-versatile");
            jsonBody.put("messages", messages);
            jsonBody.put("max_tokens", 180);
            jsonBody.put("temperature", 0.65);

            RequestBody body = RequestBody.create(jsonBody.toString(), JSON_MEDIA);
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("User-Agent", "JarvisArthurAndroid/4.0")
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    fallbackStreaming(prompt, prompt.toLowerCase(Locale.ROOT), callback);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (response) {
                        if (response.isSuccessful() && response.body() != null) {
                            JSONObject root = new JSONObject(response.body().string());
                            JSONArray choices = root.optJSONArray("choices");
                            if (choices != null && choices.length() > 0) {
                                JSONObject first = choices.getJSONObject(0);
                                JSONObject msg = first.optJSONObject("message");
                                if (msg != null) {
                                    String content = msg.optString("content", "").trim();
                                    if (!content.isEmpty()) {
                                        callback.onFirstSentence(content, "GROQ_LLM");
                                        callback.onComplete(content, "GROQ_LLM", "https://groq.com");
                                        return;
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    fallbackStreaming(prompt, prompt.toLowerCase(Locale.ROOT), callback);
                }
            });
        } catch (Exception e) {
            fallbackStreaming(prompt, prompt.toLowerCase(Locale.ROOT), callback);
        }
    }

    // --- Free Wikipedia Knowledge Source with Dynamic Natural Phrasing ---
    public void queryWikipedia(String query, KnowledgeCallback callback) {
        String stripped = query
                .replaceAll("(?i)^(who is|what is|tell me about|explain|who was|where is|search for|what are|define|how does|why does)\\s+", "")
                .trim();
        final String topic = stripped.isEmpty() ? query : stripped;

        try {
            String encoded = URLEncoder.encode(topic, StandardCharsets.UTF_8.toString());
            String searchUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
                    encoded + "&format=json&utf8=1&srlimit=1";

            Request searchReq = new Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "JarvisArthurAssistant/4.0 (contact@arthur.ai)")
                    .build();

            httpClient.newCall(searchReq).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    queryDuckDuckGo(topic, callback);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (response) {
                        if (!response.isSuccessful() || response.body() == null) {
                            queryDuckDuckGo(topic, callback);
                            return;
                        }

                        JSONObject json = new JSONObject(response.body().string());
                        JSONObject queryObj = json.optJSONObject("query");
                        JSONArray searchArr = queryObj != null ? queryObj.optJSONArray("search") : null;

                        if (searchArr != null && searchArr.length() > 0) {
                            String pageTitle = searchArr.getJSONObject(0).optString("title");
                            fetchWikipediaSummary(pageTitle, callback);
                        } else {
                            queryDuckDuckGo(topic, callback);
                        }
                    } catch (Exception e) {
                        queryDuckDuckGo(topic, callback);
                    }
                }
            });
        } catch (Exception e) {
            queryDuckDuckGo(topic, callback);
        }
    }

    private void fetchWikipediaSummary(String title, KnowledgeCallback callback) {
        try {
            String encoded = URLEncoder.encode(title.replace(" ", "_"), StandardCharsets.UTF_8.toString());
            String summaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/" + encoded;

            Request req = new Request.Builder()
                    .url(summaryUrl)
                    .header("User-Agent", "JarvisArthurAssistant/4.0 (contact@arthur.ai)")
                    .build();

            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    queryDuckDuckGo(title, callback);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (response) {
                        if (response.isSuccessful() && response.body() != null) {
                            JSONObject json = new JSONObject(response.body().string());
                            String extract = json.optString("extract", "");
                            String pageUrl = "https://en.wikipedia.org/wiki/" + encoded;
                            JSONObject contentUrls = json.optJSONObject("content_urls");
                            if (contentUrls != null) {
                                JSONObject desktop = contentUrls.optJSONObject("desktop");
                                if (desktop != null) {
                                    pageUrl = desktop.optString("page", pageUrl);
                                }
                            }

                            if (!extract.isEmpty()) {
                                String cleanExtract = cleanExtractText(extract);
                                String spoken = DynamicPhrasing.formatKnowledgeExtract(title, cleanExtract);
                                callback.onSuccess(spoken, "WIKIPEDIA", pageUrl);
                                return;
                            }
                        }
                        queryDuckDuckGo(title, callback);
                    } catch (Exception e) {
                        queryDuckDuckGo(title, callback);
                    }
                }
            });
        } catch (Exception e) {
            queryDuckDuckGo(title, callback);
        }
    }

    // --- DuckDuckGo Instant Answer Fallback ---
    private void queryDuckDuckGo(String query, KnowledgeCallback callback) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());
            String ddgUrl = "https://api.duckduckgo.com/?q=" + encoded + "&format=json&no_html=1&skip_disambig=1";

            Request req = new Request.Builder()
                    .url(ddgUrl)
                    .header("User-Agent", "JarvisArthurAssistant/4.0")
                    .build();

            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onFailure("Network unavailable.");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (response) {
                        if (response.isSuccessful() && response.body() != null) {
                            JSONObject json = new JSONObject(response.body().string());
                            String abstractText = json.optString("AbstractText", "");
                            String abstractUrl = json.optString("AbstractURL", "");
                            String heading = json.optString("Heading", query);

                            if (!abstractText.isEmpty()) {
                                String spoken = DynamicPhrasing.formatKnowledgeExtract(heading, cleanExtractText(abstractText));
                                callback.onSuccess(spoken, "DUCKDUCKGO", abstractUrl);
                                return;
                            }

                            JSONArray related = json.optJSONArray("RelatedTopics");
                            if (related != null && related.length() > 0) {
                                for (int i = 0; i < related.length(); i++) {
                                    JSONObject topic = related.optJSONObject(i);
                                    if (topic != null) {
                                        String tText = topic.optString("Text", "");
                                        String fUrl = topic.optString("FirstURL", "");
                                        if (!tText.isEmpty()) {
                                            String spoken = DynamicPhrasing.formatKnowledgeExtract(query, cleanExtractText(tText));
                                            callback.onSuccess(spoken, "DUCKDUCKGO", fUrl);
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                        callback.onFailure("No encyclopedia entry found for: " + query);
                    } catch (Exception e) {
                        callback.onFailure("DuckDuckGo error: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            callback.onFailure("DDG Query error: " + e.getMessage());
        }
    }

    private String cleanExtractText(String raw) {
        if (raw == null) return "";
        String clean = raw.replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("\\s+", " ")
                .trim();

        // Limit to 2 concise sentences for speech
        String[] sentences = clean.split("(?<=[.!?])\\s+");
        if (sentences.length > 2) {
            return sentences[0] + " " + sentences[1];
        }
        return clean;
    }

    // --- Weather Telemetry ---
    private void handleWeatherQuery(String query, KnowledgeCallback callback) {
        String location = extractLocation(query);
        try {
            String url = "https://wttr.in/" + URLEncoder.encode(location, StandardCharsets.UTF_8.toString()) + "?format=j1";

            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "JarvisArthurWeather/4.0")
                    .build();

            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onFailure("Failed to retrieve live weather telemetry.");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (response) {
                        if (response.isSuccessful() && response.body() != null) {
                            JSONObject data = new JSONObject(response.body().string());
                            JSONArray currentArr = data.optJSONArray("current_condition");
                            JSONArray areaArr = data.optJSONArray("nearest_area");

                            if (currentArr != null && currentArr.length() > 0) {
                                JSONObject curr = currentArr.getJSONObject(0);
                                String tempC = curr.optString("temp_C", "--");
                                String feelsLike = curr.optString("FeelsLikeC", tempC);
                                String humidity = curr.optString("humidity", "--");
                                String wind = curr.optString("windspeedKmph", "--");
                                String desc = "clear conditions";

                                JSONArray descArr = curr.optJSONArray("weatherDesc");
                                if (descArr != null && descArr.length() > 0) {
                                    desc = descArr.getJSONObject(0).optString("value", desc);
                                }

                                String areaName = location.isEmpty() ? "your current location" : location;
                                if (areaArr != null && areaArr.length() > 0) {
                                    JSONArray names = areaArr.getJSONObject(0).optJSONArray("areaName");
                                    if (names != null && names.length() > 0) {
                                        areaName = names.getJSONObject(0).optString("value", areaName);
                                    }
                                }

                                String dynamicReport = DynamicPhrasing.formatWeatherResponse(
                                        areaName, desc, tempC, feelsLike, humidity, wind);

                                callback.onSuccess(dynamicReport, "LIVE_WEATHER", "https://wttr.in/" + location);
                                return;
                            }
                        }
                        callback.onFailure("Weather telemetry returned empty.");
                    } catch (Exception e) {
                        callback.onFailure("Weather parsing error: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            callback.onFailure("Weather query failed: " + e.getMessage());
        }
    }

    private String extractLocation(String query) {
        Pattern pattern = Pattern.compile("(?i)weather\\s+(?:in|for|at)\\s+([a-zA-Z\\s]+)");
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    // --- Math & Calculations with Varied Phrasing ---
    private void handleMathQuery(String query, KnowledgeCallback callback) {
        try {
            double result = evaluateMath(query);
            String formatted = (result == (long) result) ?
                    String.format(Locale.US, "%,d", (long) result) :
                    String.format(Locale.US, "%,.2f", result);

            String dynamicSpoken = DynamicPhrasing.getMathResponse(formatted);
            callback.onSuccess(dynamicSpoken, "MATH_CORE", null);
        } catch (Exception e) {
            callback.onFailure("Could not calculate mathematical expression: " + e.getMessage());
        }
    }

    private double evaluateMath(String input) throws Exception {
        String clean = input.toLowerCase(Locale.ROOT)
                .replaceAll("(?i)^(calculate|what is|compute|solve|how much is)\\s+", "")
                .replaceAll("times|multiplied by|x", "*")
                .replaceAll("divided by|over", "/")
                .replaceAll("plus|add", "+")
                .replaceAll("minus|subtract", "-")
                .trim();

        // Percentage: "25 percent of 400"
        Pattern pctPattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent of)\\s*(\\d+(?:\\.\\d+)?)");
        Matcher pctMatch = pctPattern.matcher(clean);
        if (pctMatch.find()) {
            double p = Double.parseDouble(pctMatch.group(1));
            double total = Double.parseDouble(pctMatch.group(2));
            return (p / 100.0) * total;
        }

        // Square root: "square root of 144"
        Pattern sqrtPattern = Pattern.compile("(?:square root of|sqrt)\\s*(\\d+(?:\\.\\d+)?)");
        Matcher sqrtMatch = sqrtPattern.matcher(clean);
        if (sqrtMatch.find()) {
            double val = Double.parseDouble(sqrtMatch.group(1));
            return Math.sqrt(val);
        }

        // Simple binary operations
        Pattern binaryPattern = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)");
        Matcher binMatch = binaryPattern.matcher(clean);
        if (binMatch.find()) {
            double a = Double.parseDouble(binMatch.group(1));
            String op = binMatch.group(2);
            double b = Double.parseDouble(binMatch.group(3));

            switch (op) {
                case "+": return a + b;
                case "-": return a - b;
                case "*": return a * b;
                case "/":
                    if (b == 0) throw new ArithmeticException("Division by zero");
                    return a / b;
            }
        }

        throw new IllegalArgumentException("Unsupported arithmetic formula");
    }

    // --- Time & Date ---
    private void handleTimeDateQuery(String query, KnowledgeCallback callback) {
        String lower = query.toLowerCase(Locale.ROOT);
        Date now = new Date();

        if (lower.contains("date") || lower.contains("day") || lower.contains("today")) {
            SimpleDateFormat sdf = new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US);
            String[] dateVariations = {
                    "Today is " + sdf.format(now) + ", sir.",
                    "The calendar reports " + sdf.format(now) + ".",
                    "Currently, today is " + sdf.format(now) + ", sir."
            };
            String selected = dateVariations[new java.util.Random().nextInt(dateVariations.length)];
            callback.onSuccess(selected, "CHRONO_CORE", null);
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("h:mm a", Locale.US);
            String[] timeVariations = {
                    "The current time is " + sdf.format(now) + ", sir.",
                    "Time check: exactly " + sdf.format(now) + ".",
                    "It is currently " + sdf.format(now) + ", sir."
            };
            String selected = timeVariations[new java.util.Random().nextInt(timeVariations.length)];
            callback.onSuccess(selected, "CHRONO_CORE", null);
        }
    }

    // --- Device Info ---
    private void handleDeviceInfoQuery(KnowledgeCallback callback) {
        String spoken = String.format(Locale.US,
                "Sir, this device is a %s %s operating on Android version %s.",
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE);
        callback.onSuccess(spoken, "SYSTEM_TELEMETRY", null);
    }

    // --- Classification Helpers ---
    public static boolean isConversationalOrPersona(String text) {
        return text.matches("^(?:hello|hi|hey|good morning|good afternoon|good evening|greetings)\\b.*") ||
                text.contains("who created you") ||
                text.contains("who made you") ||
                text.contains("who built you") ||
                text.contains("who are you") ||
                text.contains("what is your name") ||
                text.contains("how are you") ||
                text.contains("thank you") ||
                text.contains("thanks");
    }

    public static boolean isTimeOrDateQuery(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.contains("what time") || lower.contains("current time") ||
                lower.contains("what's the time") || lower.contains("what is the date") ||
                lower.contains("today's date") || lower.contains("what day is it");
    }

    public static boolean isMathQuery(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.startsWith("calculate") || lower.contains("percent of") ||
                lower.contains("square root") || lower.matches(".*\\d+\\s*[+\\-*/x]\\s*\\d+.*");
    }

    public static boolean isWeatherQuery(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.contains("weather") || lower.contains("temperature") || lower.contains("forecast");
    }

    public static boolean isDeviceInfoQuery(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.contains("device model") || lower.contains("phone model") ||
                lower.contains("what phone is this") || lower.contains("system specs");
    }
}
