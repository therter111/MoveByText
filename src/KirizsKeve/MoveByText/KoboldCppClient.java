package KirizsKeve.MoveByText;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends text to a KoboldCpp server (e.g. Qwen 3.5 9B on another PC, reached over Tailscale) and hands the
 * reply back through a callback. Works the same way as LlmClient, the Gemini version:
 *
 * - Uses only the JDK (HttpURLConnection) and the org.json that ships with Starsector.
 * - Every request runs on one background thread, so the game never freezes while waiting.
 * - IMPORTANT: the Callback is invoked on that BACKGROUND thread. Do not touch Starsector objects from it.
 * - Keeps a short conversation history so follow-up messages have context.
 *
 * It uses KoboldCpp's own /api/v1/generate endpoint and builds the chat prompt here, in ChatML, the format
 * Qwen models use. That way KoboldCpp needs no chat template settings, and thinking can be switched off the
 * way Qwen's own template does it: by starting the answer with an empty <think></think> block.
 * A model family that doesn't use ChatML (Llama, Gemma, Mistral...) would need its own format here.
 *
 * The system prompt goes in KoboldCpp's "memory" field, which is never cut: when the chat outgrows the
 * context size KoboldCpp was started with, it drops the oldest messages instead.
 *
 * Settings come from data/config/llmdata.json: "systemPrompt" at the top (shared with Gemini) and a
 * "koboldcpp" object with these fields:
 *   url             (required) e.g. "http://gaming-pc:5001", the Tailscale name or 100.x.y.z address
 *   password        only if KoboldCpp was started with --password
 *   think           false (default) answers right away; true lets the model reason first, which is much slower
 *   maxOutputTokens longest reply, default 512. Stops a confused model from rambling for a minute.
 *   temperature     lower = more consistent answers; KoboldCpp's default (0.7) when not set
 *   timeoutSeconds  how long to wait for an answer, default 120
 */
public class KoboldCppClient implements LlmBackend {

    private static final Logger LOG = Global.getLogger(KoboldCppClient.class);

    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 512;
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int CONNECT_TIMEOUT_MS = 10000;

    // ChatML, as in Qwen 3.5's chat template
    private static final String TURN_START = "<|im_start|>";
    private static final String TURN_END = "<|im_end|>";
    /** Start of the answer with thinking off: an empty thinking block, like Qwen's template with enable_thinking=false. */
    static final String ANSWER_NO_THINK = TURN_START + "assistant\n<think>\n\n</think>\n\n";
    /** Start of the answer with thinking on. */
    static final String ANSWER_THINK = TURN_START + "assistant\n<think>\n";
    /** Stops generation at the end of the answer, before the model writes the next turn itself. */
    private static final String[] STOP_SEQUENCES = {TURN_END, TURN_START};
    /**
     * ChatML markers in player text would be read as real turn boundaries (KoboldCpp parses special tokens
     * in the prompt), so a sentence could fake a system message. They are removed before sending.
     */
    private static final Pattern CONTROL_TOKEN = Pattern.compile("<\\|(im_start|im_end|endoftext)\\|>");

    private static final Pattern THINK_OPEN = Pattern.compile("<think>", Pattern.CASE_INSENSITIVE);
    private static final Pattern THINK_CLOSE = Pattern.compile("</think>", Pattern.CASE_INSENSITIVE);

    /** Server address without a trailing slash, e.g. "http://gaming-pc:5001". */
    private final String url;
    /** null = no password. */
    private final String password;
    private final String systemPrompt;
    private final boolean think;
    private final int maxOutputTokens;
    /** null = not set, so KoboldCpp uses its default. */
    private final Double temperature;
    /** Names of required settings missing from the file, or "" when everything is there. */
    private final String missingSettings;
    /** How long to wait for the answer. Read from timeoutSeconds; tests may change it. */
    int readTimeoutMs;

    /** Entries are {role, text}. Only ever touched from the worker thread. */
    private final List<String[]> history = new ArrayList<String[]>();

    /** One thread = requests run one after another, so history stays in order. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "KirizsKeve-KoboldCppClient");
            t.setDaemon(true); // never keep the game process alive
            return t;
        }
    });

    /** Create this on the game thread (e.g. in onGameLoad) because it reads the settings file. */
    public KoboldCppClient() {
        this(LlmClient.loadConfig());
    }

    /** Takes the settings directly, so tests can run outside the game. */
    KoboldCppClient(JSONObject cfg) {
        JSONObject k = cfg.optJSONObject("koboldcpp");
        if (k == null) k = new JSONObject();

        this.url = normalizeUrl(clean(k.optString("url", null)));
        this.password = clean(k.optString("password", null));
        this.systemPrompt = clean(cfg.optString("systemPrompt", null));
        this.think = k.optBoolean("think", false);
        this.maxOutputTokens = k.optInt("maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS);

        double temp = k.optDouble("temperature", Double.NaN);
        this.temperature = (Double.isNaN(temp) || temp < 0) ? null : Double.valueOf(temp);

        int timeoutSeconds = k.optInt("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        this.readTimeoutMs = (timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS) * 1000;

        String missing = "";
        if (url == null) missing += " koboldcpp.url";
        if (systemPrompt == null) missing += " systemPrompt";
        this.missingSettings = missing;

        if (missing.isEmpty()) {
            LOG.info("KoboldCppClient ready at " + url + (think ? " (thinking on)" : ""));
        } else {
            LOG.warn("KoboldCppClient NOT ready. Missing in " + LlmClient.CONFIG_FILE + ":" + missing);
        }
    }

    /** Accepts "gaming-pc:5001", "http://gaming-pc:5001/" or the address with /api or /api/v1/generate after it. */
    static String normalizeUrl(String url) {
        if (url == null) return null;
        if (!url.contains("://")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        for (String suffix : new String[]{"/api/v1/generate", "/api/v1", "/api", "/v1"}) {
            if (url.endsWith(suffix)) {
                url = url.substring(0, url.length() - suffix.length());
                break;
            }
        }
        return url;
    }

    /** Trimmed text, or null when it is null or blank. */
    private static String clean(String value) {
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    @Override
    public boolean isConfigured() {
        return missingSettings.isEmpty();
    }

    /** KoboldCpp serves whatever model it was started with; loadedModel() asks the server for its name. */
    @Override
    public String modelName() {
        return "KoboldCpp";
    }

    @Override
    public void ask(final String userText, final LlmClient.Callback callback) {
        if (!isConfigured()) {
            callback.onError("LLM not configured. Missing in " + LlmClient.CONFIG_FILE + ":" + missingSettings);
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
                    remember("assistant", reply);
                } catch (Exception e) {
                    LOG.error("KoboldCpp request failed", e);
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

    @Override
    public String askNow(String userText) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("LLM not configured. Missing in " + LlmClient.CONFIG_FILE + ":" + missingSettings);
        }
        return sendRequest(userText);
    }

    @Override
    public void clearHistory() {
        worker.submit(new Runnable() {
            @Override
            public void run() {
                history.clear();
            }
        });
    }

    /**
     * Asks the server which model file it has loaded, e.g. "koboldcpp/Qwen3.5-9B-Q6_K". Blocking, so for
     * tests and tools only - never call this from the game thread.
     */
    String loadedModel() throws Exception {
        if (url == null) throw new IllegalStateException("koboldcpp.url is not set");
        String response = send("GET", "/api/v1/model", null);
        return new JSONObject(response).optString("result", "?");
    }

    // ------------------------------------------------------------------------------------------
    // Internals (all run on the worker thread)
    // ------------------------------------------------------------------------------------------

    private void remember(String role, String text) {
        history.add(new String[]{role, text});
        // Drop the oldest user/assistant pair at a time so history always starts with a "user" message.
        while (history.size() > MAX_HISTORY_MESSAGES) {
            history.remove(0);
            if (!history.isEmpty()) history.remove(0);
        }
    }

    private static String turn(String role, String text) {
        return TURN_START + role + "\n" + CONTROL_TOKEN.matcher(text).replaceAll("") + TURN_END + "\n";
    }

    private String sendRequest(String userText) throws Exception {
        StringBuilder prompt = new StringBuilder();
        for (String[] t : history) {
            prompt.append(turn(t[0], t[1]));
        }
        prompt.append(turn("user", userText));
        prompt.append(think ? ANSWER_THINK : ANSWER_NO_THINK);

        JSONObject body = new JSONObject();
        body.put("memory", turn("system", systemPrompt));
        body.put("prompt", prompt.toString());
        if (maxOutputTokens > 0) body.put("max_length", maxOutputTokens);
        if (temperature != null) body.put("temperature", temperature.doubleValue());
        JSONArray stops = new JSONArray();
        for (String s : STOP_SEQUENCES) stops.put(s);
        body.put("stop_sequence", stops);
        body.put("trim_stop", true); // leave the stop sequence out of the reply
        body.put("replace_instruct_placeholders", false); // send player text as written, even "{{[INPUT]}}"

        LOG.info("Sending HTTP request to KoboldCpp at " + url + "...");
        return parseReply(send("POST", "/api/v1/generate", body.toString()));
    }

    /** One HTTP request; returns the body of a 200 reply and throws with a readable message otherwise. */
    private String send(String method, String path, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url + path).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(readTimeoutMs);
            if (password != null) {
                conn.setRequestProperty("Authorization", "Bearer " + password); // header keeps it out of URLs/logs
            }

            int code;
            String responseText;
            try {
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    OutputStream os = conn.getOutputStream();
                    try {
                        os.write(body.getBytes("UTF-8"));
                    } finally {
                        os.close();
                    }
                }

                code = conn.getResponseCode();
                InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                responseText = LlmClient.readFully(stream);
            } catch (UnknownHostException e) {
                throw (UnknownHostException) new UnknownHostException("Can't find " + url
                        + ". Is Tailscale connected on this PC, and is the name in koboldcpp.url right?").initCause(e);
            } catch (ConnectException e) {
                throw (ConnectException) new ConnectException("Can't connect to KoboldCpp at " + url
                        + ". Is the other PC on, KoboldCpp running with the model loaded, and its port (usually 5001)"
                        + " open in the firewall?").initCause(e);
            } catch (SocketTimeoutException e) {
                String what = String.valueOf(e.getMessage()).toLowerCase().contains("connect")
                        ? "Timed out connecting to KoboldCpp at " + url + ". Is the other PC on and Tailscale connected?"
                        : "KoboldCpp at " + url + " gave no answer within " + readTimeoutMs / 1000
                        + " s. It may be busy with another request; try again (or raise koboldcpp.timeoutSeconds).";
                throw (SocketTimeoutException) new SocketTimeoutException(what).initCause(e);
            }

            if (code != 200) {
                String hint = (code == 401)
                        ? " (KoboldCpp was started with --password: put the same password in koboldcpp.password)" : "";
                throw new RuntimeException("KoboldCpp returned HTTP " + code + ": " + responseText + hint);
            }
            return responseText;
        } finally {
            conn.disconnect();
        }
    }

    private String parseReply(String responseText) throws Exception {
        JSONObject json = new JSONObject(responseText);

        JSONArray results = json.optJSONArray("results");
        if (results == null || results.length() == 0) {
            throw new RuntimeException("KoboldCpp returned no results: " + responseText);
        }

        JSONObject result = results.getJSONObject(0);
        String text = stripThinking(result.optString("text", ""), think).trim();

        if (text.isEmpty()) {
            String reason = result.optString("finish_reason", "unknown");
            String hint = reason.equals("length")
                    ? " It ran out of tokens, raise koboldcpp.maxOutputTokens (or set koboldcpp.think to false)."
                    : "";
            throw new RuntimeException("KoboldCpp returned an empty reply (finish_reason=" + reason + ")." + hint);
        }
        return text;
    }

    /**
     * Removes the model's reasoning: everything up to the last </think>, and anything after an unclosed
     * <think>. Reasoning often quotes example tags like <SPAWN:pirates:225>, and the game would run those
     * as real commands. With thinking on, the prompt already opened the block (startsInThinking), so a reply
     * without </think> was cut off mid-thought and holds no answer at all.
     */
    static String stripThinking(String text, boolean startsInThinking) {
        Matcher close = THINK_CLOSE.matcher(text);
        int end = -1;
        while (close.find()) end = close.end();
        if (end >= 0) {
            text = text.substring(end);
        } else if (startsInThinking) {
            return "";
        }

        Matcher open = THINK_OPEN.matcher(text);
        if (open.find()) {
            text = text.substring(0, open.start()); // cut off while still thinking: no answer after it
        }
        return text;
    }
}
