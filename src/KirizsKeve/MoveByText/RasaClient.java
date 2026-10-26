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
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Reads the player's text with a Rasa NLU server instead of a language model: Rasa's own classifier (DIET) picks
 * the intent and marks the faction, size, places and so on, and RasaCommandBuilder turns that into the same command
 * tags the LLM providers write. The Rasa project (training data, config, start scripts) is in the rasa folder of
 * the mod.
 *
 * Works the same way as the other providers: one background worker thread, the Callback runs on it, and nothing
 * here touches Starsector objects. There is no conversation history; every message is read on its own.
 *
 * Settings: a "rasa" object in data/config/llmdata.json, every field optional:
 *   url             default "http://127.0.0.1:5005", where rasa/start-server.bat listens
 *   token           only if the server was started with --auth-token
 *   minConfidence   intents Rasa is less sure about than this count as "not a request", default 0.5
 *   timeoutSeconds  default 30
 */
public class RasaClient implements LlmBackend {

    private static final Logger LOG = Global.getLogger(RasaClient.class);

    static final String DEFAULT_URL = "http://127.0.0.1:5005";
    private static final double DEFAULT_MIN_CONFIDENCE = 0.5;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** Server address without a trailing slash. */
    private final String url;
    /** null = no token. */
    private final String token;
    private final double minConfidence;
    /** How long to wait for the answer. Read from timeoutSeconds; tests may change it. */
    int readTimeoutMs;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "KirizsKeve-RasaClient");
            t.setDaemon(true); // never keep the game process alive
            return t;
        }
    });

    /** Create this on the game thread (e.g. in onGameLoad) because it reads the settings file. */
    public RasaClient() {
        this(LlmClient.loadConfig());
    }

    /** Takes the settings directly, so tests can run outside the game. */
    RasaClient(JSONObject cfg) {
        JSONObject r = cfg.optJSONObject("rasa");
        if (r == null) r = new JSONObject();

        String u = normalizeUrl(clean(r.optString("url", null)));
        this.url = u != null ? u : DEFAULT_URL;
        this.token = clean(r.optString("token", null));

        double conf = r.optDouble("minConfidence", DEFAULT_MIN_CONFIDENCE);
        this.minConfidence = (Double.isNaN(conf) || conf < 0 || conf > 1) ? DEFAULT_MIN_CONFIDENCE : conf;

        int timeoutSeconds = r.optInt("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        this.readTimeoutMs = (timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS) * 1000;

        LOG.info("RasaClient ready at " + url + " (min confidence " + minConfidence + ")");
    }

    /** Accepts "localhost:5005", "http://127.0.0.1:5005/" or the address with /model/parse after it. */
    static String normalizeUrl(String url) {
        if (url == null) return null;
        if (!url.contains("://")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        for (String suffix : new String[]{"/model/parse", "/model", "/webhooks/rest/webhook"}) {
            if (url.endsWith(suffix)) {
                url = url.substring(0, url.length() - suffix.length());
                break;
            }
        }
        return url;
    }

    private static String clean(String value) {
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    /** Everything has a default, so this is always ready; a stopped server shows up as an error on ask(). */
    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String modelName() {
        return "Rasa NLU";
    }

    @Override
    public void ask(final String userText, final LlmClient.Callback callback) {
        worker.submit(new Runnable() {
            @Override
            public void run() {
                String reply = null;
                String error = null;
                try {
                    reply = askNow(userText);
                } catch (Exception e) {
                    LOG.error("Rasa request failed", e);
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

    /**
     * Reads the message one sentence at a time: the model is trained on sentence-sized examples and misses the
     * request inside a long story. RasaCommandBuilder puts the sentences back together.
     */
    @Override
    public String askNow(String userText) throws Exception {
        LOG.info("Sending text to Rasa at " + url + "...");
        List<JSONObject> parses = new ArrayList<JSONObject>();
        for (String sentence : sentences(userText)) {
            JSONObject body = new JSONObject();
            body.put("text", sentence);
            JSONObject parse = new JSONObject(send("POST", "/model/parse", body.toString()));
            LOG.info("Rasa read: " + describe(parse));
            parses.add(parse);
        }
        return RasaCommandBuilder.toTags(parses, minConfidence);
    }

    /** At most this many sentences are read; the rest of a very long message is ignored. */
    static final int MAX_SENTENCES = 20;

    /**
     * Splits after . ! ? ; followed by a space, and at line breaks. "150.6" and "Kanta's" stay whole. Always at
     * least one piece, so an empty message still gets an answer.
     */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<String>();
        for (String piece : String.valueOf(text).split("(?<=[.!?;])\\s+|[\\r\\n]+")) {
            String s = piece.trim();
            if (!s.isEmpty() && out.size() < MAX_SENTENCES) out.add(s);
        }
        if (out.isEmpty()) out.add(String.valueOf(text).trim());
        return out;
    }

    /** Nothing to forget: Rasa reads every message on its own. */
    @Override
    public void clearHistory() {
    }

    /** The model file the server has loaded, from GET /status. Blocking; for tests and tools only. */
    String loadedModel() throws Exception {
        String file = new JSONObject(send("GET", "/status", null)).optString("model_file", "?");
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return "rasa/" + (slash >= 0 ? file.substring(slash + 1) : file);
    }

    /** "spawn_fleet 0.97: faction=pirate size=small" for the log. */
    static String describe(JSONObject parse) {
        JSONObject intent = parse.optJSONObject("intent");
        StringBuilder sb = new StringBuilder();
        sb.append(intent == null ? "?" : intent.optString("name", "?"));
        if (intent != null) sb.append(String.format(" %.2f", intent.optDouble("confidence", 0)));
        JSONArray entities = parse.optJSONArray("entities");
        if (entities != null && entities.length() > 0) {
            sb.append(":");
            for (int i = 0; i < entities.length(); i++) {
                JSONObject e = entities.optJSONObject(i);
                if (e == null) continue;
                sb.append(' ').append(e.optString("entity", "?")).append('=').append(e.optString("value", "?"));
                String group = e.optString("group", "");
                if (!group.isEmpty()) sb.append('#').append(group);
            }
        }
        return sb.toString();
    }

    private String send(String method, String path, String body) throws Exception {
        String full = url + path + (token != null ? "?token=" + URLEncoder.encode(token, "UTF-8") : "");
        HttpURLConnection conn = (HttpURLConnection) new URL(full).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(readTimeoutMs);

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
                        + ". Check rasa.url in " + LlmClient.CONFIG_FILE + ".").initCause(e);
            } catch (ConnectException e) {
                throw (ConnectException) new ConnectException("Can't connect to Rasa at " + url
                        + ". Is the Rasa server running? Start rasa/start-server.bat and wait until it says the server"
                        + " is up and running.").initCause(e);
            } catch (SocketTimeoutException e) {
                String what = String.valueOf(e.getMessage()).toLowerCase().contains("connect")
                        ? "Timed out connecting to Rasa at " + url + "."
                        : "Rasa at " + url + " gave no answer within " + readTimeoutMs / 1000
                        + " s. It may still be loading the model; try again.";
                throw (SocketTimeoutException) new SocketTimeoutException(what).initCause(e);
            }

            if (code != 200) {
                String hint = code == 401 ? " (the server was started with --auth-token: put the same token in rasa.token)"
                        : code == 409 ? " (no model is loaded: run rasa/train.bat, then restart the server)" : "";
                throw new RuntimeException("Rasa returned HTTP " + code + ": " + responseText + hint);
            }
            return responseText;
        } finally {
            conn.disconnect();
        }
    }
}
