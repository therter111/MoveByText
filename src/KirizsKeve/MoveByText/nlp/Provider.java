package KirizsKeve.MoveByText.nlp;

import KirizsKeve.MoveByText.nlp.gemini.GeminiClient;
import KirizsKeve.MoveByText.nlp.koboldcpp.KoboldCppClient;
import KirizsKeve.MoveByText.nlp.rasa.RasaClient;
import KirizsKeve.MoveByText.nlp.spacy.SpacyClient;
import com.fs.starfarer.api.Global;
import org.json.JSONObject;

/**
 * The backends the player can pick with "provider" in data/config/llmdata.json:
 * - "gemini": GeminiClient sends the text to Google's Gemini API.
 * - "koboldcpp": KoboldCppClient sends it to a KoboldCpp server, e.g. Qwen running on another PC reached over Tailscale.
 * - "rasa": RasaClient reads it with a Rasa NLU server instead: intent classification and entity extraction, no LLM.
 * - "spacy": SpacyClient does the same with a spaCy NLU server, trained on the same examples but with pretrained
 *   word vectors.
 * Without a provider, Gemini is used, so older settings files keep working.
 */
public enum Provider {

    GEMINI("gemini") {
        @Override
        public LlmBackend create(JSONObject cfg) {
            return new GeminiClient(cfg);
        }
    },
    KOBOLDCPP("koboldcpp") {
        @Override
        public LlmBackend create(JSONObject cfg) {
            return new KoboldCppClient(cfg);
        }
    },
    RASA("rasa") {
        @Override
        public LlmBackend create(JSONObject cfg) {
            return new RasaClient(cfg);
        }
    },
    SPACY("spacy") {
        @Override
        public LlmBackend create(JSONObject cfg) {
            return new SpacyClient(cfg);
        }
    };

    private final String id;

    Provider(String id) {
        this.id = id;
    }

    /** The name in the settings file, e.g. "koboldcpp". */
    public String id() {
        return id;
    }

    /** This provider's backend for the given settings. Takes them directly, so tests can run outside the game. */
    public abstract LlmBackend create(JSONObject cfg);

    /** The provider the settings pick; Gemini when none is set, and with a warning when the name is unknown. */
    public static Provider of(JSONObject cfg) {
        String name = cfg.optString("provider", GEMINI.id).trim().toLowerCase();
        for (Provider p : values()) {
            if (p.id.equals(name)) return p;
        }
        Global.getLogger(Provider.class).warn("Unknown provider \"" + name + "\" in "
                + Settings.FILE + ", using Gemini. Use \"koboldcpp\", \"rasa\", \"spacy\" or \"gemini\".");
        return GEMINI;
    }

    /** The backend the settings pick. */
    public static LlmBackend backendFor(JSONObject cfg) {
        return of(cfg).create(cfg);
    }

    /** The backend the settings file picks. Call on the game thread (e.g. in onGameLoad): it reads the file. */
    public static LlmBackend load() {
        return backendFor(Settings.load());
    }
}
