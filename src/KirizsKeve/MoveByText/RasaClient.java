package KirizsKeve.MoveByText;

import org.json.JSONObject;

/**
 * Reads the player's text with a Rasa NLU server instead of a language model: Rasa's own classifier (DIET) picks
 * the intent and marks the faction, size, places and so on, and RasaCommandBuilder turns that into the same command
 * tags the LLM providers write. The Rasa project (training data, config, start scripts) is in the rasa folder of
 * the mod. Everything but the names and hints is shared with SpacyClient, see NluServerClient.
 *
 * Settings: a "rasa" object in data/config/llmdata.json, every field optional:
 *   url             default "http://127.0.0.1:5005", where rasa/start-server.bat listens
 *   token           only if the server was started with --auth-token
 *   minConfidence   intents Rasa is less sure about than this count as "not a request", default 0.5
 *   timeoutSeconds  default 30
 */
public class RasaClient extends NluServerClient {

    static final String DEFAULT_URL = "http://127.0.0.1:5005";

    /** Create this on the game thread (e.g. in onGameLoad) because it reads the settings file. */
    public RasaClient() {
        this(LlmClient.loadConfig());
    }

    /** Takes the settings directly, so tests can run outside the game. */
    RasaClient(JSONObject cfg) {
        super(cfg, "rasa", "Rasa", "Rasa NLU", DEFAULT_URL);
    }

    @Override
    String httpHint(int code) {
        if (code == 401) return " (the server was started with --auth-token: put the same token in rasa.token)";
        if (code == 409) return " (no model is loaded: run rasa/train.bat, then restart the server)";
        return "";
    }
}
