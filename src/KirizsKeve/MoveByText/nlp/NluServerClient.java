package KirizsKeve.MoveByText.nlp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the player's text with a local NLU server instead of a language model: the server picks the intent and
 * marks the faction, size, places and so on, and CommandBuilder turns that into the same command tags the LLM
 * providers write. Both servers answer POST /model/parse in Rasa's format:
 * - RasaClient talks to a Rasa server (nlp/rasa folder of the mod);
 * - SpacyClient talks to the spaCy server in the nlp/spacy folder, which copies Rasa's format.
 *
 * Works the same way as the other providers (see AbstractBackend), and nothing here touches Starsector objects.
 * There is no conversation history; every message is read on its own.
 *
 * Settings: an object named after the server ("rasa" or "spacy") in data/config/llmdata.json, every field optional:
 *   url             where the server's start-server.bat listens
 *   token           only if the server was started with --auth-token
 *   minConfidence   intents the server is less sure about than this count as "not a request", default 0.5
 *   timeoutSeconds  default 30
 */
public abstract class NluServerClient extends AbstractBackend {

    private static final double DEFAULT_MIN_CONFIDENCE = 0.5;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** At most this many sentences are read; the rest of a very long message is ignored. */
    protected static final int MAX_SENTENCES = 20;

    /** The settings block in llmdata.json, and the folder in nlp/ with the server's scripts: "rasa" or "spacy". */
    private final String folder;
    /** "Rasa" or "spaCy", for messages. */
    private final String serverName;
    private final String modelName;

    /** Server address without a trailing slash. */
    private final String url;
    /** null = no token. */
    private final String token;
    private final CommandBuilder commands;
    private final HttpEndpoint server;

    /** Takes the settings directly, so tests can run outside the game. */
    protected NluServerClient(JSONObject cfg, String folder, String serverName, String modelName, String defaultUrl) {
        this(new Settings(cfg).block(folder), folder, serverName, modelName, defaultUrl);
    }

    private NluServerClient(Settings settings, String folder, String serverName, String modelName, String defaultUrl) {
        super(settings.timeoutMs("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS));
        this.folder = folder;
        this.serverName = serverName;
        this.modelName = modelName;

        String u = normalizeUrl(settings.text("url"));
        this.url = u != null ? u : defaultUrl;
        this.token = settings.text("token");

        double conf = settings.number("minConfidence", DEFAULT_MIN_CONFIDENCE);
        double minConfidence = (Double.isNaN(conf) || conf < 0 || conf > 1) ? DEFAULT_MIN_CONFIDENCE : conf;
        this.commands = new CommandBuilder(minConfidence);

        this.server = new NluEndpoint();
        logReady(" at " + url + " (min confidence " + minConfidence + ")");
    }

    /** The server: the token as a query parameter, and what to check when it can't be reached. */
    private final class NluEndpoint extends HttpEndpoint {
        NluEndpoint() {
            super(url, CONNECT_TIMEOUT_MS);
        }

        @Override
        protected String query() throws Exception {
            return token != null ? "?token=" + URLEncoder.encode(token, "UTF-8") : "";
        }

        @Override
        protected String cannotFind() {
            return "Can't find " + url + ". Check " + folder + ".url in " + Settings.FILE + ".";
        }

        @Override
        protected String cannotConnect() {
            return "Can't connect to " + serverName + " at " + url + ". Is the " + serverName + " server running?"
                    + " Start nlp/" + folder + "/start-server.bat and wait until it says the server is up and running.";
        }

        @Override
        protected String timedOut(boolean connecting, int readTimeoutMs) {
            return connecting
                    ? "Timed out connecting to " + serverName + " at " + url + "."
                    : serverName + " at " + url + " gave no answer within " + readTimeoutMs / 1000
                    + " s. It may still be loading the model; try again.";
        }

        @Override
        protected String httpError(int code, String responseText) {
            return serverName + " returned HTTP " + code + ": " + responseText + httpHint(code);
        }
    }

    /** The pieces of the message sent to the server one by one: its sentences by default. */
    protected List<String> pieces(String text) {
        return sentences(text);
    }

    /** Fixes to one sentence's parse before CommandBuilder reads it; none by default. SpacyClient has some. */
    protected JSONObject adjust(JSONObject parse) throws Exception {
        return parse;
    }

    /** Extra text for an HTTP error code, e.g. what to do about a 401; empty when there is nothing to add. */
    protected String httpHint(int code) {
        return "";
    }

    /** Accepts "localhost:5005", "http://127.0.0.1:5005/" or the address with /model/parse after it. */
    static String normalizeUrl(String url) {
        return HttpEndpoint.normalizeUrl(url, "/model/parse", "/model", "/webhooks/rest/webhook");
    }

    /** Where the server is, without a trailing slash. */
    public String url() {
        return url;
    }

    @Override
    protected String displayName() {
        return serverName;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    /**
     * Reads the message one sentence at a time: the model is trained on sentence-sized examples and misses the
     * request inside a long story. CommandBuilder puts the sentences back together.
     */
    @Override
    protected final String request(String userText) throws Exception {
        log().info("Sending text to " + serverName + " at " + url + "...");
        List<JSONObject> parses = new ArrayList<>();
        for (String sentence : pieces(userText)) {
            JSONObject body = new JSONObject();
            body.put("text", sentence);
            JSONObject parse = new JSONObject(server.send("POST", "/model/parse", body.toString(), readTimeoutMs()));
            log().info(serverName + " read: " + describe(parse));
            parses.add(adjust(parse));
        }
        return commands.toTags(parses);
    }

    /**
     * Splits after . ! ? ; followed by a space, and at line breaks. "150.6" and "Kanta's" stay whole. Always at
     * least one piece, so an empty message still gets an answer.
     */
    protected static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String piece : String.valueOf(text).split("(?<=[.!?;])\\s+|[\\r\\n]+")) {
            String s = piece.trim();
            if (!s.isEmpty() && out.size() < MAX_SENTENCES) out.add(s);
        }
        if (out.isEmpty()) out.add(String.valueOf(text).trim());
        return out;
    }

    /** The model the server has loaded, from GET /status, e.g. "rasa/nlu-....tar.gz". Blocking; for tests and tools. */
    @Override
    public String loadedModel() throws Exception {
        String file = new JSONObject(server.send("GET", "/status", null, readTimeoutMs())).optString("model_file", "?");
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return folder + "/" + (slash >= 0 ? file.substring(slash + 1) : file);
    }

    /** "spawn_fleet 0.97: faction=pirate size=small" for the log. */
    protected static String describe(JSONObject parse) {
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
}
