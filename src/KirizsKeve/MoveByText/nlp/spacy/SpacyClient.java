package KirizsKeve.MoveByText.nlp.spacy;

import KirizsKeve.MoveByText.nlp.NluServerClient;
import KirizsKeve.MoveByText.nlp.Settings;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the player's text with the spaCy NLU server in the nlp/spacy folder of the mod: a spaCy text classifier picks
 * the intent and a spaCy entity recognizer marks the faction, size, places and so on. It is trained on the same
 * generated examples as the Rasa model, but starts from pretrained English word vectors. The server answers in
 * Rasa's /model/parse format, so everything else is shared with RasaClient, see NluServerClient.
 *
 * Rules fix the spaCy model's common mistakes without retraining it. These run here, because they need the mod's
 * word lists; the server has more (nlp/spacy/rules.py). Each is a SpacyRule with a name; nlp/spacy/README.md says
 * what each one adds:
 *   cancel   CancelRule: a sentence only counts as "cancel" when it says so (the model read stories as "never mind")
 *   faction  FactionRule: in a short command with no faction marked, the faction is looked up in the text;
 *            ordinary words marked as a faction ("a nearby faction should help Jangala") are dropped
 *   split    SplitRule: "spawn X and send Y" is read as two pieces, one command each
 *   places   PlacesRule: a place that is an ordinary English word ("Context", "Camembert") and not a known place
 *            is dropped (the server says which words are ordinary: "plain_word")
 *   roles    RolesRule: in a sentence with one intent, the place's role follows it: a help request defends its place
 *   size     SizeRule: in a short command with no size marked, the size word is looked up in the text
 *   count    CountRule: a count above MAX_COUNT is a misread size: "SPAWN PIRATES 200" is one 200-point fleet
 *
 * Settings: a "spacy" object in data/config/llmdata.json, every field optional:
 *   url             default "http://127.0.0.1:5007", where nlp/spacy/start-server.bat listens
 *   minConfidence   intents spaCy is less sure about than this count as "not a request", default 0.5
 *   timeoutSeconds  default 30
 *   rules           which rules run: a list of names, "all" or "none"; default DEFAULT_RULES
 */
public class SpacyClient extends NluServerClient {

    static final String DEFAULT_URL = "http://127.0.0.1:5007";

    public static final List<String> ALL_RULES = Collections.unmodifiableList(SpacyRule.names());
    public static final List<String> DEFAULT_RULES = ALL_RULES;

    /** The rules that run, from spacy.rules, in the order they run. */
    private final List<SpacyRule> rules;

    /** Create this on the game thread (e.g. in onGameLoad) because it reads the settings file. */
    public SpacyClient() {
        this(Settings.load());
    }

    /** Takes the settings directly, so tests can run outside the game. */
    public SpacyClient(JSONObject cfg) {
        super(cfg, "spacy", "spaCy", "spaCy NLU", DEFAULT_URL);
        Set<String> names = readRules(new Settings(cfg).block("spacy").value("rules"));
        this.rules = SpacyRule.named(names);
        SpacyRule.LOG.info("spaCy rules: " + (names.isEmpty() ? "none" : String.join(", ", names)));
    }

    /** A JSON list of names, "all", "none" or "a,b"; anything missing or unreadable gives DEFAULT_RULES. */
    public static Set<String> readRules(Object value) {
        List<String> names = new ArrayList<String>();
        if (value instanceof JSONArray) {
            JSONArray list = (JSONArray) value;
            for (int i = 0; i < list.length(); i++) names.add(list.optString(i, ""));
        } else if (value instanceof String) {
            String s = ((String) value).trim().toLowerCase();
            if (s.equals("all")) return new LinkedHashSet<String>(ALL_RULES);
            if (s.equals("none")) return new LinkedHashSet<String>();
            names.addAll(Arrays.asList(s.split("\\s*,\\s*")));
        } else {
            return new LinkedHashSet<String>(DEFAULT_RULES);
        }
        Set<String> out = new LinkedHashSet<String>();
        for (String n : names) {
            String name = n.trim().toLowerCase();
            if (name.isEmpty()) continue;
            if (ALL_RULES.contains(name)) out.add(name);
            else SpacyRule.LOG.warn("Unknown spaCy rule \"" + name + "\" in spacy.rules, ignored. Known: " + ALL_RULES);
        }
        return out;
    }

    @Override
    protected List<String> pieces(String text) {
        List<String> pieces = super.pieces(text);
        for (SpacyRule rule : rules) pieces = rule.pieces(pieces, MAX_SENTENCES);
        return pieces;
    }

    @Override
    protected JSONObject adjust(JSONObject parse) throws Exception {
        JSONArray fired = parse.optJSONArray("rules"); // what the server's rules did, for the log
        if (fired != null && fired.length() > 0) SpacyRule.LOG.info("spaCy server rules: " + fired);

        Sentence sentence = new Sentence(parse);
        for (SpacyRule rule : rules) rule.intent(sentence);

        for (Iterator<JSONObject> it = sentence.entities().iterator(); it.hasNext(); ) {
            JSONObject entity = it.next();
            for (SpacyRule rule : rules) {
                if (!rule.keep(entity)) {
                    it.remove();
                    break;
                }
            }
        }

        for (SpacyRule rule : rules) rule.finish(sentence);
        return sentence.toParse();
    }
}
