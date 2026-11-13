package KirizsKeve.MoveByText.nlp.koboldcpp;

import KirizsKeve.MoveByText.nlp.ChatBackend;
import KirizsKeve.MoveByText.nlp.ChatHistory;
import KirizsKeve.MoveByText.nlp.HttpEndpoint;
import KirizsKeve.MoveByText.nlp.Settings;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends text to a KoboldCpp server (e.g. Qwen 3.5 9B on another PC, reached over Tailscale) and hands the
 * reply back through a callback. Works the same way as GeminiClient (both are ChatBackends):
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
public class KoboldCppClient extends ChatBackend {

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
    /** null when koboldcpp.url is missing. */
    private final HttpEndpoint kobold;

    /** Create this on the game thread (e.g. in onGameLoad) because it reads the settings file. */
    public KoboldCppClient() {
        this(Settings.load());
    }

    /** Takes the settings directly, so tests can run outside the game. */
    public KoboldCppClient(JSONObject cfg) {
        this(new Settings(cfg));
    }

    private KoboldCppClient(Settings root) {
        super(root.block("koboldcpp").timeoutMs("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS), "assistant");
        Settings k = root.block("koboldcpp");

        this.url = normalizeUrl(k.text("url"));
        this.password = k.text("password");
        this.systemPrompt = root.text("systemPrompt");
        this.think = k.flag("think", false);
        this.maxOutputTokens = k.integer("maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS);

        double temp = k.number("temperature", Double.NaN);
        this.temperature = (Double.isNaN(temp) || temp < 0) ? null : Double.valueOf(temp);

        require("koboldcpp.url", url);
        require("systemPrompt", systemPrompt);

        this.kobold = url == null ? null : new KoboldEndpoint();
        logReady(" at " + url + (think ? " (thinking on)" : ""));
    }

    /** KoboldCpp's server: the password as a bearer token, and what to check when it can't be reached. */
    private final class KoboldEndpoint extends HttpEndpoint {
        KoboldEndpoint() {
            super(url, CONNECT_TIMEOUT_MS);
        }

        @Override
        protected void prepare(HttpURLConnection conn) {
            if (password != null) {
                conn.setRequestProperty("Authorization", "Bearer " + password); // header keeps it out of URLs/logs
            }
        }

        @Override
        protected String cannotFind() {
            return "Can't find " + url + ". Is Tailscale connected on this PC, and is the name in koboldcpp.url right?";
        }

        @Override
        protected String cannotConnect() {
            return "Can't connect to KoboldCpp at " + url + ". Is the other PC on, KoboldCpp running with the model"
                    + " loaded, and its port (usually 5001) open in the firewall?";
        }

        @Override
        protected String timedOut(boolean connecting, int readTimeoutMs) {
            return connecting
                    ? "Timed out connecting to KoboldCpp at " + url + ". Is the other PC on and Tailscale connected?"
                    : "KoboldCpp at " + url + " gave no answer within " + readTimeoutMs / 1000
                    + " s. It may be busy with another request; try again (or raise koboldcpp.timeoutSeconds).";
        }

        @Override
        protected String httpError(int code, String responseText) {
            String hint = (code == 401)
                    ? " (KoboldCpp was started with --password: put the same password in koboldcpp.password)" : "";
            return "KoboldCpp returned HTTP " + code + ": " + responseText + hint;
        }
    }

    /** Accepts "gaming-pc:5001", "http://gaming-pc:5001/" or the address with /api or /api/v1/generate after it. */
    static String normalizeUrl(String url) {
        return HttpEndpoint.normalizeUrl(url, "/api/v1/generate", "/api/v1", "/api", "/v1");
    }

    @Override
    protected String displayName() {
        return "KoboldCpp";
    }

    /** KoboldCpp serves whatever model it was started with; loadedModel() asks the server for its name. */
    @Override
    public String modelName() {
        return "KoboldCpp";
    }

    /**
     * Asks the server which model file it has loaded, e.g. "koboldcpp/Qwen3.5-9B-Q6_K". Blocking, so for
     * tests and tools only - never call this from the game thread.
     */
    @Override
    public String loadedModel() throws Exception {
        if (url == null) throw new IllegalStateException("koboldcpp.url is not set");
        String response = kobold.send("GET", "/api/v1/model", null, readTimeoutMs());
        return new JSONObject(response).optString("result", "?");
    }

    private static String turn(String role, String text) {
        return TURN_START + role + "\n" + CONTROL_TOKEN.matcher(text).replaceAll("") + TURN_END + "\n";
    }

    @Override
    protected String request(String userText) throws Exception {
        StringBuilder prompt = new StringBuilder();
        for (ChatHistory.Turn t : history()) {
            prompt.append(turn(t.role(), t.text()));
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

        log().info("Sending HTTP request to KoboldCpp at " + url + "...");
        return parseReply(kobold.send("POST", "/api/v1/generate", body.toString(), readTimeoutMs()));
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
