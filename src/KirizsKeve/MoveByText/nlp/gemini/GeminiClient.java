package KirizsKeve.MoveByText.nlp.gemini;

import KirizsKeve.MoveByText.nlp.ChatBackend;
import KirizsKeve.MoveByText.nlp.ChatHistory;
import KirizsKeve.MoveByText.nlp.HttpEndpoint;
import KirizsKeve.MoveByText.nlp.Settings;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;

/**
 * Sends text to Google's Gemini API and hands the reply back through a callback.
 *
 * - Uses only the JDK (HttpURLConnection) and the org.json that ships with Starsector,
 *   so there is nothing extra to bundle into your mod jar.
 * - Every request runs on a background thread, so the game never freezes while waiting.
 * - IMPORTANT: the Callback is invoked on that BACKGROUND thread. Do not touch Starsector
 *   objects (Global.getSector(), UI, etc.) from inside it. Just store the reply somewhere
 *   thread-safe and let an advance() method on the game thread pick it up.
 * - Keeps a short conversation history so follow-up messages have context.
 *
 * ALL settings come from data/config/llmdata.json inside your mod folder and are read once,
 * when the object is created: apiKey, model, systemPrompt (required) and maxOutputTokens
 * (optional). Nothing is hardcoded here and there is no environment-variable fallback.
 */
public class GeminiClient extends ChatBackend {

    private static final String ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String KEY_PLACEHOLDER_PREFIX = "PASTE_";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    /** How long to wait for Gemini's answer. */
    private static final int READ_TIMEOUT_MS = 60000;

    private final String apiKey;
    private final String model;
    private final String systemPrompt;
    /** 0 = not set in the file, so the field is left out of the request. */
    private final int maxOutputTokens;
    private final HttpEndpoint gemini;

    /** Create this on the game thread (e.g. in onGameLoad) because it reads the settings file. */
    public GeminiClient() {
        this(Settings.load());
    }

    /** Takes the settings directly, so tests can run outside the game (no Global.getSettings()). */
    public GeminiClient(JSONObject cfg) {
        this(cfg, ENDPOINT_BASE);
    }

    /** Same, but sends requests to endpointBase instead of Google, so tests can use a fake server on localhost. */
    GeminiClient(JSONObject cfg, String endpointBase) {
        super(READ_TIMEOUT_MS, "model");
        Settings settings = new Settings(cfg);

        String key = settings.text("apiKey");
        if (key != null && key.startsWith(KEY_PLACEHOLDER_PREFIX)) {
            key = null; // still the template placeholder
        }
        this.apiKey = key;
        this.model = settings.text("model");
        this.systemPrompt = settings.text("systemPrompt");
        this.maxOutputTokens = settings.integer("maxOutputTokens", 0);

        require("apiKey", apiKey);
        require("model", model);
        require("systemPrompt", systemPrompt);

        this.gemini = new HttpEndpoint(endpointBase, CONNECT_TIMEOUT_MS) {
            @Override
            protected void prepare(HttpURLConnection conn) {
                conn.setRequestProperty("x-goog-api-key", apiKey); // header keeps the key out of URLs/logs
            }

            @Override
            protected String httpError(int code, String responseText) {
                return "Gemini API returned HTTP " + code + ": " + responseText;
            }
        };

        logReady(", model = " + model);
    }

    @Override
    protected String displayName() {
        return "Gemini";
    }

    @Override
    public String modelName() {
        return model == null ? "?" : model;
    }

    @Override
    protected String request(String userText) throws Exception {
        JSONObject body = new JSONObject();

        JSONObject systemInstruction = new JSONObject();
        systemInstruction.put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt)));
        body.put("systemInstruction", systemInstruction);

        JSONArray contents = new JSONArray();
        for (ChatHistory.Turn turn : history()) {
            contents.put(content(turn.role(), turn.text()));
        }
        contents.put(content("user", userText));
        body.put("contents", contents);

        if (maxOutputTokens > 0) {
            JSONObject generationConfig = new JSONObject();
            generationConfig.put("maxOutputTokens", maxOutputTokens);
            body.put("generationConfig", generationConfig);
        }

        log().info("Sending HTTP request to Gemini (model " + model + ")...");
        return parseReply(gemini.send("POST", model + ":generateContent", body.toString(), readTimeoutMs()));
    }

    private static JSONObject content(String role, String text) throws Exception {
        JSONObject part = new JSONObject().put("text", text);
        JSONObject c = new JSONObject();
        c.put("role", role);
        c.put("parts", new JSONArray().put(part));
        return c;
    }

    private static String parseReply(String responseText) throws Exception {
        JSONObject json = new JSONObject(responseText);

        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new RuntimeException("Gemini returned no answer (the prompt may have been blocked): " + responseText);
        }

        JSONObject candidate = candidates.getJSONObject(0);
        StringBuilder sb = new StringBuilder();

        JSONObject content = candidate.optJSONObject("content");
        if (content != null) {
            JSONArray parts = content.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    sb.append(parts.getJSONObject(i).optString("text", ""));
                }
            }
        }

        String text = sb.toString().trim();
        if (text.isEmpty()) {
            throw new RuntimeException("Gemini returned an empty reply (finishReason="
                    + candidate.optString("finishReason", "unknown")
                    + "). If it says MAX_TOKENS, raise maxOutputTokens in the settings file.");
        }
        return text;
    }
}
