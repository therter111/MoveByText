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
 * Reads the player's text with a local NLU server instead of a language model: the server picks the intent and
 * marks the faction, size, places and so on, and RasaCommandBuilder turns that into the same command tags the LLM
 * providers write. Both servers answer POST /model/parse in Rasa's format:
 * - RasaClient talks to a Rasa server (rasa folder of the mod);
 * - SpacyClient talks to the spaCy server in the spacy folder, which copies Rasa's format.
 *
 * Works the same way as the other providers: one background worker thread, the Callback runs on it, and nothing
 * here touches Starsector objects. There is no conversation history; every message is read on its own.
 *
 * Settings: an object named after the server ("rasa" or "spacy") in data/config/llmdata.json, every field optional:
 *   url             where the server's start-server.bat listens
 *   token           only if the server was started with --auth-token
 *   minConfidence   intents the server is less sure about than this count as "not a request", default 0.5
 *   timeoutSeconds  default 30
 */
abstract class NluServerClient implements LlmBackend {

    private static final double DEFAULT_MIN_CONFIDENCE = 0.5;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final Logger log = Global.getLogger(getClass());

    /** The settings block in llmdata.json, and the mod folder with the server's scripts: "rasa" or "spacy". */
    private final String folder;
    /** "Rasa" or "spaCy", for messages. */
    private final String serverName;
    private final String modelName;

    /** Server address without a trailing slash. */
    final String url;
    /** null = no token. */
    private final String token;
    private final double minConfidence;
    /** How long to wait for the answer. Read from timeoutSeconds; tests may change it. */
    int readTimeoutMs;

    private final ExecutorService worker;

    /** Takes the settings directly, so tests can run outside the game. */
    NluServerClient(JSONObject cfg, String folder, String serverName, String modelName, String defaultUrl) {
        this.folder = folder;
        this.serverName = serverName;
        this.modelName = modelName;

        JSONObject r = cfg.optJSONObject(folder);
        if (r == null) r = new JSONObject();

        String u = normalizeUrl(clean(r.optString("url", null)));
        this.url = u != null ? u : defaultUrl;
        this.token = clean(r.optString("token", null));

        double conf = r.optDouble("minConfidence", DEFAULT_MIN_CONFIDENCE);
        this.minConfidence = (Double.isNaN(conf) || conf < 0 || conf > 1) ? DEFAULT_MIN_CONFIDENCE : conf;

        int timeoutSeconds = r.optInt("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        this.readTimeoutMs = (timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS) * 1000;

        final String threadName = "KirizsKeve-" + getClass().getSimpleName();
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread t = new Thread(runnable, threadName);
                t.setDaemon(true); // never keep the game process alive
                return t;
            }
        });

        log.info(getClass().getSimpleName() + " ready at " + url + " (min confidence " + minConfidence + ")");
    }

    /** The pieces of the message sent to the server one by one: its sentences by default. */
    List<String> pieces(String text) {
        return sentences(text);
    }

    /** Fixes to one sentence's parse before RasaCommandBuilder reads it; none by default. SpacyClient has some. */
    JSONObject adjust(JSONObject parse) throws Exception {
        return parse;
    }

    /** Extra text for an HTTP error code, e.g. what to do about a 401; empty when there is nothing to add. */
    String httpHint(int code) {
        return "";
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
        return modelName;
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
                    log.error(serverName + " request failed", e);
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
        log.info("Sending text to " + serverName + " at " + url + "...");
        List<JSONObject> parses = new ArrayList<JSONObject>();
        for (String sentence : pieces(userText)) {
            JSONObject body = new JSONObject();
            body.put("text", sentence);
            JSONObject parse = new JSONObject(send("POST", "/model/parse", body.toString()));
            log.info(serverName + " read: " + describe(parse));
            parses.add(adjust(parse));
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

    /** Nothing to forget: every message is read on its own. */
    @Override
    public void clearHistory() {
    }

    /** The model the server has loaded, from GET /status, e.g. "rasa/nlu-....tar.gz". Blocking; for tests and tools. */
    String loadedModel() throws Exception {
        String file = new JSONObject(send("GET", "/status", null)).optString("model_file", "?");
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return folder + "/" + (slash >= 0 ? file.substring(slash + 1) : file);
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
                        + ". Check " + folder + ".url in " + LlmClient.CONFIG_FILE + ".").initCause(e);
            } catch (ConnectException e) {
                throw (ConnectException) new ConnectException("Can't connect to " + serverName + " at " + url
                        + ". Is the " + serverName + " server running? Start " + folder + "/start-server.bat and wait"
                        + " until it says the server is up and running.").initCause(e);
            } catch (SocketTimeoutException e) {
                String what = String.valueOf(e.getMessage()).toLowerCase().contains("connect")
                        ? "Timed out connecting to " + serverName + " at " + url + "."
                        : serverName + " at " + url + " gave no answer within " + readTimeoutMs / 1000
                        + " s. It may still be loading the model; try again.";
                throw (SocketTimeoutException) new SocketTimeoutException(what).initCause(e);
            }

            if (code != 200) {
                throw new RuntimeException(serverName + " returned HTTP " + code + ": " + responseText + httpHint(code));
            }
            return responseText;
        } finally {
            conn.disconnect();
        }
    }
}
