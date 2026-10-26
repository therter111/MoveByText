package KirizsKeve.MoveByText;

import com.fs.starfarer.api.Global;
import org.json.JSONObject;

/**
 * What the game needs from a language model, whichever one answers.
 *
 * - LlmClient sends the text to Google's Gemini API.
 * - KoboldCppClient sends it to a KoboldCpp server, e.g. Qwen running on another PC reached over Tailscale.
 * - RasaClient reads it with a Rasa NLU server instead: intent classification and entity extraction, no LLM.
 *
 * "provider" in data/config/llmdata.json picks one: "koboldcpp", "rasa" or "gemini". Without it, Gemini is used,
 * so older settings files keep working. The name says LLM, but every provider just turns text into command tags.
 */
public interface LlmBackend {

    /** False when required settings are missing; ask() then reports which ones instead of sending anything. */
    boolean isConfigured();

    /** Sends one user message. Returns immediately; the answer arrives through the callback on a background thread. */
    void ask(String userText, LlmClient.Callback callback);

    /** Blocking request without history. For tests only - never call this from the game thread. */
    String askNow(String userText) throws Exception;

    /** Forgets the conversation so far. */
    void clearHistory();

    /** The model that answers, for logs and test reports. */
    String modelName();

    /** Create this on the game thread (e.g. in onGameLoad) because it reads the settings file. */
    static LlmBackend create() {
        return fromConfig(LlmClient.loadConfig());
    }

    /** Takes the settings directly, so tests can run outside the game. */
    static LlmBackend fromConfig(JSONObject cfg) {
        String provider = cfg.optString("provider", "gemini").trim().toLowerCase();
        if (provider.equals("koboldcpp")) {
            return new KoboldCppClient(cfg);
        }
        if (provider.equals("rasa")) {
            return new RasaClient(cfg);
        }
        if (!provider.equals("gemini")) {
            Global.getLogger(LlmBackend.class).warn("Unknown provider \"" + provider + "\" in "
                    + LlmClient.CONFIG_FILE + ", using Gemini. Use \"koboldcpp\", \"rasa\" or \"gemini\".");
        }
        return new LlmClient(cfg);
    }
}
