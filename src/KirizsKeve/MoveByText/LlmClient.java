package KirizsKeve.MoveByText;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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
public class LlmClient {

    public interface Callback {
        /** Called on a background thread when the model answered. */
        void onResponse(String text);

        /** Called on a background thread when anything went wrong. */
        void onError(String message);
    }

    private static final Logger LOG = Global.getLogger(LlmClient.class);

    /** Every setting is read from this file (relative to the mod folder). */
    private static final String CONFIG_FILE = "data/config/llmdata.json";
    private static final String ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String KEY_PLACEHOLDER_PREFIX = "PASTE_";

    /** Max number of stored messages (user + model together). */
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final String apiKey;
    private final String model;
    private final String systemPrompt;
    /** 0 = not set in the file, so the field is left out of the request. */
    private final int maxOutputTokens;
    /** Names of required settings missing from the file, or "" when everything is there. */
    private final String missingSettings;

    /** Entries are {role, text}. Only ever touched from the worker thread. */
    private final List<String[]> history = new ArrayList<String[]>();

    /** One thread = requests run one after another, so history stays in order. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "KirizsKeve-LlmClient");
            t.setDaemon(true); // never keep the game process alive
            return t;
        }
    });

    /** Create this on the game thread (e.g. in onGameLoad) because it reads the settings file. */
    public LlmClient() {
        String key = null;
        String mdl = null;
        String sys = null;
        int maxTokens = 0;

        try {
            JSONObject cfg = Global.getSettings().loadJSON(CONFIG_FILE);
            key = clean(cfg.optString("apiKey", null));
            mdl = clean(cfg.optString("model", null));
            sys = clean(cfg.optString("systemPrompt", null));
            maxTokens = cfg.optInt("maxOutputTokens", 0);
        } catch (Exception e) {
            LOG.warn("Could not read " + CONFIG_FILE + ": " + e.getMessage());
        }

        if (key != null && key.startsWith(KEY_PLACEHOLDER_PREFIX)) {
            key = null; // still the template placeholder
        }

        this.apiKey = key;
        this.model = mdl;
        this.systemPrompt = sys;
        this.maxOutputTokens = maxTokens;

        String missing = "";
        if (key == null) missing += " apiKey";
        if (mdl == null) missing += " model";
        if (sys == null) missing += " systemPrompt";
        this.missingSettings = missing;

        if (missing.isEmpty()) {
            LOG.info("LlmClient ready, model = " + this.model);
        } else {
            LOG.warn("LlmClient NOT ready. Missing in " + CONFIG_FILE + ":" + missing);
        }
    }

    /** Trimmed text, or null when it is null or blank. */
    private static String clean(String value) {
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    public boolean isConfigured() {
        return missingSettings.isEmpty();
    }

    /** Sends one user message. Returns immediately; the answer arrives through the callback. */
    public void ask(final String userText, final Callback callback) {
        if (!isConfigured()) {
            callback.onError("LLM not configured. Missing in " + CONFIG_FILE + ":" + missingSettings);
            return;
        }

        worker.submit(new Runnable() {
            @Override
            public void run() {
                String reply = null;
                String error = null;
                try {
                    reply = sendRequest(userText);
                    remember("user", userText);
                    remember("model", reply);
                } catch (Exception e) {
                    LOG.error("LLM request failed", e);
                    error = (e.getMessage() != null) ? e.getMessage() : e.toString();
                }

                if (error != null) {
                    callback.onError(error);
                } else {
                    callback.onResponse(reply);
                }
            }
        });
    }

    /** Forgets the conversation so far. */
    public void clearHistory() {
        worker.submit(new Runnable() {
            @Override
            public void run() {
                history.clear();
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Internals (all run on the worker thread)
    // ------------------------------------------------------------------------------------------

    private void remember(String role, String text) {
        history.add(new String[]{role, text});
        // Drop the oldest user/model pair at a time so history always starts with a "user" message.
        while (history.size() > MAX_HISTORY_MESSAGES) {
            history.remove(0);
            if (!history.isEmpty()) history.remove(0);
        }
    }

    private String sendRequest(String userText) throws Exception {
        JSONObject body = new JSONObject();

        JSONObject systemInstruction = new JSONObject();
        systemInstruction.put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt)));
        body.put("systemInstruction", systemInstruction);

        JSONArray contents = new JSONArray();
        for (String[] turn : history) {
            contents.put(content(turn[0], turn[1]));
        }
        contents.put(content("user", userText));
        body.put("contents", contents);

        if (maxOutputTokens > 0) {
            JSONObject generationConfig = new JSONObject();
            generationConfig.put("maxOutputTokens", maxOutputTokens);
            body.put("generationConfig", generationConfig);
        }

        URL url = new URL(ENDPOINT_BASE + model + ":generateContent");
        LOG.info("Sending HTTP request to Gemini (model " + model + ")...");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("x-goog-api-key", apiKey); // header keeps the key out of URLs/logs

            OutputStream os = conn.getOutputStream();
            try {
                os.write(body.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseText = readFully(stream);

            if (code != 200) {
                throw new RuntimeException("Gemini API returned HTTP " + code + ": " + responseText);
            }
            return parseReply(responseText);
        } finally {
            conn.disconnect();
        }
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

    private static String readFully(InputStream in) throws Exception {
        if (in == null) return "";
        try {
            // Starsector's script sandbox blocks most java.io classes (e.g. ByteArrayOutputStream),
            // so grow a plain byte[] by hand instead.
            byte[] data = new byte[8192];
            int len = 0;
            int n;
            while ((n = in.read(data, len, data.length - len)) != -1) {
                len += n;
                if (len == data.length) {
                    data = Arrays.copyOf(data, data.length * 2);
                }
            }
            return new String(data, 0, len, "UTF-8");
        } finally {
            in.close();
        }
    }
}