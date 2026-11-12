package KirizsKeve.MoveByText.nlp;

import KirizsKeve.MoveByText.nlp.gemini.LlmClient;
import KirizsKeve.MoveByText.nlp.koboldcpp.KoboldCppClient;
import KirizsKeve.MoveByText.nlp.rasa.RasaClient;
import KirizsKeve.MoveByText.nlp.spacy.SpacyClient;
import com.fs.starfarer.api.Global;
import org.json.JSONObject;

/**
 * What the game needs from a language model, whichever one answers.
 *
 * - LlmClient sends the text to Google's Gemini API.
 * - KoboldCppClient sends it to a KoboldCpp server, e.g. Qwen running on another PC reached over Tailscale.
 * - RasaClient reads it with a Rasa NLU server instead: intent classification and entity extraction, no LLM.
 * - SpacyClient does the same with a spaCy NLU server, trained on the same examples but with pretrained word vectors.
 *
 * "provider" in data/config/llmdata.json picks one: "koboldcpp", "rasa", "spacy" or "gemini". Without it, Gemini is used,
 * so older settings files keep working. The name says LLM, but every provider just turns text into command tags.
 */
public interface LlmBackend {

    /** Every setting is read from this file (relative to the mod folder). */
    String CONFIG_FILE = "data/config/llmdata.json";

    interface Callback {
        /** Called on a background thread when the model answered. */
        void onResponse(String text);

        /** Called on a background thread when anything went wrong. */
        void onError(String message);
    }

    /** False when required settings are missing; ask() then reports which ones instead of sending anything. */
    boolean isConfigured();

    /** Sends one user message. Returns immediately; the answer arrives through the callback on a background thread. */
    void ask(String userText, Callback callback);

    /** Blocking request without history. For tests only - never call this from the game thread. */
    String askNow(String userText) throws Exception;

    /** Forgets the conversation so far. */
    void clearHistory();

    /** The model that answers, for logs and test reports. */
    String modelName();

    /** Create this on the game thread (e.g. in onGameLoad) because it reads the settings file. */
    static LlmBackend create() {
        return fromConfig(loadConfig());
    }

    /** The settings file, or an empty object when it can't be read. Call on the game thread. */
    static JSONObject loadConfig() {
        try {
            return Global.getSettings().loadJSON(CONFIG_FILE);
        } catch (Exception e) {
            Global.getLogger(LlmBackend.class).warn("Could not read " + CONFIG_FILE + ": " + e.getMessage());
            return new JSONObject();
        }
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
        if (provider.equals("spacy")) {
            return new SpacyClient(cfg);
        }
        if (!provider.equals("gemini")) {
            Global.getLogger(LlmBackend.class).warn("Unknown provider \"" + provider + "\" in "
                    + CONFIG_FILE + ", using Gemini. Use \"koboldcpp\", \"rasa\", \"spacy\" or \"gemini\".");
        }
        return new LlmClient(cfg);
    }
}
