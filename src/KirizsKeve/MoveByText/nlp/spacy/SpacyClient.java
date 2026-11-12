package KirizsKeve.MoveByText.nlp.spacy;

import KirizsKeve.MoveByText.nlp.LlmBackend;
import KirizsKeve.MoveByText.nlp.NluServerClient;
import KirizsKeve.MoveByText.nlp.NumberWords;
import KirizsKeve.MoveByText.nlp.RasaCommandBuilder;
import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the player's text with the spaCy NLU server in the nlp/spacy folder of the mod: a spaCy text classifier picks
 * the intent and a spaCy entity recognizer marks the faction, size, places and so on. It is trained on the same
 * generated examples as the Rasa model, but starts from pretrained English word vectors. The server answers in
 * Rasa's /model/parse format, so everything else is shared with RasaClient, see NluServerClient.
 *
 * Rules fix the spaCy model's common mistakes without retraining it. These run here, because they need the mod's
 * word lists; the server has more (nlp/spacy/rules.py). Each has a name; nlp/spacy/README.md says what each one adds:
 *   cancel   a sentence only counts as "cancel" when it says so (the model read stories as "never mind")
 *   faction  in a short command with no faction marked, the faction is looked up in the text; ordinary words
 *            marked as a faction ("a nearby faction should help Jangala") are dropped
 *   split    "spawn X and send Y" is read as two pieces, one command each
 *   places   a place that is an ordinary English word ("Context", "Camembert") and not a known place is dropped
 *            (the server says which words are ordinary: "plain_word")
 *   roles    in a sentence with one intent, the place's role follows it: a help request defends its place
 *   size     in a short command with no size marked, the size word is looked up in the text
 *   count    a count above MAX_COUNT is a misread size: "SPAWN PIRATES 200" is one 200-point fleet
 *
 * Settings: a "spacy" object in data/config/llmdata.json, every field optional:
 *   url             default "http://127.0.0.1:5007", where nlp/spacy/start-server.bat listens
 *   minConfidence   intents spaCy is less sure about than this count as "not a request", default 0.5
 *   timeoutSeconds  default 30
 *   rules           which rules run: a list of names, "all" or "none"; default DEFAULT_RULES
 */
public class SpacyClient extends NluServerClient {

    private static final Logger LOG = Global.getLogger(SpacyClient.class);

    static final String DEFAULT_URL = "http://127.0.0.1:5007";

    public static final List<String> ALL_RULES = Collections.unmodifiableList(Arrays.asList(
            "cancel", "faction", "split", "places", "roles", "size", "count"));
    public static final List<String> DEFAULT_RULES = ALL_RULES;

    /** Words a real "cancel" sentence has: "never mind", "forget it", "don't send them", "no fleets after all". */
    static final Pattern CANCEL_WORDS = Pattern.compile("(?i)\\b(never ?mind|nevermind|cancel\\w*|forget|scratch"
            + "|abort|call (it|that|them) off|ignore|changed my mind|don'?t|do not|no (fleets?|need|more)|nothing"
            + "|stop|hold off|not anymore|undo|take (it|that) back)\\b");

    /** "... and send ...", ", then spawn ...": where split cuts a sentence, before the second command verb. */
    static final Pattern SECOND_COMMAND = Pattern.compile("(?i)(?:\\s*,\\s*|\\s+)(?:and\\s+then|and|then)\\s+"
            + "(?=(?:send|spawn|have|give|get|bring|make|summon|throw|put|dispatch)\\b)");

    /**
     * Lookups (faction, size) only run in sentences up to this many words. In longer ones the word found often
     * belongs to something that is only mentioned (an enemy, a story), which cost more cases than it fixed.
     */
    static final int MAX_LOOKUP_WORDS = 8;

    private static final List<String> PLACE_TYPES = Arrays.asList("destination", "origin", "target");
    private static final List<String> SIZE_TYPES = Arrays.asList("size", "points", "multiplier", "offset", "reference");

    /** The rules that run, from spacy.rules. */
    final Set<String> rules;

    /** Create this on the game thread (e.g. in onGameLoad) because it reads the settings file. */
    public SpacyClient() {
        this(LlmBackend.loadConfig());
    }

    /** Takes the settings directly, so tests can run outside the game. */
    public SpacyClient(JSONObject cfg) {
        super(cfg, "spacy", "spaCy", "spaCy NLU", DEFAULT_URL);
        JSONObject block = cfg.optJSONObject("spacy");
        this.rules = readRules(block == null ? null : block.opt("rules"));
        LOG.info("spaCy rules: " + (rules.isEmpty() ? "none" : String.join(", ", rules)));
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
            else LOG.warn("Unknown spaCy rule \"" + name + "\" in spacy.rules, ignored. Known: " + ALL_RULES);
        }
        return out;
    }

    /** split: each sentence, cut again before a second command ("spawn X and send Y"). */
    @Override
    protected List<String> pieces(String text) {
        List<String> sentences = super.pieces(text);
        if (!rules.contains("split")) return sentences;
        List<String> out = new ArrayList<String>();
        for (String sentence : sentences) {
            for (String piece : SECOND_COMMAND.split(sentence)) {
                String p = piece.trim();
                if (!p.isEmpty() && out.size() < MAX_SENTENCES) out.add(p);
            }
        }
        return out.isEmpty() ? sentences : out;
    }

    @Override
    protected JSONObject adjust(JSONObject parse) throws Exception {
        String text = parse.optString("text", "");
        JSONObject intent = parse.optJSONObject("intent");
        String name = intent == null ? "" : intent.optString("name", "");
        JSONArray fired = parse.optJSONArray("rules"); // what the server's rules did, for the log
        if (fired != null && fired.length() > 0) LOG.info("spaCy server rules: " + fired);

        if (rules.contains("cancel") && name.equals(RasaCommandBuilder.CANCEL_INTENT) && !CANCEL_WORDS.matcher(text).find()) {
            JSONObject next = nextIntent(parse);
            parse.put("intent", next);
            name = next.optString("name", "");
            LOG.info("spaCy rule cancel: no cancel words, read as " + name);
        }

        JSONArray entities = parse.optJSONArray("entities");
        List<JSONObject> kept = new ArrayList<JSONObject>();
        for (int i = 0; entities != null && i < entities.length(); i++) {
            JSONObject e = entities.getJSONObject(i);
            String type = e.optString("entity");
            String value = e.optString("value", "");
            if (rules.contains("faction") && type.equals("faction")
                    && RasaCommandBuilder.FactionNames.resolve(value) == null && value.equals(value.toLowerCase())) {
                LOG.info("spaCy rule faction: \"" + value + "\" is not a faction, dropped");
                continue; // an ordinary word; made-up names like "Klingon" are kept, so they still give <NONE>
            }
            if (rules.contains("places") && PLACE_TYPES.contains(type) && e.optBoolean("plain_word", false)
                    && !RasaCommandBuilder.isKnownPlace(value)) {
                LOG.info("spaCy rule places: \"" + value + "\" is an ordinary word, not a place, dropped");
                continue;
            }
            if (rules.contains("count") && type.equals("count")) {
                Double n = NumberWords.parse(value);
                if (n != null && n > RasaCommandBuilder.MAX_COUNT) {
                    e.put("entity", "points");
                    LOG.info("spaCy rule count: " + value + " fleets is a size, read as points");
                }
            }
            kept.add(e);
        }

        if (rules.contains("roles")) roles(name, kept);

        boolean request = name.equals(RasaCommandBuilder.SPAWN_INTENT) || name.equals(RasaCommandBuilder.TRADE_INTENT)
                || name.equals(RasaCommandBuilder.HELP_INTENT);
        boolean shortText = words(text) <= MAX_LOOKUP_WORDS;
        if (rules.contains("faction") && shortText && name.equals(RasaCommandBuilder.SPAWN_INTENT) && !has(kept, "faction")) {
            String faction = RasaCommandBuilder.FactionNames.resolve(text);
            if (faction != null) {
                kept.add(new JSONObject().put("entity", "faction").put("value", faction).put("start", 0)
                        .put("end", text.length()));
                LOG.info("spaCy rule faction: no faction marked, found " + faction + " in the text");
            }
        }
        if (rules.contains("size") && shortText && request && !hasAny(kept, SIZE_TYPES)
                && RasaCommandBuilder.SizeWords.tier(text) != null) {
            // RasaCommandBuilder reads the size word out of the whole sentence
            kept.add(new JSONObject().put("entity", "size").put("value", text).put("start", 0).put("end", text.length()));
            LOG.info("spaCy rule size: no size marked, found size " + RasaCommandBuilder.SizeWords.tier(text) + " in the text");
        }

        JSONArray out = new JSONArray();
        for (JSONObject e : kept) out.put(e);
        parse.put("entities", out);
        return parse;
    }

    /** roles: with a single intent, a place has the role that intent gives it. */
    private static void roles(String intent, List<JSONObject> entities) throws Exception {
        boolean trade = intent.equals(RasaCommandBuilder.TRADE_INTENT);
        boolean help = intent.equals(RasaCommandBuilder.HELP_INTENT);
        boolean spawn = intent.equals(RasaCommandBuilder.SPAWN_INTENT);
        if (!trade && !help && !spawn) return;
        for (int i = entities.size() - 1; i >= 0; i--) {
            JSONObject e = entities.get(i);
            String type = e.optString("entity");
            if (!PLACE_TYPES.contains(type)) continue;
            String role = type;
            if (spawn) role = null; // war fleets never go to a place
            else if (help) role = type.equals("origin") ? null : "target";
            else if (type.equals("target")) role = has(entities, "destination") ? null : "destination";
            if (role == null) {
                entities.remove(i);
                LOG.info("spaCy rule roles: " + type + " \"" + e.optString("value") + "\" dropped for " + intent);
            } else if (!role.equals(type)) {
                e.put("entity", role);
                LOG.info("spaCy rule roles: \"" + e.optString("value") + "\" is the " + role + " of " + intent);
            }
        }
    }

    private static boolean has(List<JSONObject> entities, String type) {
        return hasAny(entities, Collections.singletonList(type));
    }

    private static boolean hasAny(List<JSONObject> entities, List<String> types) {
        for (JSONObject e : entities) {
            if (types.contains(e.optString("entity"))) return true;
        }
        return false;
    }

    /** The best intent after "cancel" in the server's ranking, or "not a request" when there is none. */
    private static JSONObject nextIntent(JSONObject parse) throws Exception {
        JSONArray ranking = parse.optJSONArray("intent_ranking");
        for (int i = 0; ranking != null && i < ranking.length(); i++) {
            JSONObject r = ranking.optJSONObject(i);
            if (r != null && !r.optString("name").equals(RasaCommandBuilder.CANCEL_INTENT)) {
                return new JSONObject().put("name", r.optString("name")).put("confidence", r.optDouble("confidence", 0));
            }
        }
        return new JSONObject().put("name", "out_of_scope").put("confidence", 1.0);
    }

    private static int words(String text) {
        String t = text.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }
}
